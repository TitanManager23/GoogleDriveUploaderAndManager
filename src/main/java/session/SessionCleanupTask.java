package session;

import java.util.Timer;
import java.util.TimerTask;

public class SessionCleanupTask {

    private static final long CLEANUP_INTERVAL_MS = 120_000; // 2 minutes

    public static void start(SessionManager sessionManager) {
        Timer timer = new Timer(true); // daemon thread
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                sessionManager.cleanupExpiredSessions();
            }
        }, CLEANUP_INTERVAL_MS, CLEANUP_INTERVAL_MS);
    }
}
