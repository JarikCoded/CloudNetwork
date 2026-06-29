package de.cloudnetwork.setup;

import de.cloudnetwork.config.CloudConfig;
import de.cloudnetwork.config.ConfigManager;
import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.database.MongoDbDatabaseManager;
import de.cloudnetwork.database.MysqlDatabaseManager;
import de.cloudnetwork.hetzner.HetznerApiClient;
import de.cloudnetwork.hetzner.HetznerServer;

import java.io.Console;
import java.io.IOException;
import java.util.Scanner;

/**
 * Interactive setup wizard that is shown whenever {@code CloudConfig.json} is
 * absent.  The user can choose between two modes:
 *
 * <ol>
 *   <li><b>automatisch</b> – A Hetzner API key is requested, validated, a new
 *       cloud server is created, MySQL is installed via cloud-init, the
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
                System.out.println("[Fehler] Ungültige Auswahl. Bitte 'automatisch' oder 'hinzufügen' eingeben.");
                yield run();
            }
        };
    }

    // ── Mode: automatic ───────────────────────────────────────────────────────

    private DatabaseManager runAutoSetup() throws Exception {
        System.out.println();
        System.out.println("=== Automatische Einrichtung ===");

        String apiKey = requestAndValidateHetznerKey();

        System.out.println("[OK] Hetzner API Key ist gültig.");
        System.out.println("Erstelle Hetzner Cloud Server...");

        HetznerApiClient hetzner = new HetznerApiClient(apiKey);
        HetznerServer server;
        try {
            server = hetzner.createServer("cloudnetwork-db");
        } catch (IOException e) {
            throw new Exception("Server-Erstellung fehlgeschlagen: " + e.getMessage(), e);
        }

        System.out.println("[OK] Server erstellt. ID=" + server.getId()
                + "  IP=" + server.getIpv4());

        // Wait until the server is running
        String ip = hetzner.waitForServerRunning(server.getId());
        System.out.println("[OK] Server läuft unter " + ip);

        // Credentials were written to /root/db_credentials.txt by cloud-init
        // (mode 600, only root can read them).  Retrieve the password via SSH:
        //   ssh root@<ip> cat /root/db_credentials.txt
        // After noting the password, delete the file on the server:
        //   ssh root@<ip> 'shred -u /root/db_credentials.txt'
        System.out.println();
        System.out.println("HINWEIS: Rufe das Passwort vom Server ab:");
        System.out.println("  ssh root@" + ip + " cat /root/db_credentials.txt");
        System.out.println("Lösche die Datei anschließend mit:");
        System.out.println("  ssh root@" + ip + " 'shred -u /root/db_credentials.txt'");
        System.out.println();

        String dbUser = "cloudnetwork";
        String dbPass = promptSecret("MySQL-Passwort (aus db_credentials.txt): ");
        String dbName = "cloudnetwork";
        int    dbPort = 3306;

        // Automatic setup always uses MySQL (cloud-init installs MySQL)
        MysqlDatabaseManager dbManager = new MysqlDatabaseManager();

        // Poll until MySQL is accepting connections (cloud-init may still be
        // running); retry every 15 s for up to 5 minutes.
        System.out.println("Warte auf MySQL-Bereitschaft (max. 5 Minuten)...");
        connectWithRetry(dbManager, ip, dbPort, dbName, dbUser, dbPass, 20, 15_000);

        dbManager.initSchema();
        dbManager.setConfigValue(DatabaseManager.HETZNER_API_KEY_NAME, apiKey);
        System.out.println("[OK] API Key in Datenbank gespeichert.");

        CloudConfig config = new CloudConfig(ip, dbPort, dbName, dbUser, dbPass);
        config.setDbType("mysql");
        config.setHetznerServerId(server.getId());
        ConfigManager.save(config);
        System.out.println("[OK] CloudConfig.json wurde gespeichert.");

        return dbManager;
    }

    // ── Mode: manual ──────────────────────────────────────────────────────────

    private DatabaseManager runManualSetup() throws Exception {
        System.out.println();
        System.out.println("=== Datenbank hinzufügen ===");

        String dbType = promptDbType();

        String host = prompt("Datenbank-Host (z.B. 192.168.1.10): ");
        int    port = promptInt(defaultPortHint(dbType), defaultPort(dbType));
        String name = prompt("Datenbankname [cloudnetwork]: ");
        if (name.isBlank()) name = "cloudnetwork";
        String user = prompt("Benutzer (leer lassen für keine Authentifizierung): ");
        String pass = user.isBlank() ? "" : promptSecret("Passwort: ");

        DatabaseManager dbManager = createManager(dbType);

        System.out.println("Teste Datenbankverbindung...");
        connectWithRetry(dbManager, host, port, name, user, pass, 1, 0);
        System.out.println("[OK] Datenbankverbindung erfolgreich.");

        dbManager.initSchema();

        // Check / store Hetzner API key
        if (dbManager.hasHetznerApiKey()) {
            System.out.println("[OK] Hetzner API Key ist bereits in der Datenbank vorhanden.");
        } else {
            System.out.println("Kein Hetzner API Key in der Datenbank gefunden.");
            String apiKey = requestAndValidateHetznerKey();
            dbManager.setConfigValue(DatabaseManager.HETZNER_API_KEY_NAME, apiKey);
            System.out.println("[OK] API Key in Datenbank gespeichert.");
        }

        CloudConfig config = new CloudConfig(host, port, name, user, pass);
        config.setDbType(dbType);
        ConfigManager.save(config);
        System.out.println("[OK] CloudConfig.json wurde gespeichert.");

        return dbManager;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void printHeader() {
        System.out.println();
        System.out.println("CloudConfig.json nicht gefunden.");
        System.out.println("Existiert bereits eine Datenbank?");
        System.out.println("  automatisch  – Neuen Hetzner Server + MySQL-Datenbank erstellen");
        System.out.println("  hinzufügen   – Vorhandene Datenbank verbinden");
        System.out.println();
    }

    private String promptChoice() {
        while (true) {
            System.out.print("Auswahl (automatisch/hinzufügen): ");
            String input = scanner.nextLine().trim().toLowerCase();
            // Accept "hinzufuegen" as alias for "hinzufügen"
            if ("hinzufuegen".equals(input)) return "hinzufügen";
            if ("automatisch".equals(input) || "hinzufügen".equals(input)) return input;
            System.out.println("Bitte 'automatisch' oder 'hinzufügen' eingeben.");
        }
    }

    private String promptDbType() {
        while (true) {
            System.out.print("Datenbanktyp (mysql/mongodb): ");
            String input = scanner.nextLine().trim().toLowerCase();
            if ("mysql".equals(input) || "mongodb".equals(input)) return input;
            System.out.println("Bitte 'mysql' oder 'mongodb' eingeben.");
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
                System.out.println("API Key darf nicht leer sein.");
                continue;
            }
            System.out.println("Überprüfe API Key...");
            HetznerApiClient client = new HetznerApiClient(apiKey);
            if (client.validateApiKey()) {
                return apiKey;
            }
            System.out.println("[Fehler] API Key ungültig oder abgelaufen. Bitte erneut eingeben.");
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
                System.out.println("  Verbindungsversuch " + i + "/" + retries
                        + " fehlgeschlagen: " + e.getMessage());
                if (i < retries && retryDelayMs > 0) {
                    System.out.println("  Nächster Versuch in "
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
            System.out.println("Ungültige Zahl, verwende Standard: " + defaultValue);
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
}

