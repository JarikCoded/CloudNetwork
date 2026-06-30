package de.cloudnetwork.storage;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;

import java.util.List;
import java.util.Properties;

/**
 * Manages the lifecycle of the Hetzner Storage Box used by CloudNetwork.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Reading / writing storage box credentials from/to the database.</li>
 *   <li>Creating the standard directory structure on the storage box via SFTP.</li>
 *   <li>Providing usage information via the Robot API.</li>
 * </ul>
 * </p>
 *
 * <h2>Standard directory layout</h2>
 * <pre>
 * /CloudNetwork/
 *   Templates/               ← JAR + config templates per instance
 *     &lt;instance-id&gt;/
 *       *.jar
 *       plugins/
 *       worlds/
 *   Static/                  ← Persistent per-instance data (worlds, player data)
 *     &lt;instance-id&gt;/
 *   Jars/                    ← Shared JARs, downloaded once and reused
 *     minecraft/
 *     velocity/
 *     plugins/
 *   Backups/                 ← Automatic / manual backups
 * </pre>
 *
 * <h2>DB config keys</h2>
 * <ul>
 *   <li>{@code storagebox_id}          – numeric Robot API ID</li>
 *   <li>{@code storagebox_host}        – SFTP hostname (u123456.your-storagebox.de)</li>
 *   <li>{@code storagebox_user}        – SFTP / SMB username</li>
 *   <li>{@code storagebox_pass}        – SFTP / SMB password</li>
 *   <li>{@code storagebox_product}     – current product code (BX11, BX21, …)</li>
 *   <li>{@code storagebox_robot_user}  – Hetzner Robot account username</li>
 *   <li>{@code storagebox_robot_pass}  – Hetzner Robot account password</li>
 * </ul>
 */
public class StorageBoxManager {

    // DB key constants
    public static final String KEY_ID         = "storagebox_id";
    public static final String KEY_HOST       = "storagebox_host";
    public static final String KEY_USER       = "storagebox_user";
    public static final String KEY_PASS       = "storagebox_pass";
    public static final String KEY_PRODUCT    = "storagebox_product";
    public static final String KEY_ROBOT_USER = "storagebox_robot_user";
    public static final String KEY_ROBOT_PASS = "storagebox_robot_pass";

    /** Initial directory tree that must exist on every fresh storage box. */
    private static final List<String> INITIAL_DIRS = List.of(
            "CloudNetwork",
            "CloudNetwork/Templates",
            "CloudNetwork/Static",
            "CloudNetwork/Jars",
            "CloudNetwork/Jars/minecraft",
            "CloudNetwork/Jars/velocity",
            "CloudNetwork/Jars/plugins",
            "CloudNetwork/Backups"
    );

    /** Product upgrade path (smallest → largest). */
    private static final List<String> UPGRADE_PATH = List.of(
            "BX11", "BX21", "BX31", "BX41", "BX61", "BX91"
    );

    private final DatabaseManager      db;
    private       HetznerRobotApiClient robotClient;

    public StorageBoxManager(DatabaseManager db) {
        this.db = db;
    }

    // ── Initialization ────────────────────────────────────────────────────────

    /**
     * Initialises the Robot API client from credentials stored in the DB.
     * Silent no-op when credentials are absent.
     */
    public void initRobotClient() {
        try {
            String user = db.getConfigValue(KEY_ROBOT_USER);
            String pass = db.getConfigValue(KEY_ROBOT_PASS);
            if (user != null && !user.isBlank() && pass != null && !pass.isBlank()) {
                robotClient = new HetznerRobotApiClient(user, pass);
            }
        } catch (Exception e) {
            ConsoleOutput.error("[FEHLER] Robot-API-Client konnte nicht initialisiert werden: " + e.getMessage());
        }
    }

    /**
     * Returns {@code true} when SFTP credentials are present in the DB, meaning
     * the storage box is ready to use.
     */
    public boolean isConfigured() {
        try {
            String host = db.getConfigValue(KEY_HOST);
            String user = db.getConfigValue(KEY_USER);
            String pass = db.getConfigValue(KEY_PASS);
            return host != null && !host.isBlank()
                    && user != null && !user.isBlank()
                    && pass != null && !pass.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    // ── SFTP operations ───────────────────────────────────────────────────────

    /**
     * Creates the standard CloudNetwork directory tree on the storage box
     * (idempotent – existing directories are silently skipped).
     *
     * @throws Exception when SFTP credentials are missing or the connection fails
     */
    public void createDirectoryStructure() throws Exception {
        String host = db.getConfigValue(KEY_HOST);
        String user = db.getConfigValue(KEY_USER);
        String pass = db.getConfigValue(KEY_PASS);
        if (host == null || host.isBlank() || user == null || user.isBlank()) {
            throw new IllegalStateException("Storage Box SFTP-Zugangsdaten fehlen.");
        }
        ConsoleOutput.info("[INFO] Erstelle Verzeichnisstruktur auf Storage Box (" + host + ")...");
        try (SftpSession sftp = new SftpSession(host, user, pass)) {
            for (String dir : INITIAL_DIRS) {
                sftp.mkdirIfMissing(dir);
            }
        }
        ConsoleOutput.info("[OK] Verzeichnisstruktur auf Storage Box angelegt.");
    }

    /**
     * Creates a template directory for the given instance ID
     * ({@code CloudNetwork/Templates/<instanceId>/plugins} and
     * {@code CloudNetwork/Templates/<instanceId>/worlds}).
     */
    public void createTemplateDirectory(String instanceId) throws Exception {
        String host = db.getConfigValue(KEY_HOST);
        String user = db.getConfigValue(KEY_USER);
        String pass = db.getConfigValue(KEY_PASS);
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("Storage Box SFTP-Zugangsdaten fehlen.");
        }
        try (SftpSession sftp = new SftpSession(host, user, pass)) {
            sftp.mkdirIfMissing("CloudNetwork/Templates/" + instanceId);
            sftp.mkdirIfMissing("CloudNetwork/Templates/" + instanceId + "/plugins");
            sftp.mkdirIfMissing("CloudNetwork/Templates/" + instanceId + "/worlds");
        }
        ConsoleOutput.info("[OK] Template-Verzeichnis erstellt: CloudNetwork/Templates/" + instanceId);
    }

    /**
     * Creates a static data directory for the given instance ID
     * ({@code CloudNetwork/Static/<instanceId>/}).
     */
    public void createStaticDirectory(String instanceId) throws Exception {
        String host = db.getConfigValue(KEY_HOST);
        String user = db.getConfigValue(KEY_USER);
        String pass = db.getConfigValue(KEY_PASS);
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("Storage Box SFTP-Zugangsdaten fehlen.");
        }
        try (SftpSession sftp = new SftpSession(host, user, pass)) {
            sftp.mkdirIfMissing("CloudNetwork/Static/" + instanceId);
        }
        ConsoleOutput.info("[OK] Static-Verzeichnis erstellt: CloudNetwork/Static/" + instanceId);
    }

    // ── Robot API ─────────────────────────────────────────────────────────────

    /**
     * Queries the current storage box usage from the Robot API.
     *
     * @return usage info, or {@code null} when the Robot API is not configured
     */
    public StorageBoxInfo getUsageInfo() throws Exception {
        if (robotClient == null) return null;
        String idStr = db.getConfigValue(KEY_ID);
        if (idStr == null || idStr.isBlank()) return null;
        long id = Long.parseLong(idStr.trim());
        return robotClient.getStorageBox(id);
    }

    // ── Credential helpers ────────────────────────────────────────────────────

    /** Persists storage box credentials to the DB. */
    public void saveCredentials(long storageBoxId, String host, String user, String pass,
                                String product) throws Exception {
        db.setConfigValue(KEY_ID,      String.valueOf(storageBoxId));
        db.setConfigValue(KEY_HOST,    host);
        db.setConfigValue(KEY_USER,    user);
        db.setConfigValue(KEY_PASS,    pass);
        db.setConfigValue(KEY_PRODUCT, product);
    }

    /** Persists Robot API credentials to the DB and re-initialises the client. */
    public void saveRobotCredentials(String robotUser, String robotPass) throws Exception {
        db.setConfigValue(KEY_ROBOT_USER, robotUser);
        db.setConfigValue(KEY_ROBOT_PASS, robotPass);
        this.robotClient = new HetznerRobotApiClient(robotUser, robotPass);
    }

    public HetznerRobotApiClient getRobotClient() { return robotClient; }

    // ── Upgrade path ──────────────────────────────────────────────────────────

    /**
     * Returns the next larger product code, or {@code null} when the storage
     * box is already at maximum size.
     */
    public static String nextUpgradeProduct(String currentProduct) {
        if (currentProduct == null) return "BX21";
        int idx = UPGRADE_PATH.indexOf(currentProduct.toUpperCase());
        if (idx < 0 || idx >= UPGRADE_PATH.size() - 1) return null;
        return UPGRADE_PATH.get(idx + 1);
    }

    // ── Internal SFTP helper ──────────────────────────────────────────────────

    /** Lightweight, {@link AutoCloseable} SFTP session backed by JSch. */
    private static final class SftpSession implements AutoCloseable {

        private final Session     session;
        private final ChannelSftp channel;

        SftpSession(String host, String user, String pass) throws Exception {
            JSch jsch = new JSch();
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "no");
            session = jsch.getSession(user, host, 22);
            session.setPassword(pass);
            session.setConfig(config);
            session.connect(30_000);
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(15_000);
        }

        /**
         * Creates {@code path} relative to the storage box root if it does not
         * yet exist. Errors are logged but not rethrown.
         */
        void mkdirIfMissing(String path) {
            try {
                channel.stat(path);
            } catch (SftpException e) {
                // Directory does not exist – create it
                try {
                    channel.mkdir(path);
                    ConsoleOutput.info("  [OK] Erstellt: " + path);
                } catch (SftpException ex) {
                    ConsoleOutput.error("  [FEHLER] Konnte Verzeichnis nicht erstellen: " + path
                            + " – " + ex.getMessage());
                }
            }
        }

        @Override
        public void close() {
            try { if (channel != null && channel.isConnected()) channel.disconnect(); } catch (Exception ignored) {}
            try { if (session != null && session.isConnected()) session.disconnect();  } catch (Exception ignored) {}
        }
    }
}
