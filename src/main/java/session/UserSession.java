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
}
