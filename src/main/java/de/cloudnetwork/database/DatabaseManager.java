package de.cloudnetwork.database;

/**
 * Common interface for all database backends supported by CloudNetwork.
 *
 * <p>Implementations must be {@link AutoCloseable} so that callers can use
 * try-with-resources or explicit {@link #close()} calls to release resources.</p>
 */
public interface DatabaseManager extends AutoCloseable {

    /** Key name used to store the Hetzner API key in the config collection/table. */
    String HETZNER_API_KEY_NAME = "hetzner_api_key";

    /**
     * Opens a connection to the database server.
     *
     * @param host     hostname or IP address
     * @param port     TCP port
     * @param database database / schema name
     * @param user     username (may be empty for MongoDB without auth)
     * @param password password (may be empty for MongoDB without auth)
     * @throws Exception on connection errors
     */
    void connect(String host, int port, String database,
                 String user, String password) throws Exception;

    /** Returns {@code true} when the connection is open and valid. */
    boolean isConnected();

    /** Releases all resources held by this manager. */
    @Override
    void close();

    /**
     * Creates the necessary schema/collection for storing config values.
     * On schema-less databases this may be a no-op.
     *
     * @throws Exception on database errors
     */
    void initSchema() throws Exception;

    /**
     * Retrieves a config value by key, or {@code null} when the key is absent.
     *
     * @throws Exception on database errors
     */
    String getConfigValue(String key) throws Exception;

    /**
     * Inserts or updates a config value (upsert).
     *
     * @throws Exception on database errors
     */
    void setConfigValue(String key, String value) throws Exception;

    /**
     * Convenience: returns {@code true} when the Hetzner API key entry exists.
     *
     * @throws Exception on database errors
     */
    default boolean hasHetznerApiKey() throws Exception {
        return getConfigValue(HETZNER_API_KEY_NAME) != null;
    }
}

