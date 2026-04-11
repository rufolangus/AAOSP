# LlmManagerService.java Integration Patch

These changes wire McpConsentManager, McpConfirmationActivity, and
LlmSessionStore into the actual execution path. Apply to:
`services/core/java/com/android/server/llm/LlmManagerService.java`

## 1. Add fields (after existing fields)

```java
/** Consent manager for HITL tool access control. */
private McpConsentManager mConsentManager;

/** Session store for conversation persistence + tool stats. */
private LlmSessionStore mSessionStore;

/** Separate handler for HITL dialog ResultReceivers.
 *  MUST NOT be mInferenceHandler — that thread blocks on dialog latches. */
private HandlerThread mDialogThread;
private Handler mDialogHandler;

/** Native model pointer. Volatile for cross-thread visibility. */
private volatile long mNativeModelPtr = 0;
```

Remove the old `private long mNativeModelPtr = 0;` declaration.

## 2. Initialize in onBootPhase (after model config resolution)

```java
// Human-in-the-loop consent + audit
mConsentManager = new McpConsentManager(mContext);

// Session persistence
mSessionStore = new LlmSessionStore(mContext);

// Dialog handler (separate thread to avoid deadlock)
mDialogThread = new HandlerThread("llm-dialog");
mDialogThread.start();
mDialogHandler = new Handler(mDialogThread.getLooper());
```

## 3. Replace executeToolCall entirely

Replace the existing `executeToolCall(ToolCallRequest toolCall)` method
with the full HITL version from `docs/executeToolCall_hitl.java`.

Update the method signature to also take the session:
```java
private String executeToolCall(InferenceSession session, ToolCallRequest toolCall)
```

Update the call site in `runInference()`:
```java
// Old:
String toolResult = executeToolCall(toolCall);

// New:
String toolResult = executeToolCall(session, toolCall);
```

## 4. Wire LlmSessionStore into runInference

In `runInference()`, after building the prompt:
```java
// Create persistent session
mSessionStore.createSession(session.sessionId, session.callingUid);
mSessionStore.addMessage(session.sessionId, "user", session.request.prompt);
```

After `notifyComplete`:
```java
mSessionStore.addMessage(session.sessionId, "assistant", fullText);
```

## 5. Add session AIDL methods to the binder

Add these methods inside `mBinder = new ILlmService.Stub() { ... }`:

```java
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
    for (LlmSessionStore.SessionSummary s :
            mSessionStore.listSessions(uid, 10000)) {
        mSessionStore.deleteSession(s.sessionId, uid);
    }
}
```

## 6. Add these methods to ILlmService.aidl

```aidl
String listSessions(int limit);
String getSessionHistory(String sessionId);
void deleteSession(String sessionId);
void clearAllSessions();
```

## 7. Add matching methods to LlmManager.java

```java
@NonNull
public String listSessions(int limit) {
    try {
        String result = mService.listSessions(limit);
        return result != null ? result : "[]";
    } catch (RemoteException e) {
        throw e.rethrowFromSystemServer();
    }
}

@NonNull
public String getSessionHistory(@NonNull String sessionId) {
    try {
        String result = mService.getSessionHistory(sessionId);
        return result != null ? result : "[]";
    } catch (RemoteException e) {
        throw e.rethrowFromSystemServer();
    }
}

public void deleteSession(@NonNull String sessionId) {
    try {
        mService.deleteSession(sessionId);
    } catch (RemoteException e) {
        throw e.rethrowFromSystemServer();
    }
}

public void clearAllSessions() {
    try {
        mService.clearAllSessions();
    } catch (RemoteException e) {
        throw e.rethrowFromSystemServer();
    }
}
```
