package de.cloudnetwork.storage;

import de.cloudnetwork.console.ConsoleOutput;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background thread that periodically checks Storage Box disk usage and warns
 * when thresholds are exceeded.
 *
 * <ul>
 *   <li>≥ 80 % → INFO warning</li>
 *   <li>≥ 90 % → ERROR warning + upgrade recommendation</li>
 * </ul>
 *
 * <p>Upgrade is not automated (no public API endpoint for ordering).  The log
 * message always shows the recommended next product and links to the Robot
 * panel.</p>
 */
public class StorageBoxMonitor {

    private static final int    CHECK_INTERVAL_MINUTES = 30;
    private static final double WARN_THRESHOLD         = 80.0;
    private static final double CRITICAL_THRESHOLD     = 90.0;

    private final StorageBoxManager        manager;
    private final ScheduledExecutorService scheduler;

    public StorageBoxMonitor(StorageBoxManager manager) {
        this.manager   = manager;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "storagebox-monitor");
            t.setDaemon(true);
            return t;
        });
    }

    /** Starts the periodic check. First check runs 5 minutes after startup. */
    public void start() {
        scheduler.scheduleAtFixedRate(this::check, 5, CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES);
        ConsoleOutput.info("[OK] Storage Box Monitor gestartet (Prüfintervall: "
                + CHECK_INTERVAL_MINUTES + " min).");
    }

    /** Stops the monitor. */
    public void stop() {
        scheduler.shutdownNow();
    }

    // ── periodic check ────────────────────────────────────────────────────────

    private void check() {
        try {
            StorageBoxInfo info = manager.getUsageInfo();
            if (info == null) return; // not configured or no Robot API

            double usagePct = info.getUsagePercent();
            long   usedGb   = info.getDiskUsageMb()  / 1024;
            long   quotaGb  = info.getDiskQuotaMb()  / 1024;

            String summary = String.format("[Storage Box] %s | %d GB / %d GB (%.1f%% belegt)",
                    info.getProduct(), usedGb, quotaGb, usagePct);

            if (usagePct >= CRITICAL_THRESHOLD) {
                String next = StorageBoxManager.nextUpgradeProduct(info.getProduct());
                ConsoleOutput.error("[WARNUNG] " + summary);
                if (next != null) {
                    ConsoleOutput.error("[WARNUNG] Storage Box zu " + String.format("%.1f", usagePct)
                            + "% voll! Upgrade auf " + next
                            + " dringend empfohlen: https://robot.hetzner.com/storagebox");
                } else {
                    ConsoleOutput.error("[WARNUNG] Storage Box zu " + String.format("%.1f", usagePct)
                            + "% voll! Maximale Paketgröße (BX91) erreicht – Daten archivieren/bereinigen.");
                }
            } else if (usagePct >= WARN_THRESHOLD) {
                ConsoleOutput.info("[WARNUNG] " + summary + " – Speicher über 80% belegt.");
            } else {
                ConsoleOutput.logOnly("[INFO] " + summary);
            }
        } catch (Exception e) {
            ConsoleOutput.logOnly("[FEHLER] Storage Box Statusprüfung fehlgeschlagen: " + e.getMessage());
        }
    }
}
