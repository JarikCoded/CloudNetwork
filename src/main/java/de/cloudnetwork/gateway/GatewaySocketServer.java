package de.cloudnetwork.gateway;

import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.database.MongoDbDatabaseManager;
import de.cloudnetwork.protocol.Message;
import de.cloudnetwork.protocol.ProxyEndpoint;
import de.cloudnetwork.worker.WorkerInfo;
import de.cloudnetwork.worker.WorkerRegistry;
import org.bson.Document;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GatewaySocketServer {
    private final WorkerRegistry registry;
    private final DatabaseManager db;
    private final int port;
    private final Map<String, WorkerSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, WorkerSession> proxyGatewaySessions = new ConcurrentHashMap<>();
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public GatewaySocketServer(WorkerRegistry registry, DatabaseManager db) {
        this(registry, db, 9876);
    }

    public GatewaySocketServer(WorkerRegistry registry, DatabaseManager db, int port) {
        this.registry = registry;
        this.db = db;
        this.port = port;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        serverSocket = new ServerSocket(port);
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
        for (WorkerSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        for (WorkerSession session : proxyGatewaySessions.values()) {
            session.close();
        }
        proxyGatewaySessions.clear();
    }

    public boolean sendCommandToWorker(String workerId, Message message) {
        WorkerSession session = sessions.get(workerId);
        if (session == null) {
            return false;
        }
        session.sendCommand(message);
        return true;
    }

    /**
     * Builds the current list of reachable Velocity proxy endpoints by joining
     * ONLINE VELOCITY instances with their worker's IPv4 address.
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

    public boolean isWorkerConnected(String workerId) {
        return workerId != null && sessions.containsKey(workerId);
    }

    void bindWorker(String workerId, WorkerSession session) {
        if (workerId != null && session != null) {
            sessions.put(workerId, session);
        }
    }

    void unbindWorker(String workerId, WorkerSession session) {
        if (workerId != null && session != null) {
            sessions.remove(workerId, session);
        }
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

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                WorkerSession session = new WorkerSession(this, registry, db, socket);
                Thread sessionThread = new Thread(session, "worker-session-" + socket.getRemoteSocketAddress());
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

