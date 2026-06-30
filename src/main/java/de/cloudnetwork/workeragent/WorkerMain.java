package de.cloudnetwork.workeragent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.cloudnetwork.config.CloudConfig;
import de.cloudnetwork.config.ConfigManager;
import de.cloudnetwork.database.MongoDbDatabaseManager;
import de.cloudnetwork.protocol.Message;
import de.cloudnetwork.protocol.MessageType;

import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class WorkerMain {
    public static void main(String[] args) {
        MongoDbDatabaseManager db = new MongoDbDatabaseManager();
        WorkerSocketClient client = null;
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        AtomicBoolean running = new AtomicBoolean(true);

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
                        handleIncomingMessage(finalClient, message, running, metricsAccumulator);
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
            if (client != null) {
                client.disconnect();
            }
            db.close();
        }
    }

    private static void handleIncomingMessage(WorkerSocketClient client,
                                              Message message,
                                              AtomicBoolean running,
                                              MetricsAccumulator metricsAccumulator) throws IOException {
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
