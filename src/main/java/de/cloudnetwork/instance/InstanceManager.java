package de.cloudnetwork.instance;

import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.database.MongoDbDatabaseManager;
import de.cloudnetwork.gateway.GatewaySocketServer;
import de.cloudnetwork.ssh.SshManager;
import de.cloudnetwork.worker.WorkerInfo;
import de.cloudnetwork.worker.WorkerRegistry;
import org.bson.Document;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

/**
 * Manages the lifecycle of Minecraft and Velocity proxy instances on worker servers.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Port allocation: finds the next free port on a worker (starting at 25577).</li>
 *   <li>Configuration generation: produces {@code velocity.toml}, {@code forwarding.secret},
 *       {@code server.properties}, and {@code config/paper-global.yml}.</li>
 *   <li>Instance start/stop via SSH: uploads config files and starts/stops screen sessions.</li>
 *   <li>PROXY_UPDATE broadcast: notifies connected ProxyGateways whenever a Velocity instance
 *       comes online or goes offline.</li>
 *   <li>Velocity reload: pushes an updated {@code velocity.toml} (and triggers
 *       {@code /velocity reload}) whenever the lobby backend list changes.</li>
 * </ul>
 *
 * <h2>DB config keys</h2>
 * <ul>
 *   <li>{@value #DB_KEY_FORWARDING_SECRET} – shared Velocity modern-forwarding secret</li>
 * </ul>
 */
public class InstanceManager {

    /** DB key for the Velocity modern-forwarding secret (shared by all instances). */
    public static final String DB_KEY_FORWARDING_SECRET = "velocity_forwarding_secret";

    /**
     * All instances on a worker start at this port; each additional instance
     * receives the next sequential port number.
     */
    static final int DEFAULT_BASE_PORT = 25577;

    /** Milliseconds to wait after starting a screen session before checking the PID. */
    private static final long INSTANCE_STARTUP_WAIT_MS = 15_000L;

    /** Seconds to wait for a graceful Minecraft/Velocity shutdown before killing the screen. */
    private static final int GRACEFUL_SHUTDOWN_WAIT_SECONDS = 5;

    private final DatabaseManager db;
    private final SshManager ssh;
    private final GatewaySocketServer socketServer;
    private final WorkerRegistry registry;

    public InstanceManager(DatabaseManager db,
                           SshManager ssh,
                           GatewaySocketServer socketServer,
                           WorkerRegistry registry) {
        this.db = db;
        this.ssh = ssh;
        this.socketServer = socketServer;
        this.registry = registry;
    }

    // ── Port allocation ───────────────────────────────────────────────────────

    /**
     * Returns the next free TCP port for a new instance on the given worker.
     * The port range starts at {@value #DEFAULT_BASE_PORT} and increments by 1
     * for every instance already assigned to that worker.
     */
    public int allocatePort(String workerId) throws Exception {
        if (!(db instanceof MongoDbDatabaseManager mongoDb)) {
            return DEFAULT_BASE_PORT;
        }
        List<Document> workerInstances = mongoDb.getInstancesByWorker(workerId);
        int maxPort = DEFAULT_BASE_PORT - 1;
        for (Document inst : workerInstances) {
            Object portObj = inst.get("port");
            if (portObj instanceof Number n) {
                maxPort = Math.max(maxPort, n.intValue());
            }
        }
        return maxPort + 1;
    }

    // ── Instance start ────────────────────────────────────────────────────────

    /**
     * Starts an instance on its assigned worker.
     * <ol>
     *   <li>Downloads the JAR (if a downloadUrl is set).</li>
     *   <li>Uploads the appropriate configuration files.</li>
     *   <li>Starts a {@code screen} session.</li>
     *   <li>Updates the DB status to {@code STARTING}.</li>
     *   <li>In a background thread: waits ~15 s, verifies the PID, then sets
     *       the status to {@code ONLINE} and triggers {@code PROXY_UPDATE} for
     *       Velocity instances.</li>
     * </ol>
     *
     * @param instance MongoDB document from the {@code minecraft_instances} collection
     */
    public void startInstance(Document instance) throws Exception {
        MongoDbDatabaseManager mongoDb = requireMongo("server start");
        String instanceId = instance.getString("_id");
        WorkerInfo worker = resolveWorker(instance);
        String workerIp = worker.getIpv4();

        String safeId = SshManager.shellEscape(instanceId);
        String instanceDir = "/home/cloudnetwork/instances/" + safeId;

        boolean isVelocity = isVelocityType(instance);
        Object portObj = instance.get("port");
        int port = portObj instanceof Number n ? n.intValue() : DEFAULT_BASE_PORT;
        String forwardingSecret = getOrGenerateForwardingSecret();

        // ── Download JAR ──────────────────────────────────────────────────────
        Object downloadUrl = instance.get("downloadUrl");
        String jarPath = instanceDir + "/server.jar";
        ssh.executeCommand(workerIp, "mkdir -p " + instanceDir);
        if (downloadUrl != null && !downloadUrl.toString().isBlank()) {
            String safeUrl = SshManager.shellEscape(downloadUrl.toString());
            ssh.executeCommand(workerIp, "wget -q -O " + instanceDir + "/server.jar " + safeUrl);
        }

        // ── Upload config ─────────────────────────────────────────────────────
        if (isVelocity) {
            uploadVelocityConfig(workerIp, instanceDir, port, forwardingSecret, mongoDb);
        } else {
            uploadLobbyConfig(workerIp, instanceDir, port, forwardingSecret);
        }

        // ── Start screen session ──────────────────────────────────────────────
        String safeJar = SshManager.shellEscape(jarPath);
        if (isVelocity) {
            ssh.executeCommand(workerIp,
                    "cd " + instanceDir
                    + " && screen -dmS " + safeId + " java -Xmx512M -jar " + safeJar
                    + " && sleep 2 && screen -S " + safeId
                    + " -Q info 2>/dev/null | grep -oP '(?<=\\(pid )\\d+' > "
                    + instanceDir + "/pid 2>/dev/null || true");
        } else {
            ssh.executeCommand(workerIp,
                    "cd " + instanceDir
                    + " && echo eula=true > eula.txt"
                    + " && screen -dmS " + safeId + " java -Xmx1G -jar " + safeJar + " nogui"
                    + " && sleep 2 && screen -S " + safeId
                    + " -Q info 2>/dev/null | grep -oP '(?<=\\(pid )\\d+' > "
                    + instanceDir + "/pid 2>/dev/null || true");
        }

        mongoDb.updateMinecraftInstanceStatus(instanceId, "STARTING");
        ConsoleOutput.info("[OK] Instanz gestartet: " + instanceId + " (Port " + port
                + ") auf " + readWorkerId(instance));

        // ── Background: wait for PID → ONLINE → PROXY_UPDATE ─────────────────
        final String finalInstanceId = instanceId;
        final String finalWorkerIp = workerIp;
        final boolean finalIsVelocity = isVelocity;
        Thread pidWaiter = new Thread(() -> {
            try {
                Thread.sleep(INSTANCE_STARTUP_WAIT_MS);
                String pid = ssh.executeCommand(finalWorkerIp,
                        "cat " + instanceDir + "/pid 2>/dev/null | tr -d '[:space:]'");
                if (pid != null && !pid.isBlank() && pid.matches("\\d+")) {
                    mongoDb.updateMinecraftInstanceStatus(finalInstanceId, "ONLINE");
                    ConsoleOutput.info("[OK] Instanz online: " + finalInstanceId);
                    if (finalIsVelocity && socketServer != null) {
                        socketServer.broadcastProxyUpdate();
                    } else if (!finalIsVelocity) {
                        // Lobby online – update velocity.toml of running Velocity instances
                        reloadVelocityInstances();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                ConsoleOutput.error("[WARN] PID-Prüfung fehlgeschlagen für "
                        + finalInstanceId + ": " + e.getMessage());
            }
        }, "instance-pid-waiter-" + instanceId);
        pidWaiter.setDaemon(true);
        pidWaiter.start();
    }

    // ── Instance stop ─────────────────────────────────────────────────────────

    /**
     * Gracefully stops an instance: sends {@code stop} to the Minecraft/Velocity
     * console, waits 5 seconds, then quits the screen session.
     */
    public void stopInstance(Document instance) throws Exception {
        MongoDbDatabaseManager mongoDb = requireMongo("server stop");
        String instanceId = instance.getString("_id");
        WorkerInfo worker = resolveWorker(instance);
        String workerIp = worker.getIpv4();

        String safeId = SshManager.shellEscape(instanceId);
        String instanceDir = "/home/cloudnetwork/instances/" + safeId;

        ssh.executeCommand(workerIp,
                "screen -S " + safeId + " -X stuff 'stop\n' 2>/dev/null; sleep " + GRACEFUL_SHUTDOWN_WAIT_SECONDS + ";"
                + " screen -S " + safeId + " -X quit 2>/dev/null; rm -f " + instanceDir + "/pid");

        mongoDb.updateMinecraftInstanceStatus(instanceId, "OFFLINE");
        ConsoleOutput.info("[OK] Instanz gestoppt: " + instanceId + " auf " + readWorkerId(instance));

        boolean isVelocity = isVelocityType(instance);
        if (isVelocity && socketServer != null) {
            socketServer.broadcastProxyUpdate();
        } else if (!isVelocity) {
            // Lobby offline – remove from velocity.toml
            reloadVelocityInstances();
        }
    }

    // ── Velocity config reload ────────────────────────────────────────────────

    /**
     * Uploads a freshly generated {@code velocity.toml} to every ONLINE Velocity
     * instance and sends a {@code /velocity reload} command via the screen session.
     * Called automatically when a lobby instance goes ONLINE or OFFLINE.
     */
    public void reloadVelocityInstances() {
        if (!(db instanceof MongoDbDatabaseManager mongoDb)) {
            return;
        }
        if (ssh == null) {
            return;
        }
        try {
            List<Document> velocityInstances = mongoDb.getOnlineVelocityInstances();
            List<Document> lobbyInstances = mongoDb.getOnlineLobbyInstances();

            for (Document vel : velocityInstances) {
                String instanceId = vel.getString("_id");
                String workerId = readWorkerId(vel);
                if (workerId == null) {
                    continue;
                }
                WorkerInfo worker = registry.get(workerId);
                if (worker == null || worker.getIpv4() == null || worker.getIpv4().isBlank()) {
                    continue;
                }
                Object portObj = vel.get("port");
                int port = portObj instanceof Number n ? n.intValue() : DEFAULT_BASE_PORT;
                String safeId = SshManager.shellEscape(instanceId);
                String instanceDir = "/home/cloudnetwork/instances/" + safeId;

                String toml = generateVelocityToml(port, lobbyInstances);
                ssh.uploadBytes(worker.getIpv4(),
                        toml.getBytes(StandardCharsets.UTF_8),
                        instanceDir + "/velocity.toml");
                // Velocity supports live reload without restart
                ssh.executeCommand(worker.getIpv4(),
                        "screen -S " + safeId + " -X stuff 'velocity reload\n' 2>/dev/null || true");
                ConsoleOutput.info("[OK] Velocity-Konfiguration neu geladen: " + instanceId);
            }
        } catch (Exception e) {
            ConsoleOutput.error("[WARN] Velocity-Reload fehlgeschlagen: " + e.getMessage());
        }
    }

    // ── Forwarding secret ─────────────────────────────────────────────────────

    /**
     * Returns the stored Velocity forwarding secret, generating and persisting a new one
     * if none exists yet.
     */
    public String getOrGenerateForwardingSecret() throws Exception {
        String secret = db.getConfigValue(DB_KEY_FORWARDING_SECRET);
        if (secret == null || secret.isBlank()) {
            byte[] data = new byte[16];
            new SecureRandom().nextBytes(data);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : data) {
                sb.append(String.format("%02x", b));
            }
            secret = sb.toString();
            db.setConfigValue(DB_KEY_FORWARDING_SECRET, secret);
            ConsoleOutput.info("[OK] Velocity-Forwarding-Secret generiert und gespeichert.");
        }
        return secret;
    }

    // ── Config generators (public for reuse) ──────────────────────────────────

    /**
     * Generates a {@code velocity.toml} configuration file.
     *
     * @param port          the port Velocity should bind to
     * @param lobbyInstances list of ONLINE lobby instance documents
     * @return TOML content as a string
     */
    public String generateVelocityToml(int port, List<Document> lobbyInstances) {
        StringBuilder servers = new StringBuilder();
        StringBuilder tryList = new StringBuilder();

        for (Document lobby : lobbyInstances) {
            String id = lobby.getString("_id");
            if (id == null) {
                continue;
            }
            Object portObj = lobby.get("port");
            int lobbyPort = portObj instanceof Number n ? n.intValue() : DEFAULT_BASE_PORT + 1;
            String workerId = readWorkerId(lobby);
            if (workerId == null) {
                continue;
            }
            WorkerInfo worker = registry.get(workerId);
            if (worker == null || worker.getIpv4() == null || worker.getIpv4().isBlank()) {
                continue;
            }
            servers.append(id).append(" = \"").append(worker.getIpv4()).append(":")
                    .append(lobbyPort).append("\"\n");
            if (!tryList.isEmpty()) {
                tryList.append(", ");
            }
            tryList.append("\"").append(id).append("\"");
        }

        return "config-version = \"2.7\"\n"
                + "bind = \"0.0.0.0:" + port + "\"\n"
                + "motd = \"&3CloudNetwork\"\n"
                + "show-max-players = 500\n"
                + "online-mode = true\n"
                + "force-key-authentication = true\n"
                + "player-info-forwarding-mode = \"modern\"\n"
                + "announce-forge = false\n"
                + "kick-existing-players = false\n"
                + "ping-passthrough = \"DISABLED\"\n\n"
                + "[servers]\n"
                + servers
                + "\n[try]\n"
                + tryList
                + "\n\n[advanced]\n"
                + "compression-threshold = 256\n"
                + "compression-level = -1\n"
                + "login-ratelimit = 3000\n"
                + "connection-timeout = 5000\n"
                + "read-timeout = 30000\n"
                + "haproxy-protocol = false\n"
                + "tcp-fast-open = false\n"
                + "bungee-plugin-messaging-channel = true\n"
                + "show-ping-requests = false\n"
                + "failover-on-unexpected-server-disconnect = true\n"
                + "announce-proxy-commands = true\n"
                + "log-command-executions = false\n"
                + "log-player-connections = true\n\n"
                + "[query]\n"
                + "enabled = false\n"
                + "port = 30123\n"
                + "map = \"CloudNetwork\"\n"
                + "show-plugins = false\n";
    }

    /**
     * Generates a {@code server.properties} file for a Paper/Spigot lobby server.
     * Sets {@code online-mode=false} because Velocity handles authentication.
     *
     * @param port the port this server should bind to
     * @return content of {@code server.properties}
     */
    public String generateServerProperties(int port) {
        return "server-ip=0.0.0.0\n"
                + "server-port=" + port + "\n"
                + "online-mode=false\n"
                + "motd=CloudNetwork Lobby\n"
                + "max-players=100\n"
                + "spawn-protection=0\n"
                + "view-distance=10\n"
                + "simulation-distance=10\n";
    }

    /**
     * Generates a minimal {@code config/paper-global.yml} that enables Velocity
     * modern-mode forwarding with the given shared secret.
     *
     * @param forwardingSecret the shared Velocity forwarding secret
     * @return content of {@code config/paper-global.yml}
     */
    public String generatePaperGlobalYml(String forwardingSecret) {
        return "_version: 28\n"
                + "proxies:\n"
                + "  velocity:\n"
                + "    enabled: true\n"
                + "    online-mode: true\n"
                + "    secret: '" + forwardingSecret + "'\n";
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void uploadVelocityConfig(String workerIp,
                                      String instanceDir,
                                      int port,
                                      String forwardingSecret,
                                      MongoDbDatabaseManager mongoDb) throws Exception {
        List<Document> lobbyInstances = mongoDb.getOnlineLobbyInstances();
        String toml = generateVelocityToml(port, lobbyInstances);
        ssh.uploadBytes(workerIp, toml.getBytes(StandardCharsets.UTF_8),
                instanceDir + "/velocity.toml");
        ssh.uploadBytes(workerIp, forwardingSecret.getBytes(StandardCharsets.UTF_8),
                instanceDir + "/forwarding.secret");
    }

    private void uploadLobbyConfig(String workerIp,
                                   String instanceDir,
                                   int port,
                                   String forwardingSecret) throws Exception {
        String serverProps = generateServerProperties(port);
        ssh.uploadBytes(workerIp, serverProps.getBytes(StandardCharsets.UTF_8),
                instanceDir + "/server.properties");
        // Paper requires config/paper-global.yml for Velocity forwarding
        String paperYml = generatePaperGlobalYml(forwardingSecret);
        ssh.executeCommand(workerIp, "mkdir -p " + instanceDir + "/config");
        ssh.uploadBytes(workerIp, paperYml.getBytes(StandardCharsets.UTF_8),
                instanceDir + "/config/paper-global.yml");
    }

    private MongoDbDatabaseManager requireMongo(String operation) {
        if (!(db instanceof MongoDbDatabaseManager m)) {
            throw new IllegalStateException(
                    operation + " benötigt MongoDB als Datenbank-Backend.");
        }
        return m;
    }

    private WorkerInfo resolveWorker(Document instance) throws Exception {
        String workerId = readWorkerId(instance);
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalStateException(
                    "Instanz " + instance.getString("_id") + " hat keinen Worker zugewiesen.");
        }
        WorkerInfo worker = registry.get(workerId);
        if (worker == null) {
            worker = db.getWorker(workerId);
        }
        if (worker == null || worker.getIpv4() == null || worker.getIpv4().isBlank()) {
            throw new IllegalStateException("Worker-IP nicht verfügbar für: " + workerId);
        }
        if (ssh == null) {
            throw new IllegalStateException("SSH-Manager nicht initialisiert.");
        }
        return worker;
    }

    static boolean isVelocityType(Document instance) {
        String type = instance.getString("type");
        return type != null && type.toUpperCase(Locale.ROOT).contains("VELOCITY");
    }

    static String readWorkerId(Document instance) {
        if (instance == null) {
            return null;
        }
        for (String key : new String[]{"assignedWorkerId", "workerId", "rootserverId"}) {
            Object v = instance.get(key);
            if (v != null) {
                return String.valueOf(v);
            }
        }
        return null;
    }
}
