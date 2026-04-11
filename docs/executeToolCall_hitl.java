/*
 * Updated executeToolCall() for LlmManagerService.java
 * with full human-in-the-loop enforcement.
 *
 * IMPORTANT: The ResultReceiver must NOT use mInferenceHandler because
 * the inference thread is blocked on dialogLatch.await(). Using the same
 * handler would deadlock. Use a separate handler thread (mDialogHandler).
 *
 * Add these fields to LlmManagerService:
 *
 *   private McpConsentManager mConsentManager;
 *   private HandlerThread mDialogThread;
 *   private Handler mDialogHandler;
 *
 * Initialize in onBootPhase(PHASE_SYSTEM_SERVICES_READY):
 *
 *   mConsentManager = new McpConsentManager(mContext);
 *   mDialogThread = new HandlerThread("llm-dialog");
 *   mDialogThread.start();
 *   mDialogHandler = new Handler(mDialogThread.getLooper());
 */

private String executeToolCall(InferenceSession session,
        ToolCallRequest toolCall) {

    McpRegistry registry = McpPackageHandler.getRegistry();
    McpRegistry.ToolEntry entry = registry.findTool(toolCall.name);

    if (entry == null) {
        return errorJson("Tool not found: " + toolCall.name);
    }

    int userId = android.os.UserHandle.getUserId(session.callingUid);

    // ── Layer 1: MCP Server Access Consent ──────────────────────

    int consent = mConsentManager.getConsent(entry.packageName, userId);

    if (consent == McpConsentManager.CONSENT_DENIED) {
        return errorJson("User denied access to "
                + entry.packageName + " tools");
    }

    if (consent == McpConsentManager.CONSENT_NOT_ASKED) {
        boolean granted = showConsentDialog(entry, userId);

        mConsentManager.setConsent(
                entry.packageName, userId, granted,
                entry.server.description,
                entry.server.tools.size());

        if (!granted) {
            return errorJson("User declined access to "
                    + entry.packageName + " tools");
        }
    }

    // ── Layer 2: Per-Action Confirmation ─────────────────────────

    boolean wasAutoConfirmed = false;
    boolean userConfirmed = false;

    if (entry.tool.requiresConfirmation) {
        // Check if user previously chose "don't ask again"
        if (mConsentManager.isAutoConfirmed(
                entry.packageName, toolCall.name, userId)) {
            wasAutoConfirmed = true;
            // Skip the dialog, but still log it
        } else {
            String summary = buildActionSummary(
                    entry.tool, toolCall.argumentsJson);
            int confirmResult = showConfirmDialog(
                    entry, toolCall, summary);

            if (confirmResult == McpConfirmationActivity.RESULT_DENIED) {
                mConsentManager.logToolCall(userId, entry.packageName,
                        toolCall.name, toolCall.argumentsJson, null,
                        true, false, false, false, 0);
                return errorJson("User cancelled " + toolCall.name);
            }

            userConfirmed = true;

            // If user checked "don't ask again", save it
            if (confirmResult == McpConfirmationActivity.RESULT_AUTO_CONFIRM) {
                mConsentManager.setAutoConfirm(
                        entry.packageName, toolCall.name, userId);
            }
        }
    }

    // ── Execute the tool ─────────────────────────────────────────

    ComponentName component = new ComponentName(
            entry.packageName, entry.server.name);
    Intent bindIntent = new Intent();
    bindIntent.setComponent(component);

    final IMcpToolProvider[] provider = new IMcpToolProvider[1];
    final CountDownLatch latch = new CountDownLatch(1);

    ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            provider[0] = IMcpToolProvider.Stub.asInterface(service);
            latch.countDown();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            provider[0] = null;
        }
    };

    boolean bound = mContext.bindService(bindIntent, conn,
            Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE);

    if (!bound) {
        logFailure(userId, entry, toolCall, "Bind failed",
                userConfirmed, wasAutoConfirmed, 0);
        return errorJson("Failed to bind to " + component);
    }

    long startTime = System.currentTimeMillis();
    try {
        if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            logFailure(userId, entry, toolCall, "Bind timeout",
                    userConfirmed, wasAutoConfirmed,
                    System.currentTimeMillis() - startTime);
            return errorJson("Timeout binding to " + component);
        }

        String result = provider[0].invokeTool(
                toolCall.name, toolCall.argumentsJson);
        long latency = System.currentTimeMillis() - startTime;

        // ── Layer 3: Audit Trail ────────────────────────────────
        mConsentManager.logToolCall(userId, entry.packageName,
                toolCall.name, toolCall.argumentsJson, result,
                entry.tool.requiresConfirmation, userConfirmed,
                wasAutoConfirmed, true, latency);

        mSessionStore.recordToolSuccess(
                toolCall.name, entry.packageName, latency);
        mSessionStore.addToolMessage(session.sessionId,
                toolCall.name, toolCall.argumentsJson, result);

        Log.i(TAG, "Tool " + toolCall.name + " completed in "
                + latency + "ms"
                + (wasAutoConfirmed ? " (auto-confirmed)" : ""));
        return result != null ? result : "{\"result\": null}";

    } catch (RemoteException e) {
        long latency = System.currentTimeMillis() - startTime;
        logFailure(userId, entry, toolCall, e.getMessage(),
                userConfirmed, wasAutoConfirmed, latency);
        mSessionStore.recordToolError(
                toolCall.name, entry.packageName, e.getMessage());
        return errorJson("Remote exception: " + e.getMessage());
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return errorJson("Interrupted");
    } finally {
        mContext.unbindService(conn);
    }
}

/**
 * Show consent dialog. Blocks until user responds.
 * Uses mDialogHandler (NOT mInferenceHandler) to avoid deadlock.
 *
 * @return true if user granted access
 */
private boolean showConsentDialog(McpRegistry.ToolEntry entry,
        int userId) {
    CountDownLatch dialogLatch = new CountDownLatch(1);
    boolean[] result = {false};

    // CRITICAL: use mDialogHandler, not mInferenceHandler.
    // mInferenceHandler's thread is blocked on this latch — using it
    // for the ResultReceiver would deadlock.
    ResultReceiver receiver = new ResultReceiver(mDialogHandler) {
        @Override
        protected void onReceiveResult(int resultCode, Bundle data) {
            result[0] = (resultCode == McpConfirmationActivity.RESULT_GRANTED);
            dialogLatch.countDown();
        }
    };

    String appLabel = getAppLabel(entry.packageName);
    String[] toolNames = entry.server.tools.stream()
            .map(t -> t.name)
            .toArray(String[]::new);

    Intent intent = new Intent(mContext, McpConfirmationActivity.class);
    intent.putExtra(McpConfirmationActivity.EXTRA_TYPE,
            McpConfirmationActivity.TYPE_CONSENT);
    intent.putExtra(McpConfirmationActivity.EXTRA_PACKAGE_NAME,
            entry.packageName);
    intent.putExtra(McpConfirmationActivity.EXTRA_APP_LABEL, appLabel);
    intent.putExtra(McpConfirmationActivity.EXTRA_DESCRIPTION,
            entry.server.description);
    intent.putExtra(McpConfirmationActivity.EXTRA_TOOL_NAMES, toolNames);
    intent.putExtra(McpConfirmationActivity.EXTRA_RESULT_RECEIVER, receiver);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

    mContext.startActivity(intent);

    try {
        // 60-second timeout — if activity crashes, don't hang forever
        if (!dialogLatch.await(60, TimeUnit.SECONDS)) {
            Log.w(TAG, "Consent dialog timed out for " + entry.packageName);
            return false;
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
    }

    return result[0];
}

/**
 * Show confirmation dialog. Blocks until user responds.
 *
 * @return RESULT_GRANTED, RESULT_DENIED, or RESULT_AUTO_CONFIRM
 */
private int showConfirmDialog(McpRegistry.ToolEntry entry,
        ToolCallRequest toolCall, String actionSummary) {
    CountDownLatch dialogLatch = new CountDownLatch(1);
    int[] result = {McpConfirmationActivity.RESULT_DENIED};

    ResultReceiver receiver = new ResultReceiver(mDialogHandler) {
        @Override
        protected void onReceiveResult(int resultCode, Bundle data) {
            result[0] = resultCode;
            dialogLatch.countDown();
        }
    };

    Intent intent = new Intent(mContext, McpConfirmationActivity.class);
    intent.putExtra(McpConfirmationActivity.EXTRA_TYPE,
            McpConfirmationActivity.TYPE_CONFIRM);
    intent.putExtra(McpConfirmationActivity.EXTRA_PACKAGE_NAME,
            entry.packageName);
    intent.putExtra(McpConfirmationActivity.EXTRA_APP_LABEL,
            getAppLabel(entry.packageName));
    intent.putExtra(McpConfirmationActivity.EXTRA_TOOL_NAME, toolCall.name);
    intent.putExtra(McpConfirmationActivity.EXTRA_ACTION_SUMMARY,
            actionSummary);
    intent.putExtra(McpConfirmationActivity.EXTRA_RESULT_RECEIVER, receiver);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

    mContext.startActivity(intent);

    try {
        if (!dialogLatch.await(60, TimeUnit.SECONDS)) {
            Log.w(TAG, "Confirm dialog timed out for " + toolCall.name);
            return McpConfirmationActivity.RESULT_DENIED;
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return McpConfirmationActivity.RESULT_DENIED;
    }

    return result[0];
}

private String getAppLabel(String packageName) {
    try {
        ApplicationInfo appInfo = mContext.getPackageManager()
                .getApplicationInfo(packageName, 0);
        return mContext.getPackageManager()
                .getApplicationLabel(appInfo).toString();
    } catch (PackageManager.NameNotFoundException e) {
        return packageName;
    }
}

private void logFailure(int userId, McpRegistry.ToolEntry entry,
        ToolCallRequest toolCall, String error,
        boolean userConfirmed, boolean autoConfirmed, long latencyMs) {
    mConsentManager.logToolCall(userId, entry.packageName,
            toolCall.name, toolCall.argumentsJson, error,
            entry.tool.requiresConfirmation, userConfirmed,
            autoConfirmed, false, latencyMs);
}

private static String errorJson(String message) {
    return "{\"error\": " + JSONObject.quote(message) + "}";
}
