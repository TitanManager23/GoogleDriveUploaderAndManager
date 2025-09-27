package service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;

import com.google.api.client.http.FileContent;
import com.google.api.client.http.javanet.NetHttpTransport;

import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;

import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;

import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.Permission;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;

public class GoogleDriveUploader {

    private static final String APPLICATION_NAME = "TelegramBotDriveUploader";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";

    // Full DRIVE scope to allow scanning and uploading
    private static final List<String> SCOPES = Collections.singletonList(DriveScopes.DRIVE);
    private static final String CREDENTIALS_FILE_PATH = "/credentials.json";

    private final Drive service;

    public GoogleDriveUploader() throws IOException, GeneralSecurityException {
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        service = new Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private static com.google.api.client.auth.oauth2.Credential getCredentials(
            final NetHttpTransport HTTP_TRANSPORT) throws IOException {
        InputStream in = GoogleDriveUploader.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
        if (in == null) {
            throw new IOException("File 'credentials.json' not found in resources.");
        }

        GoogleClientSecrets clientSecrets =
                GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                .setAccessType("offline")
                .build();

        return new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
    }

    /**
     * Uploads a file to Google Drive and deletes the local file afterward.
     *
     * @param filePath Local file path
     * @param folderId Destination folder in Drive (null = root)
     * @return Public link to the uploaded file
     */
    public String uploadFile(String filePath, String originalFileName, String folderId) throws IOException {
        File fileMetadata = new File();
        fileMetadata.setName(originalFileName); // ✅ keep original filename
        if (folderId != null) {
            fileMetadata.setParents(Collections.singletonList(folderId));
        }

        java.io.File filePathObj = new java.io.File(filePath);
        String mimeType = java.nio.file.Files.probeContentType(filePathObj.toPath());
        if (mimeType == null) mimeType = "application/octet-stream";

        FileContent mediaContent = new FileContent(mimeType, filePathObj);

        File uploadedFile = service.files().create(fileMetadata, mediaContent)
                .setFields("id")
                .execute();

        // Make the file public
        Permission permission = new Permission()
                .setType("anyone")
                .setRole("reader");
        service.permissions().create(uploadedFile.getId(), permission).execute();

        return "https://drive.google.com/uc?id=" + uploadedFile.getId();
    }


    /** Expose Drive service for folder scanning */
    public Drive getDriveService() {
        return service;
    }
}
