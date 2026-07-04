package de.cloudnetwork.ssh;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import de.cloudnetwork.console.ConsoleOutput;
import de.cloudnetwork.database.DatabaseManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
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
    private static final int COMMAND_TIMEOUT_MS = 60_000;
    private static final int SSH_PROBE_TIMEOUT_MS = 10_000;

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
        String existingPrivateKey = trimToNull(db.getConfigValue(DB_KEY_PRIVATE));
        String existingPublicKey = trimToNull(db.getConfigValue(DB_KEY_PUBLIC));

        if (existingPrivateKey != null) {
            if (existingPublicKey == null) {
                ConsoleOutput.info("[SSH] Öffentlicher SSH-Schlüssel fehlt. Leite ihn aus dem vorhandenen Private-Key ab...");
                db.setConfigValue(DB_KEY_PUBLIC, derivePublicKey(existingPrivateKey));
                ConsoleOutput.info("[SSH] Öffentlicher SSH-Schlüssel wurde aus dem vorhandenen Private-Key gespeichert.");
            }
            return;
        }

        if (existingPublicKey != null) {
            throw new IllegalStateException("Öffentlicher SSH-Schlüssel vorhanden, aber Private-Key fehlt. Bitte DB-Einträge prüfen.");
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
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            channel.setOutputStream(stdout, true);
            channel.setErrStream(stderr, true);
            channel.connect(CHANNEL_TIMEOUT_MS);
            waitForChannelToClose(channel, host, COMMAND_TIMEOUT_MS, "Befehl");
            String out = stdout.toString(StandardCharsets.UTF_8);
            String err = stderr.toString(StandardCharsets.UTF_8);
            if (err.isBlank()) {
                return out;
            }
            if (out.isBlank()) {
                return err;
            }
            return out.endsWith("\n") ? out + err : out + System.lineSeparator() + err;
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
                ensureRemoteDirectory(channel, remotePath);
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
                try (InputStream in = channel.get(remotePath)) {
                    return in.readAllBytes();
                }
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
            String output = executeCommand(host,
                    "LC_ALL=C; "
                    + "read cpu user nice system idle iowait irq softirq steal guest guest_nice < /proc/stat; "
                    + "prev_idle=$((idle + iowait)); "
                    + "prev_total=$((user + nice + system + idle + iowait + irq + softirq + steal)); "
                    + "sleep 0.2; "
                    + "read cpu user nice system idle iowait irq softirq steal guest guest_nice < /proc/stat; "
                    + "idle_now=$((idle + iowait)); "
                    + "total_now=$((user + nice + system + idle + iowait + irq + softirq + steal)); "
                    + "total_diff=$((total_now - prev_total)); "
                    + "idle_diff=$((idle_now - prev_idle)); "
                    + "CPU=$(awk -v total=\"$total_diff\" -v idle=\"$idle_diff\" 'BEGIN { if (total <= 0) print \"0.00\"; else printf \"%.2f\", (total - idle) * 100 / total }'); "
                    + "MEM=$(awk '/MemTotal:/ {t=$2} /MemAvailable:/ {a=$2} END { if (t <= 0) print \"0.00\"; else printf \"%.2f\", (t - a) * 100 / t }' /proc/meminfo); "
                    + "echo \"cpu=$CPU mem=$MEM players=0\"");
            if (output == null || output.isBlank()) {
                return null;
            }
            double cpu = parseMetric(output, "cpu=");
            double ram = parseMetric(output, "mem=");
            int players = (int) Math.round(parseMetric(output, "players="));
            return new WorkerMetrics(cpu, ram, players);
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
                if (canExecuteNoop(host)) {
                    return true;
                }
            } catch (Exception e) {
                ConsoleOutput.logOnly("[SSH] Warte auf SSH-Verfügbarkeit auf " + host + "...");
            }
            try {
                Thread.sleep(15_000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
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
        if (lineConsumer == null) {
            throw new IllegalArgumentException("lineConsumer darf nicht null sein.");
        }
        Session session = openSession(host);
        try {
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand("tail -n 50 --follow=name --retry " + shellEscape(logPath) + " 2>/dev/null");
            channel.setInputStream(null);
            InputStream in = channel.getInputStream();
            channel.connect(CHANNEL_TIMEOUT_MS);
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

    /**
     * Escapes a string for safe inclusion inside single-quoted shell arguments.
     * Single quotes in the value are replaced with the sequence {@code '\''}.
     */
    public static String shellEscape(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private Session openSession(String host) throws Exception {
        JSch jsch = new JSch();
        jsch.addIdentity("worker-key", privateKeyPem, null, null);
        Session session = jsch.getSession(SSH_USER, host, SSH_PORT);
        Properties config = new Properties();
        // StrictHostKeyChecking is disabled because worker servers are freshly provisioned
        // Hetzner VMs whose host keys are not known in advance. All communication already
        // happens over the Hetzner private network; if known_hosts tracking is desired in
        // the future, it should be implemented in ensureKeysExist() after first connection.
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "publickey");
        session.setConfig(config);
        session.connect(CONNECT_TIMEOUT_MS);
        session.setTimeout(CHANNEL_TIMEOUT_MS);
        return session;
    }

    private static String derivePublicKey(String privateKeyPem) throws Exception {
        JSch jsch = new JSch();
        KeyPair keyPair = KeyPair.load(jsch, privateKeyPem.getBytes(StandardCharsets.UTF_8), null);
        try {
            ByteArrayOutputStream publicOut = new ByteArrayOutputStream();
            keyPair.writePublicKey(publicOut, "cloudnetwork-worker");
            return publicOut.toString(StandardCharsets.UTF_8).trim();
        } finally {
            keyPair.dispose();
        }
    }

    private boolean canExecuteNoop(String host) throws Exception {
        Session session = openSession(host);
        try {
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand("true");
            channel.setInputStream(null);
            channel.connect(CHANNEL_TIMEOUT_MS);
            waitForChannelToClose(channel, host, SSH_PROBE_TIMEOUT_MS, "SSH-Check");
            return channel.getExitStatus() == 0;
        } finally {
            session.disconnect();
        }
    }

    private static void waitForChannelToClose(ChannelExec channel, String host, long timeoutMs, String action) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!channel.isClosed()) {
            if (System.currentTimeMillis() >= deadline) {
                channel.disconnect();
                throw new IOException("[SSH] " + action + " auf " + host + " hat das Timeout von " + timeoutMs + " ms überschritten.");
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.disconnect();
                throw new IOException("[SSH] " + action + " auf " + host + " wurde unterbrochen.", e);
            }
        }
        channel.disconnect();
    }

    private static void ensureRemoteDirectory(ChannelSftp channel, String remotePath) throws SftpException {
        String parent = parentPath(remotePath);
        if (parent == null || parent.isBlank() || "/".equals(parent)) {
            return;
        }

        StringBuilder current = new StringBuilder(parent.startsWith("/") ? "/" : "");
        String normalizedParent = parent.startsWith("/") ? parent.substring(1) : parent;
        for (String segment : normalizedParent.split("/")) {
            if (segment.isBlank()) continue;
            int length = current.length();
            if (length > 0 && current.charAt(length - 1) != '/') {
                current.append('/');
            }
            current.append(segment);
            String directory = current.toString();
            try {
                channel.stat(directory);
            } catch (SftpException e) {
                if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    throw e;
                }
                channel.mkdir(directory);
            }
        }
    }

    private static String parentPath(String remotePath) {
        if (remotePath == null || remotePath.isBlank()) {
            return null;
        }
        int idx = remotePath.lastIndexOf('/');
        if (idx <= 0) {
            return idx == 0 ? "/" : null;
        }
        return remotePath.substring(0, idx);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
