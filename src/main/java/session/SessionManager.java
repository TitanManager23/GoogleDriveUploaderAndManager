package session;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {

    private final Map<Long, UserSession> sessions = new ConcurrentHashMap<>();
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    /**
     * Get session if it exists and is still active.
     */
    public UserSession getSession(long chatId) {
        UserSession session = sessions.get(chatId);
        if (session != null && !isExpired(session)) {
            session.touch(); // refresh activity
            return session;
        }
        return null;
    }

    /**
     * Create a new session (replaces old one if expired).
     */
    public UserSession createSession(long chatId) {
        UserSession session = new UserSession(chatId);
        sessions.put(chatId, session);
        return session;
    }

    /**
     * Remove session (manual cleanup).
     */
    public void removeSession(long chatId) {
        sessions.remove(chatId);
    }

    /**
     * Cleanup expired sessions (called by SessionCleanupTask).
     */
    public void cleanupExpiredSessions() {
        for (Map.Entry<Long, UserSession> entry : sessions.entrySet()) {
            if (isExpired(entry.getValue())) {
                sessions.remove(entry.getKey());
            }
        }
    }

    private boolean isExpired(UserSession session) {
        return Duration.between(session.getLastActivity(), Instant.now()).compareTo(TIMEOUT) > 0;
    }
}
