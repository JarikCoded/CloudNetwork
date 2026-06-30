package de.cloudnetwork.workeragent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.cloudnetwork.config.CloudConfig;
import de.cloudnetwork.config.ConfigManager;
import de.cloudnetwork.database.MongoDbDatabaseManager;
import de.cloudnetwork.protocol.Message;
import de.cloudnetwork.protocol.MessageType;
import org.bson.Document;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class WorkerMain {
    private static final String DEFAULT_VELOCITY_URL = "https://api.papermc.io/v2/projects/velocity/versions/3.4.0/builds/474/downloads/velocity-3.4.0-474.jar";
    private static final String DEFAULT_LOBBY_URL = "https://api.papermc.io/v2/projects/paper/versions/1.20.6/builds/151/downloads/paper-1.20.6-151.jar";

    public static void main(String[] args) {
        MongoDbDatabaseManager db = new MongoDbDatabaseManager();
        WorkerSocketClient client = null;
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        AtomicBoolean running = new AtomicBoolean(true);
        Map<String, ManagedProcess> managedInstances = new ConcurrentHashMap<>();

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

            client = new WorkerSocketClient(gatewayHost, gatewayPort, workerId, authToken);
            client.connect();

            WorkerSocketClient finalClient = client;
            MetricsAccumulator metricsAccumulator = new MetricsAccumulator();
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    finalClient.sendHeartbeat();
                } catch (IOException e) {
                    System.err.println("[FEHLER] Heartbeat/Metriken konnten nicht gesendet werden: " + e.getMessage());
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
                        handleIncomingMessage(finalClient, message, running, metricsAccumulator, db, managedInstances);
                    }
                } catch (IOException e) {
                    System.err.println("[FEHLER] Gateway-Nachricht konnte nicht gelesen werden: " + e.getMessage());
                }
            }, 1, 1, TimeUnit.SECONDS);

            System.out.println("[INFO] Worker läuft. Tippe 'stop' zum Beenden.");
            try (Scanner scanner = new Scanner(System.in)) {
                while (running.get() && scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim();
                    if ("stop".equalsIgnoreCase(line)) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[FEHLER] Worker konnte nicht gestartet werden: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
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
                                              Map<String, ManagedProcess> managedInstances) throws IOException {
        if (message.getType() == MessageType.COMMAND) {
            JsonObject payload = JsonParser.parseString(message.getPayload()).getAsJsonObject();
            String command = payload.has("command") ? payload.get("command").getAsString() : "";
            System.out.println("[INFO] Befehl vom Gateway: " + command);
            if ("SCALING_CHECK".equalsIgnoreCase(command.trim())) {
                MetricsSnapshot snapshot = metricsAccumulator.drainSnapshot();
                JsonObject resultPayload = new JsonObject();
                resultPayload.addProperty("type", "SCALING_CHECK");
                resultPayload.addProperty("cpu", snapshot.cpu());
                resultPayload.addProperty("ram", snapshot.ram());
                resultPayload.addProperty("players", snapshot.players());
                resultPayload.addProperty("samples", snapshot.samples());
                client.sendCommandResult(resultPayload.toString());
            } else if (command.toLowerCase(Locale.ROOT).startsWith("start ")) {
                String instanceId = command.substring("start ".length()).trim();
                String result = startInstance(db, managedInstances, instanceId);
                client.sendCommandResult(result);
            } else if (command.toLowerCase(Locale.ROOT).startsWith("stop ")) {
                String instanceId = command.substring("stop ".length()).trim();
                String result = stopInstance(db, managedInstances, instanceId);
                client.sendCommandResult(result);
            } else {
                client.sendCommandResult("ACK " + command);
            }
        } else if (message.getType() == MessageType.SHUTDOWN) {
            System.out.println("[INFO] Shutdown vom Gateway empfangen.");
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

    private static String startInstance(MongoDbDatabaseManager db,
                                        Map<String, ManagedProcess> managedInstances,
                                        String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return "START_FAILED missing_instance_id";
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
            Path instanceDir = Path.of("/opt/cloudnetwork/instances", instanceId);
            Files.createDirectories(instanceDir);
            String type = readString(instance, "type");
            int port = readInt(instance, "port", inferDefaultPort(type, instanceId));
            String downloadUrl = readString(instance, "downloadUrl");

            ManagedProcess managedProcess;
            if ("VELOCITY".equalsIgnoreCase(type) || instanceId.toLowerCase(Locale.ROOT).contains("velocity")) {
                Path jarPath = instanceDir.resolve("velocity.jar");
                downloadJarIfMissing(downloadUrl.isBlank() ? DEFAULT_VELOCITY_URL : downloadUrl, jarPath);
                writeVelocityConfigIfMissing(instanceDir, instance);
                managedProcess = new ManagedProcess(startJavaProcess(instanceDir, jarPath, "-Xms256M", "-Xmx512M"));
            } else {
                Path jarPath = instanceDir.resolve("lobby.jar");
                downloadJarIfMissing(downloadUrl.isBlank() ? DEFAULT_LOBBY_URL : downloadUrl, jarPath);
                writeLobbyConfigIfMissing(instanceDir, port);
                managedProcess = new ManagedProcess(startJavaProcess(instanceDir, jarPath, "-Xms512M", "-Xmx1024M", "nogui"));
            }
            managedInstances.put(instanceId, managedProcess);
            db.updateMinecraftInstanceStatus(instanceId, "ONLINE");
            return "STARTED " + instanceId;
        } catch (Exception e) {
            return "START_FAILED " + instanceId + " " + e.getMessage();
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
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(workingDirectory.resolve("latest.log").toFile()));
        processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(workingDirectory.resolve("latest.log").toFile()));
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

        private ManagedProcess(Process process) {
            this.process = process;
        }

        private boolean isRunning() {
            return process != null && process.isAlive();
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
}
