package de.cloudnetwork.setup;

import de.cloudnetwork.config.CloudConfig;
import de.cloudnetwork.config.ConfigManager;
import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.database.MongoDbDatabaseManager;
import de.cloudnetwork.database.MysqlDatabaseManager;
import de.cloudnetwork.hetzner.HetznerApiClient;
import de.cloudnetwork.hetzner.HetznerServer;
import de.cloudnetwork.storage.HetznerRobotApiClient;
import de.cloudnetwork.storage.StorageBoxInfo;
import de.cloudnetwork.storage.StorageBoxManager;
import de.cloudnetwork.worker.WorkerInfo;
import org.bson.Document;

import java.io.Console;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
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
    private final SetupAutomationOptions automationOptions;

    public SetupWizard() {
        this.scanner = new Scanner(System.in);
        this.automationOptions = SetupAutomationOptions.fromEnvironment();
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

        if ("automatisch".equals(choice)) {
            return runAutoSetup();
        }
        return runManualSetup();
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

        MongoDbDatabaseManager dbManager = new MongoDbDatabaseManager();
        String ip;
        try {
            // Wait until the server is running
            ip = hetzner.waitForServerRunning(server.getId());
            ConsoleOutput.info("[OK] Server läuft unter " + ip);
            ConsoleOutput.info("[OK] MongoDB-Zugangsdaten wurden automatisch erzeugt.");

            // Poll until MongoDB is accepting connections (cloud-init may still be
            // running — apt-get + Docker image pull + MongoDB startup can take 20-30
            // minutes); retry every 15 s, with up to 3 password re-prompts.
            ConsoleOutput.info("Warte auf MongoDB-Bereitschaft (max. 30 Minuten)...");
            String connectPassword = dbPass;
            Exception lastConnectError = null;
            boolean connected = false;
            for (int passwordAttempt = 1; passwordAttempt <= 3; passwordAttempt++) {
                try {
                    connectWithRetry(dbManager, ip, dbPort, dbName, dbUser, connectPassword, 3, 15_000);
                    connected = true;
                    dbPass = connectPassword;
                    break;
                } catch (Exception e) {
                    lastConnectError = e;
                    if (passwordAttempt < 3) {
                        ConsoleOutput.info("[Fehler] Datenbankverbindung fehlgeschlagen. Bitte Passwort erneut eingeben.");
                        connectPassword = promptSecret("MongoDB-Passwort: ");
                    }
                }
            }
            if (!connected) {
                throw new Exception("Datenbankverbindung nach 3 Passwort-Eingabeversuchen fehlgeschlagen.", lastConnectError);
            }

            dbManager.initSchema();
            dbManager.setConfigValue(DatabaseManager.HETZNER_API_KEY_NAME, apiKey);
            persistBootstrapConfig(dbManager);
            ConsoleOutput.info("[OK] API Key in Datenbank gespeichert.");

            CloudConfig config = new CloudConfig(ip, dbPort, dbName, dbUser, dbPass);
            config.setDbType("mongodb");
            config.setHetznerServerId(server.getId());
            ConfigManager.save(config);
            ConsoleOutput.info("[OK] CloudConfig.json wurde gespeichert.");
        } catch (Exception e) {
            dbManager.close();
            try {
                hetzner.deleteServer(server.getId());
                ConsoleOutput.info("[INFO] Fehlgeschlagenes Setup: Server " + server.getId() + " wurde wieder gelöscht.");
            } catch (Exception deleteEx) {
                e.addSuppressed(deleteEx);
                ConsoleOutput.info("[WARNUNG] Konnte Server " + server.getId() + " nicht automatisch löschen: " + deleteEx.getMessage());
            }
            throw e;
        }

        setupStorageBox(dbManager);
        bootstrapInitialWorkerAndInstances(dbManager, hetzner);

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
        String gatewayHost = resolveGatewayHost();
        int gatewayPort = automationOptions.gatewayPort();

        dbManager.setConfigValue("gateway_host", gatewayHost);
        dbManager.setConfigValue("gateway_port", String.valueOf(gatewayPort));

        WorkerInfo worker = new WorkerInfo();
        worker.setId(workerId);
        worker.setAuthToken(authToken);
        worker.setStatus(WorkerInfo.WorkerStatus.PROVISIONING);
        worker.setLastHeartbeatMs(System.currentTimeMillis());
        dbManager.saveWorker(worker);

        ConsoleOutput.info("[INFO] Erstelle ersten Worker " + workerName + " ...");
        // Pass Storage Box credentials if already configured (setup runs after bootstrapInitialWorkerAndInstances)
        String sbHost = null, sbUser = null, sbPass = null;
        try {
            sbHost = dbManager.getConfigValue(StorageBoxManager.KEY_HOST);
            sbUser = dbManager.getConfigValue(StorageBoxManager.KEY_USER);
            sbPass = dbManager.getConfigValue(StorageBoxManager.KEY_PASS);
        } catch (Exception e) {
            ConsoleOutput.info("[INFO] Storage-Box-Zugangsdaten konnten nicht aus der DB gelesen werden: " + e.getMessage());
        }
        String workerJarUrl = null;
        try {
            workerJarUrl = dbManager.getConfigValue("worker_jar_url");
        } catch (Exception ignored) {
        }
        HetznerServer workerServer = hetzner.createWorkerServer(workerName, workerId, authToken, gatewayHost, gatewayPort,
                sbHost, sbUser, sbPass, workerJarUrl);
        String workerIp = hetzner.waitForServerRunning(workerServer.getId());
        worker.setHetznerServerId(workerServer.getId());
        worker.setIpv4(workerIp);
        dbManager.saveWorker(worker);
        ConsoleOutput.info("[OK] Erster Worker erstellt: " + workerId + " (" + workerIp + ")");

        createInitialInstance(dbManager, "velocity-01", "Velocity01", "VELOCITY", workerId, 25565, null);
        createInitialInstance(dbManager, "lobby-01", "Lobby01", "MINECRAFT", workerId, 25566, "velocity-01");
        dbManager.setConfigValue("bootstrap_initial_worker_id", workerId);
        ConsoleOutput.info("[OK] Erste Instanzen vorbereitet: Velocity01 + Lobby01.");

        // Provision the ProxyGateway server
        bootstrapProxyGateway(dbManager, hetzner, gatewayHost, gatewayPort, sbHost, sbUser, sbPass);
    }

    private void bootstrapProxyGateway(MongoDbDatabaseManager dbManager,
                                       HetznerApiClient hetzner,
                                       String gatewayHost,
                                       int gatewayPort,
                                       String storageBoxHost,
                                       String storageBoxUser,
                                       String storageBoxPass) throws Exception {
        String proxyGatewayId = "proxy-gateway-01";
        String proxyGatewayAuthToken = generateHexSecret(24);
        dbManager.setConfigValue("proxy_gateway_id", proxyGatewayId);
        dbManager.setConfigValue("proxy_gateway_auth_token", proxyGatewayAuthToken);
        dbManager.setConfigValue("proxy_gateway_port", "25565");
        String proxyGatewayJarUrl = dbManager.getConfigValue("proxy_gateway_jar_url");

        ConsoleOutput.info("[INFO] Erstelle ProxyGateway-Server...");
        HetznerServer proxyGatewayServer = hetzner.createProxyGatewayServer(
                "CloudNetwork-ProxyGateway-01", proxyGatewayId, proxyGatewayAuthToken, gatewayHost, gatewayPort,
                storageBoxHost, storageBoxUser, storageBoxPass, proxyGatewayJarUrl);
        String proxyGatewayIp = hetzner.waitForServerRunning(proxyGatewayServer.getId());
        ConsoleOutput.info("[OK] ProxyGateway-Server erstellt: " + proxyGatewayId + " (" + proxyGatewayIp + ")");
        if (proxyGatewayJarUrl == null || proxyGatewayJarUrl.isBlank()) {
            ConsoleOutput.info("     Hinweis: proxy_gateway_jar_url ist nicht gesetzt. Der Server startet automatisch, sobald /root/proxy-gateway.jar verfügbar ist.");
        } else {
            ConsoleOutput.info("     ProxyGateway-JAR wird automatisch über " + proxyGatewayJarUrl + " bereitgestellt.");
        }
        ConsoleOutput.info("     Minecraft-Clients verbinden sich mit: " + proxyGatewayIp + ":25565");
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

        String host = prompt("Datenbank-Host (z.B. 192.168.1.10): ", automationOptions.dbHost());
        int    port = promptInt(defaultPortHint(dbType), automationOptions.dbPortOrDefault(defaultPort(dbType)));
        String name = prompt("Datenbankname [cloudnetwork]: ", automationOptions.dbName());
        if (name.isBlank()) name = "cloudnetwork";
        String user = prompt("Benutzer (leer lassen für keine Authentifizierung): ", automationOptions.dbUser());
        String pass = user.isBlank() ? "" : promptSecret("Passwort: ", automationOptions.dbPassword());

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
        if (automationOptions.isNonInteractive()) {
            ConsoleOutput.info("[INFO] Nicht-interaktive Erstkonfiguration erkannt.");
        } else {
            ConsoleOutput.info("Existiert bereits eine Datenbank?");
            ConsoleOutput.info("  automatisch  – Neuen Hetzner Server + MongoDB + Weboberfläche erstellen");
            ConsoleOutput.info("  hinzufügen   – Vorhandene Datenbank verbinden");
        }
        ConsoleOutput.info("");
    }

    private String promptChoice() {
        String configuredChoice = automationOptions.setupChoice();
        if (configuredChoice != null) {
            ConsoleOutput.info("[INFO] Setup-Modus aus Umgebungsvariablen: " + configuredChoice);
            return configuredChoice;
        }
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
        String configuredDbType = automationOptions.dbType();
        if (configuredDbType != null) {
            return configuredDbType;
        }
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
        String configuredApiKey = automationOptions.hetznerApiKey();
        if (configuredApiKey != null && !configuredApiKey.isBlank()) {
            ConsoleOutput.info("Überprüfe API Key aus Umgebungsvariablen...");
            HetznerApiClient client = new HetznerApiClient(configuredApiKey);
            if (!client.validateApiKey()) {
                throw new IllegalStateException("Hetzner API Key aus Umgebungsvariablen ist ungültig oder abgelaufen.");
            }
            return configuredApiKey;
        }
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
        // First try to get the public IP from an external service
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
        // Fallback to local address
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private String resolveGatewayHost() {
        String configuredGatewayHost = automationOptions.gatewayHost();
        return configuredGatewayHost != null && !configuredGatewayHost.isBlank()
                ? configuredGatewayHost
                : detectGatewayIp();
    }

    private void persistBootstrapConfig(MongoDbDatabaseManager dbManager) throws Exception {
        if (automationOptions.workerJarUrl() != null && !automationOptions.workerJarUrl().isBlank()) {
            dbManager.setConfigValue("worker_jar_url", automationOptions.workerJarUrl());
        }
        if (automationOptions.proxyGatewayJarUrl() != null && !automationOptions.proxyGatewayJarUrl().isBlank()) {
            dbManager.setConfigValue("proxy_gateway_jar_url", automationOptions.proxyGatewayJarUrl());
        }
        dbManager.setConfigValue("gateway_port", String.valueOf(automationOptions.gatewayPort()));
        String configuredGatewayHost = automationOptions.gatewayHost();
        if (configuredGatewayHost != null && !configuredGatewayHost.isBlank()) {
            dbManager.setConfigValue("gateway_host", configuredGatewayHost);
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
        return prompt(message, null);
    }

    private String prompt(String message, String configuredValue) {
        if (configuredValue != null) {
            return configuredValue;
        }
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
        return promptSecret(message, null);
    }

    private String promptSecret(String message, String configuredValue) {
        if (configuredValue != null) {
            return configuredValue;
        }
        Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword("%s", message);
            if (chars == null) {
                return "";
            }
            String value = new String(chars);
            Arrays.fill(chars, '\0');
            return value;
        }
        // Fallback for non-interactive environments
        System.out.print(message);
        return scanner.nextLine().trim();
    }

    private record WorkerIdentity(String workerId, String serverName) {
    }

    // ── Storage Box setup ─────────────────────────────────────────────────────

    /**
     * Interactive prompt for configuring a Hetzner Storage Box during automatic
     * setup.  The user may skip this step and configure it later via the
     * {@code storagebox setup} console command.
     */
    private void setupStorageBox(MongoDbDatabaseManager dbManager) {
        if (automationOptions.hasStorageBoxCredentials()) {
            setupStorageBoxFromEnvironment(dbManager);
            return;
        }
        ConsoleOutput.info("");
        ConsoleOutput.info("=== Storage Box Einrichtung ===");
        ConsoleOutput.info("Die Storage Box wird für Welten, JAR-Dateien, Plugins und");
        ConsoleOutput.info("alle weiteren Dateien verwendet die sich über die Zeit ansammeln.");
        ConsoleOutput.info("Kleinster Tarif: BX11 (1 TB) – bei 90% wird ein Upgrade empfohlen.");
        ConsoleOutput.info("");
        ConsoleOutput.info("Du kannst diesen Schritt überspringen und später mit");
        ConsoleOutput.info("'storagebox setup' nachkonfigurieren.");
        ConsoleOutput.info("");

        System.out.print("Storage Box jetzt einrichten? (ja/nein) [nein]: ");
        String answer = scanner.nextLine().trim().toLowerCase();
        if (!answer.equals("ja") && !answer.equals("j") && !answer.equals("yes") && !answer.equals("y")) {
            ConsoleOutput.info("[INFO] Storage Box Setup übersprungen.");
            return;
        }

        // Robot API credentials (optional but needed for usage monitoring)
        ConsoleOutput.info("");
        ConsoleOutput.info("Hetzner Robot-API-Zugangsdaten (für Nutzungsüberwachung):");
        System.out.print("Robot-Nutzername (leer lassen zum Überspringen): ");
        String robotUser = scanner.nextLine().trim();
        StorageBoxManager sbManager = new StorageBoxManager(dbManager);
        if (!robotUser.isBlank()) {
            String robotPass = promptSecret("Robot-Passwort: ");
            HetznerRobotApiClient robot = new HetznerRobotApiClient(robotUser, robotPass);
            ConsoleOutput.info("Prüfe Robot-API-Zugangsdaten...");
            if (!robot.validateCredentials()) {
                ConsoleOutput.error("[FEHLER] Robot-API-Zugangsdaten ungültig. Storage-Box-Monitoring deaktiviert.");
            } else {
                try {
                    sbManager.saveRobotCredentials(robotUser, robotPass);
                    ConsoleOutput.info("[OK] Robot-API-Zugangsdaten gespeichert.");
                    // List available storage boxes
                    var boxes = robot.listStorageBoxes();
                    if (!boxes.isEmpty()) {
                        ConsoleOutput.info("Verfügbare Storage Boxes in deinem Konto:");
                        for (StorageBoxInfo box : boxes) {
                            ConsoleOutput.info("  [" + box.getId() + "] " + box.getLogin()
                                    + " | " + box.getProduct()
                                    + " | " + (box.getDiskQuotaMb() / 1024) + " GB");
                        }
                    } else {
                        ConsoleOutput.info("[INFO] Noch keine Storage Box vorhanden.");
                        ConsoleOutput.info("       Bestelle eine unter: https://robot.hetzner.com/storagebox");
                        ConsoleOutput.info("       Empfohlen: BX11 (1 TB) als Startpaket.");
                        ConsoleOutput.info("[INFO] Konfiguration nach Bestellung mit 'storagebox setup' abschließen.");
                        return;
                    }
                } catch (Exception e) {
                    ConsoleOutput.error("[FEHLER] Robot-API: " + e.getMessage());
                }
            }
        }

        // SFTP credentials
        ConsoleOutput.info("");
        ConsoleOutput.info("SFTP-Zugangsdaten der Storage Box:");
        System.out.print("SFTP-Host (z.B. u123456.your-storagebox.de): ");
        String host = scanner.nextLine().trim();
        System.out.print("SFTP-Nutzer (z.B. u123456): ");
        String user = scanner.nextLine().trim();
        String pass = promptSecret("SFTP-Passwort: ");

        if (host.isBlank() || user.isBlank() || pass.isBlank()) {
            ConsoleOutput.error("[FEHLER] Host, Nutzer und Passwort dürfen nicht leer sein. Setup übersprungen.");
            return;
        }

        try {
            StorageBoxInfo matchedBox = sbManager.findStorageBox(host, user);
            if (matchedBox != null) {
                sbManager.saveCredentials(matchedBox.getId(), host, user, pass, matchedBox.getProduct());
                ConsoleOutput.info("[OK] Storage Box automatisch erkannt: ID " + matchedBox.getId()
                        + " | Paket " + matchedBox.getProduct());
            } else {
                sbManager.saveCredentials(host, user, pass);
                ConsoleOutput.info("[INFO] Storage Box ohne Robot-Metadaten gespeichert.");
            }
            ConsoleOutput.info("[OK] Storage Box Zugangsdaten gespeichert.");
            ConsoleOutput.info("[INFO] Erstelle Verzeichnisstruktur (CloudNetwork/Templates, Static, Jars, Backups)...");
            sbManager.createDirectoryStructure();
        } catch (Exception e) {
            ConsoleOutput.error("[FEHLER] Storage Box Setup fehlgeschlagen: " + e.getMessage());
            ConsoleOutput.info("[INFO] Konfiguration kann später mit 'storagebox setup' abgeschlossen werden.");
        }
    }

    private void setupStorageBoxFromEnvironment(MongoDbDatabaseManager dbManager) {
        try {
            StorageBoxManager sbManager = new StorageBoxManager(dbManager);
            if (automationOptions.storageBoxRobotUser() != null && automationOptions.storageBoxRobotPass() != null) {
                sbManager.saveRobotCredentials(automationOptions.storageBoxRobotUser(), automationOptions.storageBoxRobotPass());
            }
            StorageBoxInfo matchedBox = sbManager.findStorageBox(automationOptions.storageBoxHost(), automationOptions.storageBoxUser());
            if (matchedBox != null) {
                sbManager.saveCredentials(matchedBox.getId(),
                        automationOptions.storageBoxHost(),
                        automationOptions.storageBoxUser(),
                        automationOptions.storageBoxPass(),
                        matchedBox.getProduct());
            } else {
                sbManager.saveCredentials(
                        automationOptions.storageBoxHost(),
                        automationOptions.storageBoxUser(),
                        automationOptions.storageBoxPass());
            }
            sbManager.createDirectoryStructure();
            ConsoleOutput.info("[OK] Storage Box automatisch aus Umgebungsvariablen eingerichtet.");
        } catch (Exception e) {
            throw new IllegalStateException("Storage-Box-Einrichtung aus Umgebungsvariablen fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    private record SetupAutomationOptions(
            String setupChoice,
            String hetznerApiKey,
            String dbType,
            String dbHost,
            Integer dbPort,
            String dbName,
            String dbUser,
            String dbPassword,
            String gatewayHost,
            int gatewayPort,
            String workerJarUrl,
            String proxyGatewayJarUrl,
            String storageBoxHost,
            String storageBoxUser,
            String storageBoxPass,
            String storageBoxRobotUser,
            String storageBoxRobotPass
    ) {
        static SetupAutomationOptions fromEnvironment() {
            String explicitChoice = normalizeChoice(readEnv("CLOUDNETWORK_SETUP_MODE", "CN_SETUP_MODE"));
            String apiKey = readEnv("CLOUDNETWORK_HETZNER_API_KEY", "CN_HETZNER_API_KEY", "HETZNER_API_KEY");
            String dbHost = readEnv("CLOUDNETWORK_DB_HOST", "CN_DB_HOST", "DB_HOST");
            String setupChoice = explicitChoice;
            if (setupChoice == null) {
                if (dbHost != null && !dbHost.isBlank()) {
                    setupChoice = "hinzufügen";
                } else if (readBooleanEnv("CLOUDNETWORK_AUTO_SETUP", "CN_AUTO_SETUP")
                        || (apiKey != null && !apiKey.isBlank())) {
                    setupChoice = "automatisch";
                }
            }
            return new SetupAutomationOptions(
                    setupChoice,
                    apiKey,
                    normalizeDbType(readEnv("CLOUDNETWORK_DB_TYPE", "CN_DB_TYPE", "DB_TYPE")),
                    dbHost,
                    parseInteger(readEnv("CLOUDNETWORK_DB_PORT", "CN_DB_PORT", "DB_PORT")),
                    readEnv("CLOUDNETWORK_DB_NAME", "CN_DB_NAME", "DB_NAME"),
                    readEnv("CLOUDNETWORK_DB_USER", "CN_DB_USER", "DB_USER"),
                    readEnv("CLOUDNETWORK_DB_PASSWORD", "CN_DB_PASSWORD", "DB_PASSWORD"),
                    readEnv("CLOUDNETWORK_GATEWAY_HOST", "CN_GATEWAY_HOST"),
                    parseInteger(readEnv("CLOUDNETWORK_GATEWAY_PORT", "CN_GATEWAY_PORT", "GATEWAY_PORT"), 9876),
                    readEnv("CLOUDNETWORK_WORKER_JAR_URL", "CN_WORKER_JAR_URL"),
                    readEnv("CLOUDNETWORK_PROXY_GATEWAY_JAR_URL", "CN_PROXY_GATEWAY_JAR_URL"),
                    readEnv("CLOUDNETWORK_STORAGEBOX_HOST", "CN_STORAGEBOX_HOST"),
                    readEnv("CLOUDNETWORK_STORAGEBOX_USER", "CN_STORAGEBOX_USER"),
                    readEnv("CLOUDNETWORK_STORAGEBOX_PASS", "CN_STORAGEBOX_PASS"),
                    readEnv("CLOUDNETWORK_STORAGEBOX_ROBOT_USER", "CN_STORAGEBOX_ROBOT_USER"),
                    readEnv("CLOUDNETWORK_STORAGEBOX_ROBOT_PASS", "CN_STORAGEBOX_ROBOT_PASS")
            );
        }

        boolean isNonInteractive() {
            return setupChoice != null;
        }

        boolean hasStorageBoxCredentials() {
            return storageBoxHost != null && !storageBoxHost.isBlank()
                    && storageBoxUser != null && !storageBoxUser.isBlank()
                    && storageBoxPass != null && !storageBoxPass.isBlank();
        }

        int dbPortOrDefault(int defaultValue) {
            return dbPort != null && dbPort > 0 ? dbPort : defaultValue;
        }

        private static String readEnv(String... keys) {
            for (String key : keys) {
                String value = System.getenv(key);
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            return null;
        }

        private static boolean readBooleanEnv(String... keys) {
            String value = readEnv(keys);
            if (value == null) {
                return false;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "1", "true", "yes", "ja", "on" -> true;
                default -> false;
            };
        }

        private static Integer parseInteger(String value) {
            return parseInteger(value, null);
        }

        private static int parseInteger(String value, int defaultValue) {
            Integer parsed = parseInteger(value, Integer.valueOf(defaultValue));
            return parsed != null ? parsed : defaultValue;
        }

        private static Integer parseInteger(String value, Integer defaultValue) {
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }

        private static String normalizeChoice(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if ("hinzufuegen".equals(normalized)) {
                return "hinzufügen";
            }
            return ("automatisch".equals(normalized) || "hinzufügen".equals(normalized)) ? normalized : null;
        }

        private static String normalizeDbType(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return ("mysql".equals(normalized) || "mongodb".equals(normalized)) ? normalized : null;
        }
    }
}
