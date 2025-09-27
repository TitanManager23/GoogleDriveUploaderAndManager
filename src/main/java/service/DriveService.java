package service;

import model.Folder;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class DriveService {

    private final GoogleDriveUploader uploader;
    private final Drive driveService;

    public DriveService() throws Exception {
        this.uploader = new GoogleDriveUploader();
        this.driveService = uploader.getDriveService();
    }

    /** Scan top-level folders in My Drive */
    public List<Folder> scanTopLevelFolders() throws IOException {
        List<Folder> topFolders = new ArrayList<>();

        String query = "mimeType = 'application/vnd.google-apps.folder' " +
                "and 'root' in parents and trashed = false";

        FileList result = driveService.files()
                .list()
                .setQ(query)
                .setFields("files(id, name)")
                .execute();

        for (File file : result.getFiles()) {
            Folder folder = new Folder(file.getId(), file.getName());
            buildFolderTree(folder);
            topFolders.add(folder);
        }
        return topFolders;
    }

    /** Recursively builds folder/file tree */
    private void buildFolderTree(Folder parentFolder) throws IOException {
        String query = String.format("'%s' in parents and trashed = false", parentFolder.getId());

        FileList result = driveService.files()
                .list()
                .setQ(query)
                .setFields("files(id, name, mimeType)")
                .execute();

        for (File file : result.getFiles()) {
            if ("application/vnd.google-apps.folder".equals(file.getMimeType())) {
                // Create subfolder and link back to parent
                Folder subFolder = new Folder(file.getId(), file.getName());
                subFolder.setParent(parentFolder);
                parentFolder.getSubFolders().add(subFolder);

                // Recursive scan
                buildFolderTree(subFolder);
            } else {
                // Add regular file names to list
                parentFolder.getFiles().add(file.getName());
            }
        }
    }

    /** Upload a file to Google Drive (uses original filename) */
    public String uploadFile(java.io.File localFile, String fileName, String folderId) throws IOException {
        return uploader.uploadFile(localFile.getAbsolutePath(), fileName, folderId);
    }

}
