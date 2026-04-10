// Additions to ILlmService.aidl — append these methods to the existing interface:

    /**
     * List recent sessions for the calling app.
     *
     * @param limit max number of sessions to return
     * @return JSON array of session summaries
     */
    String listSessions(int limit);

    /**
     * Get conversation history for a session.
     *
     * @param sessionId the session ID
     * @return JSON array of messages
     */
    String getSessionHistory(String sessionId);

    /**
     * Delete a session and all its messages.
     *
     * @param sessionId the session ID to delete
     */
    void deleteSession(String sessionId);

    /**
     * Delete all sessions for the calling app.
     */
    void clearAllSessions();
