package de.cloudnetwork.scaling;

import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.hetzner.HetznerApiClient;
import de.cloudnetwork.hetzner.HetznerServer;
import de.cloudnetwork.worker.WorkerInfo;
import de.cloudnetwork.worker.WorkerRegistry;

import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ScalingMonitor {
    /** CPU+RAM average threshold (%) above which a scale-up is triggered. */
    public static final double HIGH_LOAD_THRESHOLD = 80.0;
    /** Number of consecutive high-load checks (each 30 s) before scaling. 4 checks = 2 minutes. */
    public static final int HIGH_LOAD_TRIGGER_COUNT = 4;
    /** Cooldown period (ms) after a scale-up before another may fire. */
    public static final long SCALE_UP_COOLDOWN_MS = 10 * 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WorkerRegistry registry;
    private final HetznerApiClient hetzner;
    private final DatabaseManager db;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "scaling-monitor");
        thread.setDaemon(true);
        return thread;
    });
    private volatile int consecutiveHighLoadCount;
    private volatile long cooldownUntilMs;

    public ScalingMonitor(WorkerRegistry registry, HetznerApiClient hetzner, DatabaseManager db) {
        this.registry = registry;
        this.hetzner = hetzner;
        this.db = db;
    }

    public ScheduledFuture<?> start() {
        return executor.scheduleAtFixedRate(this::checkScaling, 30, 30, TimeUnit.SECONDS);
    }

    public void stop() {
        executor.shutdownNow();
    }

    public int getConsecutiveHighLoadCount() {
        return consecutiveHighLoadCount;
    }

    public boolean isInCooldown() {
        return cooldownUntilMs > System.currentTimeMillis();
    }

    public long getCooldownRemainingSeconds() {
        long remaining = cooldownUntilMs - System.currentTimeMillis();
        return Math.max(0L, remaining / 1000L);
    }

    private void checkScaling() {
        double averageLoad = registry.getAverageTotalLoad();
        if (averageLoad > HIGH_LOAD_THRESHOLD) {
            consecutiveHighLoadCount++;
            System.out.println("[INFO] Skalierungsprüfung: Last hoch (" + String.format("%.2f", averageLoad) + "%), Zähler=" + consecutiveHighLoadCount);
        } else {
            consecutiveHighLoadCount = 0;
        }

        if (consecutiveHighLoadCount >= HIGH_LOAD_TRIGGER_COUNT && !isInCooldown()) {
            try {
                scaleUp();
                consecutiveHighLoadCount = 0;
                cooldownUntilMs = System.currentTimeMillis() + SCALE_UP_COOLDOWN_MS;
            } catch (Exception e) {
                System.err.println("[FEHLER] Scale-Up fehlgeschlagen: " + e.getMessage());
            }
        }
    }

    private void scaleUp() throws Exception {
        String workerId = java.util.UUID.randomUUID().toString();
        String authToken = generateHexToken(24);
        String gatewayHost = db.getConfigValue("gateway_host");
        int gatewayPort = parsePort(db.getConfigValue("gateway_port"), 9876);

        WorkerInfo worker = new WorkerInfo();
        worker.setId(workerId);
        worker.setAuthToken(authToken);
        worker.setStatus(WorkerInfo.WorkerStatus.PROVISIONING);
        worker.setLastHeartbeatMs(System.currentTimeMillis());
        registry.register(worker);
        db.saveWorker(worker);

        HetznerServer server = hetzner.createWorkerServer(workerId, authToken, gatewayHost, gatewayPort);
        String ipv4 = hetzner.waitForServerRunning(server.getId());
        worker.setHetznerServerId(server.getId());
        worker.setIpv4(ipv4);
        db.saveWorker(worker);
        System.out.println("[OK] Neuer Worker wurde provisioniert: " + workerId + " (" + ipv4 + ")");
    }

    private int parsePort(String portValue, int defaultValue) {
        try {
            return portValue == null ? defaultValue : Integer.parseInt(portValue.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String generateHexToken(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        StringBuilder builder = new StringBuilder(bytes * 2);
        for (byte value : data) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
