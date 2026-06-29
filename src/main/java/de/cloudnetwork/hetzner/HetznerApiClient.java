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
    private static final String DEFAULT_SERVER_TYPE = "cx22";
    private static final List<String> PREFERRED_SERVER_TYPES = List.of(
            "cpx11",
            "cx22",
            "cpx21",
            "cx32",
            "cx42",
            "cax11"
    );

    /**
     * Builds the cloud-init script that installs MySQL, secures it, and creates
     * a dedicated {@code cloudnetwork} user + database on first boot.
     *
     * <p><b>Note:</b> The automatic setup mode always uses MySQL because this
     * script is what provisions the database server.  When using an existing
     * server, MongoDB can be selected instead via the manual setup.</p>
     *
     * <p>Security measures applied by cloud-init:</p>
     * <ul>
     *   <li>UFW is configured to allow SSH (22/tcp) from everywhere and
     *       MySQL (3306/tcp) from RFC-1918 private address ranges
     *       (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16) and, when
     *       {@code extraAllowedIp} is non-blank, from that specific address
     *       as well (the detected public IP of the machine running setup).
     *       All other inbound connections are denied by default.</li>
     *   <li>MySQL binds to 0.0.0.0 so it is reachable, but the firewall
     *       restricts access to the allowed addresses.</li>
     * </ul>
     *
     * <p>For production deployments it is strongly recommended to place the
     * database server inside a Hetzner Private Network and restrict the UFW
     * rule further to that network's CIDR.</p>
     *
     * @param extraAllowedIp public IP of the machine running setup, or blank
     *                       when detection failed (only private ranges allowed)
     */
    private static String buildCloudInitScript(String extraAllowedIp) {
        String extraUfwRule = extraAllowedIp.isBlank() ? "" :
                "ufw allow from " + extraAllowedIp + " to any port 3306 proto tcp\n";
        return "#!/bin/bash\n"
                + "export DEBIAN_FRONTEND=noninteractive\n"
                + "apt-get update -y\n"
                + "apt-get install -y mysql-server ufw\n"
                + "\n"
                + "# ── Firewall (UFW) ──────────────────────────────────────────────\n"
                + "ufw --force reset\n"
                + "ufw default deny incoming\n"
                + "ufw default allow outgoing\n"
                + "ufw allow 22/tcp\n"
                + "ufw allow from 10.0.0.0/8     to any port 3306 proto tcp\n"
                + "ufw allow from 172.16.0.0/12  to any port 3306 proto tcp\n"
                + "ufw allow from 192.168.0.0/16 to any port 3306 proto tcp\n"
                + extraUfwRule
                + "ufw --force enable\n"
                + "\n"
                + "# ── MySQL setup ─────────────────────────────────────────────────\n"
                + "systemctl enable mysql\n"
                + "systemctl start mysql\n"
                + "\n"
                + "DB_PASS=$(openssl rand -hex 32)\n"
                + "\n"
                + "mysql -e \"CREATE DATABASE IF NOT EXISTS cloudnetwork"
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\"\n"
                + "mysql -e \"CREATE USER IF NOT EXISTS 'cloudnetwork'@'%'"
                + " IDENTIFIED BY '$DB_PASS';\"\n"
                + "mysql -e \"GRANT ALL PRIVILEGES ON cloudnetwork.*"
                + " TO 'cloudnetwork'@'%';\"\n"
                + "mysql -e \"FLUSH PRIVILEGES;\"\n"
                + "\n"
                + "# Bind MySQL to all interfaces (UFW restricts external access above)\n"
                + "sed -i 's/bind-address.*=.*/bind-address = 0.0.0.0/'"
                + " /etc/mysql/mysql.conf.d/mysqld.cnf\n"
                + "systemctl restart mysql\n"
                + "\n"
                + "# Write credentials to a file readable only by root.\n"
                + "# NOTE: Retrieve the password via SSH"
                + " (ssh root@<ip> cat /root/db_credentials.txt)\n"
                + "# and then delete the file:"
                + " ssh root@<ip> 'shred -u /root/db_credentials.txt'\n"
                + "echo \"DB_USER=cloudnetwork\"  > /root/db_credentials.txt\n"
                + "echo \"DB_PASS=$DB_PASS\"     >> /root/db_credentials.txt\n"
                + "echo \"DB_NAME=cloudnetwork\" >> /root/db_credentials.txt\n"
                + "chmod 600 /root/db_credentials.txt\n";
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
     * Creates a cloud server in the Hetzner nbg1 datacenter, installs MySQL
     * via cloud-init, and returns a {@link HetznerServer} with the new server's
     * id and public IPv4 address.
     *
     * <p>The public IP of this machine is detected automatically and added to
     * the server's UFW allow-list so that MySQL connections succeed even when
     * the calling host has a public (non-RFC-1918) IP address.  If detection
     * fails, only private address ranges are allowed (as before).</p>
     *
     * @param serverName human-readable name for the server
     * @throws IOException          on HTTP / network errors
     * @throws InterruptedException if the thread is interrupted
     */
    public HetznerServer createServer(String serverName)
            throws IOException, InterruptedException {

        String serverType = findPreferredServerType();
        String localIp    = detectPublicIp();

        JsonObject body = new JsonObject();
        body.addProperty("name",         serverName);
        body.addProperty("server_type",  serverType);
        body.addProperty("image",        "ubuntu-24.04");
        body.addProperty("location",     "nbg1");
        body.addProperty("user_data",    buildCloudInitScript(localIp));
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
                String octet = "(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)";
                if (ip.matches(octet + "\\." + octet + "\\." + octet + "\\." + octet)) {
                    return ip;
                }
            }
        } catch (Exception ignored) {
            // Detection failure is non-fatal; fall back to private-only rules
        }
        return "";
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
