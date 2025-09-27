package model;

import java.util.ArrayList;
import java.util.List;

public class Folder {
    private String id;
    private String name;
    private Folder parent;
    private List<Folder> subFolders;
    private List<String> files;

    public Folder(String id, String name) {
        this.id = id;
        this.name = name;
        this.subFolders = new ArrayList<>();
        this.files = new ArrayList<>();
    }

    // Getters and setters
    public String getId() { return id; }
    public String getName() { return name; }
    public Folder getParent() { return parent; }
    public void setParent(Folder parent) { this.parent = parent; }
    public List<Folder> getSubFolders() { return subFolders; }
    public List<String> getFiles() { return files; }
}
