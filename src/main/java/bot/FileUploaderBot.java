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

    private final String TokenID = "XXX";//Replace this
    private final TelegramBot bot;
    private final DriveService driveService;
    private final SessionManager sessionManager;
    private final security.SecurityStore securityStore;

    public FileUploaderBot(DriveService driveService, SessionManager sessionManager, security.SecurityStore securityStore) {
        this.bot = new TelegramBot(TokenID);
        this.driveService = driveService;
        this.sessionManager = sessionManager;
        this.securityStore = securityStore;
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

    // =====================
    // handleMessage(Message)
    // =====================
    private void handleMessage(Message msg) {
        if (msg == null) return;
        Long chatId = (msg.chat() != null) ? msg.chat().id() : null;
        if (chatId == null) return;

        UserSession session = sessionManager.getSession(chatId);
        if (session == null) {
            session = sessionManager.createSession(chatId);
        }

        // ===== 1) FILES FIRST: document / photo upload handling =====
        // Accept files if:
        //   - user clicked "Upload Here" (waitingForUpload == true), OR
        //   - a current folder is open (be forgiving)
        if (msg.document() != null || (msg.photo() != null && msg.photo().length > 0)) {
            if (session.getCurrentFolder() == null) {
                bot.execute(new SendMessage(chatId, "Please choose a folder first (📁 Browse) and then try again."));
                return;
            }
            try {
                uploadIncomingTelegramFile(msg, session);
                session.setWaitingForUpload(false); // reset if it was set
                bot.execute(new SendMessage(chatId, "✅ File uploaded to: " + session.getCurrentFolder().getName()));
            } catch (Exception ex) {
                ex.printStackTrace();
                bot.execute(new SendMessage(chatId, "❌ Upload failed: " + ex.getMessage()));
            }
            return;
        }

        // ===== 2) TEXT COMMANDS / PROMPTS =====
        String text = msg.text();
        String awaiting = session.getAwaitingWhat();
        if (text == null) return;
        text = text.trim();

        if ("/start".equalsIgnoreCase(text) || "/restart".equalsIgnoreCase(text)) {
            session.setMode(UserSession.Mode.REGULAR);
            session.setAdminAuthenticated(false);
            session.setDirectAccessRoot(null);
            session.getUnlockedFolderIds().clear();
            session.setAwaitingWhat(null);
            session.setPendingFolderId(null);
            sendWelcomeMessage(chatId);
            return;
        }

        if (awaiting != null) {
            switch (awaiting) {
                case "ADMIN_PWD" -> {
                    if (securityStore.checkAdminPassword(text)) {
                        session.setAdminAuthenticated(true);
                        session.setMode(UserSession.Mode.ADMIN);
                        session.setAwaitingWhat(null);
                        bot.execute(new SendMessage(chatId, "✅ Admin authenticated."));
                        bot.execute(new SendMessage(chatId, "Admin menu:").replyMarkup(adminHomeKeyboard()));
                    } else {
                        bot.execute(new SendMessage(chatId, "❌ Incorrect admin password. Try again or /start."));
                    }
                    return;
                }
                case "CHANGE_ADMIN_PWD" -> {
                    if (text.isBlank()) bot.execute(new SendMessage(chatId, "Password cannot be blank."));
                    else {
                        securityStore.changeAdminPassword(text);
                        bot.execute(new SendMessage(chatId, "✅ Admin password changed."));
                    }
                    session.setAwaitingWhat(null);
                    bot.execute(new SendMessage(chatId, "Admin menu:").replyMarkup(adminHomeKeyboard()));
                    return;
                }
                case "DIRECT_FOLDER_NAME" -> {
                    // ✅ ensure roots loaded
                    ensureRootsLoaded(session);

                    Folder found = resolveFolderByInput(session.getRootFolders(), text);
                    if (found == null) {
                        bot.execute(new SendMessage(chatId,
                                "Folder not found. Try again (name, path like Parent/Sub, or folder ID):"));
                        return;
                    }
                    session.setPendingFolderId(found.getId());
                    session.setAwaitingWhat("DIRECT_CODE:" + found.getId());
                    bot.execute(new SendMessage(chatId, "Enter direct access code for '" + found.getName() + "':"));
                    return;
                }

                default -> { /* patterned awaits handled below */ }
            }

            if (awaiting.startsWith("DIRECT_CODE:")) {
                String fid = awaiting.substring("DIRECT_CODE:".length());
                if (securityStore.hasDirectAccess(fid, text)) {
                    Folder root = findFolderById(session.getRootFolders(), fid);
                    session.setMode(UserSession.Mode.DIRECT_ACCESS);
                    session.setDirectAccessRoot(root);
                    session.setCurrentFolder(root);              // ✅ <-- add this line
                    session.setAwaitingWhat(null);
                    session.getUnlockedFolderIds().add(fid);

                    InlineKeyboardMarkup kb = buildFolderKeyboard(root, false, true, root.getId());
                    bot.execute(new SendMessage(chatId,
                            "✅ Direct access granted to: " + root.getName()).replyMarkup(kb));
                } else {
                    bot.execute(new SendMessage(chatId, "❌ Invalid code. Try again:"));
                }
                return;
            }


            if (awaiting.startsWith("FOLDER_PWD:")) {
                String folderId = awaiting.substring("FOLDER_PWD:".length());
                String expected = securityStore.getFolderPassword(folderId);
                if (expected == null || expected.isBlank() || expected.equals(text)) {
                    session.getUnlockedFolderIds().add(folderId);
                    session.setAwaitingWhat(null);
                    Folder f = findFolderById(session.getRootFolders(), folderId);
                    session.setCurrentFolder(f);
                    InlineKeyboardMarkup kb = buildFolderKeyboard(
                            f,
                            session.getMode() == UserSession.Mode.ADMIN && session.isAdminAuthenticated(),
                            session.getMode() == UserSession.Mode.DIRECT_ACCESS,
                            session.getDirectAccessRoot() != null ? session.getDirectAccessRoot().getId() : null
                    );
                    bot.execute(new SendMessage(chatId, "🔓 Access granted to: " + f.getName()).replyMarkup(kb));
                } else {
                    bot.execute(new SendMessage(chatId, "❌ Incorrect password, please try again"));
                }
                return;
            }

            if (awaiting.startsWith("SET_FOLDER_PWD:")) {
                String fid = awaiting.substring("SET_FOLDER_PWD:".length());
                if ("null".equalsIgnoreCase(text) || text.isBlank()) {
                    securityStore.setFolderPassword(fid, null);
                    bot.execute(new SendMessage(chatId, "🔓 Password removed for folder."));
                } else {
                    securityStore.setFolderPassword(fid, text);
                    bot.execute(new SendMessage(chatId, "🔐 Password set."));
                }
                session.setAwaitingWhat(null);
                return;
            }

            if (awaiting.startsWith("ADD_DIRECT_CODE:")) {
                String fid = awaiting.substring("ADD_DIRECT_CODE:".length());
                if (text.isBlank()) bot.execute(new SendMessage(chatId, "Code cannot be blank."));
                else {
                    securityStore.addDirectAccessCode(fid, text);
                    bot.execute(new SendMessage(chatId, "✅ Direct code added."));
                }
                session.setAwaitingWhat(null);
                return;
            }
        }

        // No pending prompt → show welcome to guide the user
        sendWelcomeMessage(chatId);
    }




    // ========================
// handleCallback(Callback)
// ========================
    private void handleCallback(CallbackQuery callback) {
        if (callback == null || callback.message() == null) return;

        long chatId = callback.message().chat().id();
        int messageId = callback.message().messageId();
        String data = (callback.data() != null) ? callback.data() : "";

        // ✅ Ensure session exists
        UserSession session = sessionManager.getSession(chatId);
        if (session == null) {
            session = sessionManager.createSession(chatId);
        }

        // ===== Global actions =====
        if ("finish".equals(data)) {
            sessionManager.removeSession(chatId); // or manually reset
            bot.execute(new SendMessage(chatId, "👋 Session finished. Use /start to begin again."));
            return;
        }

        if ("noop".equals(data)) return;

        if ("back".equals(data)) {
            // No back in DIRECT_ACCESS (button isn't shown there)
            Folder current = session.getCurrentFolder();
            if (current != null && current.getParent() != null) {
                Folder parent = current.getParent();
                session.setCurrentFolder(parent);
                InlineKeyboardMarkup kb = buildFolderKeyboard(
                        parent,
                        session.getMode() == UserSession.Mode.ADMIN && session.isAdminAuthenticated(),
                        false,
                        null
                );
                bot.execute(new EditMessageText(chatId, messageId, "📂 Folder: " + parent.getName()).replyMarkup(kb));
            } else {
                showRootFolders(chatId, messageId);
            }
            return;
        }

        // ===== Welcome menu =====
        if ("welcome:browse".equals(data)) {
            session.setMode(UserSession.Mode.REGULAR);
            session.setAdminAuthenticated(false);
            session.setDirectAccessRoot(null);
            session.setAwaitingWhat(null);
            showRootFolders(chatId, messageId);
            return;
        }

        if ("welcome:direct".equals(data)) {
            session.setMode(UserSession.Mode.DIRECT_ACCESS);
            session.setAdminAuthenticated(false);
            session.setDirectAccessRoot(null);
            session.setAwaitingWhat("DIRECT_FOLDER_NAME");

            // ✅ ensure roots loaded now
            ensureRootsLoaded(session);

            bot.execute(new SendMessage(chatId, "Type the folder name (or path) for Direct Access:\n" +
                    "• Example name: Subfolder1\n" +
                    "• Example path: Parent/Subfolder1\n" +
                    "• Or paste folder ID"));
            return;
        }


        if ("welcome:admin".equals(data)) {
            session.setMode(UserSession.Mode.ADMIN);
            session.setAdminAuthenticated(false);
            session.setAwaitingWhat("ADMIN_PWD");
            bot.execute(new SendMessage(chatId, "Enter admin password:"));
            return;
        }

        // ===== Admin menu =====
        if ("admin:browse".equals(data)) {
            if (!session.isAdminAuthenticated()) {
                bot.execute(new SendMessage(chatId, "You must authenticate first."));
                return;
            }
            showRootFolders(chatId, messageId);
            return;
        }

        if ("admin:change_pwd".equals(data)) {
            if (!session.isAdminAuthenticated()) {
                bot.execute(new SendMessage(chatId, "Not authenticated."));
                return;
            }
            session.setAwaitingWhat("CHANGE_ADMIN_PWD");
            bot.execute(new SendMessage(chatId, "Send the new admin password:"));
            return;
        }

        if ("admin:back".equals(data)) {
            session.setAwaitingWhat(null);
            session.setMode(UserSession.Mode.REGULAR);
            session.setAdminAuthenticated(false);
            session.setDirectAccessRoot(null);
            sendWelcomeMessage(chatId);
            return;
        }

        // ===== Folder navigation =====
        if (data.startsWith("folder:")) {
            String folderId = data.substring("folder:".length());

            // Restrict to subtree in DIRECT_ACCESS
            if (session.getMode() == UserSession.Mode.DIRECT_ACCESS && session.getDirectAccessRoot() != null) {
                if (!isDescendant(session.getDirectAccessRoot(), folderId)) {
                    bot.execute(new SendMessage(chatId, "🚫 You can only navigate inside the granted folder."));
                    return;
                }
            }

            if (folderRequiresPassword(session, folderId)) {
                session.setAwaitingWhat("FOLDER_PWD:" + folderId);
                session.setPendingFolderId(folderId);
                bot.execute(new SendMessage(chatId, "🔐 This folder is protected. Enter password:"));
                return;
            }

            Folder target = findFolderById(session.getRootFolders(), folderId);
            if (target != null) {
                session.setCurrentFolder(target);
                InlineKeyboardMarkup kb = buildFolderKeyboard(
                        target,
                        session.getMode() == UserSession.Mode.ADMIN && session.isAdminAuthenticated(),
                        session.getMode() == UserSession.Mode.DIRECT_ACCESS,
                        session.getDirectAccessRoot() != null ? session.getDirectAccessRoot().getId() : null
                );
                bot.execute(new EditMessageText(chatId, messageId, "📂 Folder: " + target.getName()).replyMarkup(kb));
            }
            return;
        }

        // ===== Admin actions on a folder =====
        if (data.startsWith("admin:setpwd:")) {
            if (!session.isAdminAuthenticated()) { bot.execute(new SendMessage(chatId, "Not authenticated.")); return; }
            String fid = data.substring("admin:setpwd:".length());
            session.setAwaitingWhat("SET_FOLDER_PWD:" + fid);
            bot.execute(new SendMessage(chatId, "Send new password for folder (or 'null' to remove):"));
            return;
        }

        if (data.startsWith("admin:getpwd:")) {
            if (!session.isAdminAuthenticated()) { bot.execute(new SendMessage(chatId, "Not authenticated.")); return; }
            String fid = data.substring("admin:getpwd:".length());
            String pw = securityStore.getFolderPassword(fid);
            bot.execute(new SendMessage(chatId, "Folder password: " + (pw == null ? "(none)" : pw)));
            return;
        }

        if (data.startsWith("admin:adddirect:")) {
            if (!session.isAdminAuthenticated()) { bot.execute(new SendMessage(chatId, "Not authenticated.")); return; }
            String fid = data.substring("admin:adddirect:".length());
            session.setAwaitingWhat("ADD_DIRECT_CODE:" + fid);
            bot.execute(new SendMessage(chatId, "Send a new Direct Access code for this folder:"));
            return;
        }

        if (data.startsWith("admin:listdirect:")) {
            if (!session.isAdminAuthenticated()) { bot.execute(new SendMessage(chatId, "Not authenticated.")); return; }
            String fid = data.substring("admin:listdirect:".length());
            var list = securityStore.getDirectAccessList(fid);
            if (list.isEmpty()) {
                bot.execute(new SendMessage(chatId, "No Direct Access codes for this folder."));
            } else {
                bot.execute(new SendMessage(chatId, "Direct Access codes:\n• " + String.join("\n• ", list)));
            }
            return;
        }

        // ===== Upload =====
        if ("upload".equals(data)) {
            if (session.getCurrentFolder() == null) {
                bot.execute(new SendMessage(chatId, "Choose a folder first, then tap 'Upload Here'."));
                return;
            }
            session.setWaitingForUpload(true);
            bot.execute(new SendMessage(chatId, "📤 Send the file now to upload into: " + session.getCurrentFolder().getName()));
            return;
        }

    }



    private InlineKeyboardMarkup buildFolderKeyboard(Folder folder, boolean admin, boolean directRestricted, String restrictedRootId) {
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup();

        // Show subfolders (respect restriction boundary when direct access)
        for (Folder sub : folder.getSubFolders()) {
            // In direct access, allow only descendants of restricted root
            if (directRestricted && restrictedRootId != null) {
                // always allowed if it's a child (we’re building from a folder already inside the subtree)
            }
            kb.addRow(new InlineKeyboardButton("📂 " + sub.getName())
                    .callbackData("folder:" + sub.getId()));
        }

        // Show files
        for (String fileName : folder.getFiles()) {
            kb.addRow(new InlineKeyboardButton("📄 " + fileName).callbackData("noop"));
        }

        // Upload
        kb.addRow(new InlineKeyboardButton("⬆ Upload Here").callbackData("upload"));

        // Admin extras
        if (admin) {
            kb.addRow(new InlineKeyboardButton("🔐 Set/Unset Password").callbackData("admin:setpwd:" + folder.getId()));
            kb.addRow(new InlineKeyboardButton("👁 Get Folder Password").callbackData("admin:getpwd:" + folder.getId()));
            kb.addRow(new InlineKeyboardButton("➕ Add Direct Code").callbackData("admin:adddirect:" + folder.getId()));
            kb.addRow(new InlineKeyboardButton("📜 List Direct Codes").callbackData("admin:listdirect:" + folder.getId()));
        }

        // Back button:
        if (!directRestricted) {
            if (folder.getParent() != null || folder.getSubFolders() != null) {
                kb.addRow(new InlineKeyboardButton("🔙 Back").callbackData("back"));
            }
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


    private void sendWelcomeMessage(long chatId) {
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(
                new InlineKeyboardButton[]{ new InlineKeyboardButton("📁 Browse").callbackData("welcome:browse") },
                new InlineKeyboardButton[]{ new InlineKeyboardButton("🔑 Direct Access").callbackData("welcome:direct") },
                new InlineKeyboardButton[]{ new InlineKeyboardButton("🛠 Admin").callbackData("welcome:admin") },
                new InlineKeyboardButton[]{ new InlineKeyboardButton("❌ Finish Session").callbackData("finish") }
        );
        bot.execute(new SendMessage(chatId,
                "Welcome! Choose an option:\n" +
                        "• 📁 Browse (regular)\n" +
                        "• 🔑 Direct Access (folder + code)\n" +
                        "• 🛠 Admin (extra options)")
                .replyMarkup(kb));
    }

    private InlineKeyboardMarkup adminHomeKeyboard() {
        return new InlineKeyboardMarkup(
                new InlineKeyboardButton[]{ new InlineKeyboardButton("📁 Browse (Admin)").callbackData("admin:browse") },
                new InlineKeyboardButton[]{ new InlineKeyboardButton("🔒 Change Admin Password").callbackData("admin:change_pwd") },
                new InlineKeyboardButton[]{ new InlineKeyboardButton("⬅ Back to Welcome").callbackData("admin:back") },
                new InlineKeyboardButton[]{ new InlineKeyboardButton("❌ Finish Session").callbackData("finish") }
        );
    }

    private boolean folderRequiresPassword(UserSession s, String folderId) {
        // ✅ Admins never need passwords
        if (s != null && s.getMode() == UserSession.Mode.ADMIN && s.isAdminAuthenticated()) {
            return false;
        }

        String required = securityStore.getFolderPassword(folderId);
        if (required == null || required.isBlank()) return false;

        // If not unlocked in this session, require password
        return !s.getUnlockedFolderIds().contains(folderId);
    }


    private void showRootFolders(long chatId, Integer maybeMessageIdToEdit) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();

        try {
            // Refresh root folders and store in the session
            var session = sessionManager.getSession(chatId);
            var roots = driveService.scanTopLevelFolders();
            if (session != null) session.setRootFolders(roots);

            for (Folder folder : roots) {
                keyboard.addRow(new InlineKeyboardButton("📂 " + folder.getName())
                        .callbackData("folder:" + folder.getId()));
            }

            keyboard.addRow(new InlineKeyboardButton("❌ Finish Session").callbackData("finish"));

            if (maybeMessageIdToEdit != null) {
                bot.execute(new EditMessageText(chatId, maybeMessageIdToEdit, "📂 Choose a folder:")
                        .replyMarkup(keyboard));
            } else {
                bot.execute(new SendMessage(chatId, "📂 Choose a folder:").replyMarkup(keyboard));
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private Folder findFolderByName(java.util.List<Folder> roots, String name) {
        for (Folder f : roots) {
            if (f.getName().equalsIgnoreCase(name)) return f;
            Folder sub = findFolderByName(f.getSubFolders(), name);
            if (sub != null) return sub;
        }
        return null;
    }

    private boolean isDescendant(Folder root, String targetId) {
        if (root.getId().equals(targetId)) return true;
        for (Folder f : root.getSubFolders()) {
            if (isDescendant(f, targetId)) return true;
        }
        return false;
    }

    private Folder findFolderByName(java.util.List<Folder> folders, String name, boolean ignoreCase) {
        for (Folder f : folders) {
            if ((ignoreCase ? f.getName().equalsIgnoreCase(name) : f.getName().equals(name))) return f;
            Folder sub = findFolderByName(f.getSubFolders(), name, ignoreCase);
            if (sub != null) return sub;
        }
        return null;
    }

    // Replace your current helper with this one
    private void uploadIncomingTelegramFile(Message msg, UserSession session) throws Exception {
        String fileId;
        String originalName;

        if (msg.document() != null) {
            Document d = msg.document();
            fileId = d.fileId();
            originalName = (d.fileName() != null && !d.fileName().isBlank())
                    ? d.fileName()
                    : ("document_" + d.fileUniqueId());
        } else if (msg.photo() != null && msg.photo().length > 0) {
            // Pick the largest photo variant
            PhotoSize p = msg.photo()[msg.photo().length - 1];
            fileId = p.fileId();
            originalName = "photo_" + p.fileUniqueId() + ".jpg";
        } else {
            throw new IllegalStateException("No file content to upload.");
        }

        // Get Telegram file bytes
        GetFileResponse getFileResp = bot.execute(new GetFile(fileId));
        if (!getFileResp.isOk() || getFileResp.file() == null) {
            throw new IllegalStateException("Telegram GetFile failed.");
        }
        com.pengrad.telegrambot.model.File tgFile = getFileResp.file();
        byte[] content = bot.getFileContent(tgFile);

        // Write to a temp file for your DriveService API
        java.io.File tmp = java.io.File.createTempFile(
                "tg_", "_" + originalName.replaceAll("[^a-zA-Z0-9._-]", "_"));
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
            fos.write(content);
        }

        // ✅ Correct order: (localFile, fileName, folderId)
        String folderId = session.getCurrentFolder().getId();
        String driveId = driveService.uploadFile(tmp, originalName, folderId);

        // Optional: you can log/confirm with driveId
        System.out.println("Uploaded to Drive. ID=" + driveId);

        // Cleanup temp
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
    }


    // Load roots into session if not already present or empty
    private void ensureRootsLoaded(UserSession session) {
        try {
            if (session.getRootFolders() == null || session.getRootFolders().isEmpty()) {
                var roots = driveService.scanTopLevelFolders();
                session.setRootFolders(roots);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load root folders", e);
        }
    }

    // Resolve by id, name (case-insensitive), or path "A/B/C"
    private Folder resolveFolderByInput(java.util.List<Folder> roots, String inputRaw) {
        if (roots == null) return null;
        String input = inputRaw.trim();

        // 1) exact id match anywhere
        Folder byId = findFolderById(roots, input);
        if (byId != null) return byId;

        // 2) path match: Parent/Sub/Child
        if (input.contains("/")) {
            String[] parts = java.util.Arrays.stream(input.split("/"))
                    .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
            if (parts.length > 0) {
                for (Folder r : roots) {
                    Folder hit = matchPath(r, parts, 0);
                    if (hit != null) return hit;
                }
            }
        }

        // 3) case-insensitive name match anywhere
        return findFolderByNameIgnoreCase(roots, input);
    }

    private Folder matchPath(Folder node, String[] parts, int idx) {
        if (node.getName().equalsIgnoreCase(parts[idx])) {
            if (idx == parts.length - 1) return node;
            for (Folder child : node.getSubFolders()) {
                Folder hit = matchPath(child, parts, idx + 1);
                if (hit != null) return hit;
            }
        }
        // try deeper without matching current (in case path doesn’t start at a root name)
        for (Folder child : node.getSubFolders()) {
            Folder hit = matchPath(child, parts, idx);
            if (hit != null) return hit;
        }
        return null;
    }

    private Folder findFolderByNameIgnoreCase(java.util.List<Folder> folders, String name) {
        if (folders == null) return null;
        for (Folder f : folders) {
            if (f.getName().equalsIgnoreCase(name)) return f;
            Folder sub = findFolderByNameIgnoreCase(f.getSubFolders(), name);
            if (sub != null) return sub;
        }
        return null;
    }

}
