package de.cloudnetwork.hetzner;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.cloudnetwork.config.CloudConfig;
import de.cloudnetwork.config.ConfigManager;
import de.cloudnetwork.console.ConsoleOutput;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Thin wrapper around the Hetzner Cloud REST API v1.
 */
public class HetznerApiClient {
    private static final String BASE_URL = "https://api.hetzner.cloud/v1";
    private static final String DEFAULT_SERVER_TYPE = "cx23";
    private static final String DEFAULT_WORKER_SERVER_TYPE = "cx23";
    private static final String DEFAULT_LOCATION = "nbg1";
    private static final List<String> PREFERRED_LOCATIONS = List.of("nbg1", "fsn1", "hel1");
    private static final List<String> PREFERRED_SERVER_TYPES = List.of(
            "cx23", "cax11", "cx33", "cax21", "cx43", "cax31", "cx54", "cax41",
            "cpx22", "cpx32", "cpx42", "cpx52", "cpx62",
            "ccx13", "ccx23", "ccx33"
    );
    private static final List<String> PREFERRED_WORKER_SERVER_TYPES = List.of(
            "cx23", "cax11", "cx33", "cax21", "cx43", "cax31", "cx54", "cax41",
            "cpx22", "cpx32", "cpx42", "cpx52", "cpx62",
            "ccx13", "ccx23", "ccx33"
    );

    private final String apiKey;
    private final HttpClient httpClient;

    public record WireGuardBootstrap(
            String cidr,
            String serverAddressCidr,
            String clientAddressCidr,
            String serverPrivateKey,
            String serverPublicKey,
            String clientPrivateKey,
            String clientPublicKey,
            int listenPort
    ) {
    }

    public record NetworkInfo(long id, String name, String ipRange) {
    }

    public record ServerProvisioningOptions(Long networkId,
                                            boolean enablePublicIpv4,
                                            boolean enablePublicIpv6) {
        public static ServerProvisioningOptions defaultForCurrentEnvironment(boolean workerNode) {
            return new ServerProvisioningOptions(
                    readLongEnv("CLOUDNETWORK_HETZNER_NETWORK_ID", "CN_HETZNER_NETWORK_ID"),
                    !workerNode || !readBooleanEnv("CLOUDNETWORK_WORKER_PRIVATE_ONLY", "CN_WORKER_PRIVATE_ONLY"),
                    !workerNode || !readBooleanEnv("CLOUDNETWORK_WORKER_PRIVATE_ONLY", "CN_WORKER_PRIVATE_ONLY")
            );
        }
    }

    public HetznerApiClient(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public boolean validateApiKey() {
        try {
            HttpResponse<String> response = get("/server_types?per_page=1");
            return response.statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    public NetworkInfo findNetwork(long networkId) throws IOException, InterruptedException {
        HttpResponse<String> response = get("/networks/" + networkId);
        if (response.statusCode() != 200) {
            throw new IOException("Netzwerk " + networkId + " konnte nicht geladen werden (HTTP "
                    + response.statusCode() + "): " + response.body());
        }
        JsonObject network = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("network");
        return new NetworkInfo(
                network.get("id").getAsLong(),
                readString(network, "name"),
                readString(network, "ip_range")
        );
    }

    public NetworkInfo createNetwork(String name, String ipRange) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("ip_range", ipRange);
        JsonArray subnets = new JsonArray();
        JsonObject subnet = new JsonObject();
        subnet.addProperty("type", "cloud");
        subnet.addProperty("network_zone", "eu-central");
        subnets.add(subnet);
        body.add("subnets", subnets);

        HttpResponse<String> response = post("/networks", body.toString());
        if (response.statusCode() != 201) {
            throw new IOException("Hetzner-Netzwerk konnte nicht erstellt werden (HTTP "
                    + response.statusCode() + "): " + response.body());
        }
        JsonObject network = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("network");
        return new NetworkInfo(
                network.get("id").getAsLong(),
                readString(network, "name"),
                readString(network, "ip_range")
        );
    }

    public HetznerServer createServer(String serverName,
                                      String dbUser,
                                      String dbPassword,
                                      String dbName)
            throws IOException, InterruptedException {
        return createServer(serverName, dbUser, dbPassword, dbName, null,
                ServerProvisioningOptions.defaultForCurrentEnvironment(false));
    }

    public HetznerServer createServer(String serverName,
                                      String dbUser,
                                      String dbPassword,
                                      String dbName,
                                      WireGuardBootstrap wireGuardBootstrap)
            throws IOException, InterruptedException {
        return createServer(serverName, dbUser, dbPassword, dbName, wireGuardBootstrap,
                ServerProvisioningOptions.defaultForCurrentEnvironment(false));
    }

    public HetznerServer createServer(String serverName,
                                      String dbUser,
                                      String dbPassword,
                                      String dbName,
                                      WireGuardBootstrap wireGuardBootstrap,
                                      ServerProvisioningOptions provisioningOptions)
            throws IOException, InterruptedException {
        List<WorkerProvisioningPlan> plans = listProvisioningPlans(PREFERRED_SERVER_TYPES, DEFAULT_SERVER_TYPE);
        String cloudInitScript = buildDatabaseCloudInitScript(dbUser, dbPassword, dbName, wireGuardBootstrap);

        IOException lastError = null;
        for (WorkerProvisioningPlan plan : plans) {
            JsonObject body = new JsonObject();
            body.addProperty("name", serverName);
            body.addProperty("server_type", plan.serverType());
            body.addProperty("image", "ubuntu-24.04");
            body.addProperty("location", plan.location());
            body.addProperty("user_data", cloudInitScript);
            body.addProperty("start_after_create", true);
            applyNetworkConfiguration(body, provisioningOptions);

            HttpResponse<String> response = post("/servers", body.toString());
            if (response.statusCode() == 201) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonObject serverJson = json.getAsJsonObject("server");
                long serverId = serverJson.get("id").getAsLong();
                String ipv4 = extractIpv4(serverJson);
                String privateIpv4 = extractPrivateIpv4(serverJson, provisioningOptions.networkId());
                return new HetznerServer(serverId, ipv4, privateIpv4);
            }
            if (response.statusCode() == 412 || response.statusCode() == 422
                    || response.statusCode() == 409 || response.statusCode() == 404) {
                lastError = new IOException("Server konnte nicht erstellt werden (Typ " + plan.serverType() + ", Standort " + plan.location() + ", HTTP " + response.statusCode() + "): " + response.body());
                ConsoleOutput.info("  Servertyp " + plan.serverType() + " in " + plan.location() + " nicht verfügbar, versuche nächste Option...");
                continue;
            }
            throw new IOException("Server konnte nicht erstellt werden (Typ " + plan.serverType() + ", Standort " + plan.location() + ", HTTP " + response.statusCode() + "): " + response.body());
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IOException("Server konnte nicht erstellt werden: kein gültiger Servertyp/Standort gefunden.");
    }

    public HetznerServer createWorkerServer(String serverName,
                                            String workerId,
                                            String authToken,
                                            String gatewayIp,
                                            int gatewayPort)
            throws IOException, InterruptedException {
        return createWorkerServer(serverName, workerId, authToken, gatewayIp, gatewayPort,
                null, null, null, null,
                ServerProvisioningOptions.defaultForCurrentEnvironment(true));
    }

    /**
     * Creates a worker server, optionally pre-mounting a Hetzner Storage Box via CIFS
     * and automatically downloading the worker JAR from a URL.
     *
     * @param storageBoxHost CIFS hostname (u123456.your-storagebox.de), or {@code null} to skip mounting
     * @param storageBoxUser CIFS username
     * @param storageBoxPass CIFS password
     * @param workerJarUrl   public URL to download {@code worker.jar} from, or {@code null}
     */
    public HetznerServer createWorkerServer(String serverName,
                                            String workerId,
                                            String authToken,
                                            String gatewayIp,
                                            int gatewayPort,
                                            String storageBoxHost,
                                            String storageBoxUser,
                                            String storageBoxPass,
                                            String workerJarUrl)
            throws IOException, InterruptedException {
        return createWorkerServer(serverName, workerId, authToken, gatewayIp, gatewayPort,
                storageBoxHost, storageBoxUser, storageBoxPass, workerJarUrl,
                ServerProvisioningOptions.defaultForCurrentEnvironment(true));
    }

    public HetznerServer createWorkerServer(String serverName,
                                            String workerId,
                                            String authToken,
                                            String gatewayIp,
                                            int gatewayPort,
                                            String storageBoxHost,
                                            String storageBoxUser,
                                            String storageBoxPass,
                                            String workerJarUrl,
                                            ServerProvisioningOptions provisioningOptions)
            throws IOException, InterruptedException {
        List<WorkerProvisioningPlan> plans = listProvisioningPlans(PREFERRED_WORKER_SERVER_TYPES, DEFAULT_WORKER_SERVER_TYPE);
        IOException lastError = null;
        String script = buildWorkerCloudInitScript(workerId, authToken, gatewayIp, gatewayPort,
                storageBoxHost, storageBoxUser, storageBoxPass, workerJarUrl);
        for (WorkerProvisioningPlan plan : plans) {
            JsonObject body = new JsonObject();
            body.addProperty("name", serverName);
            body.addProperty("server_type", plan.serverType());
            body.addProperty("image", "ubuntu-24.04");
            body.addProperty("location", plan.location());
            body.addProperty("user_data", script);
            body.addProperty("start_after_create", true);
            applyNetworkConfiguration(body, provisioningOptions);

            HttpResponse<String> response = post("/servers", body.toString());
            if (response.statusCode() == 201) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonObject serverJson = json.getAsJsonObject("server");
                long serverId = serverJson.get("id").getAsLong();
                String ipv4 = extractIpv4(serverJson);
                String privateIpv4 = extractPrivateIpv4(serverJson, provisioningOptions.networkId());
                return new HetznerServer(serverId, ipv4, privateIpv4);
            }
            if (response.statusCode() == 412 || response.statusCode() == 422
                    || response.statusCode() == 409 || response.statusCode() == 404) {
                lastError = new IOException("Worker-Server konnte nicht erstellt werden (Typ " + plan.serverType() + ", Standort " + plan.location() + ", HTTP " + response.statusCode() + "): " + response.body());
                continue;
            }
            throw new IOException("Worker-Server konnte nicht erstellt werden (Typ " + plan.serverType() + ", Standort " + plan.location() + ", HTTP " + response.statusCode() + "): " + response.body());
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IOException("Worker-Server konnte nicht erstellt werden: kein gültiger Servertyp/Standort gefunden.");
    }

    public String waitForServerRunning(long serverId) throws IOException, InterruptedException {
        return waitForServerDetails(serverId, null).getIpv4();
    }

    public HetznerServer waitForServerDetails(long serverId, Long networkId) throws IOException, InterruptedException {
        ConsoleOutput.info("Warte auf Server-Start (kann einige Minuten dauern)...");
        long deadline = System.currentTimeMillis() + 10 * 60_000L;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = get("/servers/" + serverId);
            int statusCode = response.statusCode();
            if (statusCode == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonObject server = json.getAsJsonObject("server");
                String status = server.get("status").getAsString();
                if ("running".equals(status)) {
                    return new HetznerServer(
                            serverId,
                            extractIpv4(server),
                            extractPrivateIpv4(server, networkId)
                    );
                }
            } else if (statusCode == 404) {
                // Server endpoint can transiently return 404 shortly after create.
            } else if (statusCode == 429) {
                System.out.println("Hetzner API Status beim Polling: HTTP " + statusCode);
                Thread.sleep(30_000L);
            } else if (statusCode >= 400 && statusCode < 500) {
                System.out.println("Hetzner API Status beim Polling: HTTP " + statusCode);
                throw new IOException("Server-Statusabfrage fehlgeschlagen (HTTP " + statusCode + "): " + response.body());
            } else if (statusCode < 200 || statusCode >= 300) {
                System.out.println("Hetzner API Status beim Polling: HTTP " + statusCode);
            }
            ConsoleOutput.info("  Status: wird gestartet...");
            Thread.sleep(15_000L);
        }
        throw new IOException("Timeout: Server ist nach 10 Minuten noch nicht bereit.");
    }

    public void deleteServer(long serverId) throws IOException, InterruptedException {
        HttpResponse<String> response = delete("/servers/" + serverId);
        if (response.statusCode() != 204 && response.statusCode() != 200) {
            throw new IOException("Server konnte nicht gelöscht werden (HTTP " + response.statusCode() + "): " + response.body());
        }
    }

    public boolean uploadFile(String ip, String localPath, String remotePath, String sshKeyPath) {
        ConsoleOutput.info("[INFO] SCP not yet configured: " + localPath + " -> root@" + ip + ":" + remotePath);
        return false;
    }

    private String buildDatabaseCloudInitScript(String dbUser,
                                                String dbPassword,
                                                String dbName,
                                                WireGuardBootstrap wireGuardBootstrap) {
        boolean wireGuardEnabled = wireGuardBootstrap != null;
        String detectedLocalIp = detectPublicIp();
        String extraMongoRule = (!wireGuardEnabled && !detectedLocalIp.isBlank())
                ? "ufw allow from " + detectedLocalIp + " to any port 27017 proto tcp\n"
                : "";
        String extraWebRule = (!wireGuardEnabled && !detectedLocalIp.isBlank())
                ? "ufw allow from " + detectedLocalIp + " to any port 8081 proto tcp\n"
                : "";
        String sshRule = buildSshRule();
        String mongoAccessRules = wireGuardEnabled
                ? "ufw allow from " + wireGuardBootstrap.cidr() + " to any port 27017 proto tcp\n"
                : buildPrivateIngressRules(27017) + extraMongoRule;
        String webAccessRules = wireGuardEnabled
                ? "ufw allow from " + wireGuardBootstrap.cidr() + " to any port 8081 proto tcp\n"
                : buildPrivateIngressRules(8081) + extraWebRule;
        String wireGuardUfwRule = wireGuardEnabled ? "ufw allow " + wireGuardBootstrap.listenPort() + "/udp\n" : "";
        String wireGuardSetup = wireGuardEnabled
                ? "mkdir -p /etc/wireguard\n"
                + "chmod 700 /etc/wireguard\n"
                + "cat > /etc/wireguard/wg0.conf <<'EOF'\n"
                + "[Interface]\n"
                + "Address = " + wireGuardBootstrap.serverAddressCidr() + "\n"
                + "ListenPort = " + wireGuardBootstrap.listenPort() + "\n"
                + "PrivateKey = " + wireGuardBootstrap.serverPrivateKey() + "\n"
                + "\n"
                + "[Peer]\n"
                + "PublicKey = " + wireGuardBootstrap.clientPublicKey() + "\n"
                + "AllowedIPs = " + wireGuardBootstrap.clientAddressCidr() + "\n"
                + "EOF\n"
                + "chmod 600 /etc/wireguard/wg0.conf\n"
                + "cat > /etc/sysctl.d/99-cloudnetwork-wireguard.conf <<'EOF'\n"
                + "net.ipv4.ip_forward=1\n"
                + "EOF\n"
                + "sysctl --system >/dev/null 2>&1 || true\n"
                + "systemctl enable wg-quick@wg0\n"
                + "systemctl start wg-quick@wg0\n"
                : "";
        String appUser = shellQuote(dbUser);
        String appPassword = shellQuote(dbPassword);
        String appDatabase = shellQuote(dbName);
        return "#!/bin/bash\n"
                + "set -e\n"
                + "export DEBIAN_FRONTEND=noninteractive\n"
                + "apt-get update -y\n"
                + "apt-get install -y docker.io ufw" + (wireGuardEnabled ? " wireguard" : "") + "\n"
                + "systemctl enable docker\n"
                + "systemctl start docker\n"
                + "\n"
                + "ufw --force reset\n"
                + "ufw default deny incoming\n"
                + "ufw default allow outgoing\n"
                + sshRule
                + wireGuardUfwRule
                + mongoAccessRules
                + webAccessRules
                + "ufw --force enable\n"
                + wireGuardSetup
                + "\n"
                + "APP_DB_NAME=" + appDatabase + "\n"
                + "APP_DB_USER=" + appUser + "\n"
                + "APP_DB_PASS=" + appPassword + "\n"
                + "ROOT_DB_USER='admin'\n"
                + "ROOT_DB_PASS=$(openssl rand -hex 32)\n"
                + "APP_DB_NAME_B64=$(printf '%s' \"$APP_DB_NAME\" | base64 -w0)\n"
                + "APP_DB_USER_B64=$(printf '%s' \"$APP_DB_USER\" | base64 -w0)\n"
                + "APP_DB_PASS_B64=$(printf '%s' \"$APP_DB_PASS\" | base64 -w0)\n"
                + "\n"
                + "docker network create cloudnetwork >/dev/null 2>&1 || true\n"
                + "docker rm -f cloudnetwork-mongo cloudnetwork-mongo-express >/dev/null 2>&1 || true\n"
                + "\n"
                + "docker run -d --name cloudnetwork-mongo \\\n"
                + "  --restart unless-stopped \\\n"
                + "  --network cloudnetwork \\\n"
                + "  -p 27017:27017 \\\n"
                + "  -e MONGO_INITDB_ROOT_USERNAME=\"$ROOT_DB_USER\" \\\n"
                + "  -e MONGO_INITDB_ROOT_PASSWORD=\"$ROOT_DB_PASS\" \\\n"
                + "  -e MONGO_INITDB_DATABASE=\"$APP_DB_NAME\" \\\n"
                + "  mongo:7\n"
                + "\n"
                + "MONGO_READY=0\n"
                + "for i in $(seq 1 120); do\n"
                + "  if docker exec cloudnetwork-mongo mongosh --quiet --username \"$ROOT_DB_USER\" --password \"$ROOT_DB_PASS\" --authenticationDatabase admin admin --eval \"db.runCommand({ ping: 1 })\" >/dev/null 2>&1; then\n"
                + "    MONGO_READY=1\n"
                + "    break\n"
                + "  fi\n"
                + "  sleep 10\n"
                + "done\n"
                + "if [ \"$MONGO_READY\" -ne 1 ]; then\n"
                + "  echo 'ERROR: MongoDB did not become ready in time.' >&2\n"
                + "  exit 1\n"
                + "fi\n"
                + "\n"
                + "set +e\n"
                + "USER_CREATED=0\n"
                + "for j in $(seq 1 12); do\n"
                + "  if docker exec cloudnetwork-mongo mongosh --quiet --username \"$ROOT_DB_USER\" --password \"$ROOT_DB_PASS\" --authenticationDatabase admin admin --eval \"const appDb = Buffer.from('$APP_DB_NAME_B64', 'base64').toString('utf8'); const appUser = Buffer.from('$APP_DB_USER_B64', 'base64').toString('utf8'); const appPass = Buffer.from('$APP_DB_PASS_B64', 'base64').toString('utf8'); const targetDb = db.getSiblingDB(appDb); if (!targetDb.getUser(appUser)) { targetDb.createUser({ user: appUser, pwd: appPass, roles: [{ role: 'dbOwner', db: appDb }] }); }\"; then\n"
                + "    USER_CREATED=1\n"
                + "    break\n"
                + "  fi\n"
                + "  sleep 10\n"
                + "done\n"
                + "set -e\n"
                + "if [ \"$USER_CREATED\" -ne 1 ]; then\n"
                + "  echo 'ERROR: MongoDB app user could not be created.' >&2\n"
                + "  exit 1\n"
                + "fi\n"
                + "\n"
                + "docker run -d --name cloudnetwork-mongo-express \\\n"
                + "  --restart unless-stopped \\\n"
                + "  --network cloudnetwork \\\n"
                + "  -p 8081:8081 \\\n"
                + "  -e ME_CONFIG_MONGODB_SERVER=\"cloudnetwork-mongo\" \\\n"
                + "  -e ME_CONFIG_MONGODB_PORT=\"27017\" \\\n"
                + "  -e ME_CONFIG_MONGODB_ENABLE_ADMIN=\"false\" \\\n"
                + "  -e ME_CONFIG_MONGODB_AUTH_DATABASE=\"$APP_DB_NAME\" \\\n"
                + "  -e ME_CONFIG_MONGODB_AUTH_USERNAME=\"$APP_DB_USER\" \\\n"
                + "  -e ME_CONFIG_MONGODB_AUTH_PASSWORD=\"$APP_DB_PASS\" \\\n"
                + "  -e ME_CONFIG_BASICAUTH_USERNAME=\"$APP_DB_USER\" \\\n"
                + "  -e ME_CONFIG_BASICAUTH_PASSWORD=\"$APP_DB_PASS\" \\\n"
                + "  mongo-express:1.0.2\n";
    }

    private String buildWorkerCloudInitScript(String workerId,
                                              String authToken,
                                              String gatewayIp,
                                              int gatewayPort) throws IOException {
        return buildWorkerCloudInitScript(workerId, authToken, gatewayIp, gatewayPort, null, null, null, null);
    }

    private String buildWorkerCloudInitScript(String workerId,
                                              String authToken,
                                              String gatewayIp,
                                              int gatewayPort,
                                              String storageBoxHost,
                                              String storageBoxUser,
                                              String storageBoxPass,
                                              String workerJarUrl) throws IOException {
        CloudConfig config = ConfigManager.load();
        String configJson = new GsonBuilder().setPrettyPrinting().create().toJson(config);
        String effectiveGatewayIp = gatewayIp == null || gatewayIp.isBlank() ? detectPublicIp() : gatewayIp;
        String gatewayRule = effectiveGatewayIp == null || effectiveGatewayIp.isBlank()
                ? ""
                : "ufw allow from " + effectiveGatewayIp + " to any port 9876 proto tcp\n";
        String sshRule = buildSshRule();
        String privateMinecraftRules = buildPrivateIngressRules(25565);

        boolean hasStorageBox = storageBoxHost != null && !storageBoxHost.isBlank()
                && storageBoxUser != null && !storageBoxUser.isBlank()
                && storageBoxPass != null && !storageBoxPass.isBlank();

        // CIFS mount snippet for the Hetzner Storage Box.
        // The Storage Box is mounted at /mnt/cloudnetwork-storage.
        // Layout: /mnt/cloudnetwork-storage/CloudNetwork/{Templates,Static,Jars,Backups}
        String storageBoxMount = hasStorageBox
                ? "# Mount Hetzner Storage Box via CIFS\n"
                + "apt-get install -y cifs-utils\n"
                + "mkdir -p /mnt/cloudnetwork-storage\n"
                + "SMBUSER=" + shellQuote(storageBoxUser) + "\n"
                + "SMBPASS=" + shellQuote(storageBoxPass) + "\n"
                + "printf 'username=%s\\npassword=%s\\ndomain=WORKGROUP\\n' \"$SMBUSER\" \"$SMBPASS\" > /root/.storagebox-creds\n"
                + "chmod 600 /root/.storagebox-creds\n"
                + "echo '//" + storageBoxHost + "/backup /mnt/cloudnetwork-storage cifs "
                + "credentials=/root/.storagebox-creds,uid=0,gid=0,iocharset=utf8,_netdev,auto 0 0' >> /etc/fstab\n"
                + "mount /mnt/cloudnetwork-storage || true\n"
                : "";

        // Worker and config files live in /root (the root user's home directory).
        // Minecraft instance data is stored under /root/cloudnetwork/instances/.
        String safeWorkerJarUrl = workerJarUrl != null ? workerJarUrl.trim() : "";
        String jarDeployScript = "# Deploy worker.jar\n"
                + "WORKER_JAR_URL=" + shellQuote(safeWorkerJarUrl) + "\n"
                + "WORKER_JAR_PLACED=0\n"
                + "if [ -f /mnt/cloudnetwork-storage/CloudNetwork/Jars/worker.jar ]; then\n"
                + "  cp /mnt/cloudnetwork-storage/CloudNetwork/Jars/worker.jar /root/worker.jar\n"
                + "  WORKER_JAR_PLACED=1\n"
                + "fi\n"
                + "if [ \"$WORKER_JAR_PLACED\" -eq 0 ] && [ -n \"$WORKER_JAR_URL\" ]; then\n"
                + "  if wget -q -O /root/worker.jar \"$WORKER_JAR_URL\"; then\n"
                + "    WORKER_JAR_PLACED=1\n"
                + "  fi\n"
                + "fi\n";
        return "#!/bin/bash\n"
                + "set -e\n"
                + "export DEBIAN_FRONTEND=noninteractive\n"
                + "apt-get update -y\n"
                + "apt-get install -y openjdk-21-jre-headless wget ufw\n"
                + storageBoxMount
                + "cat > /root/CloudConfig.json <<'EOF'\n"
                + configJson + "\nEOF\n"
                + "chmod 600 /root/CloudConfig.json\n"
                + "cat > /etc/default/cloudnetwork-worker <<'EOF'\n"
                + "WORKER_ID=" + workerId + "\n"
                + "WORKER_AUTH_TOKEN=" + authToken + "\n"
                + "GATEWAY_HOST=" + effectiveGatewayIp + "\n"
                + "GATEWAY_PORT=" + gatewayPort + "\n"
                + (hasStorageBox ? "STORAGE_BOX_HOST=" + storageBoxHost + "\n" : "")
                + (hasStorageBox ? "STORAGE_BOX_USER=" + storageBoxUser + "\n" : "")
                + "EOF\n"
                + "cat > /etc/systemd/system/cloudnetwork-worker.service <<'EOF'\n"
                + "[Unit]\n"
                + "Description=CloudNetwork Worker\n"
                + "After=network-online.target\n"
                + "Wants=network-online.target\n"
                + "ConditionPathExists=/root/worker.jar\n\n"
                + "[Service]\n"
                + "Type=simple\n"
                + "EnvironmentFile=/etc/default/cloudnetwork-worker\n"
                + "WorkingDirectory=/root\n"
                + "ExecStart=/usr/bin/java -jar /root/worker.jar\n"
                + "Restart=always\n"
                + "RestartSec=10\n\n"
                + "[Install]\n"
                + "WantedBy=multi-user.target\n"
                + "EOF\n"
                + jarDeployScript
                + "ufw --force reset\n"
                + "ufw default deny incoming\n"
                + "ufw default allow outgoing\n"
                + sshRule
                + privateMinecraftRules
                + gatewayRule
                + "ufw --force enable\n"
                + "systemctl daemon-reload\n"
                + "systemctl enable cloudnetwork-worker.service\n"
                + "if [ \"$WORKER_JAR_PLACED\" -eq 1 ]; then\n"
                + "  systemctl start cloudnetwork-worker.service\n"
                + "fi\n";
    }

    /**
     * Creates a Hetzner server pre-configured to run the CloudNetwork ProxyGateway.
     */
    public HetznerServer createProxyGatewayServer(String serverName,
                                                  String gatewayId,
                                                  String authToken,
                                                  String mainGatewayIp,
                                                  int mainGatewayPort,
                                                  String storageBoxHost,
                                                  String storageBoxUser,
                                                  String storageBoxPass,
                                                  String proxyGatewayJarUrl)
            throws IOException, InterruptedException {
        return createProxyGatewayServer(serverName, gatewayId, authToken, mainGatewayIp, mainGatewayPort,
                storageBoxHost, storageBoxUser, storageBoxPass, proxyGatewayJarUrl,
                ServerProvisioningOptions.defaultForCurrentEnvironment(false));
    }

    public HetznerServer createProxyGatewayServer(String serverName,
                                                  String gatewayId,
                                                  String authToken,
                                                  String mainGatewayIp,
                                                  int mainGatewayPort,
                                                  String storageBoxHost,
                                                  String storageBoxUser,
                                                  String storageBoxPass,
                                                  String proxyGatewayJarUrl,
                                                  ServerProvisioningOptions provisioningOptions)
            throws IOException, InterruptedException {
        List<WorkerProvisioningPlan> plans = listProvisioningPlans(PREFERRED_WORKER_SERVER_TYPES, DEFAULT_WORKER_SERVER_TYPE);
        IOException lastError = null;
        String script = buildProxyGatewayCloudInitScript(
                gatewayId, authToken, mainGatewayIp, mainGatewayPort,
                storageBoxHost, storageBoxUser, storageBoxPass, proxyGatewayJarUrl);
        for (WorkerProvisioningPlan plan : plans) {
            JsonObject body = new JsonObject();
            body.addProperty("name", serverName);
            body.addProperty("server_type", plan.serverType());
            body.addProperty("image", "ubuntu-24.04");
            body.addProperty("location", plan.location());
            body.addProperty("user_data", script);
            body.addProperty("start_after_create", true);
            applyNetworkConfiguration(body, provisioningOptions);

            HttpResponse<String> response = post("/servers", body.toString());
            if (response.statusCode() == 201) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonObject serverJson = json.getAsJsonObject("server");
                long serverId = serverJson.get("id").getAsLong();
                String ipv4 = extractIpv4(serverJson);
                String privateIpv4 = extractPrivateIpv4(serverJson, provisioningOptions.networkId());
                return new HetznerServer(serverId, ipv4, privateIpv4);
            }
            if (response.statusCode() == 412 || response.statusCode() == 422
                    || response.statusCode() == 409 || response.statusCode() == 404) {
                lastError = new IOException("ProxyGateway-Server konnte nicht erstellt werden (HTTP "
                        + response.statusCode() + "): " + response.body());
                continue;
            }
            throw new IOException("ProxyGateway-Server konnte nicht erstellt werden (HTTP "
                    + response.statusCode() + "): " + response.body());
        }
        if (lastError != null) throw lastError;
        throw new IOException("ProxyGateway-Server konnte nicht erstellt werden: kein gültiger Servertyp/Standort.");
    }

    private String buildProxyGatewayCloudInitScript(String gatewayId,
                                                    String authToken,
                                                    String mainGatewayIp,
                                                    int mainGatewayPort,
                                                    String storageBoxHost,
                                                    String storageBoxUser,
                                                    String storageBoxPass,
                                                    String proxyGatewayJarUrl) throws IOException {
        CloudConfig config = ConfigManager.load();
        String configJson = new GsonBuilder().setPrettyPrinting().create().toJson(config);
        String gatewayRule = mainGatewayIp == null || mainGatewayIp.isBlank()
                ? ""
                : "ufw allow from " + mainGatewayIp + " to any port 9876 proto tcp\n";
        String sshRule = buildSshRule();
        boolean hasStorageBox = storageBoxHost != null && !storageBoxHost.isBlank()
                && storageBoxUser != null && !storageBoxUser.isBlank()
                && storageBoxPass != null && !storageBoxPass.isBlank();
        String storageBoxMount = hasStorageBox
                ? "# Mount Hetzner Storage Box via CIFS\n"
                + "apt-get install -y cifs-utils\n"
                + "mkdir -p /mnt/cloudnetwork-storage\n"
                + "SMBUSER=" + shellQuote(storageBoxUser) + "\n"
                + "SMBPASS=" + shellQuote(storageBoxPass) + "\n"
                + "printf 'username=%s\\npassword=%s\\ndomain=WORKGROUP\\n' \"$SMBUSER\" \"$SMBPASS\" > /root/.storagebox-creds\n"
                + "chmod 600 /root/.storagebox-creds\n"
                + "echo '//" + storageBoxHost + "/backup /mnt/cloudnetwork-storage cifs "
                + "credentials=/root/.storagebox-creds,uid=0,gid=0,iocharset=utf8,_netdev,auto 0 0' >> /etc/fstab\n"
                + "mount /mnt/cloudnetwork-storage || true\n"
                : "";
        String safeProxyJarUrl = proxyGatewayJarUrl != null ? proxyGatewayJarUrl.trim() : "";
        String jarDeployScript = "# Deploy proxy-gateway.jar\n"
                + "PROXY_JAR_URL=" + shellQuote(safeProxyJarUrl) + "\n"
                + "PROXY_JAR_PLACED=0\n"
                + "if [ -f /mnt/cloudnetwork-storage/CloudNetwork/Jars/proxy-gateway.jar ]; then\n"
                + "  cp /mnt/cloudnetwork-storage/CloudNetwork/Jars/proxy-gateway.jar /root/proxy-gateway.jar\n"
                + "  PROXY_JAR_PLACED=1\n"
                + "fi\n"
                + "if [ \"$PROXY_JAR_PLACED\" -eq 0 ] && [ -n \"$PROXY_JAR_URL\" ]; then\n"
                + "  if wget -q -O /root/proxy-gateway.jar \"$PROXY_JAR_URL\"; then\n"
                + "    PROXY_JAR_PLACED=1\n"
                + "  fi\n"
                + "fi\n";
        return "#!/bin/bash\n"
                + "set -e\n"
                + "export DEBIAN_FRONTEND=noninteractive\n"
                + "apt-get update -y\n"
                + "apt-get install -y openjdk-21-jre-headless ufw wget fail2ban\n"
                + storageBoxMount
                + "cat > /root/CloudConfig.json <<'EOF'\n"
                + configJson + "\nEOF\n"
                + "chmod 600 /root/CloudConfig.json\n"
                + "cat > /etc/default/cloudnetwork-proxy-gateway <<'EOF'\n"
                + "PROXY_GATEWAY_ID=" + gatewayId + "\n"
                + "PROXY_GATEWAY_AUTH_TOKEN=" + authToken + "\n"
                + "GATEWAY_HOST=" + mainGatewayIp + "\n"
                + "GATEWAY_PORT=" + mainGatewayPort + "\n"
                + "PROXY_GATEWAY_PORT=25565\n"
                + "EOF\n"
                + "cat > /etc/systemd/system/cloudnetwork-proxy-gateway.service <<'EOF'\n"
                + "[Unit]\n"
                + "Description=CloudNetwork ProxyGateway\n"
                + "After=network-online.target\n"
                + "Wants=network-online.target\n"
                + "ConditionPathExists=/root/proxy-gateway.jar\n\n"
                + "[Service]\n"
                + "Type=simple\n"
                + "EnvironmentFile=/etc/default/cloudnetwork-proxy-gateway\n"
                + "WorkingDirectory=/root\n"
                + "ExecStart=/usr/bin/java -jar /root/proxy-gateway.jar\n"
                + "Restart=always\n"
                + "RestartSec=10\n\n"
                + "[Install]\n"
                + "WantedBy=multi-user.target\n"
                + "EOF\n"
                + jarDeployScript
                + "ufw --force reset\n"
                + "ufw default deny incoming\n"
                + "ufw default allow outgoing\n"
                + sshRule
                + "ufw limit 25565/tcp\n"
                + gatewayRule
                + "ufw --force enable\n"
                + "systemctl daemon-reload\n"
                + "systemctl enable cloudnetwork-proxy-gateway.service\n"
                + "if [ \"$PROXY_JAR_PLACED\" -eq 1 ]; then\n"
                + "  systemctl start cloudnetwork-proxy-gateway.service\n"
                + "fi\n"
                + "systemctl enable fail2ban\n"
                + "systemctl start fail2ban\n";
    }

    private String detectPublicIp() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.ipify.org?format=text"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String ip = response.body().trim();
                String octetPattern = "(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)";
                if (ip.matches(octetPattern + "\\." + octetPattern + "\\." + octetPattern + "\\." + octetPattern)) {
                    return ip;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String extractIpv4(JsonObject serverJson) {
        try {
            return serverJson.getAsJsonObject("public_net").getAsJsonObject("ipv4").get("ip").getAsString();
        } catch (Exception e) {
            JsonElement el = serverJson.get("ip");
            return el != null ? el.getAsString() : "";
        }
    }

    private String extractPrivateIpv4(JsonObject serverJson, Long networkId) {
        try {
            JsonObject privateNet = serverJson.getAsJsonObject("private_net");
            if (privateNet == null) {
                return "";
            }
            JsonArray networkArray = privateNet.getAsJsonArray("network");
            if (networkArray == null || networkArray.size() == 0) {
                return "";
            }
            for (JsonElement element : networkArray) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject network = element.getAsJsonObject();
                long attachedNetworkId = network.get("id").getAsLong();
                if (networkId == null || attachedNetworkId == networkId.longValue()) {
                    return readString(network, "ip");
                }
            }
            JsonObject first = networkArray.get(0).getAsJsonObject();
            return readString(first, "ip");
        } catch (Exception ignored) {
            return "";
        }
    }

    private void applyNetworkConfiguration(JsonObject body, ServerProvisioningOptions provisioningOptions) {
        Long networkId = provisioningOptions != null ? provisioningOptions.networkId() : null;
        if (networkId != null && networkId > 0L) {
            JsonArray networks = new JsonArray();
            networks.add(networkId);
            body.add("networks", networks);
        }
        if (provisioningOptions != null && (!provisioningOptions.enablePublicIpv4() || !provisioningOptions.enablePublicIpv6())) {
            JsonObject publicNet = new JsonObject();
            publicNet.addProperty("enable_ipv4", provisioningOptions.enablePublicIpv4());
            publicNet.addProperty("enable_ipv6", provisioningOptions.enablePublicIpv6());
            body.add("public_net", publicNet);
        }
    }

    private String buildSshRule() {
        String wireGuardCidr = readEnv("CLOUDNETWORK_WIREGUARD_CIDR", "CN_WIREGUARD_CIDR");
        if (wireGuardCidr == null || wireGuardCidr.isBlank()) {
            return "ufw allow 22/tcp\n";
        }
        return "ufw allow from " + wireGuardCidr + " to any port 22 proto tcp\n";
    }

    private String buildPrivateIngressRules(int port) {
        StringBuilder rules = new StringBuilder();
        rules.append("ufw allow from 10.0.0.0/8 to any port ").append(port).append(" proto tcp\n");
        rules.append("ufw allow from 172.16.0.0/12 to any port ").append(port).append(" proto tcp\n");
        rules.append("ufw allow from 192.168.0.0/16 to any port ").append(port).append(" proto tcp\n");
        String privateCidr = readEnv("CLOUDNETWORK_PRIVATE_NETWORK_CIDR", "CN_PRIVATE_NETWORK_CIDR");
        if (privateCidr != null && !privateCidr.isBlank()) {
            rules.append("ufw allow from ").append(privateCidr).append(" to any port ").append(port).append(" proto tcp\n");
        }
        String wireGuardCidr = readEnv("CLOUDNETWORK_WIREGUARD_CIDR", "CN_WIREGUARD_CIDR");
        if (wireGuardCidr != null && !wireGuardCidr.isBlank()) {
            rules.append("ufw allow from ").append(wireGuardCidr).append(" to any port ").append(port).append(" proto tcp\n");
        }
        return rules.toString();
    }

    private static String readEnv(String... keys) {
        for (String key : keys) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean readBooleanEnv(String... keys) {
        String value = readEnv(keys);
        if (value == null) {
            return false;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "ja", "on" -> true;
            default -> false;
        };
    }

    private static Long readLongEnv(String... keys) {
        String value = readEnv(keys);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private WorkerProvisioningPlan chooseProvisioningPlan(List<String> preferredTypes, String fallbackType) {
        List<WorkerProvisioningPlan> plans = listProvisioningPlans(preferredTypes, fallbackType);
        return plans.isEmpty() ? new WorkerProvisioningPlan(fallbackType, DEFAULT_LOCATION) : plans.get(0);
    }

    private List<WorkerProvisioningPlan> listProvisioningPlans(List<String> preferredTypes, String fallbackType) {
        List<WorkerProvisioningPlan> plans = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            HttpResponse<String> response = get("/server_types?per_page=100");
            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray serverTypes = json.getAsJsonArray("server_types");
                if (serverTypes != null) {
                    for (String preferred : preferredTypes) {
                        for (JsonElement element : serverTypes) {
                            if (!element.isJsonObject()) {
                                continue;
                            }
                            JsonObject serverType = element.getAsJsonObject();
                            String name = readString(serverType, "name");
                            if (!preferred.equalsIgnoreCase(name) || isDeprecated(serverType)) {
                                continue;
                            }
                            List<String> supportedLocations = readSupportedLocations(serverType);
                            if (supportedLocations.isEmpty()) {
                                addPlan(plans, seen, name, DEFAULT_LOCATION);
                                continue;
                            }
                            for (String preferredLocation : PREFERRED_LOCATIONS) {
                                if (supportedLocations.contains(preferredLocation)) {
                                    addPlan(plans, seen, name, preferredLocation);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (plans.isEmpty()) {
            addPlan(plans, seen, fallbackType, DEFAULT_LOCATION);
            for (String location : PREFERRED_LOCATIONS) {
                addPlan(plans, seen, fallbackType, location);
            }
        }
        return plans;
    }

    private void addPlan(List<WorkerProvisioningPlan> plans, Set<String> seen, String serverType, String location) {
        if (serverType == null || serverType.isBlank() || location == null || location.isBlank()) {
            return;
        }
        String key = serverType.toLowerCase(Locale.ROOT) + "|" + location.toLowerCase(Locale.ROOT);
        if (seen.add(key)) {
            plans.add(new WorkerProvisioningPlan(serverType.toLowerCase(Locale.ROOT), location.toLowerCase(Locale.ROOT)));
        }
    }

    private List<String> readSupportedLocations(JsonObject serverType) {
        List<String> locations = new ArrayList<>();
        JsonElement locationsElement = serverType.get("locations");
        if (locationsElement == null || !locationsElement.isJsonArray()) {
            return locations;
        }
        for (JsonElement element : locationsElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject locationObject = element.getAsJsonObject();
            String name = readString(locationObject, "name").toLowerCase(Locale.ROOT);
            boolean available = !locationObject.has("available")
                    || locationObject.get("available").isJsonNull()
                    || locationObject.get("available").getAsBoolean();
            if (!name.isBlank() && available) {
                locations.add(name);
            }
        }
        return locations;
    }

    private boolean isDeprecated(JsonObject serverType) {
        JsonElement deprecated = serverType.get("deprecated");
        if (deprecated == null || deprecated.isJsonNull()) {
            return false;
        }
        if (deprecated.isJsonPrimitive()) {
            if (deprecated.getAsJsonPrimitive().isBoolean()) {
                return deprecated.getAsBoolean();
            }
            if (deprecated.getAsJsonPrimitive().isString()) {
                return !deprecated.getAsString().isBlank();
            }
        }
        return true;
    }

    private String readString(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) {
            return "";
        }
        try {
            return value.getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private record WorkerProvisioningPlan(String serverType, String location) {
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String jsonBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .DELETE()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
