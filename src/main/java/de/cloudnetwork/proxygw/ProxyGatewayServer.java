package de.cloudnetwork.proxygw;

import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.protocol.ProxyEndpoint;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Listens for incoming Minecraft client connections on the configured port and
 * transparently forwards each connection to one of the registered Velocity
 * proxy servers using a round-robin selection strategy.
 */
public class ProxyGatewayServer {

    private final int port;
    private final CopyOnWriteArrayList<ProxyEndpoint> proxyList = new CopyOnWriteArrayList<>();
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);
    private ServerSocket serverSocket;
    private volatile boolean running;
    private Thread acceptThread;

    public ProxyGatewayServer(int port) {
        this.port = port;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(port);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "proxy-gateway-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        ConsoleOutput.info("[OK] ProxyGateway-Server läuft auf Port " + port);
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Replaces the current proxy list with the given entries.
     * Thread-safe – called by {@link ProxyGatewayClient} when it receives a
     * {@code PROXY_UPDATE} message.
     */
    public void updateProxyList(List<ProxyEndpoint> proxies) {
        proxyList.clear();
        proxyList.addAll(proxies);
        ConsoleOutput.info("[INFO] Proxy-Liste aktualisiert: " + proxies.size() + " Proxy(s) verfügbar");
    }

    /** Returns a snapshot of the current proxy list. */
    public List<ProxyEndpoint> getProxyList() {
        return List.copyOf(proxyList);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                ProxyEndpoint proxy = selectProxy();
                if (proxy == null) {
                    ConsoleOutput.error("[FEHLER] Kein Proxy verfügbar – Verbindung abgelehnt: "
                            + clientSocket.getRemoteSocketAddress());
                    clientSocket.close();
                    continue;
                }
                try {
                    Socket proxySocket = new Socket(proxy.host(), proxy.port());
                    TcpRelay relay = new TcpRelay(clientSocket, proxySocket);
                    Thread relayThread = new Thread(relay,
                            "relay-" + clientSocket.getRemoteSocketAddress() + "->" + proxy.host() + ":" + proxy.port());
                    relayThread.setDaemon(true);
                    relayThread.start();
                } catch (IOException e) {
                    ConsoleOutput.error("[FEHLER] Verbindung zu Proxy " + proxy.host() + ":" + proxy.port()
                            + " fehlgeschlagen: " + e.getMessage());
                    clientSocket.close();
                }
            } catch (IOException e) {
                if (running) {
                    ConsoleOutput.error("[FEHLER] Fehler im Accept-Loop: " + e.getMessage());
                }
            }
        }
    }

    /** Round-robin proxy selection; returns {@code null} when no proxies are registered. */
    private ProxyEndpoint selectProxy() {
        List<ProxyEndpoint> snapshot = List.copyOf(proxyList);
        if (snapshot.isEmpty()) return null;
        int index = Math.floorMod(roundRobinIndex.getAndIncrement(), snapshot.size());
        return snapshot.get(index);
    }
}
