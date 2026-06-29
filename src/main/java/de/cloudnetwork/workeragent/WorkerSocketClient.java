package de.cloudnetwork.workeragent;

import de.cloudnetwork.protocol.Message;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public class WorkerSocketClient {
    private final String gatewayHost;
    private final int gatewayPort;
    private final String workerId;
    private final String authToken;
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;

    public WorkerSocketClient(String gatewayHost, int gatewayPort, String workerId, String authToken) {
        this.gatewayHost = gatewayHost;
        this.gatewayPort = gatewayPort;
        this.workerId = workerId;
        this.authToken = authToken;
    }

    public synchronized void connect() throws IOException {
        connectWithRetry();
        sendMessage(Message.register(workerId, authToken));
    }

    public synchronized void sendHeartbeat() throws IOException {
        sendMessage(Message.heartbeat(workerId));
    }

    public synchronized void sendMetrics(double cpu, double ram, int players) throws IOException {
        sendMessage(Message.metrics(workerId, cpu, ram, players));
    }

    public synchronized void sendCommandResult(String result) throws IOException {
        sendMessage(Message.commandResult(workerId, result));
    }

    public synchronized void disconnect() {
        try {
            if (isConnected()) {
                sendMessage(Message.shutdown(workerId));
            }
        } catch (IOException ignored) {
        }
        closeSocket();
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public synchronized Message readMessage() throws IOException {
        if (!isConnected() || reader == null) {
            return null;
        }
        try {
            if (!reader.ready()) {
                return null;
            }
            String line = reader.readLine();
            if (line == null) {
                handleDisconnect();
                return null;
            }
            return Message.fromJson(line);
        } catch (SocketTimeoutException e) {
            return null;
        }
    }

    private void sendMessage(Message message) throws IOException {
        ensureConnected();
        try {
            writer.println(message.toJson());
            writer.flush();
            if (writer.checkError()) {
                throw new IOException("Schreiben auf Gateway-Socket fehlgeschlagen.");
            }
        } catch (IOException e) {
            handleDisconnect();
            ensureConnected();
            writer.println(message.toJson());
            writer.flush();
            if (writer.checkError()) {
                throw new IOException("Nach Reconnect konnte nicht geschrieben werden.", e);
            }
        }
    }

    private void ensureConnected() throws IOException {
        if (!isConnected()) {
            connectWithRetry();
            writer.println(Message.register(workerId, authToken).toJson());
            writer.flush();
        }
    }

    private void connectWithRetry() throws IOException {
        IOException lastException = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                closeSocket();
                socket = new Socket(gatewayHost, gatewayPort);
                socket.setSoTimeout(500);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                System.out.println("[OK] Mit Gateway verbunden: " + gatewayHost + ":" + gatewayPort);
                return;
            } catch (IOException e) {
                lastException = e;
                System.err.println("[FEHLER] Gateway-Verbindung fehlgeschlagen (Versuch " + attempt + "/5): " + e.getMessage());
                if (attempt < 5) {
                    try {
                        Thread.sleep(10_000L);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Reconnect unterbrochen.", interruptedException);
                    }
                }
            }
        }
        throw lastException != null ? lastException : new IOException("Gateway-Verbindung fehlgeschlagen.");
    }

    private void handleDisconnect() {
        closeSocket();
    }

    private void closeSocket() {
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (IOException ignored) {
        }
        if (writer != null) {
            writer.close();
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        reader = null;
        writer = null;
        socket = null;
    }
}
