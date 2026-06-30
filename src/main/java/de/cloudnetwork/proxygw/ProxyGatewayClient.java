package de.cloudnetwork.proxygw;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.protocol.Message;
import de.cloudnetwork.protocol.MessageType;
import de.cloudnetwork.protocol.ProxyEndpoint;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Socket client that connects to the Main Gateway, registers the ProxyGateway,
 * and listens for {@link MessageType#PROXY_UPDATE} messages.  On every update
 * the new proxy list is forwarded to the {@link ProxyGatewayServer}.
 *
 * <p>The client automatically reconnects with a 10-second delay on disconnect.</p>
 */
public class ProxyGatewayClient implements Runnable {

    private final String gatewayHost;
    private final int gatewayPort;
    private final String gatewayId;
    private final String authToken;
    private final ProxyGatewayServer proxyServer;
    private volatile boolean running = true;

    public ProxyGatewayClient(String gatewayHost, int gatewayPort,
                               String gatewayId, String authToken,
                               ProxyGatewayServer proxyServer) {
        this.gatewayHost = gatewayHost;
        this.gatewayPort = gatewayPort;
        this.gatewayId = gatewayId;
        this.authToken = authToken;
        this.proxyServer = proxyServer;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        while (running) {
            try {
                connectAndProcess();
            } catch (IOException e) {
                if (running) {
                    ConsoleOutput.error("[FEHLER] Verbindung zum Gateway verloren: " + e.getMessage()
                            + " – Neuer Versuch in 10 Sekunden");
                }
            }
            if (running) {
                try {
                    Thread.sleep(10_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void connectAndProcess() throws IOException {
        ConsoleOutput.info("[INFO] Verbinde mit Gateway " + gatewayHost + ":" + gatewayPort + " ...");
        try (Socket socket = new Socket(gatewayHost, gatewayPort);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {

            // Register as proxy_gateway
            writer.println(Message.proxyRegister(gatewayId, authToken).toJson());
            ConsoleOutput.info("[OK] ProxyGateway-Registrierung gesendet als " + gatewayId);

            String line;
            while (running && (line = reader.readLine()) != null) {
                Message msg = Message.fromJson(line);
                if (msg != null) {
                    handleMessage(msg, writer);
                }
            }
        }
    }

    private void handleMessage(Message msg, PrintWriter writer) {
        if (msg.getType() == null) return;

        switch (msg.getType()) {
            case PROXY_UPDATE -> {
                List<ProxyEndpoint> endpoints = parseProxyList(msg.getPayload());
                proxyServer.updateProxyList(endpoints);
                ConsoleOutput.info("[INFO] PROXY_UPDATE empfangen: " + endpoints.size() + " Proxy(s)");
            }
            case COMMAND_RESULT -> {
                JsonObject payload = JsonParser.parseString(
                        msg.getPayload() != null ? msg.getPayload() : "{}").getAsJsonObject();
                String result = payload.has("result") ? payload.get("result").getAsString() : "";
                if ("AUTH_FAILED".equals(result)) {
                    ConsoleOutput.error("[FEHLER] ProxyGateway-Authentifizierung fehlgeschlagen! Prüfe den Auth-Token in der DB.");
                    running = false;
                } else {
                    ConsoleOutput.info("[OK] Gateway-Antwort: " + result);
                }
            }
            case COMMAND -> {
                // Gateway can send commands (e.g. reload) – acknowledge
                JsonObject payload = JsonParser.parseString(
                        msg.getPayload() != null ? msg.getPayload() : "{}").getAsJsonObject();
                String command = payload.has("command") ? payload.get("command").getAsString() : "";
                ConsoleOutput.info("[INFO] Gateway-Befehl empfangen: " + command);
                writer.println(Message.commandResult(gatewayId, "ACK " + command).toJson());
            }
            default -> { /* ignore */ }
        }
    }

    private static List<ProxyEndpoint> parseProxyList(String payload) {
        List<ProxyEndpoint> endpoints = new ArrayList<>();
        try {
            JsonObject obj = JsonParser.parseString(payload != null ? payload : "{}").getAsJsonObject();
            JsonArray arr = obj.has("proxies") ? obj.getAsJsonArray("proxies") : new JsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject p = el.getAsJsonObject();
                String id = p.has("id") ? p.get("id").getAsString() : "unknown";
                String host = p.has("host") ? p.get("host").getAsString() : "";
                int port = p.has("port") ? p.get("port").getAsInt() : 25565;
                if (!host.isBlank()) {
                    endpoints.add(new ProxyEndpoint(id, host, port));
                }
            }
        } catch (Exception e) {
            ConsoleOutput.error("[FEHLER] Proxy-Liste konnte nicht geparst werden: " + e.getMessage());
        }
        return endpoints;
    }
}
