package de.cloudnetwork;

import de.cloudnetwork.config.CloudConfig;
import de.cloudnetwork.config.ConfigManager;
import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.database.MongoDbDatabaseManager;
import de.cloudnetwork.database.MysqlDatabaseManager;
import de.cloudnetwork.setup.SetupWizard;

import java.io.IOException;
import java.util.Scanner;

/**
 * Entry point for the CloudNetwork Hetzner console manager.
 *
 * <p>On startup the banner "start cloud" is printed.  If {@code
 * CloudConfig.json} is present the application connects to the configured
 * database and enters the main loop.  Otherwise the interactive
 * {@link SetupWizard} is launched.</p>
 */
public class Main {

    public static void main(String[] args) {
        printBanner();

        DatabaseManager dbManager = null;

        try {
            if (ConfigManager.configExists()) {
                dbManager = loadAndConnect();
            } else {
                SetupWizard wizard = new SetupWizard();
                dbManager = wizard.run();
            }

            System.out.println();
            System.out.println("CloudNetwork ist bereit. Datenbankverbindung aktiv.");
            runMainLoop(dbManager);

        } catch (Exception e) {
            System.err.println("[FEHLER] " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            if (dbManager != null) {
                dbManager.close();
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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
        System.out.println("Verbinde mit " + dbType + "-Datenbank " + config.getDbHost()
                + ":" + config.getDbPort() + " ...");

        DatabaseManager db = "mongodb".equals(dbType)
                ? new MongoDbDatabaseManager()
                : new MysqlDatabaseManager();
        try {
            db.connect(config.getDbHost(), config.getDbPort(),
                       config.getDbName(), config.getDbUser(),
                       config.getDbPassword());
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

    /**
     * Interactive command loop.  Blocks until the user types {@code stop}.
     * Unknown commands print a hint to type {@code help}.
     */
    private static void runMainLoop(DatabaseManager db) {
        System.out.println("Tippe 'help' für verfügbare Befehle.");
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("> ");
            if (!scanner.hasNextLine()) {
                // EOF on stdin (e.g. pipe closed) – exit cleanly
                break;
            }
            String line = scanner.nextLine().trim().toLowerCase();

            switch (line) {
                case "stop" -> {
                    System.out.println("CloudNetwork wird beendet. Auf Wiedersehen!");
                    return;
                }
                case "help" -> {
                    System.out.println("Verfügbare Befehle:");
                    System.out.println("  help  – Diese Hilfe anzeigen");
                    System.out.println("  stop  – Programm beenden");
                }
                case "" -> { /* ignore blank input */ }
                default -> System.out.println("Unbekannter Befehl: '" + line + "'. Tippe 'help' für eine Liste der Befehle.");
            }
        }
    }
}
