package de.cloudnetwork.scaling;

import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.hetzner.HetznerApiClient;
import de.cloudnetwork.hetzner.HetznerServer;
import de.cloudnetwork.protocol.Message;
import de.cloudnetwork.worker.WorkerInfo;
import de.cloudnetwork.worker.WorkerRegistry;
import de.cloudnetwork.gateway.GatewaySocketServer;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ScalingMonitor {
    public static final String CONFIG_HIGH_LOAD_THRESHOLD = "scaling_high_load_threshold";
    public static final String CONFIG_LOW_LOAD_THRESHOLD = "scaling_low_load_threshold";
    public static final String CONFIG_TARGET_MIN = "scaling_target_min";
    public static final String CONFIG_TARGET_MAX = "scaling_target_max";
    public static final String CONFIG_WINDOW_MINUTES = "scaling_window_minutes";

    public static final double DEFAULT_HIGH_LOAD_THRESHOLD = 80.0;
    public static final double DEFAULT_LOW_LOAD_THRESHOLD = 40.0;
    public static final double DEFAULT_TARGET_MIN = 60.0;
    public static final double DEFAULT_TARGET_MAX = 70.0;
    public static final int DEFAULT_WINDOW_MINUTES = 4;
    public static final int HIGH_LOAD_TRIGGER_COUNT = 2;
    public static final int LOW_LOAD_TRIGGER_COUNT = 2;
    public static final long SCALE_UP_COOLDOWN_MS = 10 * 60_000L;
    public static final long SCALE_DOWN_COOLDOWN_MS = 5 * 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WorkerRegistry registry;
    private final HetznerApiClient hetzner;
    private final DatabaseManager db;
    private final GatewaySocketServer socketServer;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "scaling-monitor");
        thread.setDaemon(true);
        return thread;
    });
    private final Deque<Double> recentLoadSamples = new ArrayDeque<>();
    private volatile int consecutiveHighLoadCount;
    private volatile int consecutiveLowLoadCount;
    private volatile long cooldownUntilMs;
    private volatile double highLoadThreshold = DEFAULT_HIGH_LOAD_THRESHOLD;
    private volatile double lowLoadThreshold = DEFAULT_LOW_LOAD_THRESHOLD;
    private volatile double targetMin = DEFAULT_TARGET_MIN;
    private volatile double targetMax = DEFAULT_TARGET_MAX;
    private volatile int windowMinutes = DEFAULT_WINDOW_MINUTES;

    public ScalingMonitor(WorkerRegistry registry, HetznerApiClient hetzner, DatabaseManager db, GatewaySocketServer socketServer) {
        this.registry = registry;
        this.hetzner = hetzner;
        this.db = db;
        this.socketServer = socketServer;
    }

    public ScheduledFuture<?> start() {
        reloadSettings();
        return executor.scheduleAtFixedRate(this::checkScaling, 30, 30, TimeUnit.SECONDS);
    }

    public void stop() {
        executor.shutdownNow();
    }

    public int getConsecutiveHighLoadCount() {
        return consecutiveHighLoadCount;
    }

    public int getConsecutiveLowLoadCount() {
        return consecutiveLowLoadCount;
    }

    public boolean isInCooldown() {
        return cooldownUntilMs > System.currentTimeMillis();
    }

    public long getCooldownRemainingSeconds() {
        long remaining = cooldownUntilMs - System.currentTimeMillis();
        return Math.max(0L, remaining / 1000L);
    }

    public double getHighLoadThreshold() {
        return highLoadThreshold;
    }

    public double getLowLoadThreshold() {
        return lowLoadThreshold;
    }

    public double getTargetMin() {
        return targetMin;
    }

    public double getTargetMax() {
        return targetMax;
    }

    public int getWindowMinutes() {
        return windowMinutes;
    }

    public double getSmoothedLoad() {
        synchronized (recentLoadSamples) {
            return computeAverage(recentLoadSamples);
        }
    }

    public void updateSettings(double highThreshold, double lowThreshold, double newTargetMin, double newTargetMax, int newWindowMinutes) throws Exception {
        if (highThreshold <= lowThreshold) {
            throw new IllegalArgumentException("High-Threshold muss größer als Low-Threshold sein.");
        }
        if (newTargetMin > newTargetMax) {
            throw new IllegalArgumentException("targetMin darf nicht größer als targetMax sein.");
        }
        if (newWindowMinutes < 1 || newWindowMinutes > 10) {
            throw new IllegalArgumentException("windowMinutes muss zwischen 1 und 10 liegen.");
        }
        db.setConfigValue(CONFIG_HIGH_LOAD_THRESHOLD, String.valueOf(highThreshold));
        db.setConfigValue(CONFIG_LOW_LOAD_THRESHOLD, String.valueOf(lowThreshold));
        db.setConfigValue(CONFIG_TARGET_MIN, String.valueOf(newTargetMin));
        db.setConfigValue(CONFIG_TARGET_MAX, String.valueOf(newTargetMax));
        db.setConfigValue(CONFIG_WINDOW_MINUTES, String.valueOf(newWindowMinutes));
        reloadSettings();
    }

    public void reloadSettings() {
        highLoadThreshold = readDoubleSetting(CONFIG_HIGH_LOAD_THRESHOLD, DEFAULT_HIGH_LOAD_THRESHOLD);
        lowLoadThreshold = readDoubleSetting(CONFIG_LOW_LOAD_THRESHOLD, DEFAULT_LOW_LOAD_THRESHOLD);
        targetMin = readDoubleSetting(CONFIG_TARGET_MIN, DEFAULT_TARGET_MIN);
        targetMax = readDoubleSetting(CONFIG_TARGET_MAX, DEFAULT_TARGET_MAX);
        windowMinutes = readIntSetting(CONFIG_WINDOW_MINUTES, DEFAULT_WINDOW_MINUTES, 1, 10);
    }

    private void checkScaling() {
        reloadSettings();
        double averageLoad = registry.getAverageTotalLoad();
        double smoothed = recordAndCalculateSmoothedLoad(averageLoad);
        if (smoothed > highLoadThreshold) {
            consecutiveHighLoadCount++;
            consecutiveLowLoadCount = 0;
            ConsoleOutput.info("[INFO] Skalierungsprüfung: Last hoch (" + String.format("%.2f", smoothed) + "%), Zähler=" + consecutiveHighLoadCount);
        } else if (smoothed < lowLoadThreshold) {
            consecutiveLowLoadCount++;
            consecutiveHighLoadCount = 0;
            ConsoleOutput.info("[INFO] Skalierungsprüfung: Last niedrig (" + String.format("%.2f", smoothed) + "%), Zähler=" + consecutiveLowLoadCount);
        } else {
            consecutiveHighLoadCount = 0;
            consecutiveLowLoadCount = 0;
        }

        if (consecutiveHighLoadCount >= HIGH_LOAD_TRIGGER_COUNT && !isInCooldown()) {
            try {
                scaleUp();
                consecutiveHighLoadCount = 0;
                consecutiveLowLoadCount = 0;
                cooldownUntilMs = System.currentTimeMillis() + SCALE_UP_COOLDOWN_MS;
            } catch (Exception e) {
                ConsoleOutput.error("[FEHLER] Scale-Up fehlgeschlagen: " + e.getMessage());
            }
            return;
        }

        if (consecutiveLowLoadCount >= LOW_LOAD_TRIGGER_COUNT && !isInCooldown()) {
            try {
                boolean removed = scaleDown();
                if (removed) {
                    cooldownUntilMs = System.currentTimeMillis() + SCALE_DOWN_COOLDOWN_MS;
                }
                consecutiveLowLoadCount = 0;
                consecutiveHighLoadCount = 0;
            } catch (Exception e) {
                ConsoleOutput.error("[FEHLER] Scale-Down fehlgeschlagen: " + e.getMessage());
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
        ConsoleOutput.info("[OK] Neuer Worker wurde provisioniert: " + workerId + " (" + ipv4 + ")");
    }

    private boolean scaleDown() throws Exception {
        List<WorkerInfo> onlineWorkers = registry.getAll().stream()
                .filter(worker -> worker.getStatus() == WorkerInfo.WorkerStatus.ONLINE)
                .toList();
        if (onlineWorkers.size() <= 1) {
            return false;
        }
        WorkerInfo candidate = onlineWorkers.stream()
                .filter(worker -> worker.getPlayerCount() <= 0)
                .min(Comparator
                        .comparingDouble(this::workerLoad)
                        .thenComparingLong(WorkerInfo::getLastHeartbeatMs))
                .orElse(null);
        if (candidate == null) {
            return false;
        }

        if (socketServer != null) {
            socketServer.sendCommandToWorker(candidate.getId(), Message.shutdown(candidate.getId()));
        }
        candidate.setStatus(WorkerInfo.WorkerStatus.DELETED);
        db.saveWorker(candidate);
        registry.remove(candidate.getId());
        if (candidate.getHetznerServerId() > 0) {
            hetzner.deleteServer(candidate.getHetznerServerId());
        }
        ConsoleOutput.info("[OK] Worker wurde zur Kostensenkung entfernt: " + candidate.getId());
        return true;
    }

    private double workerLoad(WorkerInfo worker) {
        return (worker.getCpuPercent() + worker.getRamPercent()) / 2.0D;
    }

    private double recordAndCalculateSmoothedLoad(double currentLoad) {
        synchronized (recentLoadSamples) {
            recentLoadSamples.addLast(currentLoad);
            int maxSamples = Math.max(2, (windowMinutes * 60) / 30);
            while (recentLoadSamples.size() > maxSamples) {
                recentLoadSamples.removeFirst();
            }
            return computeAverage(recentLoadSamples);
        }
    }

    private double computeAverage(Iterable<Double> values) {
        double total = 0.0D;
        int count = 0;
        for (Double value : values) {
            if (value == null) {
                continue;
            }
            total += value;
            count++;
        }
        return count == 0 ? 0.0D : total / count;
    }

    private double readDoubleSetting(String key, double defaultValue) {
        try {
            String value = db.getConfigValue(key);
            if (value == null || value.isBlank()) {
                db.setConfigValue(key, String.valueOf(defaultValue));
                return defaultValue;
            }
            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private int readIntSetting(String key, int defaultValue, int min, int max) {
        try {
            String value = db.getConfigValue(key);
            if (value == null || value.isBlank()) {
                db.setConfigValue(key, String.valueOf(defaultValue));
                return defaultValue;
            }
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) {
                return defaultValue;
            }
            return parsed;
        } catch (Exception ignored) {
            return defaultValue;
        }
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
