package de.cloudnetwork.setup;

import de.cloudnetwork.config.CloudConfig;
import de.cloudnetwork.config.ConfigManager;
import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.database.MongoDbDatabaseManager;
import de.cloudnetwork.database.MysqlDatabaseManager;
import de.cloudnetwork.hetzner.HetznerApiClient;
import de.cloudnetwork.hetzner.HetznerServer;
import de.cloudnetwork.worker.WorkerInfo;
import org.bson.Document;

import java.io.Console;
import java.io.IOException;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Scanner;

/**
 * Interactive setup wizard that is shown whenever {@code CloudConfig.json} is
 * absent.  The user can choose between two modes:
 *
 * <ol>
 *   <li><b>automatisch</b> – A Hetzner API key is requested, validated, a new
 *       cloud server is created, MongoDB plus web UI are installed via
 *       cloud-init, the
 *       connection info is saved to {@code CloudConfig.json} and the API key
 *       is stored in the database.</li>
 *   <li><b>hinzufügen</b> – The user supplies existing database credentials,
 *       the connection is tested, the Hetzner API key is read from the database
 *       (or requested when absent), validated and then saved.</li>
 * </ol>
 */
public class SetupWizard {

    private final Scanner scanner;

    public SetupWizard() {
        this.scanner = new Scanner(System.in);
    }

    /**
     * Runs the complete setup and returns a connected {@link DatabaseManager}.
     * The caller is responsible for closing the manager when done.
     *
     * @throws Exception on unrecoverable errors
     */
    public DatabaseManager run() throws Exception {
        printHeader();

        String choice = promptChoice();

        return switch (choice) {
            case "automatisch" -> runAutoSetup();
            case "hinzufügen"  -> runManualSetup();
            default -> {
                ConsoleOutput.info("[Fehler] Ungültige Auswahl. Bitte 'automatisch' oder 'hinzufügen' eingeben.");
                yield run();
            }
        };
    }

    // ── Mode: automatic ───────────────────────────────────────────────────────

    private DatabaseManager runAutoSetup() throws Exception {
        ConsoleOutput.info("");
        ConsoleOutput.info("=== Automatische Einrichtung ===");

        String apiKey = requestAndValidateHetznerKey();
        String dbUser = "cloudnetwork";
        String dbPass = generateHexSecret(24);
        String dbName = "cloudnetwork";
        int    dbPort = 27017;

        ConsoleOutput.info("[OK] Hetzner API Key ist gültig.");
        ConsoleOutput.info("Erstelle Hetzner Cloud Server...");

        HetznerApiClient hetzner = new HetznerApiClient(apiKey);
        HetznerServer server;
        try {
            server = hetzner.createServer("CloudNetwork-Datenbank-01", dbUser, dbPass, dbName);
        } catch (IOException e) {
            throw new Exception("Server-Erstellung fehlgeschlagen: " + e.getMessage(), e);
        }

        ConsoleOutput.info("[OK] Server erstellt. ID=" + server.getId()
                + "  IP=" + server.getIpv4());

        // Wait until the server is running
        String ip = hetzner.waitForServerRunning(server.getId());
        ConsoleOutput.info("[OK] Server läuft unter " + ip);

        ConsoleOutput.info("[OK] MongoDB-Zugangsdaten wurden automatisch erzeugt.");

        // Automatic setup now provisions MongoDB.
        MongoDbDatabaseManager dbManager = new MongoDbDatabaseManager();

        // Poll until MongoDB is accepting connections (cloud-init may still be
        // running — apt-get + Docker image pull + MongoDB startup can take 20-30
        // minutes); retry every 15 s for up to 30 minutes.
        ConsoleOutput.info("Warte auf MongoDB-Bereitschaft (max. 30 Minuten)...");
        connectWithRetry(dbManager, ip, dbPort, dbName, dbUser, dbPass, 120, 15_000);

        dbManager.initSchema();
        dbManager.setConfigValue(DatabaseManager.HETZNER_API_KEY_NAME, apiKey);
        ConsoleOutput.info("[OK] API Key in Datenbank gespeichert.");

        bootstrapInitialWorkerAndInstances(dbManager, hetzner);

        CloudConfig config = new CloudConfig(ip, dbPort, dbName, dbUser, dbPass);
        config.setDbType("mongodb");
        config.setHetznerServerId(server.getId());
        ConfigManager.save(config);
        ConsoleOutput.info("[OK] CloudConfig.json wurde gespeichert.");
        ConsoleOutput.info("MongoDB Weboberfläche:");
        ConsoleOutput.info("  URL: http://" + ip + ":8081");
        ConsoleOutput.info("  Benutzer: " + dbUser);
        ConsoleOutput.info("  Passwort: steht in CloudConfig.json");

        return dbManager;
    }

    private void bootstrapInitialWorkerAndInstances(MongoDbDatabaseManager dbManager, HetznerApiClient hetzner) throws Exception {
        WorkerIdentity workerIdentity = nextWorkerIdentity(dbManager);
        String workerId = workerIdentity.workerId();
        String workerName = workerIdentity.serverName();
        String authToken = generateHexSecret(24);
        String gatewayHost = detectGatewayIp();
        int gatewayPort = 9876;

        dbManager.setConfigValue("gateway_host", gatewayHost);
        dbManager.setConfigValue("gateway_port", String.valueOf(gatewayPort));

        WorkerInfo worker = new WorkerInfo();
        worker.setId(workerId);
        worker.setAuthToken(authToken);
        worker.setStatus(WorkerInfo.WorkerStatus.PROVISIONING);
        worker.setLastHeartbeatMs(System.currentTimeMillis());
        dbManager.saveWorker(worker);

        ConsoleOutput.info("[INFO] Erstelle ersten Worker " + workerName + " ...");
        HetznerServer workerServer = hetzner.createWorkerServer(workerName, workerId, authToken, gatewayHost, gatewayPort);
        String workerIp = hetzner.waitForServerRunning(workerServer.getId());
        worker.setHetznerServerId(workerServer.getId());
        worker.setIpv4(workerIp);
        dbManager.saveWorker(worker);
        ConsoleOutput.info("[OK] Erster Worker erstellt: " + workerId + " (" + workerIp + ")");

        createInitialInstance(dbManager, "velocity-01", "Velocity01", "VELOCITY", workerId, 25565, null);
        createInitialInstance(dbManager, "lobby-01", "Lobby01", "MINECRAFT", workerId, 25566, "velocity-01");
        dbManager.setConfigValue("bootstrap_initial_worker_id", workerId);
        ConsoleOutput.info("[OK] Erste Instanzen vorbereitet: Velocity01 + Lobby01.");
    }

    private void createInitialInstance(MongoDbDatabaseManager dbManager,
                                       String id,
                                       String name,
                                       String type,
                                       String workerId,
                                       int port,
                                       String proxyTarget) {
        Document instance = new Document("_id", id)
                .append("id", id)
                .append("name", name)
                .append("type", type.toUpperCase(Locale.ROOT))
                .append("workerId", workerId)
                .append("assignedWorkerId", workerId)
                .append("status", "PENDING_START")
                .append("autoStart", true)
                .append("port", port)
                .append("createdAt", System.currentTimeMillis());
        if (proxyTarget != null && !proxyTarget.isBlank()) {
            instance.append("proxyTarget", proxyTarget);
        }
        dbManager.upsertMinecraftInstance(instance);
    }

    // ── Mode: manual ──────────────────────────────────────────────────────────

    private DatabaseManager runManualSetup() throws Exception {
        ConsoleOutput.info("");
        ConsoleOutput.info("=== Datenbank hinzufügen ===");

        String dbType = promptDbType();

        String host = prompt("Datenbank-Host (z.B. 192.168.1.10): ");
        int    port = promptInt(defaultPortHint(dbType), defaultPort(dbType));
        String name = prompt("Datenbankname [cloudnetwork]: ");
        if (name.isBlank()) name = "cloudnetwork";
        String user = prompt("Benutzer (leer lassen für keine Authentifizierung): ");
        String pass = user.isBlank() ? "" : promptSecret("Passwort: ");

        DatabaseManager dbManager = createManager(dbType);

        ConsoleOutput.info("Teste Datenbankverbindung...");
        connectWithRetry(dbManager, host, port, name, user, pass, 1, 0);
        ConsoleOutput.info("[OK] Datenbankverbindung erfolgreich.");

        dbManager.initSchema();

        // Check / store Hetzner API key
        if (dbManager.hasHetznerApiKey()) {
            ConsoleOutput.info("[OK] Hetzner API Key ist bereits in der Datenbank vorhanden.");
        } else {
            ConsoleOutput.info("Kein Hetzner API Key in der Datenbank gefunden.");
            String apiKey = requestAndValidateHetznerKey();
            dbManager.setConfigValue(DatabaseManager.HETZNER_API_KEY_NAME, apiKey);
            ConsoleOutput.info("[OK] API Key in Datenbank gespeichert.");
        }

        CloudConfig config = new CloudConfig(host, port, name, user, pass);
        config.setDbType(dbType);
        ConfigManager.save(config);
        ConsoleOutput.info("[OK] CloudConfig.json wurde gespeichert.");

        return dbManager;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void printHeader() {
        ConsoleOutput.info("");
        ConsoleOutput.info("CloudConfig.json nicht gefunden.");
        ConsoleOutput.info("Existiert bereits eine Datenbank?");
        ConsoleOutput.info("  automatisch  – Neuen Hetzner Server + MongoDB + Weboberfläche erstellen");
        ConsoleOutput.info("  hinzufügen   – Vorhandene Datenbank verbinden");
        ConsoleOutput.info("");
    }

    private String promptChoice() {
        while (true) {
            System.out.print("Auswahl (automatisch/hinzufügen): ");
            String input = scanner.nextLine().trim().toLowerCase();
            // Accept "hinzufuegen" as alias for "hinzufügen"
            if ("hinzufuegen".equals(input)) return "hinzufügen";
            if ("automatisch".equals(input) || "hinzufügen".equals(input)) return input;
            ConsoleOutput.info("Bitte 'automatisch' oder 'hinzufügen' eingeben.");
        }
    }

    private String promptDbType() {
        while (true) {
            System.out.print("Datenbanktyp (mysql/mongodb): ");
            String input = scanner.nextLine().trim().toLowerCase();
            if ("mysql".equals(input) || "mongodb".equals(input)) return input;
            ConsoleOutput.info("Bitte 'mysql' oder 'mongodb' eingeben.");
        }
    }

    private DatabaseManager createManager(String dbType) {
        return "mongodb".equals(dbType) ? new MongoDbDatabaseManager() : new MysqlDatabaseManager();
    }

    private String defaultPortHint(String dbType) {
        return "mongodb".equals(dbType)
                ? "Datenbank-Port [27017]: "
                : "Datenbank-Port [3306]: ";
    }

    private int defaultPort(String dbType) {
        return "mongodb".equals(dbType) ? 27017 : 3306;
    }

    private String requestAndValidateHetznerKey() {
        while (true) {
            String apiKey = promptSecret("Hetzner API Key: ");
            if (apiKey.isBlank()) {
                ConsoleOutput.info("API Key darf nicht leer sein.");
                continue;
            }
            ConsoleOutput.info("Überprüfe API Key...");
            HetznerApiClient client = new HetznerApiClient(apiKey);
            if (client.validateApiKey()) {
                return apiKey;
            }
            ConsoleOutput.info("[Fehler] API Key ungültig oder abgelaufen. Bitte erneut eingeben.");
        }
    }

    private String generateHexSecret(int bytes) {
        byte[] data = new byte[bytes];
        new SecureRandom().nextBytes(data);
        StringBuilder builder = new StringBuilder(bytes * 2);
        for (byte b : data) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private WorkerIdentity nextWorkerIdentity(DatabaseManager db) throws Exception {
        synchronized (db) {
            String currentValue = db.getConfigValue("worker_name_counter");
            int current;
            try {
                current = currentValue == null || currentValue.isBlank() ? 0 : Integer.parseInt(currentValue.trim());
            } catch (NumberFormatException ignored) {
                current = 0;
            }
            int next = current + 1;
            db.setConfigValue("worker_name_counter", String.valueOf(next));
            return new WorkerIdentity(String.format("Worker%02d", next), String.format("CloudNetwork-Worker-%02d", next));
        }
    }

    private String detectGatewayIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /**
     * Attempts to connect to the database up to {@code retries} times, waiting
     * {@code retryDelayMs} milliseconds between attempts.  Pass
     * {@code retryDelayMs = 0} for an immediate single-shot attempt.
     */
    private void connectWithRetry(DatabaseManager dbManager,
                                   String host, int port, String db,
                                   String user, String pass,
                                   int retries, long retryDelayMs)
            throws Exception {

        for (int i = 1; i <= retries; i++) {
            try {
                dbManager.connect(host, port, db, user, pass);
                if (dbManager.isConnected()) return;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                String status;
                if (msg.contains("Connection refused") || msg.contains("Connect timed out")
                        || msg.contains("SocketTimeoutException")) {
                    status = "MongoDB startet noch (Verbindung abgelehnt)";
                } else if (msg.contains("authenticating") || msg.contains("Authentication failed")) {
                    status = "MongoDB läuft, App-Benutzer wird noch eingerichtet (cloud-init läuft)";
                } else {
                    status = msg;
                }
                ConsoleOutput.info("  Versuch " + i + "/" + retries + ": " + status);
                if (i < retries && retryDelayMs > 0) {
                    ConsoleOutput.info("  Nächster Versuch in "
                            + (retryDelayMs / 1000) + " Sekunden...");
                    Thread.sleep(retryDelayMs);
                }
            }
        }
        throw new Exception("Datenbankverbindung nach " + retries + " Versuchen fehlgeschlagen.");
    }

    private String prompt(String message) {
        System.out.print(message);
        return scanner.nextLine().trim();
    }

    private int promptInt(String message, int defaultValue) {
        System.out.print(message);
        String input = scanner.nextLine().trim();
        if (input.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            ConsoleOutput.info("Ungültige Zahl, verwende Standard: " + defaultValue);
            return defaultValue;
        }
    }

    /**
     * Reads a password from the console without echoing it (when a real
     * terminal is attached) and falls back to a plain {@link Scanner} read
     * in environments without a console (e.g. IDE / tests).
     */
    private String promptSecret(String message) {
        Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword("%s", message);
            return chars != null ? new String(chars) : "";
        }
        // Fallback for non-interactive environments
        System.out.print(message);
        return scanner.nextLine().trim();
    }

    private record WorkerIdentity(String workerId, String serverName) {
    }
}
