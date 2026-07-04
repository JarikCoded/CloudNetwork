package de.cloudnetwork.scaling;

import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.database.MongoDbDatabaseManager;
import de.cloudnetwork.hetzner.HetznerApiClient;
import de.cloudnetwork.hetzner.HetznerServer;
import de.cloudnetwork.instance.InstanceManager;
import de.cloudnetwork.ssh.SshManager;
import de.cloudnetwork.worker.WorkerInfo;
import de.cloudnetwork.worker.WorkerRegistry;
import org.bson.Document;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
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
    public static final String CONFIG_WORKER_NAME_COUNTER = "worker_name_counter";

    public static final double DEFAULT_HIGH_LOAD_THRESHOLD = 80.0;
    public static final double DEFAULT_LOW_LOAD_THRESHOLD = 40.0;
    public static final double DEFAULT_TARGET_MIN = 60.0;
    public static final double DEFAULT_TARGET_MAX = 70.0;
    public static final int DEFAULT_WINDOW_MINUTES = 4;
    public static final int CHECK_INTERVAL_SECONDS = 15;
    public static final int HIGH_LOAD_TRIGGER_COUNT = (4 * 60) / CHECK_INTERVAL_SECONDS;
    public static final int LOW_LOAD_TRIGGER_COUNT = (15 * 60) / CHECK_INTERVAL_SECONDS;
    public static final long SCALE_ACTION_COOLDOWN_MS = CHECK_INTERVAL_SECONDS * 1000L;

    private final WorkerRegistry registry;
    private final HetznerApiClient hetzner;
    private final DatabaseManager db;
    private final SshManager ssh;
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
    private final AtomicBoolean scaleUpInProgress = new AtomicBoolean(false);
    private final AtomicBoolean scaleDownInProgress = new AtomicBoolean(false);
    /** Optional InstanceManager used to start default instances on new workers. */
    private InstanceManager instanceManager;

    public ScalingMonitor(WorkerRegistry registry, HetznerApiClient hetzner, DatabaseManager db, SshManager ssh) {
        this.registry = registry;
        this.hetzner = hetzner;
        this.db = db;
        this.ssh = ssh;
    }

    public void setInstanceManager(InstanceManager instanceManager) {
        this.instanceManager = instanceManager;
    }

    public ScheduledFuture<?> start() {
        reloadSettings();
        ConsoleOutput.logOnly("[INFO] Starte Skalierungsüberprüfung im Hintergrund (Intervall " + CHECK_INTERVAL_SECONDS + "s).");
        return executor.scheduleAtFixedRate(this::checkScaling, CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
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
        collectMetricsFromWorkers();
        double averageLoad = registry.getAverageTotalLoad();
        double smoothed = recordAndCalculateSmoothedLoad(averageLoad);
        ConsoleOutput.logOnly("[INFO] Skalierungsprüfung ausgeführt: Last=" + String.format("%.2f", smoothed) + "%.");
        if (smoothed > highLoadThreshold) {
            consecutiveHighLoadCount++;
            consecutiveLowLoadCount = 0;
            ConsoleOutput.logOnly("[INFO] Skalierungsprüfung: Last hoch (" + String.format("%.2f", smoothed) + "%), Zähler=" + consecutiveHighLoadCount);
        } else if (smoothed < lowLoadThreshold) {
            consecutiveLowLoadCount++;
            consecutiveHighLoadCount = 0;
            ConsoleOutput.logOnly("[INFO] Skalierungsprüfung: Last niedrig (" + String.format("%.2f", smoothed) + "%), Zähler=" + consecutiveLowLoadCount);
        } else {
            consecutiveHighLoadCount = 0;
            consecutiveLowLoadCount = 0;
        }

        if (consecutiveHighLoadCount >= HIGH_LOAD_TRIGGER_COUNT && !isInCooldown() && scaleUpInProgress.compareAndSet(false, true)) {
            ConsoleOutput.logOnly("[INFO] Scale-Up-Thread wird gestartet.");
            Thread scaleUpThread = new Thread(this::runScaleUpInThread, "scale-up-worker");
            scaleUpThread.setDaemon(true);
            scaleUpThread.start();
            return;
        }

        if (consecutiveLowLoadCount >= LOW_LOAD_TRIGGER_COUNT && !isInCooldown() && scaleDownInProgress.compareAndSet(false, true)) {
            ConsoleOutput.logOnly("[INFO] Scale-Down-Thread wird gestartet.");
            Thread scaleDownThread = new Thread(this::runScaleDownInThread, "scale-down-worker");
            scaleDownThread.setDaemon(true);
            scaleDownThread.start();
        }
    }

    /**
     * Polls CPU/RAM metrics from all ONLINE workers via SSH and updates the registry.
     */
    private void collectMetricsFromWorkers() {
        if (ssh == null) return;
        List<WorkerInfo> onlineWorkers = registry.getAll().stream()
                .filter(w -> w.getStatus() == WorkerInfo.WorkerStatus.ONLINE
                        && w.getIpv4() != null && !w.getIpv4().isBlank())
                .toList();
        for (WorkerInfo worker : onlineWorkers) {
            Thread t = new Thread(() -> {
                SshManager.WorkerMetrics metrics = ssh.collectMetrics(worker.getIpv4());
                if (metrics != null) {
                    registry.updateMetrics(worker.getId(), metrics.cpuPercent(), metrics.ramPercent(), metrics.playerCount());
                    try {
                        db.saveWorker(registry.get(worker.getId()));
                    } catch (Exception ignored) {
                    }
                    ConsoleOutput.logOnly("[SSH] Metriken aktualisiert für " + worker.getId()
                            + ": CPU=" + metrics.cpuPercent() + "% RAM=" + metrics.ramPercent() + "%");
                }
            }, "metrics-poll-" + worker.getId());
            t.setDaemon(true);
            t.start();
        }
    }


    private void runScaleUpInThread() {
        try {
            scaleUp();
            cooldownUntilMs = System.currentTimeMillis() + SCALE_ACTION_COOLDOWN_MS;
            consecutiveHighLoadCount = 0;
            consecutiveLowLoadCount = 0;
        } catch (Exception e) {
            ConsoleOutput.error("[FEHLER] Scale-Up fehlgeschlagen: " + e.getMessage());
        } finally {
            scaleUpInProgress.set(false);
        }
    }

    private void runScaleDownInThread() {
        try {
            boolean removed = scaleDown();
            if (removed) {
                cooldownUntilMs = System.currentTimeMillis() + SCALE_ACTION_COOLDOWN_MS;
            }
            consecutiveLowLoadCount = 0;
            consecutiveHighLoadCount = 0;
        } catch (Exception e) {
            ConsoleOutput.error("[FEHLER] Scale-Down fehlgeschlagen: " + e.getMessage());
        } finally {
            scaleDownInProgress.set(false);
        }
    }

    private void scaleUp() throws Exception {
        WorkerIdentity workerIdentity = nextWorkerIdentity();
        String workerId = workerIdentity.workerId();
        String workerName = workerIdentity.serverName();

        WorkerInfo worker = new WorkerInfo();
        worker.setId(workerId);
        worker.setStatus(WorkerInfo.WorkerStatus.PROVISIONING);
        worker.setLastHeartbeatMs(System.currentTimeMillis());
        registry.register(worker);
        db.saveWorker(worker);

        ConsoleOutput.info("[INFO] Neuer Worker wird erstellt: " + workerName);
        String sshPublicKey = SshManager.getPublicKey(db);
        Long networkId = null;
        try {
            String networkIdValue = db.getConfigValue("hetzner_network_id");
            if (networkIdValue != null && !networkIdValue.isBlank()) {
                networkId = Long.parseLong(networkIdValue.trim());
            }
        } catch (NumberFormatException ignored) {
        }
        HetznerApiClient.ServerProvisioningOptions provisioningOptions =
                networkId != null && networkId > 0
                        ? new HetznerApiClient.ServerProvisioningOptions(networkId, false, false)
                        : HetznerApiClient.ServerProvisioningOptions.defaultForCurrentEnvironment(true);
        HetznerServer server = hetzner.createWorkerServer(workerName, sshPublicKey,
                null, null, null, provisioningOptions);
        HetznerServer readyServer = hetzner.waitForServerDetails(server.getId(), networkId);
        String ipv4 = readyServer.getPrivateIpv4() != null && !readyServer.getPrivateIpv4().isBlank()
                ? readyServer.getPrivateIpv4()
                : readyServer.getIpv4();
        worker.setHetznerServerId(server.getId());
        worker.setIpv4(ipv4);
        db.saveWorker(worker);

        ConsoleOutput.info("[INFO] Worker-Server läuft (" + workerName + " / " + ipv4 + "). Warte auf SSH-Verfügbarkeit im Hintergrund...");
        String finalWorkerId = workerId;
        String finalIpv4 = ipv4;
        String finalWorkerName = workerName;
        Thread waiter = new Thread(() -> {
            if (ssh != null && ssh.waitForSsh(finalIpv4, 20 * 60_000L)) {
                WorkerInfo current = registry.get(finalWorkerId);
                if (current != null) {
                    current.setStatus(WorkerInfo.WorkerStatus.ONLINE);
                    current.setLastHeartbeatMs(System.currentTimeMillis());
                    try {
                        db.saveWorker(current);
                    } catch (Exception ignored) {
                    }
                }
                ConsoleOutput.info("[OK] Worker per SSH erreichbar und bereit: " + finalWorkerName
                        + " (" + finalWorkerId + " / " + finalIpv4 + ")");
                // Start default instances on the new worker (1 Velocity + N Lobby)
                startDefaultInstancesOnWorker(finalWorkerId);
            } else {
                ConsoleOutput.error("[FEHLER] Worker ist nach 20 Minuten noch nicht per SSH erreichbar: "
                        + finalWorkerName + " / " + finalWorkerId + " (" + finalIpv4 + ")");
            }
        }, "worker-ssh-waiter-" + workerId);
        waiter.setDaemon(true);
        waiter.start();
    }

    /**
     * Starts default Velocity and lobby instances on a freshly provisioned worker.
     * The number of instances is read from DB keys {@code scaling_default_velocity_count}
     * (default 1) and {@code scaling_default_lobby_count} (default 1).
     * Falls back silently when no InstanceManager or JAR URLs are configured.
     */
    private void startDefaultInstancesOnWorker(String workerId) {
        if (instanceManager == null) {
            return;
        }
        if (!(db instanceof MongoDbDatabaseManager mongoDb)) {
            return;
        }
        try {
            int velocityCount = readIntSetting("scaling_default_velocity_count", 1, 0, 10);
            int lobbyCount = readIntSetting("scaling_default_lobby_count", 1, 0, 10);
            String velocityUrl = db.getConfigValue("default_velocity_url");
            String paperUrl = db.getConfigValue("default_paper_url");

            int portOffset = 0;
            // Sanitize workerId: keep only lowercase alphanumeric + hyphens to avoid ID collisions
            String safeWorkerSegment = workerId.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9-]", "-")
                    .replaceAll("-{2,}", "-");
            for (int i = 0; i < velocityCount; i++) {
                int port = instanceManager.allocatePort(workerId) + portOffset;
                portOffset++;
                String instanceId = "velocity-" + safeWorkerSegment + "-" + (i + 1);
                // Ensure uniqueness: append port if ID already exists
                if (mongoDb.getMinecraftInstance(instanceId) != null) {
                    instanceId = instanceId + "-" + port;
                }
                Document instance = new Document("_id", instanceId)
                        .append("id", instanceId)
                        .append("name", instanceId)
                        .append("type", "VELOCITY")
                        .append("assignedWorkerId", workerId)
                        .append("workerId", workerId)
                        .append("status", "PENDING_START")
                        .append("autoStart", true)
                        .append("port", port)
                        .append("createdAt", System.currentTimeMillis());
                if (velocityUrl != null && !velocityUrl.isBlank()) {
                    instance.append("downloadUrl", velocityUrl);
                }
                mongoDb.upsertMinecraftInstance(instance);
                instanceManager.startInstance(instance);
                ConsoleOutput.info("[OK] Velocity-Instanz auf neuem Worker gestartet: " + instanceId);
            }

            for (int i = 0; i < lobbyCount; i++) {
                int port = instanceManager.allocatePort(workerId) + portOffset;
                portOffset++;
                String instanceId = "lobby-" + safeWorkerSegment + "-" + (i + 1);
                // Ensure uniqueness: append port if ID already exists
                if (mongoDb.getMinecraftInstance(instanceId) != null) {
                    instanceId = instanceId + "-" + port;
                }
                Document instance = new Document("_id", instanceId)
                        .append("id", instanceId)
                        .append("name", instanceId)
                        .append("type", "MINECRAFT")
                        .append("assignedWorkerId", workerId)
                        .append("workerId", workerId)
                        .append("status", "PENDING_START")
                        .append("autoStart", true)
                        .append("port", port)
                        .append("createdAt", System.currentTimeMillis());
                if (paperUrl != null && !paperUrl.isBlank()) {
                    instance.append("downloadUrl", paperUrl);
                }
                mongoDb.upsertMinecraftInstance(instance);
                instanceManager.startInstance(instance);
                ConsoleOutput.info("[OK] Lobby-Instanz auf neuem Worker gestartet: " + instanceId);
            }
        } catch (Exception e) {
            ConsoleOutput.error("[WARN] Standardinstanzen konnten nicht auf neuem Worker gestartet werden: "
                    + e.getMessage());
        }
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

        // Alle laufenden Minecraft-Instanzen per SSH herunterfahren
        if (ssh != null && candidate.getIpv4() != null && !candidate.getIpv4().isBlank()) {
            try {
                ssh.executeCommand(candidate.getIpv4(),
                        "for s in $(screen -ls | grep -oP '\\d+\\.\\S+'); do screen -S \"$s\" -X stuff 'stop\n' 2>/dev/null; done; sleep 5; for s in $(screen -ls | grep -oP '\\d+\\.\\S+'); do screen -S \"$s\" -X quit 2>/dev/null; done");
            } catch (Exception e) {
                ConsoleOutput.error("[WARN] SSH-Shutdown für Worker " + candidate.getId() + " fehlgeschlagen: " + e.getMessage());
            }
        }
        ConsoleOutput.logOnly("[INFO] Entferne Worker im Hintergrund: " + candidate.getId());
        candidate.setStatus(WorkerInfo.WorkerStatus.DELETED);
        db.saveWorker(candidate);
        registry.remove(candidate.getId());
        if (candidate.getHetznerServerId() > 0) {
            hetzner.deleteServer(candidate.getHetznerServerId());
        }
        ConsoleOutput.info("[OK] Worker wurde zur Kostensenkung entfernt: " + candidate.getId());
        return true;
    }

    private WorkerIdentity nextWorkerIdentity() throws Exception {
        synchronized (db) {
            String currentValue = db.getConfigValue(CONFIG_WORKER_NAME_COUNTER);
            int current;
            try {
                current = currentValue == null || currentValue.isBlank() ? 0 : Integer.parseInt(currentValue.trim());
            } catch (NumberFormatException ignored) {
                current = 0;
            }
            int next = current + 1;
            db.setConfigValue(CONFIG_WORKER_NAME_COUNTER, String.valueOf(next));
            return new WorkerIdentity(String.format("Worker%02d", next), String.format("CloudNetwork-Worker-%02d", next));
        }
    }

    private double workerLoad(WorkerInfo worker) {
        return (worker.getCpuPercent() + worker.getRamPercent()) / 2.0D;
    }

    private double recordAndCalculateSmoothedLoad(double currentLoad) {
        synchronized (recentLoadSamples) {
            recentLoadSamples.addLast(currentLoad);
            int maxSamples = Math.max(2, (windowMinutes * 60) / CHECK_INTERVAL_SECONDS);
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

    private record WorkerIdentity(String workerId, String serverName) {
    }
}
