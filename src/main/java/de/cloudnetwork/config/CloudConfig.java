package de.cloudnetwork.config;

/**
 * Holds the database connection parameters and the Hetzner server ID
 * that are persisted in {@code CloudConfig.json}.
 *
 * <p><b>Security note:</b> The database password is stored in plaintext.
 * {@link de.cloudnetwork.config.ConfigManager#save} restricts the file to
 * owner read/write only (mode 600 on POSIX systems).  For higher-security
 * environments consider replacing the password field with a reference to an
 * environment variable or a system keyring entry.</p>
 */
public class CloudConfig {

    private String dbHost;
    private int    dbPort;
    private String dbName;
    private String dbUser;
    private String dbPassword;
    /** Optional: the ID of the Hetzner server that was automatically created. */
    private Long   hetznerServerId;

    public CloudConfig() {}

    public CloudConfig(String dbHost, int dbPort, String dbName,
                       String dbUser, String dbPassword) {
        this.dbHost     = dbHost;
        this.dbPort     = dbPort;
        this.dbName     = dbName;
        this.dbUser     = dbUser;
        this.dbPassword = dbPassword;
    }

    // ── getters & setters ────────────────────────────────────────────────────

    public String getDbHost()     { return dbHost; }
    public void   setDbHost(String dbHost) { this.dbHost = dbHost; }

    public int    getDbPort()     { return dbPort; }
    public void   setDbPort(int dbPort) { this.dbPort = dbPort; }

    public String getDbName()     { return dbName; }
    public void   setDbName(String dbName) { this.dbName = dbName; }

    public String getDbUser()     { return dbUser; }
    public void   setDbUser(String dbUser) { this.dbUser = dbUser; }

    public String getDbPassword() { return dbPassword; }
    public void   setDbPassword(String dbPassword) { this.dbPassword = dbPassword; }

    public Long   getHetznerServerId() { return hetznerServerId; }
    public void   setHetznerServerId(Long hetznerServerId) {
        this.hetznerServerId = hetznerServerId;
    }
}
