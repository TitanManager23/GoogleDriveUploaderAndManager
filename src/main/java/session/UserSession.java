package session;

import model.Folder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class UserSession {

    private final long chatId;
    private Folder currentFolder;
    private List<Folder> rootFolders = new ArrayList<>();
    private boolean waitingForUpload = false;
    private Instant lastActivity;

    public UserSession(long chatId) {
        this.chatId = chatId;
        this.lastActivity = Instant.now();
    }

    public long getChatId() {
        return chatId;
    }

    public Folder getCurrentFolder() {
        return currentFolder;
    }

    public void setCurrentFolder(Folder currentFolder) {
        this.currentFolder = currentFolder;
        touch();
    }

    public List<Folder> getRootFolders() {
        return rootFolders;
    }

    public void setRootFolders(List<Folder> rootFolders) {
        this.rootFolders = rootFolders;
        touch();
    }

    public boolean isWaitingForUpload() {
        return waitingForUpload;
    }

    public void setWaitingForUpload(boolean waitingForUpload) {
        this.waitingForUpload = waitingForUpload;
        touch();
    }

    public Instant getLastActivity() {
        return lastActivity;
    }

    public void touch() {
        this.lastActivity = Instant.now();
    }


    /**
     * Security update:
     */

    // add near the top
    public enum Mode { REGULAR, ADMIN, DIRECT_ACCESS }

    // add fields
    private Mode mode = Mode.REGULAR;
    private boolean adminAuthenticated = false;

    // for unlocking folders this session
    private final java.util.Set<String> unlockedFolderIds = new java.util.HashSet<>();

    // pending prompts
    private String awaitingWhat = null;          // e.g., "ADMIN_PWD", "FOLDER_PWD:<id>", "CHANGE_ADMIN_PWD", "SET_FOLDER_PWD:<id>", "ADD_DIRECT_CODE:<id>", "DIRECT_FOLDER_NAME", "DIRECT_CODE:<folderId>"
    private String pendingFolderId = null;       // used by several flows
    private model.Folder directAccessRoot = null;

    // getters/setters
    public Mode getMode() { return mode; }
    public void setMode(Mode m) { this.mode = m; touch(); }

    public boolean isAdminAuthenticated() { return adminAuthenticated; }
    public void setAdminAuthenticated(boolean v) { this.adminAuthenticated = v; touch(); }

    public java.util.Set<String> getUnlockedFolderIds() { return unlockedFolderIds; }

    public String getAwaitingWhat() { return awaitingWhat; }
    public void setAwaitingWhat(String a) { this.awaitingWhat = a; touch(); }

    public String getPendingFolderId() { return pendingFolderId; }
    public void setPendingFolderId(String id) { this.pendingFolderId = id; touch(); }

    public model.Folder getDirectAccessRoot() { return directAccessRoot; }
    public void setDirectAccessRoot(model.Folder f) { this.directAccessRoot = f; touch(); }

}
