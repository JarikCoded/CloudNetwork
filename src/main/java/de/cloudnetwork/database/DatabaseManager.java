package de.cloudnetwork.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages the JDBC connection to the MySQL / MariaDB database and provides
 * helper methods for reading and writing the {@code cloud_config} table that
 * stores the Hetzner API key.
 */
public class DatabaseManager implements AutoCloseable {

    /** Name of the config table used to persist the Hetzner API key. */
    private static final String CONFIG_TABLE = "cloud_config";

    /** Key name used to store the Hetzner API key in the config table. */
    public static final String HETZNER_API_KEY_NAME = "hetzner_api_key";

    private Connection connection;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Opens a JDBC connection to the given MySQL / MariaDB server.
     *
     * @throws SQLException on connection errors
     */
    public void connect(String host, int port, String database,
                        String user, String password) throws SQLException {

        String url = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC&connectTimeout=10000",
                host, port, database);

        connection = DriverManager.getConnection(url, user, password);
    }

    /** Returns {@code true} when the connection is open and valid. */
    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed() && connection.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {}
        }
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    /**
     * Creates the {@code cloud_config} table if it does not already exist.
     *
     * @throws SQLException on database errors
     */
    public void initSchema() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS " + CONFIG_TABLE + " ("
                + "config_key   VARCHAR(255) NOT NULL PRIMARY KEY, "
                + "config_value TEXT         NOT NULL, "
                + "created_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    // ── Config values ─────────────────────────────────────────────────────────

    /**
     * Retrieves a config value by key, or {@code null} when the key is absent.
     *
     * @throws SQLException on database errors
     */
    public String getConfigValue(String key) throws SQLException {
        String sql = "SELECT config_value FROM " + CONFIG_TABLE
                + " WHERE config_key = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("config_value") : null;
            }
        }
    }

    /**
     * Inserts or updates a config value (upsert).
     *
     * @throws SQLException on database errors
     */
    public void setConfigValue(String key, String value) throws SQLException {
        // Use the row alias syntax (MySQL 8.0.20+ / MySQL 9.x compatible).
        // VALUES() in ON DUPLICATE KEY UPDATE was deprecated in 8.0.20 and
        // removed in 9.0.
        String sql = "INSERT INTO " + CONFIG_TABLE
                + " (config_key, config_value) VALUES (?, ?) AS new_row "
                + "ON DUPLICATE KEY UPDATE config_value = new_row.config_value";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    /**
     * Convenience: returns {@code true} when the Hetzner API key row exists
     * in the config table.
     *
     * @throws SQLException on database errors
     */
    public boolean hasHetznerApiKey() throws SQLException {
        return getConfigValue(HETZNER_API_KEY_NAME) != null;
    }
}
