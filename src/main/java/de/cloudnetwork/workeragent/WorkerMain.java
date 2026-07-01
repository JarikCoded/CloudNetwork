package de.cloudnetwork.workeragent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.cloudnetwork.config.CloudConfig;
import de.cloudnetwork.config.ConfigManager;
import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.MongoDbDatabaseManager;
import de.cloudnetwork.protocol.Message;
import de.cloudnetwork.protocol.MessageType;
import de.cloudnetwork.tls.TlsManager;
import org.bson.Document;

import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class WorkerMain {
    private static final String FALLBACK_VELOCITY_URL = "https://api.papermc.io/v2/projects/velocity/versions/3.4.0/builds/474/downloads/velocity-3.4.0-474.jar";
    private static final String FALLBACK_PAPER_URL = "https://api.papermc.io/v2/projects/paper/versions/1.20.6/builds/151/downloads/paper-1.20.6-151.jar";
    /** DB config key for the global default Velocity download URL. */
    static final String CONFIG_VELOCITY_URL = "default_velocity_url";
    /** DB config key for the global default Paper/Minecraft download URL. */
    static final String CONFIG_PAPER_URL = "default_paper_url";
    /** Base directory for all managed Minecraft instance data. */
    private static final Path INSTANCES_BASE = Path.of(System.getProperty("user.home"), "cloudnetwork", "instances");
    /** Local JAR cache shared between instances on this worker. */
    private static final Path JARS_BASE = Path.of(System.getProperty("user.home"), "cloudnetwork", "jars");

    public static void main(String[] args) {
        MongoDbDatabaseManager db = new MongoDbDatabaseManager();
        WorkerSocketClient client = null;
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        AtomicBoolean running = new AtomicBoolean(true);
        Map<String, ManagedProcess> managedInstances = new ConcurrentHashMap<>();
        /** Instance IDs for which an interactive console session is currently active. */
        Set<String> attachedConsoles = ConcurrentHashMap.newKeySet();

        try {
            CloudConfig config = ConfigManager.load();
            db.connect(config.getDbHost(), config.getDbPort(), config.getDbName(), config.getDbUser(), config.getDbPassword());

            String workerId = readValue(db, "worker_id", "WORKER_ID");
            String authToken = readValue(db, "worker_auth_token", "WORKER_AUTH_TOKEN");
            String gatewayHost = readValue(db, "gateway_host", "GATEWAY_HOST");
            int gatewayPort = readIntValue(db, "gateway_port", "GATEWAY_PORT", 9876);

            if (workerId == null || workerId.isBlank() || authToken == null || authToken.isBlank() || gatewayHost == null || gatewayHost.isBlank()) {
                throw new IllegalStateException("Worker-Konfiguration unvollständig.");
            }

            SSLContext sslContext = TlsManager.createClientSSLContext(db);
            client = new WorkerSocketClient(gatewayHost, gatewayPort, workerId, authToken, sslContext);
            client.connect();

            // Forward the worker's own log output to the Gateway as LOG_LINE messages.
            final String finalWorkerId = workerId;
            final WorkerSocketClient finalClient = client;
            ConsoleOutput.setLogListener(entry -> {
                try {
                    finalClient.sendLogLine("worker", entry);
                } catch (IOException ignored) {
                }
            });

            MetricsAccumulator metricsAccumulator = new MetricsAccumulator();
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    finalClient.sendHeartbeat();
                } catch (IOException e) {
                    ConsoleOutput.error("[FEHLER] Heartbeat/Metriken konnten nicht gesendet werden: " + e.getMessage());
                }
            }, 0, 10, TimeUnit.SECONDS);

            scheduler.scheduleAtFixedRate(() -> metricsAccumulator.addSample(
                    SystemMetrics.getCpuUsagePercent(),
                    SystemMetrics.getRamUsagePercent(),
                    0
            ), 0, 5, TimeUnit.SECONDS);

            scheduler.scheduleAtFixedRate(() -> {
                try {
                    Message message = finalClient.readMessage();
                    if (message != null) {
                        handleIncomingMessage(finalClient, message, running, metricsAccumulator, db, managedInstances, attachedConsoles);
                    }
                } catch (IOException e) {
                    ConsoleOutput.error("[FEHLER] Gateway-Nachricht konnte nicht gelesen werden: " + e.getMessage());
                }
            }, 1, 1, TimeUnit.SECONDS);

            ConsoleOutput.info("[INFO] Worker läuft. Tippe 'stop' zum Beenden.");
            try (Scanner scanner = new Scanner(System.in)) {
                while (running.get() && scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim();
                    if ("stop".equalsIgnoreCase(line)) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            ConsoleOutput.error("[FEHLER] Worker konnte nicht gestartet werden: " + e.getMessage());
            ConsoleOutput.logException(e);
            System.exit(1);
        } finally {
            ConsoleOutput.setLogListener(null);
            running.set(false);
            scheduler.shutdownNow();
            for (ManagedProcess process : managedInstances.values()) {
                process.stop();
            }
            if (client != null) {
                client.disconnect();
            }
            db.close();
        }
    }

    private static void handleIncomingMessage(WorkerSocketClient client,
                                              Message message,
                                              AtomicBoolean running,
                                              MetricsAccumulator metricsAccumulator,
                                              MongoDbDatabaseManager db,
                                              Map<String, ManagedProcess> managedInstances,
                                              Set<String> attachedConsoles) throws IOException {
        if (message.getType() == MessageType.COMMAND) {
            JsonObject payload = JsonParser.parseString(message.getPayload()).getAsJsonObject();
            String command = payload.has("command") ? payload.get("command").getAsString() : "";
            ConsoleOutput.info("[INFO] Befehl vom Gateway: " + command);
            if ("SCALING_CHECK".equalsIgnoreCase(command.trim())) {
                MetricsSnapshot snapshot = metricsAccumulator.drainSnapshot();
                JsonObject resultPayload = new JsonObject();
                resultPayload.addProperty("type", "SCALING_CHECK");
                resultPayload.addProperty("cpu", snapshot.cpu());
                resultPayload.addProperty("ram", snapshot.ram());
                resultPayload.addProperty("players", snapshot.players());
                resultPayload.addProperty("samples", snapshot.samples());
                client.sendCommandResult(resultPayload.toString());
            } else if (command.toLowerCase(Locale.ROOT).startsWith("prepare ")) {
                String instanceId = command.substring("prepare ".length()).trim();
                String result = prepareInstance(db, instanceId);
                client.sendCommandResult(result);
            } else if (command.toLowerCase(Locale.ROOT).startsWith("start ")) {
                String instanceId = command.substring("start ".length()).trim();
                String result = startInstance(db, managedInstances, attachedConsoles, client, instanceId);
                client.sendCommandResult(result);
            } else if (command.toLowerCase(Locale.ROOT).startsWith("stop ")) {
                String instanceId = command.substring("stop ".length()).trim();
                String result = stopInstance(db, managedInstances, instanceId);
                client.sendCommandResult(result);
            } else {
                client.sendCommandResult("ACK " + command);
            }
        } else if (message.getType() == MessageType.CONSOLE_ATTACH) {
            JsonObject payload = JsonParser.parseString(message.getPayload() != null ? message.getPayload() : "{}").getAsJsonObject();
            String instanceId = payload.has("instanceId") ? payload.get("instanceId").getAsString() : "";
            if (!instanceId.isBlank()) {
                attachedConsoles.add(instanceId);
                ConsoleOutput.info("[INFO] Konsolen-Sitzung gestartet für: " + instanceId);
            }
        } else if (message.getType() == MessageType.CONSOLE_DETACH) {
            JsonObject payload = JsonParser.parseString(message.getPayload() != null ? message.getPayload() : "{}").getAsJsonObject();
            String instanceId = payload.has("instanceId") ? payload.get("instanceId").getAsString() : "";
            if (!instanceId.isBlank()) {
                attachedConsoles.remove(instanceId);
                ConsoleOutput.info("[INFO] Konsolen-Sitzung beendet für: " + instanceId);
            }
        } else if (message.getType() == MessageType.CONSOLE_INPUT) {
            JsonObject payload = JsonParser.parseString(message.getPayload() != null ? message.getPayload() : "{}").getAsJsonObject();
            String instanceId = payload.has("instanceId") ? payload.get("instanceId").getAsString() : "";
            String line = payload.has("line") ? payload.get("line").getAsString() : "";
            if (!instanceId.isBlank() && !line.isBlank()) {
                ManagedProcess proc = managedInstances.get(instanceId);
                if (proc != null) {
                    proc.writeStdin(line);
                }
            }
        } else if (message.getType() == MessageType.SHUTDOWN) {
            ConsoleOutput.info("[INFO] Shutdown vom Gateway empfangen.");
            client.sendCommandResult("SHUTTING_DOWN");
            running.set(false);
        }
    }

    private static String readValue(MongoDbDatabaseManager db, String configKey, String envKey) throws Exception {
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        return db.getConfigValue(configKey);
    }

    private static int readIntValue(MongoDbDatabaseManager db, String configKey, String envKey, int defaultValue) throws Exception {
        String value = readValue(db, configKey, envKey);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Downloads the server JAR and writes configuration files for {@code instanceId}
     * without starting the JVM process.  Idempotent – safe to call multiple times.
     *
     * <p>JAR resolution order:
     * <ol>
     *   <li>The instance's own {@code downloadUrl} field (if non-blank)</li>
     *   <li>A shared local JAR in {@code ~/cloudnetwork/jars/} (velocity.jar or paper.jar)</li>
     *   <li>The global default URL stored in the DB ({@code default_velocity_url} / {@code default_paper_url})</li>
     *   <li>The built-in fallback URL</li>
     * </ol>
     */
    private static String prepareInstance(MongoDbDatabaseManager db, String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return "PREPARE_FAILED missing_instance_id";
        }
        if (!isValidInstanceId(instanceId)) {
            return "PREPARE_FAILED invalid_instance_id " + instanceId;
        }
        try {
            Document instance = db.getMinecraftInstance(instanceId);
            if (instance == null) {
                return "PREPARE_FAILED instance_not_found " + instanceId;
            }
            Path instanceDir = resolveInstanceDir(instanceId);
            Files.createDirectories(instanceDir);
            String type = readString(instance, "type");
            int port = readInt(instance, "port", inferDefaultPort(type, instanceId));
            String downloadUrl = readString(instance, "downloadUrl");

            if ("VELOCITY".equalsIgnoreCase(type) || instanceId.toLowerCase(Locale.ROOT).contains("velocity")) {
                Path jarPath = instanceDir.resolve("velocity.jar");
                String effectiveUrl = resolveJarUrl(db, downloadUrl, "velocity.jar", CONFIG_VELOCITY_URL, FALLBACK_VELOCITY_URL);
                copyOrDownloadJar(effectiveUrl, jarPath, "velocity.jar");
                writeVelocityConfigIfMissing(instanceDir, instance);
            } else {
                Path jarPath = instanceDir.resolve("lobby.jar");
                String effectiveUrl = resolveJarUrl(db, downloadUrl, "paper.jar", CONFIG_PAPER_URL, FALLBACK_PAPER_URL);
                copyOrDownloadJar(effectiveUrl, jarPath, "paper.jar");
                writeLobbyConfigIfMissing(instanceDir, port);
            }
            return "PREPARED " + instanceId;
        } catch (Exception e) {
            return "PREPARE_FAILED " + instanceId + " " + e.getMessage();
        }
    }

    /**
     * Resolves the effective JAR URL/path in priority order.
     *
     * @param downloadUrl  instance-specific URL (may be blank)
     * @param localJarName file name in ~/cloudnetwork/jars/ (e.g. "velocity.jar")
     * @param dbKey        DB config key for the global default URL
     * @param fallback     built-in fallback URL
     * @return URL string (http/https) or absolute local path string
     */
    private static String resolveJarUrl(MongoDbDatabaseManager db, String downloadUrl,
                                        String localJarName, String dbKey, String fallback) {
        // 1. Instance-specific URL
        if (!downloadUrl.isBlank()) {
            return downloadUrl;
        }
        // 2. Local shared JAR on this worker
        Path localJar = JARS_BASE.resolve(localJarName);
        if (Files.exists(localJar)) {
            return localJar.toAbsolutePath().toString();
        }
        // 3. DB-configured global default
        try {
            String dbUrl = db.getConfigValue(dbKey);
            if (dbUrl != null && !dbUrl.isBlank()) {
                return dbUrl;
            }
        } catch (Exception ignored) {
        }
        // 4. Built-in fallback
        return fallback;
    }

    /**
     * Copies a local JAR or downloads from URL into the target path (if not already present).
     *
     * @param sourceUrlOrPath either an http(s) URL or an absolute file path
     * @param target          destination path
     * @param displayName     name for log messages
     */
    private static void copyOrDownloadJar(String sourceUrlOrPath, Path target, String displayName) throws Exception {
        if (Files.exists(target)) {
            return;
        }
        // Detect local path vs URL
        if (!sourceUrlOrPath.startsWith("http://") && !sourceUrlOrPath.startsWith("https://")) {
            Path source = Path.of(sourceUrlOrPath);
            if (!Files.exists(source)) {
                throw new IOException("Lokale JAR-Datei nicht gefunden: " + source);
            }
            ConsoleOutput.info("[INFO] Kopiere lokale JAR: " + source + " → " + displayName);
            Files.copy(source, target);
            return;
        }
        downloadJarIfMissing(sourceUrlOrPath, target);
    }

    private static String startInstance(MongoDbDatabaseManager db,
                                        Map<String, ManagedProcess> managedInstances,
                                        Set<String> attachedConsoles,
                                        WorkerSocketClient client,
                                        String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return "START_FAILED missing_instance_id";
        }
        if (!isValidInstanceId(instanceId)) {
            return "START_FAILED invalid_instance_id " + instanceId;
        }
        ManagedProcess existing = managedInstances.get(instanceId);
        if (existing != null && existing.isRunning()) {
            return "START_SKIPPED already_running " + instanceId;
        }
        try {
            Document instance = db.getMinecraftInstance(instanceId);
            if (instance == null) {
                return "START_FAILED instance_not_found " + instanceId;
            }
            // Ensure JAR and configs are present first
            String prepareResult = prepareInstance(db, instanceId);
            if (prepareResult.startsWith("PREPARE_FAILED")) {
                return "START_FAILED " + instanceId + " preparation failed: " + prepareResult;
            }
            Path instanceDir = resolveInstanceDir(instanceId);
            String type = readString(instance, "type");

            Process process;
            if ("VELOCITY".equalsIgnoreCase(type) || instanceId.toLowerCase(Locale.ROOT).contains("velocity")) {
                Path jarPath = instanceDir.resolve("velocity.jar");
                process = startJavaProcess(instanceDir, jarPath, "-Xms256M", "-Xmx512M");
            } else {
                Path jarPath = instanceDir.resolve("lobby.jar");
                process = startJavaProcess(instanceDir, jarPath, "-Xms512M", "-Xmx1024M", "nogui");
            }
            ManagedProcess managedProcess = new ManagedProcess(process);
            managedInstances.put(instanceId, managedProcess);

            // Start log-piping thread: captures process stdout/stderr, writes to file, and
            // forwards to the Gateway as LOG_LINE (always) and CONSOLE_OUTPUT (when attached).
            Path logFile = instanceDir.resolve("latest.log");
            Thread piper = new Thread(() -> pipeProcessLog(
                    process.getInputStream(), instanceId, logFile, client, attachedConsoles),
                    "log-piper-" + instanceId);
            piper.setDaemon(true);
            piper.start();

            db.updateMinecraftInstanceStatus(instanceId, "ONLINE");
            return "STARTED " + instanceId;
        } catch (Exception e) {
            return "START_FAILED " + instanceId + " " + e.getMessage();
        }
    }

    /**
     * Reads lines from {@code stream}, appends them to {@code logFile}, sends
     * {@code LOG_LINE} to the Gateway, and – when a console session is active –
     * also sends {@code CONSOLE_OUTPUT}.
     */
    private static void pipeProcessLog(java.io.InputStream stream,
                                       String instanceId,
                                       Path logFile,
                                       WorkerSocketClient client,
                                       Set<String> attachedConsoles) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Append to local log file
                try {
                    Files.createDirectories(logFile.getParent());
                    Files.writeString(logFile, line + System.lineSeparator(),
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) {
                }
                // Forward to Gateway as LOG_LINE
                try {
                    client.sendLogLine(instanceId, line);
                } catch (IOException ignored) {
                }
                // Forward to Gateway as CONSOLE_OUTPUT if a peer session is active
                if (attachedConsoles.contains(instanceId)) {
                    try {
                        client.sendConsoleOutput(instanceId, line);
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static String stopInstance(MongoDbDatabaseManager db,
                                       Map<String, ManagedProcess> managedInstances,
                                       String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return "STOP_FAILED missing_instance_id";
        }
        ManagedProcess process = managedInstances.remove(instanceId);
        if (process == null) {
            try {
                db.updateMinecraftInstanceStatus(instanceId, "OFFLINE");
            } catch (Exception ignored) {
            }
            return "STOP_SKIPPED not_running " + instanceId;
        }
        process.stop();
        try {
            db.updateMinecraftInstanceStatus(instanceId, "OFFLINE");
        } catch (Exception ignored) {
        }
        return "STOPPED " + instanceId;
    }

    private static Process startJavaProcess(Path workingDirectory, Path jarPath, String... extraArgs) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(workingDirectory.toFile());
        processBuilder.command(buildJavaCommand(jarPath, extraArgs));
        // Merge stderr into stdout so the LogPiper reads a single stream.
        // Do NOT redirect to file here – the LogPiper thread handles file writing and forwarding.
        processBuilder.redirectErrorStream(true);
        return processBuilder.start();
    }

    private static java.util.List<String> buildJavaCommand(Path jarPath, String... extraArgs) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("java");
        if (extraArgs != null && extraArgs.length > 0) {
            for (String extraArg : extraArgs) {
                if (extraArg != null && !extraArg.isBlank()) {
                    command.add(extraArg);
                }
            }
        }
        command.add("-jar");
        command.add(jarPath.toString());
        return command;
    }

    private static void downloadJarIfMissing(String url, Path target) throws Exception {
        if (Files.exists(target)) {
            return;
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Download fehlgeschlagen (HTTP " + response.statusCode() + ")");
        }
        Files.write(target, response.body(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static void writeVelocityConfigIfMissing(Path instanceDir, Document instance) throws IOException {
        Path velocityToml = instanceDir.resolve("velocity.toml");
        if (Files.exists(velocityToml)) {
            return;
        }
        String lobbyId = readString(instance, "proxyTarget");
        if (lobbyId.isBlank()) {
            lobbyId = "lobby-01";
        }
        String config = """
                bind = "0.0.0.0:25565"
                motd = "CloudNetwork Velocity"
                show-max-players = 100
                online-mode = true
                forwarding-secret-file = "forwarding.secret"

                [servers]
                lobby = "127.0.0.1:25566"

                [forced-hosts]
                "lobby.cloudnetwork.local" = ["lobby"]
                """;
        Files.writeString(velocityToml, config, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Files.writeString(instanceDir.resolve("forwarding.secret"), "cloudnetwork-secret-" + lobbyId + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static void writeLobbyConfigIfMissing(Path instanceDir, int port) throws IOException {
        Files.writeString(instanceDir.resolve("eula.txt"), "eula=true" + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Path serverProperties = instanceDir.resolve("server.properties");
        if (Files.exists(serverProperties)) {
            return;
        }
        String properties = "server-port=" + port + System.lineSeparator()
                + "online-mode=false" + System.lineSeparator()
                + "motd=CloudNetwork Lobby" + System.lineSeparator();
        Files.writeString(serverProperties, properties,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static int inferDefaultPort(String type, String instanceId) {
        if ("VELOCITY".equalsIgnoreCase(type) || instanceId.toLowerCase(Locale.ROOT).contains("velocity")) {
            return 25565;
        }
        return 25566;
    }

    private static String readString(Document document, String key) {
        if (document == null) {
            return "";
        }
        Object value = document.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static int readInt(Document document, String key, int defaultValue) {
        if (document == null) {
            return defaultValue;
        }
        Object value = document.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private static final class ManagedProcess {
        private final Process process;
        private final PrintWriter stdin;

        private ManagedProcess(Process process) {
            this.process = process;
            this.stdin = new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8);
        }

        private boolean isRunning() {
            return process != null && process.isAlive();
        }

        /**
         * Writes a line to the managed process's standard input.
         * Silently ignored if the process is no longer running.
         */
        private void writeStdin(String line) {
            if (process == null || !process.isAlive()) return;
            stdin.println(line);
        }

        private void stop() {
            if (process == null) {
                return;
            }
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private record MetricsSnapshot(double cpu, double ram, int players, int samples) {
    }

    private static final class MetricsAccumulator {
        private double cpuTotal;
        private double ramTotal;
        private int playerTotal;
        private int sampleCount;

        synchronized void addSample(double cpu, double ram, int players) {
            cpuTotal += cpu;
            ramTotal += ram;
            playerTotal += players;
            sampleCount++;
        }

        synchronized MetricsSnapshot drainSnapshot() {
            if (sampleCount <= 0) {
                addSample(SystemMetrics.getCpuUsagePercent(), SystemMetrics.getRamUsagePercent(), 0);
            }
            double averageCpu = cpuTotal / sampleCount;
            double averageRam = ramTotal / sampleCount;
            int averagePlayers = (int) Math.round((double) playerTotal / sampleCount);
            MetricsSnapshot snapshot = new MetricsSnapshot(
                    round(averageCpu),
                    round(averageRam),
                    averagePlayers,
                    sampleCount
            );
            cpuTotal = 0.0D;
            ramTotal = 0.0D;
            playerTotal = 0;
            sampleCount = 0;
            return snapshot;
        }

        private double round(double value) {
            return Math.round(value * 100.0D) / 100.0D;
        }
    }

    // ── Security helpers ──────────────────────────────────────────────────────

    /**
     * Validates that an instance ID contains only safe characters and cannot be
     * used for path traversal or command injection.
     * <p>Allowed: letters, digits, hyphens, underscores, dots (not at start/end,
     * and not consecutive to prevent ".." sequences).</p>
     */
    private static boolean isValidInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) return false;
        if (instanceId.length() > 128) return false;
        // Only allow: a-z A-Z 0-9 - _ .
        if (!instanceId.matches("[a-zA-Z0-9][a-zA-Z0-9_\\-.]*[a-zA-Z0-9]|[a-zA-Z0-9]")) return false;
        // Prevent ".." path traversal sequences
        if (instanceId.contains("..")) return false;
        return true;
    }

    /**
     * Resolves the instance directory and verifies it is strictly inside
     * {@link #INSTANCES_BASE} to prevent path traversal attacks.
     */
    private static Path resolveInstanceDir(String instanceId) throws IOException {
        Path resolved = INSTANCES_BASE.resolve(instanceId).normalize();
        if (!resolved.startsWith(INSTANCES_BASE.normalize())) {
            throw new IOException("Ungültige Instanz-ID (Pfad-Traversal verweigert): " + instanceId);
        }
        return resolved;
    }
}
