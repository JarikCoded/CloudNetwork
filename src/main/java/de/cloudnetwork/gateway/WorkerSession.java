package de.cloudnetwork.gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.database.MongoDbDatabaseManager;
import de.cloudnetwork.protocol.Message;
import de.cloudnetwork.protocol.MessageType;
import de.cloudnetwork.worker.WorkerInfo;
import de.cloudnetwork.worker.WorkerRegistry;
import org.bson.Document;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class WorkerSession implements Runnable {
    private final GatewaySocketServer server;
    private final WorkerRegistry registry;
    private final DatabaseManager db;
    private final Socket socket;
    private volatile boolean running = true;
    private BufferedReader reader;
    private PrintWriter writer;
    private String workerId;
    /** True when this session belongs to a ProxyGateway (not a Worker). */
    private boolean isProxyGateway = false;

    public WorkerSession(GatewaySocketServer server, WorkerRegistry registry, DatabaseManager db, Socket socket) {
        this.server = server;
        this.registry = registry;
        this.db = db;
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            String line;
            while (running && (line = reader.readLine()) != null) {
                handleMessage(Message.fromJson(line));
            }
        } catch (Exception e) {
            ConsoleOutput.error("[FEHLER] Worker-Session abgebrochen: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    public synchronized void sendCommand(Message message) {
        if (!running || writer == null) {
            return;
        }
        writer.println(message.toJson());
        writer.flush();
    }

    public void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void handleMessage(Message message) throws Exception {
        if (message == null || message.getType() == null) {
            return;
        }
        switch (message.getType()) {
            case REGISTER -> handleRegister(message);
            case HEARTBEAT -> handleHeartbeat(message);
            case METRICS -> handleMetrics(message);
            case COMMAND_RESULT -> handleCommandResult(message);
            case SHUTDOWN -> handleShutdown(message);
            case COMMAND, PROXY_UPDATE -> {
            }
        }
    }

    private void handleRegister(Message message) throws Exception {
        JsonObject payload = parsePayload(message);
        String role = payload.has("role") ? payload.get("role").getAsString() : "worker";
        if ("proxy_gateway".equals(role)) {
            handleProxyGatewayRegister(message, payload);
        } else {
            handleWorkerRegister(message, payload);
        }
    }

    private void handleWorkerRegister(Message message, JsonObject payload) throws Exception {
        String incomingWorkerId = message.getWorkerId();
        String authToken = payload.has("authToken") ? payload.get("authToken").getAsString() : "";
        WorkerInfo storedWorker = db.getWorker(incomingWorkerId);
        if (storedWorker == null || storedWorker.getAuthToken() == null || !storedWorker.getAuthToken().equals(authToken)) {
            ConsoleOutput.error("[FEHLER] Worker-Authentifizierung fehlgeschlagen für " + incomingWorkerId);
            sendCommand(Message.commandResult(incomingWorkerId, "AUTH_FAILED"));
            close();
            return;
        }

        boolean wasProvisioning = storedWorker.getStatus() == WorkerInfo.WorkerStatus.PROVISIONING;
        storedWorker.setStatus(WorkerInfo.WorkerStatus.ONLINE);
        storedWorker.setLastHeartbeatMs(System.currentTimeMillis());
        if (storedWorker.getIpv4() == null || storedWorker.getIpv4().isBlank()) {
            storedWorker.setIpv4(socket.getInetAddress().getHostAddress());
        }
        registry.register(storedWorker);
        db.saveWorker(storedWorker);
        db.updateWorkerStatus(incomingWorkerId, WorkerInfo.WorkerStatus.ONLINE.name());
        workerId = incomingWorkerId;
        server.bindWorker(workerId, this);
        sendCommand(Message.commandResult(workerId, "ACK"));
        if (wasProvisioning) {
            ConsoleOutput.info("[OK] Worker-Provisioning abgeschlossen – bereit für Minecraft-Server: " + workerId + " (" + storedWorker.getIpv4() + ")");
            triggerInitialBootstrapIfConfigured(workerId);
        } else {
            ConsoleOutput.info("[OK] Worker registriert: " + workerId + " (" + storedWorker.getIpv4() + ")");
        }
    }

    private void handleProxyGatewayRegister(Message message, JsonObject payload) throws Exception {
        String gatewayId = message.getWorkerId();
        String authToken = payload.has("authToken") ? payload.get("authToken").getAsString() : "";
        String storedToken = db.getConfigValue("proxy_gateway_auth_token");
        if (storedToken == null || storedToken.isBlank() || !storedToken.equals(authToken)) {
            ConsoleOutput.error("[FEHLER] ProxyGateway-Authentifizierung fehlgeschlagen: " + gatewayId);
            sendCommand(Message.commandResult(gatewayId, "AUTH_FAILED"));
            close();
            return;
        }
        this.workerId = gatewayId;
        this.isProxyGateway = true;
        server.bindProxyGateway(gatewayId, this);
        sendCommand(Message.commandResult(gatewayId, "ACK"));
        // Send initial proxy list immediately after registration
        sendCommand(Message.proxyUpdate(gatewayId, server.buildCurrentProxyList()));
        ConsoleOutput.info("[OK] ProxyGateway registriert: " + gatewayId + " (" + socket.getInetAddress().getHostAddress() + ")");
    }

    private void handleHeartbeat(Message message) throws Exception {
        if (isProxyGateway) return;
        WorkerInfo worker = registry.get(message.getWorkerId());
        if (worker != null) {
            worker.setLastHeartbeatMs(message.getTimestamp());
        }
        registry.markOnline(message.getWorkerId());
        db.updateWorkerStatus(message.getWorkerId(), WorkerInfo.WorkerStatus.ONLINE.name());
    }

    private void handleMetrics(Message message) throws Exception {
        if (isProxyGateway) return;
        JsonObject payload = parsePayload(message);
        double cpu = payload.has("cpuPercent") ? payload.get("cpuPercent").getAsDouble() : 0.0D;
        double ram = payload.has("ramPercent") ? payload.get("ramPercent").getAsDouble() : 0.0D;
        int players = payload.has("playerCount") ? payload.get("playerCount").getAsInt() : 0;
        registry.updateMetrics(message.getWorkerId(), cpu, ram, players);
        WorkerInfo worker = registry.get(message.getWorkerId());
        if (worker != null) {
            db.saveWorker(worker);
        }
    }

    private void handleShutdown(Message message) throws Exception {
        if (isProxyGateway) return;
        registry.markOffline(message.getWorkerId());
        db.updateWorkerStatus(message.getWorkerId(), WorkerInfo.WorkerStatus.OFFLINE.name());
    }

    private void handleCommandResult(Message message) throws Exception {
        if (isProxyGateway) return;
        JsonObject payload = parsePayload(message);
        String result = payload.has("result") ? payload.get("result").getAsString() : "";
        if (result == null || result.isBlank()) {
            return;
        }

        // Handle SCALING_CHECK results (JSON or text format)
        if (result.startsWith("{")) {
            JsonObject resultJson = JsonParser.parseString(result).getAsJsonObject();
            String type = resultJson.has("type") ? resultJson.get("type").getAsString() : "";
            if ("SCALING_CHECK".equalsIgnoreCase(type)) {
                double cpu = resultJson.has("cpu") ? resultJson.get("cpu").getAsDouble() : 0.0D;
                double ram = resultJson.has("ram") ? resultJson.get("ram").getAsDouble() : 0.0D;
                int players = resultJson.has("players") ? resultJson.get("players").getAsInt() : 0;
                registry.updateMetrics(message.getWorkerId(), cpu, ram, players);
                WorkerInfo worker = registry.get(message.getWorkerId());
                if (worker != null) {
                    db.saveWorker(worker);
                }
                ConsoleOutput.logOnly("[INFO] Skalierungsmetriken empfangen von " + message.getWorkerId()
                        + ": CPU=" + cpu + "% RAM=" + ram + "%");
            }
            return;
        }
        if (result.startsWith("SCALING_CHECK")) {
            double cpu = parseMetric(result, "cpu");
            double ram = parseMetric(result, "ram");
            int players = (int) Math.round(parseMetric(result, "players"));
            registry.updateMetrics(message.getWorkerId(), cpu, ram, players);
            WorkerInfo worker = registry.get(message.getWorkerId());
            if (worker != null) {
                db.saveWorker(worker);
            }
            ConsoleOutput.logOnly("[INFO] Skalierungsmetriken empfangen von " + message.getWorkerId()
                    + ": CPU=" + cpu + "% RAM=" + ram + "%");
            return;
        }

        // Detect STARTED / STOPPED lifecycle events to push live proxy updates
        if (result.startsWith("STARTED ") || result.startsWith("STOPPED ")) {
            String instanceId = result.contains(" ") ? result.substring(result.indexOf(' ') + 1).trim() : "";
            if (!instanceId.isBlank()) {
                tryBroadcastProxyUpdateForInstance(instanceId);
            }
        }
    }

    /**
     * If the given instance is a VELOCITY type, broadcasts an updated proxy list
     * to all connected ProxyGateway sessions.
     */
    private void tryBroadcastProxyUpdateForInstance(String instanceId) {
        try {
            if (!(db instanceof MongoDbDatabaseManager mongoDb)) return;
            Document instance = mongoDb.getMinecraftInstance(instanceId);
            if (instance != null && "VELOCITY".equalsIgnoreCase(String.valueOf(instance.get("type")))) {
                server.broadcastProxyUpdate();
            }
        } catch (Exception e) {
            ConsoleOutput.error("[FEHLER] Proxy-Update nach Instanz-Ereignis fehlgeschlagen: " + e.getMessage());
        }
    }

    private JsonObject parsePayload(Message message) {
        String payload = message.getPayload();
        if (payload == null || payload.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(payload).getAsJsonObject();
    }

    private double parseMetric(String result, String key) {
        String search = key + "=";
        for (String part : result.split("\\s+")) {
            if (part.startsWith(search)) {
                String value = part.substring(search.length()).replace(",", ".").trim();
                try {
                    return Double.parseDouble(value);
                } catch (NumberFormatException ignored) {
                    return 0.0D;
                }
            }
        }
        return 0.0D;
    }

    private void cleanup() {
        running = false;
        if (workerId != null) {
            if (isProxyGateway) {
                server.unbindProxyGateway(workerId, this);
                ConsoleOutput.info("[INFO] ProxyGateway getrennt: " + workerId);
            } else {
                registry.markOffline(workerId);
                try {
                    db.updateWorkerStatus(workerId, WorkerInfo.WorkerStatus.OFFLINE.name());
                } catch (Exception e) {
                    ConsoleOutput.error("[FEHLER] Worker-Status konnte nicht gespeichert werden: " + e.getMessage());
                }
                server.unbindWorker(workerId, this);
            }
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void triggerInitialBootstrapIfConfigured(String incomingWorkerId) {
        try {
            String bootstrapWorkerId = db.getConfigValue("bootstrap_initial_worker_id");
            if (bootstrapWorkerId == null || bootstrapWorkerId.isBlank() || !bootstrapWorkerId.equals(incomingWorkerId)) {
                return;
            }
            if (!(db instanceof MongoDbDatabaseManager mongoDb)) {
                return;
            }
            List<Document> instances = new ArrayList<>();
            for (Document instance : mongoDb.getMinecraftInstances()) {
                if (instance == null) {
                    continue;
                }
                String worker = readWorkerId(instance);
                Object autoStartValue = instance.get("autoStart");
                boolean autoStart = autoStartValue instanceof Boolean bool ? bool
                        : "true".equalsIgnoreCase(String.valueOf(autoStartValue));
                if (incomingWorkerId.equals(worker) && autoStart) {
                    instances.add(instance);
                }
            }
            instances.sort(Comparator
                    .comparingInt((Document doc) -> "VELOCITY".equalsIgnoreCase(String.valueOf(doc.get("type"))) ? 0 : 1)
                    .thenComparing(doc -> String.valueOf(doc.get("_id"))));
            for (Document instance : instances) {
                String instanceId = String.valueOf(instance.get("_id"));
                sendCommand(Message.command(incomingWorkerId, "start " + instanceId));
                ConsoleOutput.info("[INFO] Auto-Start für Instanz gestartet: " + instanceId + " auf " + incomingWorkerId);
            }
            db.setConfigValue("bootstrap_initial_worker_id", "");
        } catch (Exception e) {
            ConsoleOutput.error("[FEHLER] Auto-Start der initialen Instanzen fehlgeschlagen: " + e.getMessage());
        }
    }

    private String readWorkerId(Document instance) {
        Object value = instance.get("workerId");
        if (value == null) value = instance.get("assignedWorkerId");
        if (value == null) value = instance.get("rootserverId");
        return value != null ? String.valueOf(value) : null;
    }
}
