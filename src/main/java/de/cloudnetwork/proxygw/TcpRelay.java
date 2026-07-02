package de.cloudnetwork.proxygw;

import de.cloudnetwork.console.ConsoleOutput;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Bidirectional byte-stream relay between a Minecraft client socket and a
 * Velocity proxy socket.  Both directions are forwarded in separate daemon
 * threads; when either direction closes the other is closed too.
 */
public class TcpRelay implements Runnable {

    private final Socket clientSocket;
    private final Socket proxySocket;

    public TcpRelay(Socket clientSocket, Socket proxySocket) {
        this.clientSocket = clientSocket;
        this.proxySocket = proxySocket;
    }

    @Override
    public void run() {
        Thread forward = new Thread(() -> pipe(clientSocket, proxySocket), "relay-fwd");
        Thread backward = new Thread(() -> pipe(proxySocket, clientSocket), "relay-bwd");
        forward.setDaemon(true);
        backward.setDaemon(true);
        forward.start();
        backward.start();
        try {
            forward.join();
            backward.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(clientSocket);
            closeQuietly(proxySocket);
        }
    }

    private static void pipe(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {
            // Normal on connection close
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            if (s != null && !s.isClosed()) s.close();
        } catch (IOException ignored) {
        }
    }
}
