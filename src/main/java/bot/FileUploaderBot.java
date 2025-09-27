package bot;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.*;
import com.pengrad.telegrambot.model.request.*;
import com.pengrad.telegrambot.request.*;
import com.pengrad.telegrambot.response.GetFileResponse;
import com.pengrad.telegrambot.response.SendResponse;
import model.Folder;
import service.DriveService;
import session.SessionManager;
import session.UserSession;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;

public class FileUploaderBot {

    private final TelegramBot bot;
    private final DriveService driveService;
    private final SessionManager sessionManager;
    private final String TokenID = "XXXXXXX";//Replace this

    public FileUploaderBot(DriveService driveService, SessionManager sessionManager) {
        this.bot = new TelegramBot(TokenID);
        this.driveService = driveService;
        this.sessionManager = sessionManager;
    }

    public void start() {
        bot.setUpdatesListener(updates -> {
            for (Update update : updates) {
                if (update.message() != null) {
                    handleMessage(update.message());
                } else if (update.callbackQuery() != null) {
                    handleCallback(update.callbackQuery());
                }
            }
            return UpdatesListener.CONFIRMED_UPDATES_ALL;
        });
    }

    private void handleMessage(Message msg) {
        long chatId = msg.chat().id();
        UserSession session = sessionManager.getSession(chatId);

        // --- /start or new session ---
        if (msg.text() != null && (msg.text().equals("/start") || session == null)) {
            try {
                session = sessionManager.createSession(chatId);

                // Fetch top-level folders
                List<Folder> roots = driveService.scanTopLevelFolders();
                session.setRootFolders(roots);

                // Build keyboard
                InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
                for (Folder root : roots) {
                    keyboard.addRow(new InlineKeyboardButton("📂 " + root.getName())
                            .callbackData("folder:" + root.getId()));
                }
                keyboard.addRow(new InlineKeyboardButton("⬆ Upload Here").callbackData("upload"));

                // Send message WITH keyboard
                bot.execute(new SendMessage(chatId, "📂 Welcome! Select a folder:")
                        .replyMarkup(keyboard));

                // Debug for folders
                System.out.println("Loaded top-level folders: " + roots.size());
                for (Folder f : roots) {
                    System.out.println("📂 " + f.getName() + " (" + f.getId() + ")");
                }

            } catch (Exception e) {
                bot.execute(new SendMessage(chatId, "❌ Failed to load Drive: " + e.getMessage()));
            }
            return;
        }

        // --- File upload ---
        if (msg.document() != null) {
            if (session != null && session.isWaitingForUpload()) {
                session.setWaitingForUpload(false);

                try {
                    // Download file from Telegram
                    String fileId = msg.document().fileId();
                    GetFileResponse fileResponse = bot.execute(new GetFile(fileId));
                    com.pengrad.telegrambot.model.File tgFile = fileResponse.file();

                    java.io.File localFile = downloadTelegramFile(tgFile);

                    // ✅ Upload to Google Drive with correct folderId
                    String folderId = (session.getCurrentFolder() != null)
                            ? session.getCurrentFolder().getId()
                            : null;

                    String uploadedUrl = driveService.uploadFile(
                            localFile,
                            msg.document().fileName(),
                            session.getCurrentFolder() != null ? session.getCurrentFolder().getId() : null
                    );

                    //confirmation message
                    String fileName = msg.document().fileName();
                    String folderName = (session.getCurrentFolder() != null)
                            ? session.getCurrentFolder().getName()
                            : "Root";

                    // Use HTML instead of Markdown (avoids parse errors with underscores, asterisks, etc.)
                    SendMessage confirmationMsg = new SendMessage(chatId,
                            "✅ Uploaded <b>" + fileName + "</b> into folder: <b>" + folderName + "</b>" +
                                    "\n🔗 <a href=\"" + uploadedUrl + "\">Open in Drive</a>"
                    )
                            .parseMode(ParseMode.HTML)   // HTML is safer than Markdown
                            .disableWebPagePreview(true); // Cleaner look

                    SendResponse response = bot.execute(confirmationMsg);

                    // Log any issue with Telegram API (previously was silent)
                    if (!response.isOk()) {
                        System.err.println("⚠️ Failed to send confirmation for file: " + fileName +
                                " | Reason: " + response.description());
                    }


                } catch (Exception e) {
                    e.printStackTrace();
                    bot.execute(new SendMessage(chatId,
                            "❌ Upload failed for *" + msg.document().fileName() +
                                    "*: " + e.getMessage()).parseMode(ParseMode.Markdown));
                }
            } else {
                bot.execute(new SendMessage(chatId, "⚠️ Please choose '⬆ Upload Here' first."));
            }
            return;
        }
    }

    private void handleCallback(CallbackQuery callback) {
        long chatId = callback.message().chat().id();
        String data = callback.data();
        UserSession session = sessionManager.getSession(chatId);

        if (session == null) {
            bot.execute(new SendMessage(chatId, "⚠️ Session expired. Send /start again."));
            return;
        }

        try {
            if (data.startsWith("folder:")) {
                String folderId = data.substring("folder:".length());
                Folder target = findFolderById(session.getRootFolders(), folderId);

                if (target != null) {
                    session.setCurrentFolder(target);
                    InlineKeyboardMarkup kb = buildFolderKeyboard(target);

                    bot.execute(new EditMessageText(chatId, callback.message().messageId(),
                            "📂 Folder: " + target.getName()).replyMarkup(kb));
                }
            } else if (data.equals("upload")) {
                if (session.getCurrentFolder() != null) {
                    session.setWaitingForUpload(true);
                    bot.execute(new SendMessage(chatId,
                            "⬆ Please send the file to upload into: " + session.getCurrentFolder().getName()));
                }
            } else if (data.equals("back")) {
                Folder current = session.getCurrentFolder();
                if (current != null && current.getParent() != null) {
                    Folder parent = current.getParent();
                    session.setCurrentFolder(parent);
                    InlineKeyboardMarkup kb = buildFolderKeyboard(parent);
                    bot.execute(new EditMessageText(chatId, callback.message().messageId(),
                            "📂 Folder: " + parent.getName()).replyMarkup(kb));
                }
            }
        } catch (Exception e) {
            bot.execute(new SendMessage(chatId, "❌ Error: " + e.getMessage()));
        }
    }

    private InlineKeyboardMarkup buildFolderKeyboard(Folder folder) {
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();

        // Subfolders
        for (Folder sub : folder.getSubFolders()) {
            kb.addRow(new InlineKeyboardButton("📂 " + sub.getName())
                    .callbackData("folder:" + sub.getId()));
        }

        // Files (just for display, no action)
        for (String f : folder.getFiles()) {
            kb.addRow(new InlineKeyboardButton("📄 " + f).callbackData("noop"));
        }

        // Upload option
        kb.addRow(new InlineKeyboardButton("⬆ Upload Here").callbackData("upload"));

        // ✅ Back button (only if not at root)
        if (folder.getParent() != null) {
            kb.addRow(new InlineKeyboardButton("🔙 Back").callbackData("back"));
        }

        return kb;
    }

    private Folder findFolderById(List<Folder> folders, String id) {
        for (Folder f : folders) {
            if (f.getId().equals(id)) return f;
            Folder sub = findFolderById(f.getSubFolders(), id);
            if (sub != null) return sub;
        }
        return null;
    }

    private java.io.File downloadTelegramFile(com.pengrad.telegrambot.model.File file) throws IOException {
        String url = bot.getFullFilePath(file);
        java.io.File temp = java.io.File.createTempFile("tgfile", null);

        try (InputStream in = new URL(url).openStream();
             FileOutputStream out = new FileOutputStream(temp)) {
            in.transferTo(out);
        }
        return temp;
    }
}
