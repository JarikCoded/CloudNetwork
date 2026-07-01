package de.cloudnetwork.database;

import de.cloudnetwork.worker.WorkerInfo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link DatabaseManager} implementation backed by MySQL / MariaDB via JDBC.
 */
public class MysqlDatabaseManager implements DatabaseManager {
    private static final String CONFIG_TABLE = "cloud_config";
    private static final String WORKERS_TABLE = "workers";

    private Connection connection;

    @Override
    public void connect(String host, int port, String database,
                        String user, String password) throws SQLException {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException ignored) {
            }
        }
        String url = String.format(
                "jdbc:mysql://%s:%d/%s?sslMode=PREFERRED&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000",
                host, port, database);
        connection = DriverManager.getConnection(url, user, password);
    }

    @Override
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
            } catch (SQLException ignored) {
            }
        }
    }

    @Override
    public void initSchema() throws SQLException {
        String configSql = "CREATE TABLE IF NOT EXISTS " + CONFIG_TABLE + " ("
                + "config_key   VARCHAR(255) NOT NULL PRIMARY KEY, "
                + "config_value TEXT         NOT NULL, "
                + "created_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
        String workersSql = "CREATE TABLE IF NOT EXISTS " + WORKERS_TABLE + " ("
                + "worker_id           VARCHAR(255) NOT NULL PRIMARY KEY, "
                + "ipv4                VARCHAR(255) NULL, "
                + "hetzner_server_id   BIGINT       DEFAULT 0, "
                + "status              VARCHAR(32)  NOT NULL, "
                + "cpu_percent         DOUBLE       DEFAULT 0, "
                + "ram_percent         DOUBLE       DEFAULT 0, "
                + "player_count        INT          DEFAULT 0, "
                + "last_heartbeat_ms   BIGINT       DEFAULT 0, "
                + "auth_token          VARCHAR(255) NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(configSql);
            stmt.execute(workersSql);
        }
    }

    @Override
    public String getConfigValue(String key) throws SQLException {
        String sql = "SELECT config_value FROM " + CONFIG_TABLE + " WHERE config_key = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("config_value") : null;
            }
        }
    }

    @Override
    public void setConfigValue(String key, String value) throws SQLException {
        String sql = "INSERT INTO " + CONFIG_TABLE
                + " (config_key, config_value) VALUES (?, ?) AS new_row "
                + "ON DUPLICATE KEY UPDATE config_value = new_row.config_value";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    @Override
    public void saveWorker(WorkerInfo worker) throws SQLException {
        String sql = "INSERT INTO " + WORKERS_TABLE + " (worker_id, ipv4, hetzner_server_id, status, cpu_percent, ram_percent, player_count, last_heartbeat_ms, auth_token) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) AS new_row "
                + "ON DUPLICATE KEY UPDATE "
                + "ipv4 = new_row.ipv4, hetzner_server_id = new_row.hetzner_server_id, status = new_row.status, "
                + "cpu_percent = new_row.cpu_percent, ram_percent = new_row.ram_percent, player_count = new_row.player_count, "
                + "last_heartbeat_ms = new_row.last_heartbeat_ms, auth_token = new_row.auth_token";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, worker.getId());
            ps.setString(2, worker.getIpv4());
            ps.setLong(3, worker.getHetznerServerId());
            ps.setString(4, worker.getStatus() != null ? worker.getStatus().name() : WorkerInfo.WorkerStatus.OFFLINE.name());
            ps.setDouble(5, worker.getCpuPercent());
            ps.setDouble(6, worker.getRamPercent());
            ps.setInt(7, worker.getPlayerCount());
            ps.setLong(8, worker.getLastHeartbeatMs());
            ps.setString(9, worker.getAuthToken());
            ps.executeUpdate();
        }
    }

    @Override
    public WorkerInfo getWorker(String workerId) throws SQLException {
        String sql = "SELECT * FROM " + WORKERS_TABLE + " WHERE worker_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, workerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapWorker(rs) : null;
            }
        }
    }

    @Override
    public List<WorkerInfo> getAllWorkers() throws SQLException {
        List<WorkerInfo> workers = new ArrayList<>();
        String sql = "SELECT * FROM " + WORKERS_TABLE;
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                workers.add(mapWorker(rs));
            }
        }
        return workers;
    }

    @Override
    public void updateWorkerStatus(String workerId, String status) throws SQLException {
        String sql = "UPDATE " + WORKERS_TABLE + " SET status = ?, last_heartbeat_ms = ? WHERE worker_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, workerId);
            ps.executeUpdate();
        }
    }

    private WorkerInfo mapWorker(ResultSet rs) throws SQLException {
        WorkerInfo worker = new WorkerInfo();
        worker.setId(rs.getString("worker_id"));
        worker.setIpv4(rs.getString("ipv4"));
        worker.setHetznerServerId(rs.getLong("hetzner_server_id"));
        String status = rs.getString("status");
        if (status != null && !status.isBlank()) {
            try {
                worker.setStatus(WorkerInfo.WorkerStatus.valueOf(status));
            } catch (IllegalArgumentException ignored) {
                worker.setStatus(WorkerInfo.WorkerStatus.OFFLINE);
            }
        }
        worker.setCpuPercent(rs.getDouble("cpu_percent"));
        worker.setRamPercent(rs.getDouble("ram_percent"));
        worker.setPlayerCount(rs.getInt("player_count"));
        worker.setLastHeartbeatMs(rs.getLong("last_heartbeat_ms"));
        worker.setAuthToken(rs.getString("auth_token"));
        return worker;
    }
}
