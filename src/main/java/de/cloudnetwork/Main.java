package de.cloudnetwork;

import de.cloudnetwork.config.CloudConfig;
import de.cloudnetwork.config.ConfigManager;
import de.cloudnetwork.console.ConsoleHandler;
import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.database.MongoDbDatabaseManager;
import de.cloudnetwork.database.MysqlDatabaseManager;
import de.cloudnetwork.gateway.GatewaySocketServer;
import de.cloudnetwork.hetzner.HetznerApiClient;
import de.cloudnetwork.scaling.ScalingMonitor;
import de.cloudnetwork.setup.SetupWizard;
import de.cloudnetwork.worker.WorkerInfo;
import de.cloudnetwork.worker.WorkerRegistry;

import java.io.IOException;
import java.net.InetAddress;

/**
 * Entry point for the CloudNetwork Hetzner console manager.
 */
public class Main {
    public static void main(String[] args) {
        printBanner();

        DatabaseManager dbManager = null;
        GatewaySocketServer socketServer = null;
        ScalingMonitor scalingMonitor = null;

        try {
            if (ConfigManager.configExists()) {
                dbManager = loadAndConnect();
            } else {
                SetupWizard wizard = new SetupWizard();
                dbManager = wizard.run();
            }

            int gatewayPort = readGatewayPort(dbManager);
            String gatewayHost = ensureGatewayHost(dbManager);
            String apiKey = dbManager.getConfigValue(DatabaseManager.HETZNER_API_KEY_NAME);
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("Hetzner API Key fehlt in der Datenbank.");
            }

            WorkerRegistry registry = new WorkerRegistry();
            for (WorkerInfo worker : dbManager.getAllWorkers()) {
                registry.register(worker);
            }

            HetznerApiClient hetzner = new HetznerApiClient(apiKey);
            socketServer = new GatewaySocketServer(registry, dbManager, gatewayPort);
            socketServer.start();

            scalingMonitor = new ScalingMonitor(registry, hetzner, dbManager, socketServer);
            scalingMonitor.start();

            System.out.println();
            System.out.println("[OK] CloudNetwork ist bereit. Gateway erreichbar unter " + gatewayHost + ":" + gatewayPort);
            ConsoleHandler consoleHandler = new ConsoleHandler(dbManager, registry, socketServer, hetzner);
            consoleHandler.setScalingMonitor(scalingMonitor);
            consoleHandler.run();
        } catch (Exception e) {
            System.err.println("[FEHLER] " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            if (socketServer != null) {
                socketServer.stop();
            }
            if (scalingMonitor != null) {
                scalingMonitor.stop();
            }
            if (dbManager != null) {
                dbManager.close();
            }
        }
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║         start cloud                  ║");
        System.out.println("║  CloudNetwork – Hetzner Console Mgr  ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println();
    }

    private static DatabaseManager loadAndConnect() throws IOException {
        System.out.println("Lade CloudConfig.json...");
        CloudConfig config;
        try {
            config = ConfigManager.load();
        } catch (IOException e) {
            throw new IOException("Konnte CloudConfig.json nicht laden: " + e.getMessage(), e);
        }

        String dbType = config.getDbType();
        System.out.println("Verbinde mit " + dbType + "-Datenbank " + config.getDbHost() + ":" + config.getDbPort() + " ...");
        DatabaseManager db = "mongodb".equals(dbType) ? new MongoDbDatabaseManager() : new MysqlDatabaseManager();
        try {
            db.connect(config.getDbHost(), config.getDbPort(), config.getDbName(), config.getDbUser(), config.getDbPassword());
            db.initSchema();
        } catch (Exception e) {
            db.close();
            throw new IOException("Datenbankverbindung fehlgeschlagen: " + e.getMessage(), e);
        }

        if (!db.isConnected()) {
            db.close();
            throw new IOException("Datenbankverbindung konnte nicht hergestellt werden.");
        }

        System.out.println("[OK] Datenbankverbindung hergestellt.");
        return db;
    }

    private static int readGatewayPort(DatabaseManager db) throws Exception {
        String gatewayPortValue = db.getConfigValue("gateway_port");
        int gatewayPort = 9876;
        if (gatewayPortValue != null && !gatewayPortValue.isBlank()) {
            try {
                gatewayPort = Integer.parseInt(gatewayPortValue.trim());
            } catch (NumberFormatException ignored) {
                gatewayPort = 9876;
            }
        }
        db.setConfigValue("gateway_port", String.valueOf(gatewayPort));
        return gatewayPort;
    }

    private static String ensureGatewayHost(DatabaseManager db) throws Exception {
        String gatewayHost = db.getConfigValue("gateway_host");
        if (gatewayHost == null || gatewayHost.isBlank()) {
            try {
                gatewayHost = InetAddress.getLocalHost().getHostAddress();
            } catch (Exception e) {
                gatewayHost = "127.0.0.1";
            }
            db.setConfigValue("gateway_host", gatewayHost);
        }
        return gatewayHost;
    }
}
