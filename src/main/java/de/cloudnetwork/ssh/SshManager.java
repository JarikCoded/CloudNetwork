package de.cloudnetwork.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Session;
import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.function.Function;

/**
 * SSH/SFTP client for managing worker servers directly from the master.
 *
 * <p>Worker servers no longer run a worker-agent JAR. Instead the master uses this
 * class to execute commands and transfer files over SSH/SFTP.</p>
 *
 * <h2>DB config keys</h2>
 * <ul>
 *   <li>{@code worker_ssh_private_key} – PEM-encoded RSA private key (generated once)</li>
 *   <li>{@code worker_ssh_public_key}  – OpenSSH-format public key, added to worker authorized_keys</li>
 * </ul>
 */
public class SshManager {

    public static final String DB_KEY_PRIVATE = "worker_ssh_private_key";
    public static final String DB_KEY_PUBLIC = "worker_ssh_public_key";

    private static final String SSH_USER = "root";
    private static final int SSH_PORT = 22;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int CHANNEL_TIMEOUT_MS = 30_000;

    /** PEM-encoded RSA private key used for all worker SSH connections. */
    private final byte[] privateKeyPem;

    public SshManager(byte[] privateKeyPem) {
        this.privateKeyPem = privateKeyPem;
    }

    // ── Key management ────────────────────────────────────────────────────────

    /**
     * Returns the public key stored in the DB (OpenSSH format), or {@code null} if none.
     * Use this to embed the key into worker cloud-init scripts.
     */
    public static String getPublicKey(DatabaseManager db) {
        try {
            return db.getConfigValue(DB_KEY_PUBLIC);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Generates an RSA key pair and saves it to the DB if no key exists yet.
     * Safe to call multiple times.
     */
    public static void ensureKeysExist(DatabaseManager db) throws Exception {
        String existing = db.getConfigValue(DB_KEY_PRIVATE);
        if (existing != null && !existing.isBlank()) {
            return;
        }
        ConsoleOutput.info("[SSH] Generiere SSH-Schlüsselpaar für Worker-Zugriff...");
        JSch jsch = new JSch();
        KeyPair kp = KeyPair.genKeyPair(jsch, KeyPair.RSA, 4096);

        ByteArrayOutputStream privateOut = new ByteArrayOutputStream();
        kp.writePrivateKey(privateOut);
        String privateKeyPem = privateOut.toString(StandardCharsets.UTF_8);

        ByteArrayOutputStream publicOut = new ByteArrayOutputStream();
        kp.writePublicKey(publicOut, "cloudnetwork-worker");
        String publicKey = publicOut.toString(StandardCharsets.UTF_8).trim();

        db.setConfigValue(DB_KEY_PRIVATE, privateKeyPem);
        db.setConfigValue(DB_KEY_PUBLIC, publicKey);
        ConsoleOutput.info("[SSH] SSH-Schlüsselpaar generiert und in der Datenbank gespeichert.");
    }

    /** Creates an {@link SshManager} from the private key stored in the DB. */
    public static SshManager fromDb(DatabaseManager db) throws Exception {
        String privateKey = db.getConfigValue(DB_KEY_PRIVATE);
        if (privateKey == null || privateKey.isBlank()) {
            throw new IllegalStateException("Kein SSH-Private-Key in der Datenbank. Führe zuerst ensureKeysExist() aus.");
        }
        return new SshManager(privateKey.getBytes(StandardCharsets.UTF_8));
    }

    // ── SSH command execution ─────────────────────────────────────────────────

    /**
     * Executes a shell command on the remote host and returns combined stdout + stderr.
     * Throws if the SSH connection fails; swallows non-zero exit codes (check the returned output).
     */
    public String executeCommand(String host, String command) throws Exception {
        Session session = openSession(host);
        try {
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);
            channel.setErrStream(System.err, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            channel.setOutputStream(out);
            channel.connect(CHANNEL_TIMEOUT_MS);
            long deadline = System.currentTimeMillis() + 60_000L;
            while (!channel.isClosed() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200L);
            }
            channel.disconnect();
            return out.toString(StandardCharsets.UTF_8);
        } finally {
            session.disconnect();
        }
    }

    // ── SFTP file transfer ────────────────────────────────────────────────────

    /** Uploads {@code data} to {@code remotePath} on the remote host via SFTP. */
    public void uploadBytes(String host, byte[] data, String remotePath) throws Exception {
        Session session = openSession(host);
        try {
            ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(CHANNEL_TIMEOUT_MS);
            try {
                channel.put(new ByteArrayInputStream(data), remotePath);
            } finally {
                channel.disconnect();
            }
        } finally {
            session.disconnect();
        }
    }

    /** Downloads the remote file at {@code remotePath} and returns its content as bytes. */
    public byte[] downloadBytes(String host, String remotePath) throws Exception {
        Session session = openSession(host);
        try {
            ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(CHANNEL_TIMEOUT_MS);
            try {
                InputStream in = channel.get(remotePath);
                return in.readAllBytes();
            } finally {
                channel.disconnect();
            }
        } finally {
            session.disconnect();
        }
    }

    // ── Metrics collection ────────────────────────────────────────────────────

    public record WorkerMetrics(double cpuPercent, double ramPercent, int playerCount) {
    }

    /**
     * Collects CPU and RAM usage from the remote host via SSH.
     * Returns {@code null} if the host is unreachable or the command fails.
     */
    public WorkerMetrics collectMetrics(String host) {
        try {
            // "top -bn1" for CPU, "free -m" for RAM
            String output = executeCommand(host,
                    "CPU=$(top -bn1 | grep 'Cpu(s)' | awk '{print $2}' | tr -d '%us,'); "
                    + "MEM=$(free -m | awk 'NR==2{printf \"%.2f\", $3*100/$2}'); "
                    + "echo \"cpu=$CPU mem=$MEM\"");
            if (output == null || output.isBlank()) {
                return null;
            }
            double cpu = parseMetric(output, "cpu=");
            double ram = parseMetric(output, "mem=");
            return new WorkerMetrics(cpu, ram, 0);
        } catch (Exception e) {
            ConsoleOutput.logOnly("[SSH] Metriken konnten nicht gelesen werden von " + host + ": " + e.getMessage());
            return null;
        }
    }

    // ── Connectivity check ────────────────────────────────────────────────────

    /**
     * Waits up to {@code timeoutMs} milliseconds for SSH to become accessible on the host.
     *
     * @return {@code true} if SSH is reachable before the timeout, {@code false} otherwise
     */
    public boolean waitForSsh(String host, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Session session = openSession(host);
                session.disconnect();
                return true;
            } catch (Exception e) {
                ConsoleOutput.logOnly("[SSH] Warte auf SSH-Verfügbarkeit auf " + host + "...");
                try {
                    Thread.sleep(15_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Streams the log file from the remote host line by line via SSH {@code tail -f}.
     *
     * <p>The {@code lineConsumer} is called for each line. It should return {@code true}
     * to continue streaming, or {@code false} to stop.</p>
     *
     * @param host         remote host IP
     * @param logPath      absolute path to the log file on the remote host
     * @param lineConsumer callback; returns {@code false} to stop, {@code true} to continue
     */
    public void tailLog(String host, String logPath, Function<String, Boolean> lineConsumer) throws Exception {
        Session session = openSession(host);
        try {
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand("tail -n 50 -f " + logPath + " 2>/dev/null");
            channel.setInputStream(null);
            InputStream in = channel.getInputStream();
            channel.connect(CONNECT_TIMEOUT_MS);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) break;
                    Boolean cont = lineConsumer.apply(line);
                    if (Boolean.FALSE.equals(cont)) break;
                }
            } finally {
                channel.disconnect();
            }
        } finally {
            session.disconnect();
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private Session openSession(String host) throws Exception {
        JSch jsch = new JSch();
        jsch.addIdentity("worker-key", privateKeyPem, null, null);
        Session session = jsch.getSession(SSH_USER, host, SSH_PORT);
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);
        session.connect(CONNECT_TIMEOUT_MS);
        return session;
    }

    private static double parseMetric(String output, String prefix) {
        for (String token : output.split("[\\s,]+")) {
            if (token.startsWith(prefix)) {
                try {
                    return Double.parseDouble(token.substring(prefix.length()).replace(",", ".").trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0.0;
    }
}
