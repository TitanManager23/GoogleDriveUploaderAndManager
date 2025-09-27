package bot;

import service.DriveService;
import session.SessionCleanupTask;
import session.SessionManager;

public class Main {
    public static void main(String[] args) {
        try {
            // Initialize Google Drive service (OAuth-based)
            DriveService driveService = new DriveService();

            // Initialize session management
            SessionManager sessionManager = new SessionManager();

            // Start background session cleanup task
            SessionCleanupTask.start(sessionManager);

            // Start Telegram bot
            FileUploaderBot bot = new FileUploaderBot(driveService, sessionManager);
            bot.start();

            System.out.println("✅ Telegram Drive Bot is running...");
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("❌ Failed to start bot: " + e.getMessage());
        }
    }
}
