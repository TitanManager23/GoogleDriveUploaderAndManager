package security;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, tiny JSON-backed credential store.
 * File format: security.json
 *
 *  {
 *    "adminPassword": "1234567890",
 *    "folders": {
 *      "<folderId>": {
 *        "password": "abc123",              // null or empty => no password
 *        "directAccess": ["x1y2", "z9z9"]   // zero or more codes
 *      }
 *    }
 *  }
 */
public class SecurityStore {

    public static final String DEFAULT_ADMIN_PASSWORD = "1234567890";
    private static final String FILE_NAME = "security.json";

    private final Object lock = new Object();
    private final ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final File file;

    private volatile String adminPassword;
    private final Map<String, FolderSecurity> folders = new ConcurrentHashMap<>();

    public static class FolderSecurity {
        public String password;               // null => no password required
        public Set<String> directAccess = new HashSet<>();
    }

    public SecurityStore() {
        this(new File(FILE_NAME));
    }

    public SecurityStore(File file) {
        this.file = file;
        loadOrInit();
    }

    private void loadOrInit() {
        synchronized (lock) {
            try {
                if (!file.exists() || Files.size(file.toPath()) == 0) {
                    this.adminPassword = DEFAULT_ADMIN_PASSWORD;
                    saveLocked();
                    return;
                }
                Map<String, Object> root = mapper.readValue(file, new TypeReference<>() {});
                Object ap = root.get("adminPassword");
                this.adminPassword = (ap instanceof String s && !s.isBlank()) ? s : DEFAULT_ADMIN_PASSWORD;

                Object f = root.get("folders");
                if (f instanceof Map<?, ?> raw) {
                    for (Map.Entry<?, ?> e : raw.entrySet()) {
                        String fid = String.valueOf(e.getKey());
                        Map<?, ?> v = (Map<?, ?>) e.getValue();
                        FolderSecurity fs = new FolderSecurity();
                        Object pw = v.get("password");
                        fs.password = (pw == null || String.valueOf(pw).isBlank()) ? null : String.valueOf(pw);
                        Object da = v.get("directAccess");
                        if (da instanceof Collection<?> col) {
                            for (Object o : col) fs.directAccess.add(String.valueOf(o));
                        }
                        folders.put(fid, fs);
                    }
                }
            } catch (Exception ex) {
                // On any parse error, reset to safe defaults
                this.adminPassword = DEFAULT_ADMIN_PASSWORD;
                folders.clear();
                saveLocked();
            }
        }
    }

    private void saveLocked() {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("adminPassword", adminPassword);
            Map<String, Object> f = new LinkedHashMap<>();
            for (var e : folders.entrySet()) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("password", e.getValue().password);
                v.put("directAccess", new ArrayList<>(e.getValue().directAccess));
                f.put(e.getKey(), v);
            }
            root.put("folders", f);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save security store", e);
        }
    }

    // === Admin password ===
    public boolean checkAdminPassword(String candidate) {
        return Objects.equals(candidate, adminPassword);
    }

    public void changeAdminPassword(String newPassword) {
        synchronized (lock) {
            this.adminPassword = newPassword;
            saveLocked();
        }
    }

    // === Folder credentials ===
    private FolderSecurity ensure(String folderId) {
        return folders.computeIfAbsent(folderId, k -> new FolderSecurity());
    }

    public String getFolderPassword(String folderId) {
        FolderSecurity fs = folders.get(folderId);
        return fs == null ? null : fs.password;
    }

    public void setFolderPassword(String folderId, String newPasswordOrNull) {
        synchronized (lock) {
            FolderSecurity fs = ensure(folderId);
            fs.password = (newPasswordOrNull == null || newPasswordOrNull.isBlank()) ? null : newPasswordOrNull;
            saveLocked();
        }
    }

    public Set<String> getDirectAccessList(String folderId) {
        FolderSecurity fs = folders.get(folderId);
        return fs == null ? Collections.emptySet() : Collections.unmodifiableSet(fs.directAccess);
    }

    public void addDirectAccessCode(String folderId, String code) {
        synchronized (lock) {
            FolderSecurity fs = ensure(folderId);
            fs.directAccess.add(code);
            saveLocked();
        }
    }

    public boolean hasDirectAccess(String folderId, String code) {
        FolderSecurity fs = folders.get(folderId);
        return fs != null && fs.directAccess.contains(code);
    }
}
