package de.cloudnetwork.gateway;

import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.protocol.Message;
import de.cloudnetwork.worker.WorkerRegistry;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GatewaySocketServer {
    private final WorkerRegistry registry;
    private final DatabaseManager db;
    private final int port;
    private final Map<String, WorkerSession> sessions = new ConcurrentHashMap<>();
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
    }

    public boolean sendCommandToWorker(String workerId, Message message) {
        WorkerSession session = sessions.get(workerId);
        if (session == null) {
            return false;
        }
        session.sendCommand(message);
        return true;
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
}
