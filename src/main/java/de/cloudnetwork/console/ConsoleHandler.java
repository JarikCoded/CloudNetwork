package de.cloudnetwork.console;

import de.cloudnetwork.database.DatabaseManager;
import de.cloudnetwork.database.MongoDbDatabaseManager;
import de.cloudnetwork.gateway.GatewaySocketServer;
import de.cloudnetwork.hetzner.HetznerApiClient;
import de.cloudnetwork.hetzner.HetznerServer;
import de.cloudnetwork.protocol.Message;
import de.cloudnetwork.scaling.ScalingMonitor;
import de.cloudnetwork.worker.WorkerInfo;
import de.cloudnetwork.worker.WorkerRegistry;
import org.bson.Document;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.completer.AggregateCompleter;
import org.jline.reader.impl.completer.NullCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConsoleHandler {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern WORKER_ID_PATTERN = Pattern.compile("(?i)^worker(\\d+)$");

    private final DatabaseManager db;
    private final WorkerRegistry registry;
    private final GatewaySocketServer socketServer;
    private final HetznerApiClient hetzner;
    private ScalingMonitor scalingMonitor;

    public ConsoleHandler(DatabaseManager db, WorkerRegistry registry, GatewaySocketServer socketServer, HetznerApiClient hetzner) {
        this.db = db;
        this.registry = registry;
        this.socketServer = socketServer;
        this.hetzner = hetzner;
    }

    public void setScalingMonitor(ScalingMonitor scalingMonitor) {
        this.scalingMonitor = scalingMonitor;
    }

    public boolean run() {
        ConsoleOutput.info("[INFO] Tippe 'help' für verfügbare Befehle.");
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(buildCompleter())
                    .build();
            reader.setOpt(LineReader.Option.MENU_COMPLETE);
            reader.setOpt(LineReader.Option.AUTO_MENU);
            ConsoleOutput.attachLineReader(reader);
            while (true) {
                String line;
                try {
                    line = reader.readLine("> ").trim();
                } catch (UserInterruptException ignored) {
                    continue;
                } catch (EndOfFileException eof) {
                    return true;
                }
                if (line.isBlank()) {
                    continue;
                }
                if (handleCommand(line)) {
                    return true;
                }
            }
        } catch (Exception e) {
            ConsoleOutput.error("[FEHLER] Konsole konnte nicht gestartet werden: " + e.getMessage());
            return true;
        } finally {
            ConsoleOutput.detachLineReader(null);
        }
    }

    private boolean handleCommand(String line) {
        String[] parts = line.split("\\s+");
        String command = parts[0].toLowerCase();
        try {
            return switch (command) {
                case "help" -> {
                    printHelp();
                    yield false;
                }
                case "stop" -> {
                    socketServer.stop();
                    ConsoleOutput.info("[OK] CloudNetwork wird beendet. Bye.");
                    yield true;
                }
                case "worker" -> {
                    handleWorkerCommand(parts);
                    yield false;
                }
                case "server" -> {
                    handleServerCommand(parts);
                    yield false;
                }
                case "scale" -> {
                    handleScaleCommand(parts);
                    yield false;
                }
                default -> {
                    ConsoleOutput.info("[INFO] Unbekannter Befehl. Tippe 'help'.");
                    yield false;
                }
            };
        } catch (Exception e) {
            ConsoleOutput.error("[FEHLER] Befehl fehlgeschlagen: " + e.getMessage());
            return false;
        }
    }

    private void printHelp() {
        ConsoleOutput.info("Verfügbare Befehle:");
        ConsoleOutput.info("  help");
        ConsoleOutput.info("  stop");
        ConsoleOutput.info("  worker list");
        ConsoleOutput.info("  worker create");
        ConsoleOutput.info("  worker remove <id|*>");
        ConsoleOutput.info("  server list");
        ConsoleOutput.info("  server start <instance-id>");
        ConsoleOutput.info("  server stop <instance-id>");
        ConsoleOutput.info("  scale status");
        ConsoleOutput.info("  scale set <high> <low> <targetMin> <targetMax> <windowMin>");
        ConsoleOutput.info("  scale reload");
    }

    private void handleWorkerCommand(String[] parts) throws Exception {
        if (parts.length < 2) {
            ConsoleOutput.info("[INFO] Nutzung: worker <list|create|remove>");
            return;
        }
        switch (parts[1].toLowerCase()) {
            case "list" -> printWorkers();
            case "create" -> createWorker();
            case "remove" -> {
                if (parts.length < 3) {
                    ConsoleOutput.info("[INFO] Nutzung: worker remove <id|*>");
                    return;
                }
                if ("*".equals(parts[2])) {
                    removeAllWorkers();
                    return;
                }
                removeWorker(parts[2]);
            }
            default -> ConsoleOutput.info("[INFO] Unbekannter worker-Befehl.");
        }
    }

    private void handleServerCommand(String[] parts) throws Exception {
        if (parts.length < 2) {
            ConsoleOutput.info("[INFO] Nutzung: server <list|start|stop>");
            return;
        }
        switch (parts[1].toLowerCase()) {
            case "list" -> listMinecraftInstances();
            case "start", "stop" -> {
                if (parts.length < 3) {
                    ConsoleOutput.info("[INFO] Nutzung: server " + parts[1].toLowerCase() + " <instance-id>");
                    return;
                }
                sendServerCommand(parts[1].toLowerCase(), parts[2]);
            }
            default -> ConsoleOutput.info("[INFO] Unbekannter server-Befehl.");
        }
    }

    private void handleScaleCommand(String[] parts) {
        if (parts.length < 2) {
            ConsoleOutput.info("[INFO] Nutzung: scale <status|set|reload>");
            return;
        }
        if (scalingMonitor == null) {
            ConsoleOutput.info("[INFO] Skalierungsmonitor ist nicht verfügbar.");
            return;
        }
        if ("status".equalsIgnoreCase(parts[1])) {
            double averageLoad = registry.getAverageTotalLoad();
            double smoothedLoad = scalingMonitor.getSmoothedLoad();
            int highLoadCount = scalingMonitor.getConsecutiveHighLoadCount();
            int lowLoadCount = scalingMonitor.getConsecutiveLowLoadCount();
            boolean cooldown = scalingMonitor.isInCooldown();
            long cooldownSeconds = scalingMonitor.getCooldownRemainingSeconds();
            ConsoleOutput.info("[INFO] Durchschnittslast (aktuell): " + String.format("%.2f", averageLoad) + "%");
            ConsoleOutput.info("[INFO] Durchschnittslast (geglättet): " + String.format("%.2f", smoothedLoad) + "%");
            ConsoleOutput.info("[INFO] High-Load-Zähler: " + highLoadCount + "/" + ScalingMonitor.HIGH_LOAD_TRIGGER_COUNT + " | Trigger > " + String.format("%.2f", scalingMonitor.getHighLoadThreshold()) + "%");
            ConsoleOutput.info("[INFO] Low-Load-Zähler: " + lowLoadCount + "/" + ScalingMonitor.LOW_LOAD_TRIGGER_COUNT + " | Trigger < " + String.format("%.2f", scalingMonitor.getLowLoadThreshold()) + "%");
            ConsoleOutput.info("[INFO] Zielbereich: " + String.format("%.2f", scalingMonitor.getTargetMin()) + "% - " + String.format("%.2f", scalingMonitor.getTargetMax()) + "%");
            ConsoleOutput.info("[INFO] Fenster: " + scalingMonitor.getWindowMinutes() + " Minuten");
            ConsoleOutput.info("[INFO] Cooldown: " + (cooldown ? (cooldownSeconds + "s verbleibend") : "nein"));
            return;
        }
        if ("reload".equalsIgnoreCase(parts[1])) {
            scalingMonitor.reloadSettings();
            ConsoleOutput.info("[OK] Skalierungs-Einstellungen aus der Datenbank neu geladen.");
            return;
        }
        if ("set".equalsIgnoreCase(parts[1])) {
            if (parts.length < 7) {
                ConsoleOutput.info("[INFO] Nutzung: scale set <high> <low> <targetMin> <targetMax> <windowMin>");
                return;
            }
            try {
                double high = Double.parseDouble(parts[2]);
                double low = Double.parseDouble(parts[3]);
                double targetMin = Double.parseDouble(parts[4]);
                double targetMax = Double.parseDouble(parts[5]);
                int windowMinutes = Integer.parseInt(parts[6]);
                scalingMonitor.updateSettings(high, low, targetMin, targetMax, windowMinutes);
                ConsoleOutput.info("[OK] Skalierungs-Einstellungen gespeichert.");
            } catch (NumberFormatException e) {
                ConsoleOutput.error("[FEHLER] Ungültige Zahlenwerte. Nutzung: scale set <high> <low> <targetMin> <targetMax> <windowMin>");
            } catch (Exception e) {
                ConsoleOutput.error("[FEHLER] Einstellungen konnten nicht gespeichert werden: " + e.getMessage());
            }
            return;
        }
        ConsoleOutput.info("[INFO] Nutzung: scale <status|set|reload>");
    }

    private void printWorkers() throws Exception {
        Collection<WorkerInfo> liveWorkers = registry.getAll();
        if (liveWorkers.isEmpty()) {
            liveWorkers = db.getAllWorkers();
        }
        if (liveWorkers.isEmpty()) {
            ConsoleOutput.info("[INFO] Keine Worker vorhanden.");
            return;
        }
        for (WorkerInfo worker : liveWorkers) {
            long ageSeconds = worker.getLastHeartbeatMs() > 0 ? Duration.ofMillis(System.currentTimeMillis() - worker.getLastHeartbeatMs()).toSeconds() : -1;
            String socketStatus = socketServer.isWorkerConnected(worker.getId()) ? "verbunden" : "nicht verbunden";
            ConsoleOutput.info(displayWorkerName(worker)
                    + " | ip=" + safe(worker.getIpv4())
                    + " | status=" + worker.getStatus()
                    + " | cpu=" + String.format("%.2f", worker.getCpuPercent()) + "%"
                    + " | ram=" + String.format("%.2f", worker.getRamPercent()) + "%"
                    + " | players=" + worker.getPlayerCount()
                    + " | lastSeen=" + (ageSeconds >= 0 ? ageSeconds + "s" : "-")
                    + " | socket=" + socketStatus
            );
        }
    }

    private void createWorker() throws Exception {
        WorkerIdentity workerIdentity = nextWorkerIdentity();
        String workerId = workerIdentity.workerId();
        String workerName = workerIdentity.serverName();
        String authToken = generateHexToken(24);
        String gatewayHost = db.getConfigValue("gateway_host");
        if (gatewayHost == null || gatewayHost.isBlank()) {
            gatewayHost = detectGatewayIp();
            db.setConfigValue("gateway_host", gatewayHost);
        }
        int gatewayPort = parsePort(db.getConfigValue("gateway_port"), socketServer.getPort());
        db.setConfigValue("gateway_port", String.valueOf(gatewayPort));

        WorkerInfo worker = new WorkerInfo();
        worker.setId(workerId);
        worker.setAuthToken(authToken);
        worker.setStatus(WorkerInfo.WorkerStatus.PROVISIONING);
        worker.setLastHeartbeatMs(System.currentTimeMillis());
        registry.register(worker);
        db.saveWorker(worker);

        HetznerServer server = hetzner.createWorkerServer(workerName, workerId, authToken, gatewayHost, gatewayPort);
        String ipv4 = hetzner.waitForServerRunning(server.getId());
        worker.setIpv4(ipv4);
        worker.setHetznerServerId(server.getId());
        db.saveWorker(worker);
        ConsoleOutput.info("[OK] Worker erstellt: " + workerName + " (" + workerId + ") auf " + ipv4);
    }

    private void removeWorker(String workerId) throws Exception {
        WorkerInfo worker = db.getWorker(workerId);
        if (worker == null) {
            ConsoleOutput.info("[INFO] Worker nicht gefunden: " + workerId);
            return;
        }
        socketServer.sendCommandToWorker(workerId, Message.shutdown(workerId));
        db.updateWorkerStatus(workerId, WorkerInfo.WorkerStatus.DELETED.name());
        registry.remove(workerId);
        if (worker.getHetznerServerId() > 0) {
            hetzner.deleteServer(worker.getHetznerServerId());
        }
        ConsoleOutput.info("[OK] Worker entfernt: " + workerId);
    }

    private void removeAllWorkers() throws Exception {
        Collection<WorkerInfo> workers = db.getAllWorkers();
        if (workers.isEmpty()) {
            ConsoleOutput.info("[INFO] Keine Worker zum Entfernen vorhanden.");
            return;
        }
        int removed = 0;
        for (WorkerInfo worker : workers) {
            if (worker == null || worker.getId() == null || worker.getId().isBlank()) {
                continue;
            }
            try {
                removeWorker(worker.getId());
                removed++;
            } catch (Exception e) {
                ConsoleOutput.error("[FEHLER] Worker konnte nicht entfernt werden (" + worker.getId() + "): " + e.getMessage());
            }
        }
        ConsoleOutput.info("[OK] Worker remove * abgeschlossen. Entfernt: " + removed);
    }

    private void listMinecraftInstances() throws Exception {
        if (!(db instanceof MongoDbDatabaseManager mongoDb)) {
            ConsoleOutput.info("[INFO] server list ist derzeit nur für MongoDB implementiert.");
            return;
        }
        List<Document> instances = mongoDb.getMinecraftInstances();
        if (instances.isEmpty()) {
            ConsoleOutput.info("[INFO] Keine Minecraft-Instanzen gefunden.");
            return;
        }
        for (Document instance : instances) {
            ConsoleOutput.info(instance.get("_id")
                    + " | name=" + safe(instance.getString("name"))
                    + " | worker=" + safe(readWorkerId(instance))
                    + " | status=" + safe(instance.getString("status")));
        }
    }

    private void sendServerCommand(String action, String instanceId) throws Exception {
        if (!(db instanceof MongoDbDatabaseManager mongoDb)) {
            ConsoleOutput.info("[INFO] server-Befehle sind derzeit nur für MongoDB implementiert.");
            return;
        }
        Document instance = mongoDb.getMinecraftInstance(instanceId);
        if (instance == null) {
            ConsoleOutput.info("[INFO] Instanz nicht gefunden: " + instanceId);
            return;
        }
        String workerId = readWorkerId(instance);
        if (workerId == null || workerId.isBlank()) {
            ConsoleOutput.info("[INFO] Instanz ist keinem Worker zugewiesen: " + instanceId);
            return;
        }
        boolean sent = socketServer.sendCommandToWorker(workerId, Message.command(workerId, action + " " + instanceId));
        if (sent) {
            ConsoleOutput.info("[OK] Befehl gesendet an Worker " + workerId + ": " + action + " " + instanceId);
        } else {
            ConsoleOutput.info("[INFO] Worker ist aktuell nicht verbunden: " + workerId);
        }
    }

    private Completer buildCompleter() {
        return new AggregateCompleter(
                new StringsCompleter("help", "stop"),
                new ArgumentCompleter(
                        new StringsCompleter("worker"),
                        new StringsCompleter("list", "create", "remove"),
                        new WorkerRemoveTargetCompleter(),
                        NullCompleter.INSTANCE
                ),
                new ArgumentCompleter(
                        new StringsCompleter("server"),
                        new StringsCompleter("list", "start", "stop"),
                        NullCompleter.INSTANCE
                ),
                new ArgumentCompleter(
                        new StringsCompleter("scale"),
                        new StringsCompleter("status", "set", "reload"),
                        NullCompleter.INSTANCE
                )
        );
    }

    private final class WorkerRemoveTargetCompleter implements Completer {
        @Override
        public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
            candidates.add(new Candidate("*"));
            for (WorkerInfo worker : registry.getAll()) {
                if (worker != null && worker.getId() != null && !worker.getId().isBlank()) {
                    candidates.add(new Candidate(worker.getId()));
                }
            }
            try {
                for (WorkerInfo worker : db.getAllWorkers()) {
                    if (worker != null && worker.getId() != null && !worker.getId().isBlank()) {
                        candidates.add(new Candidate(worker.getId()));
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private String readWorkerId(Document instance) {
        Object value = instance.get("workerId");
        if (value == null) value = instance.get("assignedWorkerId");
        if (value == null) value = instance.get("rootserverId");
        return value != null ? String.valueOf(value) : null;
    }

    private int parsePort(String value, int defaultValue) {
        try {
            return value == null ? defaultValue : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String detectGatewayIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private String generateHexToken(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        StringBuilder builder = new StringBuilder(bytes * 2);
        for (byte value : data) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private WorkerIdentity nextWorkerIdentity() throws Exception {
        synchronized (db) {
            String currentValue = db.getConfigValue(ScalingMonitor.CONFIG_WORKER_NAME_COUNTER);
            int current;
            try {
                current = currentValue == null || currentValue.isBlank() ? 0 : Integer.parseInt(currentValue.trim());
            } catch (NumberFormatException ignored) {
                current = 0;
            }
            int next = current + 1;
            db.setConfigValue(ScalingMonitor.CONFIG_WORKER_NAME_COUNTER, String.valueOf(next));
            return new WorkerIdentity(String.format("Worker%02d", next), String.format("CloudNetwork-Worker-%02d", next));
        }
    }

    private String displayWorkerName(WorkerInfo worker) {
        if (worker == null || worker.getId() == null) {
            return "-";
        }
        Matcher matcher = WORKER_ID_PATTERN.matcher(worker.getId().trim());
        if (!matcher.matches()) {
            return worker.getId().trim();
        }
        int number = Integer.parseInt(matcher.group(1));
        return String.format("Worker%02d", number);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private record WorkerIdentity(String workerId, String serverName) {
    }
}
