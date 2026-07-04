package de.cloudnetwork.proxygw;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.protocol.Message;
import de.cloudnetwork.protocol.MessageType;
import de.cloudnetwork.protocol.ProxyEndpoint;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
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
    /** Optional TLS context.  When set the connection to the Gateway uses mTLS. */
    private final SSLContext sslContext;
    private volatile boolean running = true;

    public ProxyGatewayClient(String gatewayHost, int gatewayPort,
                               String gatewayId, String authToken,
                               ProxyGatewayServer proxyServer) {
        this(gatewayHost, gatewayPort, gatewayId, authToken, proxyServer, null);
    }

    public ProxyGatewayClient(String gatewayHost, int gatewayPort,
                               String gatewayId, String authToken,
                               ProxyGatewayServer proxyServer,
                               SSLContext sslContext) {
        this.gatewayHost = gatewayHost;
        this.gatewayPort = gatewayPort;
        this.gatewayId = gatewayId;
        this.authToken = authToken;
        this.proxyServer = proxyServer;
        this.sslContext = sslContext;
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
        Socket rawSocket;
        if (sslContext != null) {
            SSLSocket sslSocket = (SSLSocket) sslContext.getSocketFactory()
                    .createSocket(gatewayHost, gatewayPort);
            sslSocket.startHandshake();
            rawSocket = sslSocket;
        } else {
            rawSocket = new Socket(gatewayHost, gatewayPort);
        }
        try (Socket socket = rawSocket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {

            // Forward ProxyGateway's own log output to the Main Gateway as LOG_LINE messages.
            ConsoleOutput.setLogListener(entry -> {
                Message logMsg = Message.logLine(gatewayId, "proxy-gateway", entry);
                writer.println(logMsg.toJson());
            });

            // Register as proxy_gateway
            writer.println(Message.proxyRegister(gatewayId, authToken).toJson());
            ConsoleOutput.info("[OK] ProxyGateway-Registrierung gesendet als " + gatewayId);

            String line;
            while (running && (line = reader.readLine()) != null) {
                Message msg = Message.fromJson(line);
                if (msg != null) {
                    handleMessage(msg);
                }
            }
        } finally {
            ConsoleOutput.setLogListener(null);
        }
    }

    private void handleMessage(Message msg) {
        if (msg.getType() == null) return;

        switch (msg.getType()) {
            case PROXY_UPDATE -> {
                List<ProxyEndpoint> endpoints = parseProxyList(msg.getPayload());
                proxyServer.updateProxyList(endpoints);
                ConsoleOutput.info("[INFO] PROXY_UPDATE empfangen: " + endpoints.size() + " Proxy(s)");
            }
            default -> { /* REGISTER, LOG_LINE, CONSOLE_OUTPUT are not expected from the Gateway */ }
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
