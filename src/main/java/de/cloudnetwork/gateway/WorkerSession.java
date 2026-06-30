package de.cloudnetwork.gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.protocol.Message;
import de.cloudnetwork.protocol.MessageType;
import de.cloudnetwork.worker.WorkerInfo;
import de.cloudnetwork.worker.WorkerRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class WorkerSession implements Runnable {
    private final GatewaySocketServer server;
    private final WorkerRegistry registry;
    private final DatabaseManager db;
    private final Socket socket;
    private volatile boolean running = true;
    private BufferedReader reader;
    private PrintWriter writer;
    private String workerId;

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
            case COMMAND -> {
            }
        }
    }

    private void handleRegister(Message message) throws Exception {
        JsonObject payload = parsePayload(message);
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
            ConsoleOutput.info("[OK] Worker-Provisioning abgeschlossen – Datenbank & Gateway verbunden, bereit für Minecraft-Server: " + workerId + " (" + storedWorker.getIpv4() + ")");
        } else {
            ConsoleOutput.info("[OK] Worker registriert: " + workerId + " (" + storedWorker.getIpv4() + ")");
        }
    }

    private void handleHeartbeat(Message message) throws Exception {
        WorkerInfo worker = registry.get(message.getWorkerId());
        if (worker != null) {
            worker.setLastHeartbeatMs(message.getTimestamp());
        }
        registry.markOnline(message.getWorkerId());
        db.updateWorkerStatus(message.getWorkerId(), WorkerInfo.WorkerStatus.ONLINE.name());
    }

    private void handleMetrics(Message message) throws Exception {
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
        registry.markOffline(message.getWorkerId());
        db.updateWorkerStatus(message.getWorkerId(), WorkerInfo.WorkerStatus.OFFLINE.name());
    }

    private void handleCommandResult(Message message) throws Exception {
        JsonObject payload = parsePayload(message);
        String result = payload.has("result") ? payload.get("result").getAsString() : "";
        if (result == null || !result.startsWith("SCALING_CHECK")) {
            return;
        }
        double cpu = parseMetric(result, "cpu");
        double ram = parseMetric(result, "ram");
        int players = (int) Math.round(parseMetric(result, "players"));
        registry.updateMetrics(message.getWorkerId(), cpu, ram, players);
        WorkerInfo worker = registry.get(message.getWorkerId());
        if (worker != null) {
            db.saveWorker(worker);
        }
        ConsoleOutput.logOnly("[INFO] Skalierungsmetriken empfangen von " + message.getWorkerId() + ": CPU=" + cpu + "% RAM=" + ram + "%");
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
            registry.markOffline(workerId);
            try {
                db.updateWorkerStatus(workerId, WorkerInfo.WorkerStatus.OFFLINE.name());
            } catch (Exception e) {
                ConsoleOutput.error("[FEHLER] Worker-Status konnte nicht gespeichert werden: " + e.getMessage());
            }
            server.unbindWorker(workerId, this);
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
