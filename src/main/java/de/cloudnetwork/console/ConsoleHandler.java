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

import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

public class ConsoleHandler {
    private static final SecureRandom RANDOM = new SecureRandom();

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
        System.out.println("[INFO] Tippe 'help' für verfügbare Befehle.");
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                if (!scanner.hasNextLine()) {
                    return true;
                }
                String line = scanner.nextLine().trim();
                if (line.isBlank()) {
                    continue;
                }
                if (handleCommand(line)) {
                    return true;
                }
            }
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
                    System.out.println("[OK] CloudNetwork wird beendet. Bye.");
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
                    System.out.println("[INFO] Unbekannter Befehl. Tippe 'help'.");
                    yield false;
                }
            };
        } catch (Exception e) {
            System.err.println("[FEHLER] Befehl fehlgeschlagen: " + e.getMessage());
            return false;
        }
    }

    private void printHelp() {
        System.out.println("Verfügbare Befehle:");
        System.out.println("  help");
        System.out.println("  stop");
        System.out.println("  worker list");
        System.out.println("  worker create");
        System.out.println("  worker remove <id>");
        System.out.println("  server list");
        System.out.println("  server start <instance-id>");
        System.out.println("  server stop <instance-id>");
        System.out.println("  scale status");
        System.out.println("  scale set <high> <low> <targetMin> <targetMax> <windowMin>");
        System.out.println("  scale reload");
    }

    private void handleWorkerCommand(String[] parts) throws Exception {
        if (parts.length < 2) {
            System.out.println("[INFO] Nutzung: worker <list|create|remove>");
            return;
        }
        switch (parts[1].toLowerCase()) {
            case "list" -> printWorkers();
            case "create" -> createWorker();
            case "remove" -> {
                if (parts.length < 3) {
                    System.out.println("[INFO] Nutzung: worker remove <id>");
                    return;
                }
                removeWorker(parts[2]);
            }
            default -> System.out.println("[INFO] Unbekannter worker-Befehl.");
        }
    }

    private void handleServerCommand(String[] parts) throws Exception {
        if (parts.length < 2) {
            System.out.println("[INFO] Nutzung: server <list|start|stop>");
            return;
        }
        switch (parts[1].toLowerCase()) {
            case "list" -> listMinecraftInstances();
            case "start", "stop" -> {
                if (parts.length < 3) {
                    System.out.println("[INFO] Nutzung: server " + parts[1].toLowerCase() + " <instance-id>");
                    return;
                }
                sendServerCommand(parts[1].toLowerCase(), parts[2]);
            }
            default -> System.out.println("[INFO] Unbekannter server-Befehl.");
        }
    }

    private void handleScaleCommand(String[] parts) {
        if (parts.length < 2) {
            System.out.println("[INFO] Nutzung: scale <status|set|reload>");
            return;
        }
        if (scalingMonitor == null) {
            System.out.println("[INFO] Skalierungsmonitor ist nicht verfügbar.");
            return;
        }
        if ("status".equalsIgnoreCase(parts[1])) {
            double averageLoad = registry.getAverageTotalLoad();
            double smoothedLoad = scalingMonitor.getSmoothedLoad();
            int highLoadCount = scalingMonitor.getConsecutiveHighLoadCount();
            int lowLoadCount = scalingMonitor.getConsecutiveLowLoadCount();
            boolean cooldown = scalingMonitor.isInCooldown();
            long cooldownSeconds = scalingMonitor.getCooldownRemainingSeconds();
            System.out.println("[INFO] Durchschnittslast (aktuell): " + String.format("%.2f", averageLoad) + "%");
            System.out.println("[INFO] Durchschnittslast (geglättet): " + String.format("%.2f", smoothedLoad) + "%");
            System.out.println("[INFO] High-Load-Zähler: " + highLoadCount + "/2 | Trigger > " + String.format("%.2f", scalingMonitor.getHighLoadThreshold()) + "%");
            System.out.println("[INFO] Low-Load-Zähler: " + lowLoadCount + "/2 | Trigger < " + String.format("%.2f", scalingMonitor.getLowLoadThreshold()) + "%");
            System.out.println("[INFO] Zielbereich: " + String.format("%.2f", scalingMonitor.getTargetMin()) + "% - " + String.format("%.2f", scalingMonitor.getTargetMax()) + "%");
            System.out.println("[INFO] Fenster: " + scalingMonitor.getWindowMinutes() + " Minuten");
            System.out.println("[INFO] Cooldown: " + (cooldown ? (cooldownSeconds + "s verbleibend") : "nein"));
            return;
        }
        if ("reload".equalsIgnoreCase(parts[1])) {
            scalingMonitor.reloadSettings();
            System.out.println("[OK] Skalierungs-Einstellungen aus der Datenbank neu geladen.");
            return;
        }
        if ("set".equalsIgnoreCase(parts[1])) {
            if (parts.length < 7) {
                System.out.println("[INFO] Nutzung: scale set <high> <low> <targetMin> <targetMax> <windowMin>");
                return;
            }
            try {
                double high = Double.parseDouble(parts[2]);
                double low = Double.parseDouble(parts[3]);
                double targetMin = Double.parseDouble(parts[4]);
                double targetMax = Double.parseDouble(parts[5]);
                int windowMinutes = Integer.parseInt(parts[6]);
                scalingMonitor.updateSettings(high, low, targetMin, targetMax, windowMinutes);
                System.out.println("[OK] Skalierungs-Einstellungen gespeichert.");
            } catch (NumberFormatException e) {
                System.out.println("[FEHLER] Ungültige Zahlenwerte. Nutzung: scale set <high> <low> <targetMin> <targetMax> <windowMin>");
            } catch (Exception e) {
                System.out.println("[FEHLER] Einstellungen konnten nicht gespeichert werden: " + e.getMessage());
            }
            return;
        }
        System.out.println("[INFO] Nutzung: scale <status|set|reload>");
    }

    private void printWorkers() throws Exception {
        Collection<WorkerInfo> liveWorkers = registry.getAll();
        if (liveWorkers.isEmpty()) {
            liveWorkers = db.getAllWorkers();
        }
        if (liveWorkers.isEmpty()) {
            System.out.println("[INFO] Keine Worker vorhanden.");
            return;
        }
        for (WorkerInfo worker : liveWorkers) {
            long ageSeconds = worker.getLastHeartbeatMs() > 0 ? Duration.ofMillis(System.currentTimeMillis() - worker.getLastHeartbeatMs()).toSeconds() : -1;
            System.out.println(worker.getId()
                    + " | ip=" + safe(worker.getIpv4())
                    + " | status=" + worker.getStatus()
                    + " | cpu=" + String.format("%.2f", worker.getCpuPercent()) + "%"
                    + " | ram=" + String.format("%.2f", worker.getRamPercent()) + "%"
                    + " | players=" + worker.getPlayerCount()
                    + " | lastSeen=" + (ageSeconds >= 0 ? ageSeconds + "s" : "-")
            );
        }
    }

    private void createWorker() throws Exception {
        String workerId = UUID.randomUUID().toString();
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

        HetznerServer server = hetzner.createWorkerServer(workerId, authToken, gatewayHost, gatewayPort);
        String ipv4 = hetzner.waitForServerRunning(server.getId());
        worker.setIpv4(ipv4);
        worker.setHetznerServerId(server.getId());
        db.saveWorker(worker);
        System.out.println("[OK] Worker erstellt: " + workerId + " auf " + ipv4);
    }

    private void removeWorker(String workerId) throws Exception {
        WorkerInfo worker = db.getWorker(workerId);
        if (worker == null) {
            System.out.println("[INFO] Worker nicht gefunden: " + workerId);
            return;
        }
        socketServer.sendCommandToWorker(workerId, Message.shutdown(workerId));
        db.updateWorkerStatus(workerId, WorkerInfo.WorkerStatus.DELETED.name());
        registry.remove(workerId);
        if (worker.getHetznerServerId() > 0) {
            hetzner.deleteServer(worker.getHetznerServerId());
        }
        System.out.println("[OK] Worker entfernt: " + workerId);
    }

    private void listMinecraftInstances() throws Exception {
        if (!(db instanceof MongoDbDatabaseManager mongoDb)) {
            System.out.println("[INFO] server list ist derzeit nur für MongoDB implementiert.");
            return;
        }
        List<Document> instances = mongoDb.getMinecraftInstances();
        if (instances.isEmpty()) {
            System.out.println("[INFO] Keine Minecraft-Instanzen gefunden.");
            return;
        }
        for (Document instance : instances) {
            System.out.println(instance.get("_id")
                    + " | name=" + safe(instance.getString("name"))
                    + " | worker=" + safe(readWorkerId(instance))
                    + " | status=" + safe(instance.getString("status")));
        }
    }

    private void sendServerCommand(String action, String instanceId) throws Exception {
        if (!(db instanceof MongoDbDatabaseManager mongoDb)) {
            System.out.println("[INFO] server-Befehle sind derzeit nur für MongoDB implementiert.");
            return;
        }
        Document instance = mongoDb.getMinecraftInstance(instanceId);
        if (instance == null) {
            System.out.println("[INFO] Instanz nicht gefunden: " + instanceId);
            return;
        }
        String workerId = readWorkerId(instance);
        if (workerId == null || workerId.isBlank()) {
            System.out.println("[INFO] Instanz ist keinem Worker zugewiesen: " + instanceId);
            return;
        }
        boolean sent = socketServer.sendCommandToWorker(workerId, Message.command(workerId, action + " " + instanceId));
        if (sent) {
            System.out.println("[OK] Befehl gesendet an Worker " + workerId + ": " + action + " " + instanceId);
        } else {
            System.out.println("[INFO] Worker ist aktuell nicht verbunden: " + workerId);
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

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
