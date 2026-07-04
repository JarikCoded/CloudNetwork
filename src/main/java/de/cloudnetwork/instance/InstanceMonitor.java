package de.cloudnetwork.instance;

import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.database.MongoDbDatabaseManager;
import de.cloudnetwork.gateway.GatewaySocketServer;
import de.cloudnetwork.ssh.SshManager;
import de.cloudnetwork.worker.WorkerInfo;
import de.cloudnetwork.worker.WorkerRegistry;
import org.bson.Document;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Periodically checks whether running Minecraft/Velocity instances are still alive
 * by inspecting their PID files on the worker servers.
 *
 * <p>Every {@value #CHECK_INTERVAL_SECONDS} seconds all non-{@code OFFLINE} instances
 * are checked. If a PID file no longer exists, or the PID is no longer running, the
 * instance status is set to {@code OFFLINE}. Optionally the instance is restarted
 * automatically if its {@code autoStart} flag is set.</p>
 *
 * <p>When a Velocity instance transitions from any state to {@code OFFLINE} a
 * {@code PROXY_UPDATE} is broadcast to all connected ProxyGateways.</p>
 */
public class InstanceMonitor {

    public static final int CHECK_INTERVAL_SECONDS = 30;

    private final DatabaseManager db;
    private final SshManager ssh;
    private final WorkerRegistry registry;
    private final GatewaySocketServer socketServer;
    private final InstanceManager instanceManager;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "instance-monitor");
        t.setDaemon(true);
        return t;
    });

    public InstanceMonitor(DatabaseManager db,
                           SshManager ssh,
                           WorkerRegistry registry,
                           GatewaySocketServer socketServer,
                           InstanceManager instanceManager) {
        this.db = db;
        this.ssh = ssh;
        this.registry = registry;
        this.socketServer = socketServer;
        this.instanceManager = instanceManager;
    }

    public ScheduledFuture<?> start() {
        ConsoleOutput.logOnly("[INFO] InstanceMonitor gestartet (Intervall " + CHECK_INTERVAL_SECONDS + "s).");
        return executor.scheduleAtFixedRate(
                this::checkInstances,
                CHECK_INTERVAL_SECONDS,
                CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    public void stop() {
        executor.shutdownNow();
    }

    // ── Check loop ────────────────────────────────────────────────────────────

    private void checkInstances() {
        if (!(db instanceof MongoDbDatabaseManager mongoDb)) {
            return;
        }
        if (ssh == null) {
            return;
        }
        List<Document> instances;
        try {
            instances = mongoDb.getMinecraftInstances();
        } catch (Exception e) {
            ConsoleOutput.logOnly("[WARN] InstanceMonitor: Instanzliste konnte nicht gelesen werden: "
                    + e.getMessage());
            return;
        }

        for (Document instance : instances) {
            String status = instance.getString("status");
            // Only monitor instances that should be running
            if ("OFFLINE".equalsIgnoreCase(status) || "DELETED".equalsIgnoreCase(status)) {
                continue;
            }
            String instanceId = instance.getString("_id");
            String workerId = InstanceManager.readWorkerId(instance);
            if (workerId == null) {
                continue;
            }
            WorkerInfo worker = registry.get(workerId);
            if (worker == null || worker.getStatus() != WorkerInfo.WorkerStatus.ONLINE) {
                continue;
            }
            String ip = worker.getIpv4();
            if (ip == null || ip.isBlank()) {
                continue;
            }

            Thread checker = new Thread(() -> checkInstance(mongoDb, instance, instanceId, ip),
                    "instance-check-" + instanceId);
            checker.setDaemon(true);
            checker.start();
        }
    }

    private void checkInstance(MongoDbDatabaseManager mongoDb,
                                Document instance,
                                String instanceId,
                                String workerIp) {
        try {
            String safeId = SshManager.shellEscape(instanceId);
            String pidFile = "/home/cloudnetwork/instances/" + safeId + "/pid";

            // Check: does the PID file exist and is the process alive?
            String checkResult = ssh.executeCommand(workerIp,
                    "PID=$(cat " + pidFile + " 2>/dev/null | tr -d '[:space:]');"
                    + " [ -n \"$PID\" ] && kill -0 \"$PID\" 2>/dev/null && echo running || echo dead");
            boolean alive = checkResult != null && checkResult.contains("running");

            String currentStatus = instance.getString("status");
            boolean isVelocity = InstanceManager.isVelocityType(instance);

            if (!alive && !"STARTING".equalsIgnoreCase(currentStatus)) {
                // Instance has crashed or been stopped externally
                mongoDb.updateMinecraftInstanceStatus(instanceId, "OFFLINE");
                ConsoleOutput.info("[WARN] Instanz nicht mehr aktiv (automatisch als OFFLINE markiert): "
                        + instanceId);
                if (isVelocity && socketServer != null) {
                    socketServer.broadcastProxyUpdate();
                } else if (!isVelocity) {
                    instanceManager.reloadVelocityInstances();
                }

                // Auto-restart if the flag is set
                Boolean autoStart = instance.getBoolean("autoStart");
                if (Boolean.TRUE.equals(autoStart)) {
                    ConsoleOutput.info("[INFO] Auto-Restart für: " + instanceId);
                    try {
                        instanceManager.startInstance(instance);
                    } catch (Exception e) {
                        ConsoleOutput.error("[FEHLER] Auto-Restart fehlgeschlagen für " + instanceId
                                + ": " + e.getMessage());
                    }
                }
            } else if (alive && "STARTING".equalsIgnoreCase(currentStatus)) {
                // Process is up but DB still shows STARTING – update to ONLINE
                mongoDb.updateMinecraftInstanceStatus(instanceId, "ONLINE");
                ConsoleOutput.info("[OK] Instanz jetzt online (von STARTING): " + instanceId);
                if (isVelocity && socketServer != null) {
                    socketServer.broadcastProxyUpdate();
                } else if (!isVelocity) {
                    instanceManager.reloadVelocityInstances();
                }
            }
        } catch (Exception e) {
            ConsoleOutput.logOnly("[WARN] InstanceMonitor-Check fehlgeschlagen für " + instanceId
                    + ": " + e.getMessage());
        }
    }
}
