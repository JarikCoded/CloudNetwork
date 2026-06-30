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
        } catch (Exception e) {
            return false;
        }
    }

    public HetznerServer createServer(String serverName,
                                      String dbUser,
                                      String dbPassword,
                                      String dbName)
            throws IOException, InterruptedException {
        WorkerProvisioningPlan plan = chooseProvisioningPlan(PREFERRED_SERVER_TYPES, DEFAULT_SERVER_TYPE);
        String localIp = detectPublicIp();

        JsonObject body = new JsonObject();
        body.addProperty("name", serverName);
        body.addProperty("server_type", plan.serverType());
        body.addProperty("image", "ubuntu-24.04");
        body.addProperty("location", plan.location());
        body.addProperty("user_data", buildDatabaseCloudInitScript(localIp, dbUser, dbPassword, dbName));
        body.addProperty("start_after_create", true);

        HttpResponse<String> response = post("/servers", body.toString());
        if (response.statusCode() != 201) {
            throw new IOException("Server konnte nicht erstellt werden (Typ " + plan.serverType() + ", Standort " + plan.location() + ", HTTP " + response.statusCode() + "): " + response.body());
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject serverJson = json.getAsJsonObject("server");
        long serverId = serverJson.get("id").getAsLong();
        String ipv4 = extractIpv4(serverJson);
        return new HetznerServer(serverId, ipv4);
    }

    public HetznerServer createWorkerServer(String serverName,
                                            String workerId,
                                            String authToken,
                                            String gatewayIp,
                                            int gatewayPort)
            throws IOException, InterruptedException {
        List<WorkerProvisioningPlan> plans = listProvisioningPlans(PREFERRED_WORKER_SERVER_TYPES, DEFAULT_WORKER_SERVER_TYPE);
        IOException lastError = null;
        for (WorkerProvisioningPlan plan : plans) {
            JsonObject body = new JsonObject();
            body.addProperty("name", serverName);
            body.addProperty("server_type", plan.serverType());
            body.addProperty("image", "ubuntu-24.04");
            body.addProperty("location", plan.location());
            body.addProperty("user_data", buildWorkerCloudInitScript(workerId, authToken, gatewayIp, gatewayPort));
            body.addProperty("start_after_create", true);

            HttpResponse<String> response = post("/servers", body.toString());
            if (response.statusCode() == 201) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonObject serverJson = json.getAsJsonObject("server");
                long serverId = serverJson.get("id").getAsLong();
                String ipv4 = extractIpv4(serverJson);
                return new HetznerServer(serverId, ipv4);
            }
            if (response.statusCode() == 422 || response.statusCode() == 409 || response.statusCode() == 404) {
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
        ConsoleOutput.info("Warte auf Server-Start (kann einige Minuten dauern)...");
        long deadline = System.currentTimeMillis() + 10 * 60_000L;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = get("/servers/" + serverId);
            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonObject server = json.getAsJsonObject("server");
                String status = server.get("status").getAsString();
                if ("running".equals(status)) {
                    return extractIpv4(server);
                }
            }
            ConsoleOutput.info("  Status: wird gestartet...");
            Thread.sleep(15_000L);
        }
        throw new IOException("Timeout: Server ist nach 10 Minuten noch nicht bereit.");
    }

    public void deleteServer(long serverId) throws IOException, InterruptedException {
        HttpResponse<String> response = delete("/servers/" + serverId);
        if (response.statusCode() != 204) {
            throw new IOException("Server konnte nicht gelöscht werden (HTTP " + response.statusCode() + "): " + response.body());
        }
    }

    public boolean uploadFile(String ip, String localPath, String remotePath, String sshKeyPath) {
        ConsoleOutput.info("[INFO] SCP not yet configured: " + localPath + " -> root@" + ip + ":" + remotePath);
        return false;
    }

    private String buildDatabaseCloudInitScript(String extraAllowedIp,
                                                String dbUser,
                                                String dbPassword,
                                                String dbName) {
        String extraMongoRule = extraAllowedIp.isBlank() ? "" : "ufw allow from " + extraAllowedIp + " to any port 27017 proto tcp\n";
        String extraWebRule = extraAllowedIp.isBlank() ? "" : "ufw allow from " + extraAllowedIp + " to any port 8081 proto tcp\n";
        String appUser = shellQuote(dbUser);
        String appPassword = shellQuote(dbPassword);
        String appDatabase = shellQuote(dbName);
        return "#!/bin/bash\n"
                + "set -e\n"
                + "export DEBIAN_FRONTEND=noninteractive\n"
                + "apt-get update -y\n"
                + "apt-get install -y docker.io ufw\n"
                + "systemctl enable docker\n"
                + "systemctl start docker\n"
                + "\n"
                + "ufw --force reset\n"
                + "ufw default deny incoming\n"
                + "ufw default allow outgoing\n"
                + "ufw allow 22/tcp\n"
                + "ufw allow from 10.0.0.0/8 to any port 27017 proto tcp\n"
                + "ufw allow from 172.16.0.0/12 to any port 27017 proto tcp\n"
                + "ufw allow from 192.168.0.0/16 to any port 27017 proto tcp\n"
                + "ufw allow from 10.0.0.0/8 to any port 8081 proto tcp\n"
                + "ufw allow from 172.16.0.0/12 to any port 8081 proto tcp\n"
                + "ufw allow from 192.168.0.0/16 to any port 8081 proto tcp\n"
                + extraMongoRule
                + extraWebRule
                + "ufw --force enable\n"
                + "\n"
                + "APP_DB_NAME=" + appDatabase + "\n"
                + "APP_DB_USER=" + appUser + "\n"
                + "APP_DB_PASS=" + appPassword + "\n"
                + "ROOT_DB_USER='admin'\n"
                + "ROOT_DB_PASS=$(openssl rand -hex 32)\n"
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
                + "for i in $(seq 1 60); do\n"
                + "  if docker exec cloudnetwork-mongo mongosh --quiet --username \"$ROOT_DB_USER\" --password \"$ROOT_DB_PASS\" --authenticationDatabase admin admin --eval \"db.runCommand({ ping: 1 })\" >/dev/null 2>&1; then\n"
                + "    MONGO_READY=1\n"
                + "    break\n"
                + "  fi\n"
                + "  sleep 5\n"
                + "done\n"
                + "if [ \"$MONGO_READY\" -ne 1 ]; then\n"
                + "  echo 'ERROR: MongoDB did not become ready in time.' >&2\n"
                + "  exit 1\n"
                + "fi\n"
                + "\n"
                + "set +e\n"
                + "USER_CREATED=0\n"
                + "for j in $(seq 1 12); do\n"
                + "  if docker exec cloudnetwork-mongo mongosh --quiet --username \"$ROOT_DB_USER\" --password \"$ROOT_DB_PASS\" --authenticationDatabase admin \"$APP_DB_NAME\" --eval \"if (!db.getUser(\\\"$APP_DB_USER\\\")) { db.createUser({ user: \\\"$APP_DB_USER\\\", pwd: \\\"$APP_DB_PASS\\\", roles: [{ role: \\\"dbOwner\\\", db: \\\"$APP_DB_NAME\\\" }] }); }\"; then\n"
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
        CloudConfig config = ConfigManager.load();
        String configJson = new GsonBuilder().setPrettyPrinting().create().toJson(config);
        String effectiveGatewayIp = gatewayIp == null || gatewayIp.isBlank() ? detectPublicIp() : gatewayIp;
        String gatewayRule = effectiveGatewayIp == null || effectiveGatewayIp.isBlank()
                ? ""
                : "ufw allow from " + effectiveGatewayIp + " to any port 9876 proto tcp\n";
        return "#!/bin/bash\n"
                + "set -e\n"
                + "export DEBIAN_FRONTEND=noninteractive\n"
                + "apt-get update -y\n"
                + "apt-get install -y openjdk-21-jre-headless ufw\n"
                + "mkdir -p /opt/cloudnetwork\n"
                + "cat > /opt/cloudnetwork/CloudConfig.json <<'EOF'\n"
                + configJson + "\nEOF\n"
                + "cat > /etc/default/cloudnetwork-worker <<'EOF'\n"
                + "WORKER_ID=" + workerId + "\n"
                + "WORKER_AUTH_TOKEN=" + authToken + "\n"
                + "GATEWAY_HOST=" + effectiveGatewayIp + "\n"
                + "GATEWAY_PORT=" + gatewayPort + "\n"
                + "EOF\n"
                + "cat > /opt/cloudnetwork/bootstrap-worker.sh <<'EOF'\n"
                + "#!/bin/bash\n"
                + "# TODO: Upload /opt/cloudnetwork/worker.jar via SSH/SCP after provisioning.\n"
                + "EOF\n"
                + "chmod +x /opt/cloudnetwork/bootstrap-worker.sh\n"
                + "cat > /etc/systemd/system/cloudnetwork-worker.service <<'EOF'\n"
                + "[Unit]\n"
                + "Description=CloudNetwork Worker\n"
                + "After=network-online.target\n"
                + "Wants=network-online.target\n"
                + "ConditionPathExists=/opt/cloudnetwork/worker.jar\n\n"
                + "[Service]\n"
                + "Type=simple\n"
                + "EnvironmentFile=/etc/default/cloudnetwork-worker\n"
                + "WorkingDirectory=/opt/cloudnetwork\n"
                + "ExecStart=/usr/bin/java -jar /opt/cloudnetwork/worker.jar\n"
                + "Restart=always\n"
                + "RestartSec=10\n\n"
                + "[Install]\n"
                + "WantedBy=multi-user.target\n"
                + "EOF\n"
                + "ufw --force reset\n"
                + "ufw default deny incoming\n"
                + "ufw default allow outgoing\n"
                + "ufw allow 22/tcp\n"
                + gatewayRule
                + "ufw --force enable\n"
                + "systemctl daemon-reload\n"
                + "systemctl enable cloudnetwork-worker.service\n";
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
            return el != null ? el.getAsString() : "unknown";
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
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String jsonBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .DELETE()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
