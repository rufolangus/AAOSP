/*
 * Integration code for LlmManagerService to use LlmSessionStore.
 *
 * These snippets show what to add/change in the existing LlmManagerService.java.
 * Not a standalone file — merge into the existing class.
 */

// === Add field ===
private LlmSessionStore mSessionStore;

// === In onBootPhase(PHASE_SYSTEM_SERVICES_READY), after model config ===
mSessionStore = new LlmSessionStore(mContext);

// === In submit(), after creating the session ===
// Persist the session
mSessionStore.createSession(sessionId, Binder.getCallingUid());

// === In runInference(), on user prompt ===
// Persist user message
mSessionStore.addMessage(session.sessionId, "user", session.request.prompt);

// If resuming a session, load history into the prompt
if (session.request.sessionId != null) {
    List<LlmSessionStore.StoredMessage> history =
            mSessionStore.getSessionHistory(session.request.sessionId, 20);
    // Inject history into the Qwen prompt as conversation turns
}

// === In runInference(), after onComplete ===
// Persist assistant response
mSessionStore.addMessage(session.sessionId, "assistant", fullText);

// === In executeToolCall(), wrap the invocation with timing ===
long startTime = System.currentTimeMillis();
String result;
try {
    result = provider[0].invokeTool(toolCall.name, toolCall.argumentsJson);
    long latency = System.currentTimeMillis() - startTime;
    mSessionStore.recordToolSuccess(toolCall.name, entry.packageName, latency);
    mSessionStore.addToolMessage(session.sessionId, toolCall.name,
            toolCall.argumentsJson, result);
} catch (RemoteException e) {
    mSessionStore.recordToolError(toolCall.name, entry.packageName,
            e.getMessage());
    result = "{\"error\": \"" + e.getMessage() + "\"}";
}

// === In buildToolsDescription(), use stats to annotate tools ===
// Add reliability info to tool descriptions so the LLM can prefer reliable tools
LlmSessionStore.ToolStats stats = mSessionStore.getToolStats(entry.indexKey);
if (stats != null && stats.callCount > 5) {
    if (stats.successRate() < 0.5f) {
        function.put("reliability_note",
                "This tool has been unreliable recently ("
                + Math.round(stats.successRate() * 100) + "% success rate)");
    }
}

// === New binder methods ===

@Override
public String listSessions(int limit) {
    mContext.enforceCallingOrSelfPermission(
            "android.permission.SUBMIT_LLM_REQUEST",
            "Must hold SUBMIT_LLM_REQUEST");
    int uid = Binder.getCallingUid();
    List<LlmSessionStore.SessionSummary> sessions =
            mSessionStore.listSessions(uid, limit);
    try {
        JSONArray arr = new JSONArray();
        for (LlmSessionStore.SessionSummary s : sessions) {
            JSONObject obj = new JSONObject();
            obj.put("sessionId", s.sessionId);
            obj.put("title", s.title);
            obj.put("createdAt", s.createdAt);
            obj.put("updatedAt", s.updatedAt);
            obj.put("messageCount", s.messageCount);
            arr.put(obj);
        }
        return arr.toString();
    } catch (JSONException e) {
        return "[]";
    }
}

@Override
public String getSessionHistory(String sessionId) {
    mContext.enforceCallingOrSelfPermission(
            "android.permission.SUBMIT_LLM_REQUEST",
            "Must hold SUBMIT_LLM_REQUEST");
    List<LlmSessionStore.StoredMessage> messages =
            mSessionStore.getSessionHistory(sessionId, 100);
    try {
        JSONArray arr = new JSONArray();
        for (LlmSessionStore.StoredMessage m : messages) {
            JSONObject obj = new JSONObject();
            obj.put("role", m.role);
            obj.put("content", m.content);
            if (m.toolName != null) obj.put("toolName", m.toolName);
            if (m.toolArgsJson != null) obj.put("toolArgs", m.toolArgsJson);
            if (m.toolResultJson != null) obj.put("toolResult", m.toolResultJson);
            arr.put(obj);
        }
        return arr.toString();
    } catch (JSONException e) {
        return "[]";
    }
}

@Override
public void deleteSession(String sessionId) {
    mContext.enforceCallingOrSelfPermission(
            "android.permission.SUBMIT_LLM_REQUEST",
            "Must hold SUBMIT_LLM_REQUEST");
    mSessionStore.deleteSession(sessionId, Binder.getCallingUid());
}

@Override
public void clearAllSessions() {
    mContext.enforceCallingOrSelfPermission(
            "android.permission.SUBMIT_LLM_REQUEST",
            "Must hold SUBMIT_LLM_REQUEST");
    int uid = Binder.getCallingUid();
    List<LlmSessionStore.SessionSummary> sessions =
            mSessionStore.listSessions(uid, 1000);
    for (LlmSessionStore.SessionSummary s : sessions) {
        mSessionStore.deleteSession(s.sessionId, uid);
    }
    Log.i(TAG, "Cleared all sessions for uid " + uid);
}

// === Add sessionId to LlmRequest ===
// In LlmRequest.java, add:
//   public final String sessionId;  // null = new session, non-null = continue
// In LlmRequest.Builder, add:
//   public Builder setSessionId(String sessionId) { ... }
