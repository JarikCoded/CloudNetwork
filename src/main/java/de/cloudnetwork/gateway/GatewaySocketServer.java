package de.cloudnetwork.gateway;

import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.database.MongoDbDatabaseManager;
import de.cloudnetwork.protocol.Message;
import de.cloudnetwork.protocol.ProxyEndpoint;
import de.cloudnetwork.worker.WorkerInfo;
import de.cloudnetwork.worker.WorkerRegistry;
import org.bson.Document;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Gateway socket server.
 *
 * <p>Only ProxyGateway instances connect here. Worker servers are managed directly
 * by the master via SSH/SFTP and no longer maintain a persistent socket connection.</p>
 */
public class GatewaySocketServer {
    private final WorkerRegistry registry;
    private final DatabaseManager db;
    private final int port;
    /** Optional TLS context. When set the gateway uses mTLS (server + client auth). */
    private final SSLContext sslContext;
    private final Map<String, WorkerSession> proxyGatewaySessions = new ConcurrentHashMap<>();
    /**
     * Active peer-console registrations: maps instance ID → output consumer.
     */
    private final Map<String, Consumer<String>> consolePeers = new ConcurrentHashMap<>();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public GatewaySocketServer(WorkerRegistry registry, DatabaseManager db) {
        this(registry, db, 9876, null);
    }

    public GatewaySocketServer(WorkerRegistry registry, DatabaseManager db, int port) {
        this(registry, db, port, null);
    }

    public GatewaySocketServer(WorkerRegistry registry, DatabaseManager db, int port, SSLContext sslContext) {
        this.registry = registry;
        this.db = db;
        this.port = port;
        this.sslContext = sslContext;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        if (sslContext != null) {
            SSLServerSocket sslServerSocket =
                    (SSLServerSocket) sslContext.getServerSocketFactory().createServerSocket(port);
            sslServerSocket.setNeedClientAuth(true);
            serverSocket = sslServerSocket;
            ConsoleOutput.info("[TLS] mTLS aktiv – Client-Authentifizierung erforderlich.");
        } else {
            serverSocket = new ServerSocket(port);
        }
        running = true;
        acceptThread = new Thread(this::acceptLoop, "gateway-socket-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        ConsoleOutput.info("[OK] Gateway-Socketserver läuft auf Port " + port);
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            ConsoleOutput.error("[FEHLER] Socketserver konnte nicht gestoppt werden: " + e.getMessage());
        }
        for (WorkerSession session : proxyGatewaySessions.values()) {
            session.close();
        }
        proxyGatewaySessions.clear();
    }

    /**
     * Builds the current list of reachable Velocity proxy endpoints.
     */
    public List<ProxyEndpoint> buildCurrentProxyList() {
        if (!(db instanceof MongoDbDatabaseManager mongoDb)) {
            return List.of();
        }
        try {
            List<Document> instances = mongoDb.getOnlineVelocityInstances();
            List<ProxyEndpoint> proxies = new ArrayList<>();
            for (Document inst : instances) {
                String instanceId = inst.getString("_id");
                Object portObj = inst.get("port");
                int proxyPort = portObj instanceof Number n ? n.intValue() : 25565;
                String workerId = readWorkerId(inst);
                if (workerId == null) continue;
                WorkerInfo worker = registry.get(workerId);
                if (worker != null && worker.getIpv4() != null && !worker.getIpv4().isBlank()) {
                    proxies.add(new ProxyEndpoint(instanceId, worker.getIpv4(), proxyPort));
                }
            }
            return proxies;
        } catch (Exception e) {
            ConsoleOutput.error("[FEHLER] Proxy-Liste konnte nicht abgerufen werden: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Sends an up-to-date {@code PROXY_UPDATE} message to every connected ProxyGateway.
     */
    public void broadcastProxyUpdate() {
        if (proxyGatewaySessions.isEmpty()) {
            return;
        }
        List<ProxyEndpoint> proxies = buildCurrentProxyList();
        Message msg = Message.proxyUpdate("gateway", proxies);
        for (Map.Entry<String, WorkerSession> entry : proxyGatewaySessions.entrySet()) {
            entry.getValue().sendCommand(msg);
        }
        ConsoleOutput.info("[INFO] Proxy-Update an " + proxyGatewaySessions.size() + " ProxyGateway(s) gesendet: " + proxies.size() + " Proxy(s)");
    }

    public int getPort() {
        return port;
    }

    void bindProxyGateway(String gatewayId, WorkerSession session) {
        if (gatewayId != null && session != null) {
            proxyGatewaySessions.put(gatewayId, session);
        }
    }

    void unbindProxyGateway(String gatewayId, WorkerSession session) {
        if (gatewayId != null && session != null) {
            proxyGatewaySessions.remove(gatewayId, session);
        }
    }

    // ── Console peer API ──────────────────────────────────────────────────────

    public void registerConsolePeer(String sourceId, Consumer<String> consumer) {
        if (sourceId != null && consumer != null) {
            consolePeers.put(sourceId, consumer);
        }
    }

    public void unregisterConsolePeer(String sourceId) {
        if (sourceId != null) {
            consolePeers.remove(sourceId);
        }
    }

    public void deliverConsoleOutput(String sourceId, String line) {
        Consumer<String> consumer = consolePeers.get(sourceId);
        if (consumer != null) {
            try {
                consumer.accept(line);
            } catch (Exception ignored) {
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                WorkerSession session = new WorkerSession(this, registry, db, socket);
                Thread sessionThread = new Thread(session, "gateway-session-" + socket.getRemoteSocketAddress());
                sessionThread.setDaemon(true);
                sessionThread.start();
            } catch (IOException e) {
                if (running) {
                    ConsoleOutput.error("[FEHLER] Fehler im Accept-Loop: " + e.getMessage());
                }
            }
        }
    }

    private String readWorkerId(Document instance) {
        for (String key : new String[]{"assignedWorkerId", "workerId", "rootserverId"}) {
            Object v = instance.get(key);
            if (v != null) return String.valueOf(v);
        }
        return null;
    }
}
