package de.cloudnetwork.gateway;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.protocol.Message;
import de.cloudnetwork.worker.WorkerRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Handles a single socket connection to the Gateway.
 *
 * <p>Worker servers no longer connect here – only ProxyGateway instances do.
 * The session therefore handles only REGISTER (with role=proxy_gateway) and LOG_LINE messages.</p>
 */
public class WorkerSession implements Runnable {
    private final GatewaySocketServer server;
    private final WorkerRegistry registry;
    private final DatabaseManager db;
    private final Socket socket;
    private volatile boolean running = true;
    private BufferedReader reader;
    private PrintWriter writer;
    private String sessionId;
    /** True once this session has been authenticated as a ProxyGateway. */
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
            ConsoleOutput.error("[FEHLER] Gateway-Session abgebrochen: " + e.getMessage());
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
            case LOG_LINE -> handleLogLine(message);
            case CONSOLE_OUTPUT -> handleConsoleOutput(message);
            default -> {
                // All other message types are not expected from ProxyGateways
            }
        }
    }

    private void handleRegister(Message message) throws Exception {
        JsonObject payload = parsePayload(message);
        String role = payload.has("role") ? payload.get("role").getAsString().trim() : "";
        if (!"proxy_gateway".equals(role)) {
            ConsoleOutput.error("[FEHLER] Unbekannte Rolle bei REGISTER: " + role + " – Verbindung wird getrennt.");
            close();
            return;
        }
        String gatewayId = message.getWorkerId() != null ? message.getWorkerId().trim() : "";
        if (gatewayId.isBlank()) {
            ConsoleOutput.error("[FEHLER] REGISTER ohne gültige gatewayId – Verbindung wird getrennt.");
            close();
            return;
        }
        String authToken = payload.has("authToken") ? payload.get("authToken").getAsString() : "";
        String storedToken = db.getConfigValue("proxy_gateway_auth_token");
        if (storedToken == null || storedToken.isBlank() || !storedToken.equals(authToken)) {
            ConsoleOutput.error("[FEHLER] ProxyGateway-Authentifizierung fehlgeschlagen: " + gatewayId);
            close();
            return;
        }
        this.sessionId = gatewayId;
        this.isProxyGateway = true;
        server.bindProxyGateway(gatewayId, this);
        // Send PROXY_UPDATE immediately after successful registration (no intermediate ACK step).
        sendCommand(Message.proxyUpdate("gateway", server.buildCurrentProxyList()));
        ConsoleOutput.info("[OK] ProxyGateway registriert: " + gatewayId + " (" + socket.getInetAddress().getHostAddress() + ")");
    }

    /**
     * Handles a LOG_LINE message from a ProxyGateway.
     * Writes the line to {@code logs/proxygateways/{gatewayId}.log}.
     */
    private void handleLogLine(Message message) {
        if (!isProxyGateway) return;
        JsonObject payload = parsePayload(message);
        String line = payload.has("line") ? payload.get("line").getAsString() : "";
        if (line.isBlank()) return;

        String senderId = message.getWorkerId() != null ? message.getWorkerId() : "unknown";
        Path logDir = Path.of("logs", "proxygateways");
        String baseName = sanitizeLogName(senderId);
        if (baseName.isBlank()) return;
        Path logFile = logDir.resolve(baseName + ".log");
        try {
            Path canonicalDir = logDir.toAbsolutePath().normalize();
            Path canonicalFile = logFile.toAbsolutePath().normalize();
            if (!canonicalFile.startsWith(canonicalDir)) return;
            Files.createDirectories(logDir);
            Files.writeString(logFile, line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private void handleConsoleOutput(Message message) {
        if (!isProxyGateway) return;
        JsonObject payload = parsePayload(message);
        String instanceId = payload.has("instanceId") ? payload.get("instanceId").getAsString() : "";
        String line = payload.has("line") ? payload.get("line").getAsString() : "";
        if (!instanceId.isBlank()) {
            server.deliverConsoleOutput(instanceId, line);
        }
    }

    private JsonObject parsePayload(Message message) {
        String payload = message.getPayload();
        if (payload == null || payload.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(payload).getAsJsonObject();
    }

    private static String sanitizeLogName(String name) {
        if (name == null) return "";
        String cleaned = name.replaceAll("[^a-zA-Z0-9\\-_]", "_");
        int start = 0;
        int end = cleaned.length();
        while (start < end && isTrimChar(cleaned.charAt(start))) start++;
        while (end > start && isTrimChar(cleaned.charAt(end - 1))) end--;
        cleaned = cleaned.substring(start, end);
        return cleaned.length() > 128 ? cleaned.substring(0, 128) : cleaned;
    }

    private static boolean isTrimChar(char value) {
        return value == '_' || value == '-';
    }

    private void cleanup() {
        running = false;
        if (sessionId != null && isProxyGateway) {
            server.unbindProxyGateway(sessionId, this);
            ConsoleOutput.info("[INFO] ProxyGateway getrennt: " + sessionId);
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
