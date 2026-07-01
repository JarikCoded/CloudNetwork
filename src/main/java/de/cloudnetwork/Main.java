package de.cloudnetwork;

import de.cloudnetwork.config.CloudConfig;
import de.cloudnetwork.config.ConfigManager;
import de.cloudnetwork.console.ConsoleHandler;
import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.database.MongoDbDatabaseManager;
import de.cloudnetwork.database.MysqlDatabaseManager;
import de.cloudnetwork.gateway.GatewaySocketServer;
import de.cloudnetwork.hetzner.HetznerApiClient;
import de.cloudnetwork.scaling.ScalingMonitor;
import de.cloudnetwork.setup.SetupWizard;
import de.cloudnetwork.storage.StorageBoxManager;
import de.cloudnetwork.storage.StorageBoxMonitor;
import de.cloudnetwork.worker.WorkerInfo;
import de.cloudnetwork.worker.WorkerRegistry;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Entry point for the CloudNetwork Hetzner console manager.
 */
public class Main {
    public static void main(String[] args) {
        printBanner();

        DatabaseManager dbManager = null;
        GatewaySocketServer socketServer = null;
        ScalingMonitor scalingMonitor = null;
        StorageBoxMonitor storageBoxMonitor = null;

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

            // Storage Box
            StorageBoxManager storageBoxManager = new StorageBoxManager(dbManager);
            storageBoxManager.initRobotClient();
            if (storageBoxManager.isConfigured()) {
                ConsoleOutput.info("[OK] Storage Box konfiguriert (" + dbManager.getConfigValue(StorageBoxManager.KEY_HOST) + ").");
                storageBoxMonitor = new StorageBoxMonitor(storageBoxManager);
                storageBoxMonitor.start();
            } else {
                ConsoleOutput.info("[INFO] Keine Storage Box konfiguriert. Nutze 'storagebox setup' zum Einrichten.");
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

            ConsoleOutput.info("");
            ConsoleOutput.info("[OK] CloudNetwork wurde erfolgreich konfiguriert. Gateway erreichbar unter " + gatewayHost + ":" + gatewayPort);
            ConsoleHandler consoleHandler = new ConsoleHandler(dbManager, registry, socketServer, hetzner, storageBoxManager);
            consoleHandler.setScalingMonitor(scalingMonitor);
            consoleHandler.run();
        } catch (Exception e) {
            ConsoleOutput.error("[FEHLER] " + e.getMessage());
            ConsoleOutput.logException(e);
            System.exit(1);
        } finally {
            if (socketServer != null) {
                socketServer.stop();
            }
            if (scalingMonitor != null) {
                scalingMonitor.stop();
            }
            if (storageBoxMonitor != null) {
                storageBoxMonitor.stop();
            }
            if (dbManager != null) {
                dbManager.close();
            }
        }
    }

    private static void printBanner() {
        ConsoleOutput.info("");
        ConsoleOutput.info("╔══════════════════════════════════════╗");
        ConsoleOutput.info("║         start cloud                  ║");
        ConsoleOutput.info("║  CloudNetwork – Hetzner Console Mgr  ║");
        ConsoleOutput.info("╚══════════════════════════════════════╝");
        ConsoleOutput.info("");
    }

    private static DatabaseManager loadAndConnect() throws IOException {
        ConsoleOutput.info("Lade CloudConfig.json...");
        CloudConfig config;
        try {
            config = ConfigManager.load();
        } catch (IOException e) {
            throw new IOException("Konnte CloudConfig.json nicht laden: " + e.getMessage(), e);
        }

        String dbType = config.getDbType();
        ConsoleOutput.info("Verbinde mit " + dbType + "-Datenbank " + config.getDbHost() + ":" + config.getDbPort() + " ...");
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

        ConsoleOutput.info("[OK] Datenbankverbindung hergestellt.");
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
            gatewayHost = detectPublicIp();
            db.setConfigValue("gateway_host", gatewayHost);
        }
        return gatewayHost;
    }

    private static String detectPublicIp() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.ipify.org?format=text"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String ip = response.body().trim();
                String octet = "(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)";
                if (ip.matches(octet + "\\." + octet + "\\." + octet + "\\." + octet)) {
                    return ip;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}
