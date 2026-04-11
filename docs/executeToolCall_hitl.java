/*
 * Updated executeToolCall() for LlmManagerService.java
 * with full human-in-the-loop enforcement.
 *
 * Replaces the existing executeToolCall() method.
 */

private String executeToolCall(InferenceSession session,
        ToolCallRequest toolCall) {

    McpRegistry registry = McpPackageHandler.getRegistry();
    McpRegistry.ToolEntry entry = registry.findTool(toolCall.name);

    if (entry == null) {
        return error("Tool not found: " + toolCall.name);
    }

    int userId = android.os.UserHandle.getUserId(session.callingUid);

    // ── Layer 1: MCP Server Access Consent ──────────────────────
    // First-use check: has the user ever granted this app's tools?

    int consent = mConsentManager.getConsent(entry.packageName, userId);

    if (consent == McpConsentManager.CONSENT_DENIED) {
        return error("User has denied access to "
                + entry.packageName + " tools");
    }

    if (consent == McpConsentManager.CONSENT_NOT_ASKED) {
        // Show consent dialog and block until user responds
        boolean granted = showConsentDialog(entry, userId);

        // Record the decision
        mConsentManager.setConsent(
                entry.packageName,
                userId,
                granted,
                entry.server.description,
                entry.server.tools.size());

        if (!granted) {
            return error("User declined access to "
                    + entry.packageName + " tools");
        }
    }

    // ── Layer 2: Per-Action Confirmation ─────────────────────────
    // Destructive tools require confirmation every time.

    boolean userConfirmed = false;
    if (entry.tool.requiresConfirmation) {
        String summary = buildActionSummary(
                entry.tool, toolCall.argumentsJson);

        userConfirmed = showConfirmDialog(entry, toolCall, summary);

        if (!userConfirmed) {
            // Log the declined action
            mConsentManager.logToolCall(userId, entry.packageName,
                    toolCall.name, toolCall.argumentsJson, null,
                    true, false, false, 0);
            return error("User cancelled " + toolCall.name);
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
        mConsentManager.logToolCall(userId, entry.packageName,
                toolCall.name, toolCall.argumentsJson, null,
                entry.tool.requiresConfirmation, userConfirmed,
                false, 0);
        return error("Failed to bind to " + component);
    }

    long startTime = System.currentTimeMillis();
    try {
        if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            mConsentManager.logToolCall(userId, entry.packageName,
                    toolCall.name, toolCall.argumentsJson,
                    "Bind timeout",
                    entry.tool.requiresConfirmation, userConfirmed,
                    false, System.currentTimeMillis() - startTime);
            return error("Timeout binding to " + component);
        }

        String result = provider[0].invokeTool(
                toolCall.name, toolCall.argumentsJson);
        long latency = System.currentTimeMillis() - startTime;

        // ── Layer 3: Audit Trail ────────────────────────────────
        mConsentManager.logToolCall(userId, entry.packageName,
                toolCall.name, toolCall.argumentsJson, result,
                entry.tool.requiresConfirmation, userConfirmed,
                true, latency);

        // Also update tool reliability stats
        mSessionStore.recordToolSuccess(
                toolCall.name, entry.packageName, latency);
        mSessionStore.addToolMessage(session.sessionId,
                toolCall.name, toolCall.argumentsJson, result);

        Log.i(TAG, "Tool " + toolCall.name + " completed in "
                + latency + "ms");
        return result != null ? result : "{\"result\": null}";

    } catch (RemoteException e) {
        long latency = System.currentTimeMillis() - startTime;
        mConsentManager.logToolCall(userId, entry.packageName,
                toolCall.name, toolCall.argumentsJson, e.getMessage(),
                entry.tool.requiresConfirmation, userConfirmed,
                false, latency);
        mSessionStore.recordToolError(
                toolCall.name, entry.packageName, e.getMessage());
        return error("Remote exception: " + e.getMessage());
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return error("Interrupted");
    } finally {
        mContext.unbindService(conn);
    }
}

/**
 * Show a first-use consent dialog for an MCP server.
 * Blocks the calling thread until the user responds.
 *
 * @return true if the user granted access
 */
private boolean showConsentDialog(McpRegistry.ToolEntry entry, int userId) {
    CountDownLatch dialogLatch = new CountDownLatch(1);
    boolean[] result = {false};

    ResultReceiver receiver = new ResultReceiver(mInferenceHandler) {
        @Override
        protected void onReceiveResult(int resultCode, Bundle data) {
            result[0] = (resultCode == McpConfirmationActivity.RESULT_GRANTED);
            dialogLatch.countDown();
        }
    };

    // Get app label
    String appLabel;
    try {
        ApplicationInfo appInfo = mContext.getPackageManager()
                .getApplicationInfo(entry.packageName, 0);
        appLabel = mContext.getPackageManager()
                .getApplicationLabel(appInfo).toString();
    } catch (PackageManager.NameNotFoundException e) {
        appLabel = entry.packageName;
    }

    // Build tool name list
    String[] toolNames = new String[entry.server.tools.size()];
    for (int i = 0; i < entry.server.tools.size(); i++) {
        toolNames[i] = entry.server.tools.get(i).name;
    }

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
        // Wait for user response (no timeout — user decides)
        dialogLatch.await();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
    }

    return result[0];
}

/**
 * Show a confirmation dialog for a destructive tool action.
 * Blocks the calling thread until the user responds.
 *
 * @return true if the user confirmed
 */
private boolean showConfirmDialog(McpRegistry.ToolEntry entry,
        ToolCallRequest toolCall, String actionSummary) {
    CountDownLatch dialogLatch = new CountDownLatch(1);
    boolean[] result = {false};

    ResultReceiver receiver = new ResultReceiver(mInferenceHandler) {
        @Override
        protected void onReceiveResult(int resultCode, Bundle data) {
            result[0] = (resultCode == McpConfirmationActivity.RESULT_GRANTED);
            dialogLatch.countDown();
        }
    };

    String appLabel;
    try {
        ApplicationInfo appInfo = mContext.getPackageManager()
                .getApplicationInfo(entry.packageName, 0);
        appLabel = mContext.getPackageManager()
                .getApplicationLabel(appInfo).toString();
    } catch (PackageManager.NameNotFoundException e) {
        appLabel = entry.packageName;
    }

    Intent intent = new Intent(mContext, McpConfirmationActivity.class);
    intent.putExtra(McpConfirmationActivity.EXTRA_TYPE,
            McpConfirmationActivity.TYPE_CONFIRM);
    intent.putExtra(McpConfirmationActivity.EXTRA_PACKAGE_NAME,
            entry.packageName);
    intent.putExtra(McpConfirmationActivity.EXTRA_APP_LABEL, appLabel);
    intent.putExtra(McpConfirmationActivity.EXTRA_TOOL_NAME, toolCall.name);
    intent.putExtra(McpConfirmationActivity.EXTRA_ACTION_SUMMARY,
            actionSummary);
    intent.putExtra(McpConfirmationActivity.EXTRA_RESULT_RECEIVER, receiver);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

    mContext.startActivity(intent);

    try {
        dialogLatch.await();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
    }

    return result[0];
}

/**
 * Build a human-readable summary of what a tool call will do.
 * Used in the confirmation dialog.
 */
private String buildActionSummary(McpToolInfo tool, String argsJson) {
    try {
        JSONObject args = new JSONObject(argsJson);
        StringBuilder sb = new StringBuilder();
        sb.append(tool.description != null ? tool.description : tool.name);
        sb.append("\n\n");

        for (McpInputInfo input : tool.inputs) {
            if (args.has(input.name)) {
                String label = input.description != null
                        ? input.description : input.name;
                sb.append(label).append(": ")
                        .append(args.opt(input.name)).append("\n");
            }
        }

        return sb.toString().trim();
    } catch (JSONException e) {
        return tool.description != null ? tool.description : tool.name;
    }
}

private static String error(String message) {
    return "{\"error\": " + JSONObject.quote(message) + "}";
}
