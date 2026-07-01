package de.cloudnetwork.setup;

import de.cloudnetwork.config.CloudConfig;
import de.cloudnetwork.config.ConfigManager;
import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.database.MongoDbDatabaseManager;
import de.cloudnetwork.database.MysqlDatabaseManager;
import de.cloudnetwork.hetzner.HetznerApiClient;
import de.cloudnetwork.hetzner.HetznerServer;
import de.cloudnetwork.storage.StorageBoxInfo;
import de.cloudnetwork.storage.StorageBoxManager;
import de.cloudnetwork.worker.WorkerInfo;
import org.bson.Document;
import org.bouncycastle.math.ec.rfc7748.X25519;

import java.io.Console;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
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

        String choice = automationOptions.setupChoice();
        if (choice == null) {
            ConsoleOutput.info("[INFO] Starte automatische Erstkonfiguration (override mit CLOUDNETWORK_SETUP_MODE=hinzufügen).");
            choice = "automatisch";
        }
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
        HetznerApiClient hetzner = new HetznerApiClient(apiKey);
        NetworkSelection networkSelection = ensurePrivateNetwork(hetzner);
        String gatewayPrivateHost = resolveGatewayPrivateHost();
        String gatewayPublicHost = resolveGatewayPublicHost();
        int gatewayPort = automationOptions.gatewayPort();
        LobbyProxySetup lobbyProxySetup = resolveLobbyProxySetup();
        String dbUser = "cloudnetwork";
        String dbPass = generateHexSecret(24);
        String dbName = "cloudnetwork";
        int    dbPort = 27017;

        ConsoleOutput.info("[OK] Hetzner API Key ist gültig.");
        ConsoleOutput.info("Master-Rolle: " + gatewayPublicHost + " (intern: " + gatewayPrivateHost + ")");
        ConsoleOutput.info("Private-Netzwerk: " + networkSelection.name() + " (ID=" + networkSelection.networkId()
                + ", CIDR=" + networkSelection.cidr() + ")");
        ConsoleOutput.info("Erstelle privaten Datenbank-Server im Hetzner-Netzwerk...");

        HetznerApiClient.WireGuardBootstrap wireGuardBootstrap = createWireGuardBootstrap();
        HetznerServer server;
        HetznerApiClient.ServerProvisioningOptions databaseProvisioning = new HetznerApiClient.ServerProvisioningOptions(
                networkSelection.networkId(), false, false);
        try {
            server = hetzner.createServer("CloudNetwork-Datenbank-01", dbUser, dbPass, dbName,
                    wireGuardBootstrap, databaseProvisioning);
        } catch (IOException e) {
            throw new Exception("Server-Erstellung fehlgeschlagen: " + e.getMessage(), e);
        }

        ConsoleOutput.info("[OK] Server erstellt. ID=" + server.getId()
                + "  Private-IP=" + server.getPrivateIpv4());

        MongoDbDatabaseManager dbManager = new MongoDbDatabaseManager();
        String dbHost;
        try {
            // Wait until the server is running
            HetznerServer readyServer = hetzner.waitForServerDetails(server.getId(), networkSelection.networkId());
            dbHost = readyServer.getPrivateIpv4() != null && !readyServer.getPrivateIpv4().isBlank()
                    ? readyServer.getPrivateIpv4()
                    : readyServer.getIpv4();
            if (dbHost == null || dbHost.isBlank()) {
                throw new Exception("Datenbank-Server hat keine erreichbare IP-Adresse zurückgeliefert.");
            }
            ConsoleOutput.info("[OK] Server läuft unter " + dbHost + " (privat)");
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
                    connectWithRetry(dbManager, dbHost, dbPort, dbName, dbUser, connectPassword, 3, 15_000);
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
            dbManager.setConfigValue("wireguard_enabled", "true");
            dbManager.setConfigValue("wireguard_cidr", wireGuardBootstrap.cidr());
            dbManager.setConfigValue("wireguard_endpoint", dbHost + ":" + wireGuardBootstrap.listenPort());
            dbManager.setConfigValue("wireguard_db_host", "10.200.0.1");
            dbManager.setConfigValue("hetzner_network_id", String.valueOf(networkSelection.networkId()));
            dbManager.setConfigValue("hetzner_network_name", networkSelection.name());
            dbManager.setConfigValue("hetzner_network_cidr", networkSelection.cidr());
            dbManager.setConfigValue("gateway_host", gatewayPrivateHost);
            dbManager.setConfigValue("gateway_public_host", gatewayPublicHost);
            dbManager.setConfigValue("gateway_port", String.valueOf(gatewayPort));
            dbManager.setConfigValue("database_private_host", dbHost);
            persistBootstrapConfig(dbManager);
            ConsoleOutput.info("[OK] API Key in Datenbank gespeichert.");

            CloudConfig config = new CloudConfig(dbHost, dbPort, dbName, dbUser, dbPass);
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
        bootstrapInitialWorkerAndInstances(dbManager, hetzner, networkSelection, gatewayPrivateHost, gatewayPort, lobbyProxySetup);

        ConsoleOutput.info("MongoDB ist nur intern/WireGuard erreichbar:");
        ConsoleOutput.info("  MongoDB:       " + dbHost + ":27017");
        ConsoleOutput.info("  MongoExpress:  http://" + dbHost + ":8081");
        ConsoleOutput.info("  Benutzer:      " + dbUser);
        ConsoleOutput.info("  Passwort:      steht in CloudConfig.json");
        printWireGuardClientInstructions(dbHost, wireGuardBootstrap);

        return dbManager;
    }

    private void bootstrapInitialWorkerAndInstances(MongoDbDatabaseManager dbManager,
                                                    HetznerApiClient hetzner,
                                                    NetworkSelection networkSelection,
                                                    String gatewayHost,
                                                    int gatewayPort,
                                                    LobbyProxySetup lobbyProxySetup) throws Exception {
        WorkerIdentity workerIdentity = nextWorkerIdentity(dbManager);
        String workerId = workerIdentity.workerId();
        String workerName = workerIdentity.serverName();
        String authToken = generateHexSecret(24);

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
        HetznerApiClient.ServerProvisioningOptions workerProvisioning = new HetznerApiClient.ServerProvisioningOptions(
                networkSelection.networkId(), false, false);
        HetznerServer workerServer = hetzner.createWorkerServer(workerName, workerId, authToken, gatewayHost, gatewayPort,
                sbHost, sbUser, sbPass, workerJarUrl, workerProvisioning);
        HetznerServer readyWorker = hetzner.waitForServerDetails(workerServer.getId(), networkSelection.networkId());
        String workerIp = readyWorker.getPrivateIpv4();
        worker.setHetznerServerId(workerServer.getId());
        if (workerIp != null && !workerIp.isBlank()) {
            worker.setIpv4(workerIp);
        }
        dbManager.saveWorker(worker);
        ConsoleOutput.info("[OK] Erster Worker erstellt: " + workerId + " (" + workerIp + ")");

        if (lobbyProxySetup.installLobbyAndProxy()) {
            createInitialInstance(dbManager, "velocity-01", lobbyProxySetup.velocityDisplayName(),
                    "VELOCITY", workerId, 25565, null, lobbyProxySetup.velocityJarUrl());
            createInitialInstance(dbManager, "lobby-01", lobbyProxySetup.lobbyDisplayName(),
                    "MINECRAFT", workerId, 25566, "velocity-01", lobbyProxySetup.lobbyJarUrl());
            dbManager.setConfigValue("bootstrap_initial_worker_id", workerId);
            ConsoleOutput.info("[OK] Erste Instanzen vorbereitet: "
                    + lobbyProxySetup.velocityDisplayName() + " + " + lobbyProxySetup.lobbyDisplayName() + ".");
        } else {
            dbManager.setConfigValue("bootstrap_initial_worker_id", "");
            ConsoleOutput.info("[INFO] Lobby/Proxy-Seed wurde übersprungen.");
        }

        bootstrapProxyGateway(dbManager, hetzner, networkSelection, gatewayHost, gatewayPort,
                sbHost, sbUser, sbPass, lobbyProxySetup.installLobbyAndProxy());
    }

    private void bootstrapProxyGateway(MongoDbDatabaseManager dbManager,
                                       HetznerApiClient hetzner,
                                       NetworkSelection networkSelection,
                                       String gatewayHost,
                                       int gatewayPort,
                                       String storageBoxHost,
                                       String storageBoxUser,
                                       String storageBoxPass,
                                       boolean clientReady) throws Exception {
        String proxyGatewayId = "proxy-gateway-01";
        String proxyGatewayAuthToken = generateHexSecret(24);
        dbManager.setConfigValue("proxy_gateway_id", proxyGatewayId);
        dbManager.setConfigValue("proxy_gateway_auth_token", proxyGatewayAuthToken);
        dbManager.setConfigValue("proxy_gateway_port", "25565");
        String proxyGatewayJarUrl = dbManager.getConfigValue("proxy_gateway_jar_url");

        ConsoleOutput.info("[INFO] Erstelle ProxyGateway-Server...");
        HetznerApiClient.ServerProvisioningOptions proxyProvisioning = new HetznerApiClient.ServerProvisioningOptions(
                networkSelection.networkId(), true, true);
        HetznerServer proxyGatewayServer = hetzner.createProxyGatewayServer(
                "CloudNetwork-ProxyGateway-01", proxyGatewayId, proxyGatewayAuthToken, gatewayHost, gatewayPort,
                storageBoxHost, storageBoxUser, storageBoxPass, proxyGatewayJarUrl, proxyProvisioning);
        HetznerServer readyProxyGateway = hetzner.waitForServerDetails(proxyGatewayServer.getId(), networkSelection.networkId());
        String proxyGatewayIp = readyProxyGateway.getIpv4();
        ConsoleOutput.info("[OK] ProxyGateway-Server erstellt: " + proxyGatewayId + " (" + proxyGatewayIp + ")");
        if (proxyGatewayJarUrl == null || proxyGatewayJarUrl.isBlank()) {
            ConsoleOutput.info("     Hinweis: proxy_gateway_jar_url ist nicht gesetzt. Der Server startet automatisch, sobald /root/proxy-gateway.jar verfügbar ist.");
        } else {
            ConsoleOutput.info("     ProxyGateway-JAR wird automatisch über " + proxyGatewayJarUrl + " bereitgestellt.");
        }
        if (clientReady) {
            dbManager.setConfigValue("client_gateway_public_host", proxyGatewayIp);
            ConsoleOutput.info("     Minecraft-Clients verbinden sich mit: " + proxyGatewayIp + ":25565");
        } else {
            dbManager.setConfigValue("client_gateway_public_host", "");
            ConsoleOutput.info("     Client-Gateway ist bereit, aber noch kein Lobby/Proxy-Backend aktiviert.");
        }
    }

    private void createInitialInstance(MongoDbDatabaseManager dbManager,
                                       String id,
                                       String name,
                                       String type,
                                       String workerId,
                                       int port,
                                       String proxyTarget,
                                       String downloadUrl) {
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
        if (downloadUrl != null && !downloadUrl.isBlank()) {
            instance.append("downloadUrl", downloadUrl);
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

    private HetznerApiClient.WireGuardBootstrap createWireGuardBootstrap() {
        SecureRandom random = new SecureRandom();
        byte[] serverPrivate = new byte[X25519.SCALAR_SIZE];
        byte[] serverPublic = new byte[X25519.POINT_SIZE];
        byte[] clientPrivate = new byte[X25519.SCALAR_SIZE];
        byte[] clientPublic = new byte[X25519.POINT_SIZE];
        X25519.generatePrivateKey(random, serverPrivate);
        X25519.generatePublicKey(serverPrivate, 0, serverPublic, 0);
        X25519.generatePrivateKey(random, clientPrivate);
        X25519.generatePublicKey(clientPrivate, 0, clientPublic, 0);
        Base64.Encoder encoder = Base64.getEncoder();
        return new HetznerApiClient.WireGuardBootstrap(
                "10.200.0.0/24",
                "10.200.0.1/24",
                "10.200.0.2/32",
                encoder.encodeToString(serverPrivate),
                encoder.encodeToString(serverPublic),
                encoder.encodeToString(clientPrivate),
                encoder.encodeToString(clientPublic),
                51820
        );
    }

    private void printWireGuardClientInstructions(String endpointIp, HetznerApiClient.WireGuardBootstrap wireGuardBootstrap) {
        ConsoleOutput.info("");
        ConsoleOutput.info("WireGuard-Zugang für den internen Datenbankpfad:");
        ConsoleOutput.info("  1) WireGuard installieren (Linux): sudo apt-get install -y wireguard");
        ConsoleOutput.info("  2) Client-Konfiguration als cloudnetwork-db.conf speichern:");
        ConsoleOutput.info("----- BEGIN cloudnetwork-db.conf -----");
        ConsoleOutput.info("[Interface]");
        ConsoleOutput.info("PrivateKey = " + wireGuardBootstrap.clientPrivateKey());
        ConsoleOutput.info("Address = " + wireGuardBootstrap.clientAddressCidr());
        ConsoleOutput.info("DNS = 1.1.1.1");
        ConsoleOutput.info("");
        ConsoleOutput.info("[Peer]");
        ConsoleOutput.info("PublicKey = " + wireGuardBootstrap.serverPublicKey());
        ConsoleOutput.info("Endpoint = " + endpointIp + ":" + wireGuardBootstrap.listenPort());
        ConsoleOutput.info("AllowedIPs = " + wireGuardBootstrap.cidr());
        ConsoleOutput.info("PersistentKeepalive = 25");
        ConsoleOutput.info("----- END cloudnetwork-db.conf -----");
        ConsoleOutput.info("  3) Verbinden: sudo wg-quick up ./cloudnetwork-db.conf");
        ConsoleOutput.info("  4) Trennen:   sudo wg-quick down ./cloudnetwork-db.conf");
        ConsoleOutput.info("Sobald WireGuard aktiv ist (vom Master/Privatnetz aus):");
        ConsoleOutput.info("  MongoDB:       " + "10.200.0.1:27017");
        ConsoleOutput.info("  MongoExpress:  " + "http://10.200.0.1:8081");
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

    private NetworkSelection ensurePrivateNetwork(HetznerApiClient hetzner) throws Exception {
        Long configuredNetworkId = automationOptions.networkId();
        if (configuredNetworkId != null && configuredNetworkId > 0) {
            HetznerApiClient.NetworkInfo network = hetzner.findNetwork(configuredNetworkId);
            return new NetworkSelection(network.id(), network.name(), network.ipRange());
        }
        String networkName = automationOptions.networkName();
        String networkCidr = automationOptions.networkCidr();
        if (automationOptions.isNonInteractive()) {
            if (networkName == null || networkName.isBlank()) {
                networkName = "CloudNetwork-PrivateNet";
            }
            if (networkCidr == null || networkCidr.isBlank()) {
                networkCidr = "10.10.0.0/16";
            }
            HetznerApiClient.NetworkInfo network = hetzner.createNetwork(networkName, networkCidr);
            ConsoleOutput.info("[OK] Neues Hetzner-Netzwerk automatisch erstellt: " + network.name() + " (ID=" + network.id() + ")");
            return new NetworkSelection(network.id(), network.name(), network.ipRange());
        }
        if (networkName == null || networkName.isBlank()) {
            networkName = prompt("Bestehende Netzwerk-ID (leer = neues Netzwerk anlegen): ");
            if (!networkName.isBlank()) {
                try {
                    HetznerApiClient.NetworkInfo network = hetzner.findNetwork(Long.parseLong(networkName.trim()));
                    return new NetworkSelection(network.id(), network.name(), network.ipRange());
                } catch (NumberFormatException e) {
                    ConsoleOutput.info("[WARNUNG] Ungültige Netzwerk-ID, es wird stattdessen ein neues Netzwerk erstellt.");
                }
            }
            networkName = prompt("Neuer Hetzner-Netzwerkname [CloudNetwork-PrivateNet]: ", "CloudNetwork-PrivateNet");
        }
        if (networkCidr == null || networkCidr.isBlank()) {
            networkCidr = prompt("Netzwerk-CIDR [10.10.0.0/16]: ", "10.10.0.0/16");
        }
        HetznerApiClient.NetworkInfo network = hetzner.createNetwork(networkName, networkCidr);
        ConsoleOutput.info("[OK] Neues Hetzner-Netzwerk erstellt: " + network.name() + " (ID=" + network.id() + ")");
        return new NetworkSelection(network.id(), network.name(), network.ipRange());
    }

    private String detectPublicIp() {
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

    private String detectPrivateIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            if (localHost.isSiteLocalAddress()) {
                return localHost.getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return detectPublicIp();
    }

    private String resolveGatewayPrivateHost() {
        String configuredGatewayHost = automationOptions.gatewayPrivateHost();
        if (configuredGatewayHost != null && !configuredGatewayHost.isBlank()) {
            return configuredGatewayHost;
        }
        String detected = detectPrivateIp();
        if (automationOptions.isNonInteractive()) {
            return detected;
        }
        return prompt("Master interne IP/Hostname [" + detected + "]: ", detected);
    }

    private String resolveGatewayPublicHost() {
        String configuredGatewayHost = automationOptions.gatewayPublicHost();
        if (configuredGatewayHost != null && !configuredGatewayHost.isBlank()) {
            return configuredGatewayHost;
        }
        String detected = detectPublicIp();
        if (automationOptions.isNonInteractive()) {
            return detected;
        }
        return prompt("Master öffentliche IP/Hostname [" + detected + "]: ", detected);
    }

    private LobbyProxySetup resolveLobbyProxySetup() {
        Boolean install = automationOptions.installLobbyAndProxy();
        if (install == null) {
            install = promptYesNo("Lobby + Proxy initial vorbereiten?", true);
        }
        if (!install) {
            return new LobbyProxySetup(false, "", "", "Velocity01", "Lobby01");
        }
        String velocityUrl = automationOptions.velocityJarUrl();
        String lobbyUrl = automationOptions.lobbyJarUrl();
        String velocityName = firstNonBlank(automationOptions.velocityDisplayName(), "Velocity01");
        String lobbyName = firstNonBlank(automationOptions.lobbyDisplayName(), "Lobby01");
        if (!automationOptions.isNonInteractive()) {
            velocityUrl = firstNonBlank(velocityUrl,
                    prompt("Velocity-JAR-URL (leer = Worker-Fallback verwenden): ", ""));
            lobbyUrl = firstNonBlank(lobbyUrl,
                    prompt("Lobby/Paper-JAR-URL (leer = Worker-Fallback verwenden): ", ""));
            velocityName = firstNonBlank(automationOptions.velocityDisplayName(),
                    prompt("Anzeigename für Proxy [Velocity01]: ", "Velocity01"));
            lobbyName = firstNonBlank(automationOptions.lobbyDisplayName(),
                    prompt("Anzeigename für Lobby [Lobby01]: ", "Lobby01"));
        }
        return new LobbyProxySetup(true, velocityUrl, lobbyUrl, velocityName, lobbyName);
    }

    private boolean promptYesNo(String message, boolean defaultValue) {
        if (automationOptions.isNonInteractive()) {
            return defaultValue;
        }
        String suffix = defaultValue ? " [J/n]: " : " [j/N]: ";
        while (true) {
            System.out.print(message + suffix);
            String input = scanner.nextLine().trim().toLowerCase(Locale.ROOT);
            if (input.isBlank()) {
                return defaultValue;
            }
            if ("j".equals(input) || "ja".equals(input) || "y".equals(input) || "yes".equals(input)) {
                return true;
            }
            if ("n".equals(input) || "nein".equals(input) || "no".equals(input)) {
                return false;
            }
            ConsoleOutput.info("Bitte mit ja oder nein antworten.");
        }
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private void persistBootstrapConfig(MongoDbDatabaseManager dbManager) throws Exception {
        if (automationOptions.workerJarUrl() != null && !automationOptions.workerJarUrl().isBlank()) {
            dbManager.setConfigValue("worker_jar_url", automationOptions.workerJarUrl());
        }
        if (automationOptions.proxyGatewayJarUrl() != null && !automationOptions.proxyGatewayJarUrl().isBlank()) {
            dbManager.setConfigValue("proxy_gateway_jar_url", automationOptions.proxyGatewayJarUrl());
        }
        if (automationOptions.velocityJarUrl() != null && !automationOptions.velocityJarUrl().isBlank()) {
            dbManager.setConfigValue("default_velocity_url", automationOptions.velocityJarUrl());
        }
        if (automationOptions.lobbyJarUrl() != null && !automationOptions.lobbyJarUrl().isBlank()) {
            dbManager.setConfigValue("default_paper_url", automationOptions.lobbyJarUrl());
        }
        dbManager.setConfigValue("gateway_port", String.valueOf(automationOptions.gatewayPort()));
        String configuredGatewayHost = automationOptions.gatewayPrivateHost();
        if (configuredGatewayHost != null && !configuredGatewayHost.isBlank()) {
            dbManager.setConfigValue("gateway_host", configuredGatewayHost);
        }
        String configuredGatewayPublicHost = automationOptions.gatewayPublicHost();
        if (configuredGatewayPublicHost != null && !configuredGatewayPublicHost.isBlank()) {
            dbManager.setConfigValue("gateway_public_host", configuredGatewayPublicHost);
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

    private record NetworkSelection(long networkId, String name, String cidr) {
    }

    private record LobbyProxySetup(boolean installLobbyAndProxy,
                                   String velocityJarUrl,
                                   String lobbyJarUrl,
                                   String velocityDisplayName,
                                   String lobbyDisplayName) {
    }

    // ── Storage Box setup ─────────────────────────────────────────────────────

    /**
     * Interactive prompt for configuring a Hetzner Storage Box during automatic
     * setup.  The user may skip this step and configure it later via the
     * {@code storagebox setup} console command.
     */
    private void setupStorageBox(MongoDbDatabaseManager dbManager) {
        if (automationOptions.hasStorageBoxCredentials()) {
            setupStorageBoxWithCredentials(dbManager,
                    automationOptions.storageBoxHost(),
                    automationOptions.storageBoxUser(),
                    automationOptions.storageBoxPass(),
                    true);
            return;
        }
        if (!promptYesNo("Storage Box jetzt einrichten?", false)) {
            ConsoleOutput.info("[INFO] Storage Box wird übersprungen (optional später via 'storagebox setup').");
            return;
        }
        String host = prompt("Storage Box SFTP-Host: ");
        String user = prompt("Storage Box Nutzer: ");
        String pass = promptSecret("Storage Box Passwort: ");
        setupStorageBoxWithCredentials(dbManager, host, user, pass, false);
    }

    private void setupStorageBoxWithCredentials(MongoDbDatabaseManager dbManager,
                                                String host,
                                                String user,
                                                String pass,
                                                boolean fromEnvironment) {
        try {
            StorageBoxManager sbManager = new StorageBoxManager(dbManager);
            if (automationOptions.storageBoxRobotUser() != null && automationOptions.storageBoxRobotPass() != null) {
                sbManager.saveRobotCredentials(automationOptions.storageBoxRobotUser(), automationOptions.storageBoxRobotPass());
            }
            StorageBoxInfo matchedBox = sbManager.findStorageBox(host, user);
            if (matchedBox != null) {
                sbManager.saveCredentials(matchedBox.getId(),
                        host,
                        user,
                        pass,
                        matchedBox.getProduct());
            } else {
                sbManager.saveCredentials(host, user, pass);
            }
            sbManager.createDirectoryStructure();
            ConsoleOutput.info(fromEnvironment
                    ? "[OK] Storage Box automatisch aus Umgebungsvariablen eingerichtet."
                    : "[OK] Storage Box eingerichtet.");
        } catch (Exception e) {
            throw new IllegalStateException("Storage-Box-Einrichtung fehlgeschlagen: " + e.getMessage(), e);
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
            String gatewayPrivateHost,
            String gatewayPublicHost,
            int gatewayPort,
            Long networkId,
            String networkName,
            String networkCidr,
            String workerJarUrl,
            String proxyGatewayJarUrl,
            Boolean installLobbyAndProxy,
            String velocityJarUrl,
            String lobbyJarUrl,
            String velocityDisplayName,
            String lobbyDisplayName,
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
                    readEnv("CLOUDNETWORK_GATEWAY_PRIVATE_HOST", "CN_GATEWAY_PRIVATE_HOST", "CLOUDNETWORK_GATEWAY_HOST", "CN_GATEWAY_HOST"),
                    readEnv("CLOUDNETWORK_GATEWAY_PUBLIC_HOST", "CN_GATEWAY_PUBLIC_HOST"),
                    parseInteger(readEnv("CLOUDNETWORK_GATEWAY_PORT", "CN_GATEWAY_PORT", "GATEWAY_PORT"), 9876),
                    parseLong(readEnv("CLOUDNETWORK_HETZNER_NETWORK_ID", "CN_HETZNER_NETWORK_ID")),
                    readEnv("CLOUDNETWORK_HETZNER_NETWORK_NAME", "CN_HETZNER_NETWORK_NAME"),
                    readEnv("CLOUDNETWORK_PRIVATE_NETWORK_CIDR", "CN_PRIVATE_NETWORK_CIDR", "CLOUDNETWORK_HETZNER_NETWORK_CIDR", "CN_HETZNER_NETWORK_CIDR"),
                    readEnv("CLOUDNETWORK_WORKER_JAR_URL", "CN_WORKER_JAR_URL"),
                    readEnv("CLOUDNETWORK_PROXY_GATEWAY_JAR_URL", "CN_PROXY_GATEWAY_JAR_URL"),
                    parseBooleanOrNull(readEnv("CLOUDNETWORK_INSTALL_LOBBY_PROXY", "CN_INSTALL_LOBBY_PROXY")),
                    readEnv("CLOUDNETWORK_VELOCITY_JAR_URL", "CN_VELOCITY_JAR_URL", "CLOUDNETWORK_DEFAULT_VELOCITY_URL"),
                    readEnv("CLOUDNETWORK_LOBBY_JAR_URL", "CN_LOBBY_JAR_URL", "CLOUDNETWORK_DEFAULT_PAPER_URL"),
                    readEnv("CLOUDNETWORK_VELOCITY_NAME", "CN_VELOCITY_NAME"),
                    readEnv("CLOUDNETWORK_LOBBY_NAME", "CN_LOBBY_NAME"),
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

        private static Long parseLong(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private static Boolean parseBooleanOrNull(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "1", "true", "yes", "ja", "on" -> true;
                case "0", "false", "no", "nein", "off" -> false;
                default -> null;
            };
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
