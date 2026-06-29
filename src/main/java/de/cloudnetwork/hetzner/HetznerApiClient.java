package de.cloudnetwork.hetzner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Thin wrapper around the Hetzner Cloud REST API v1.
 *
 * <p>All network I/O uses the JDK 11+ built-in {@link HttpClient} so there are
 * no extra HTTP-library dependencies.</p>
 */
public class HetznerApiClient {

    private static final String BASE_URL = "https://api.hetzner.cloud/v1";
    private static final String DEFAULT_SERVER_TYPE = "cpx22";
    private static final List<String> PREFERRED_SERVER_TYPES = List.of(
            "cpx22",
            "cx22",
            "cpx21",
            "cx32",
            "cx42",
            "cax11"
    );

    /**
     * Builds the cloud-init script that installs Docker, provisions MongoDB,
     * creates the application user/database and starts a MongoDB web UI.
     *
     * <p>The automatic setup mode now provisions MongoDB because the
     * application already supports it directly. The web UI is provided by
     * {@code mongo-express} and shares the same application credentials.</p>
     *
     * <p>Security measures applied by cloud-init:</p>
     * <ul>
     *   <li>UFW is configured to allow SSH (22/tcp), MongoDB (27017/tcp) and
     *       the MongoDB web UI (8081/tcp) from RFC-1918 private address ranges
     *       and, when {@code extraAllowedIp} is non-blank, from that specific
     *       address as well.</li>
     *   <li>MongoDB and the web UI run in containers with restart policies so
     *       they survive reboots.</li>
     * </ul>
     *
     * @param extraAllowedIp public IP of the machine running setup, or blank
     *                       when detection failed (only private ranges allowed)
     */
    private static String buildCloudInitScript(String extraAllowedIp,
                                               String dbUser,
                                               String dbPassword,
                                               String dbName) {
        String extraMongoRule = extraAllowedIp.isBlank() ? "" :
                "ufw allow from " + extraAllowedIp + " to any port 27017 proto tcp\n";
        String extraWebRule = extraAllowedIp.isBlank() ? "" :
                "ufw allow from " + extraAllowedIp + " to any port 8081 proto tcp\n";
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
                + "# ── Firewall (UFW) ──────────────────────────────────────────────\n"
                + "ufw --force reset\n"
                + "ufw default deny incoming\n"
                + "ufw default allow outgoing\n"
                + "ufw allow 22/tcp\n"
                + "ufw allow from 10.0.0.0/8     to any port 27017 proto tcp\n"
                + "ufw allow from 172.16.0.0/12  to any port 27017 proto tcp\n"
                + "ufw allow from 192.168.0.0/16 to any port 27017 proto tcp\n"
                + "ufw allow from 10.0.0.0/8     to any port 8081 proto tcp\n"
                + "ufw allow from 172.16.0.0/12  to any port 8081 proto tcp\n"
                + "ufw allow from 192.168.0.0/16 to any port 8081 proto tcp\n"
                + extraMongoRule
                + extraWebRule
                + "ufw --force enable\n"
                + "\n"
                + "# ── MongoDB + Web UI setup ──────────────────────────────────────\n"
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
                + "for i in $(seq 1 60); do\n"
                + "  if docker exec cloudnetwork-mongo mongosh --quiet \\\n"
                + "    --username \"$ROOT_DB_USER\" \\\n"
                + "    --password \"$ROOT_DB_PASS\" \\\n"
                + "    --authenticationDatabase admin admin \\\n"
                + "    --eval \"db.runCommand({ ping: 1 })\" >/dev/null 2>&1; then\n"
                + "    break\n"
                + "  fi\n"
                + "  sleep 5\n"
                + "done\n"
                + "\n"
                + "docker exec cloudnetwork-mongo mongosh --quiet \\\n"
                + "  --username \"$ROOT_DB_USER\" \\\n"
                + "  --password \"$ROOT_DB_PASS\" \\\n"
                + "  --authenticationDatabase admin \"$APP_DB_NAME\" \\\n"
                + "  --eval \"if (!db.getUser(\\\"$APP_DB_USER\\\")) { db.createUser({ user: \\\"$APP_DB_USER\\\", pwd: \\\"$APP_DB_PASS\\\", roles: [{ role: \\\"dbOwner\\\", db: \\\"$APP_DB_NAME\\\" }] }); }\"\n"
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

    private final String apiKey;
    private final HttpClient httpClient;

    public HetznerApiClient(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Validates the API key by listing server types (a lightweight, read-only
     * endpoint).  Returns {@code true} when the key is accepted by Hetzner.
     */
    public boolean validateApiKey() {
        try {
            HttpResponse<String> response = get("/server_types?per_page=1");
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Creates a cloud server in the Hetzner nbg1 datacenter, installs MongoDB
     * plus its web UI via cloud-init, and returns a {@link HetznerServer} with
     * the new server's id and public IPv4 address.
     *
     * <p>The public IP of this machine is detected automatically and added to
     * the server's UFW allow-list so that MongoDB and the web UI remain
     * reachable even when the calling host has a public (non-RFC-1918) IP
     * address. If detection fails, only private address ranges are allowed (as
     * before).</p>
     *
     * @param serverName human-readable name for the server
     * @param dbUser application database username
     * @param dbPassword application database password
     * @param dbName application database name
     * @throws IOException          on HTTP / network errors
     * @throws InterruptedException if the thread is interrupted
     */
    public HetznerServer createServer(String serverName,
                                      String dbUser,
                                      String dbPassword,
                                      String dbName)
            throws IOException, InterruptedException {

        String serverType = findPreferredServerType();
        String localIp    = detectPublicIp();

        JsonObject body = new JsonObject();
        body.addProperty("name",         serverName);
        body.addProperty("server_type",  serverType);
        body.addProperty("image",        "ubuntu-24.04");
        body.addProperty("location",     "nbg1");
        body.addProperty("user_data",    buildCloudInitScript(localIp, dbUser, dbPassword, dbName));
        body.addProperty("start_after_create", true);

        HttpResponse<String> response = post("/servers", body.toString());

        if (response.statusCode() != 201) {
            throw new IOException("Server konnte nicht erstellt werden (Typ "
                    + serverType + ", HTTP " + response.statusCode() + "): "
                    + response.body());
        }

        JsonObject json       = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject serverJson = json.getAsJsonObject("server");
        long       serverId   = serverJson.get("id").getAsLong();

        // IPv4 may be inside public_net.ipv4.ip
        String ipv4 = extractIpv4(serverJson);

        return new HetznerServer(serverId, ipv4);
    }

    /**
     * Polls the server status until it is {@code "running"} or the timeout
     * (default 10 minutes) is reached.
     *
     * @return the public IPv4 address once the server is running
     */
    public String waitForServerRunning(long serverId)
            throws IOException, InterruptedException {

        System.out.println("Warte auf Server-Start (kann einige Minuten dauern)...");
        long deadline = System.currentTimeMillis() + 10 * 60_000L;

        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = get("/servers/" + serverId);
            if (response.statusCode() == 200) {
                JsonObject json   = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonObject server = json.getAsJsonObject("server");
                String     status = server.get("status").getAsString();

                if ("running".equals(status)) {
                    return extractIpv4(server);
                }
            }
            System.out.println("  Status: wird gestartet...");
            Thread.sleep(15_000);
        }
        throw new IOException("Timeout: Server ist nach 10 Minuten noch nicht bereit.");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Attempts to detect the public IPv4 address of this machine by calling
     * a lightweight external service.  Returns an empty string if detection
     * fails; in that case only RFC-1918 ranges are allowed in the UFW rules.
     */
    private String detectPublicIp() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.ipify.org?format=text"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String ip = response.body().trim();
                // Validate IPv4 format with correct octet range (0–255)
                String octetPattern = "(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)";
                if (ip.matches(octetPattern + "\\." + octetPattern + "\\."
                        + octetPattern + "\\." + octetPattern)) {
                    return ip;
                }
            }
        } catch (Exception ignored) {
            // Detection failure is non-fatal; fall back to private-only rules
        }
        return "";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String extractIpv4(JsonObject serverJson) {
        try {
            return serverJson
                    .getAsJsonObject("public_net")
                    .getAsJsonObject("ipv4")
                    .get("ip").getAsString();
        } catch (Exception e) {
            // Fallback: some responses use a flat "ip" field
            JsonElement el = serverJson.get("ip");
            return el != null ? el.getAsString() : "unknown";
        }
    }

    /**
     * Chooses a non-deprecated server type from Hetzner's available server
     * types, preferring small CX/CPX families. Falls back to
     * {@value #DEFAULT_SERVER_TYPE} when discovery fails.
     */
    private String findPreferredServerType() {
        try {
            HttpResponse<String> response = get("/server_types?per_page=100");
            if (response.statusCode() != 200) {
                return DEFAULT_SERVER_TYPE;
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray serverTypes = json.getAsJsonArray("server_types");
            if (serverTypes == null) {
                return DEFAULT_SERVER_TYPE;
            }

            for (String preferred : PREFERRED_SERVER_TYPES) {
                for (JsonElement element : serverTypes) {
                    if (!element.isJsonObject()) continue;
                    JsonObject serverType = element.getAsJsonObject();
                    String name = readString(serverType, "name");
                    if (preferred.equals(name) && !isDeprecated(serverType)) {
                        return name;
                    }
                }
            }

            for (JsonElement element : serverTypes) {
                if (!element.isJsonObject()) continue;
                JsonObject serverType = element.getAsJsonObject();
                String name = readString(serverType, "name");
                if (!name.isBlank() && !isDeprecated(serverType)) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // Fallback to a known default when discovery fails.
        }
        return DEFAULT_SERVER_TYPE;
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

    private HttpResponse<String> get(String path)
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type",  "application/json")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String jsonBody)
            throws IOException, InterruptedException {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type",  "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
