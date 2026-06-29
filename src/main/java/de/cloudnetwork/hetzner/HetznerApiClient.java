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

/**
 * Thin wrapper around the Hetzner Cloud REST API v1.
 *
 * <p>All network I/O uses the JDK 11+ built-in {@link HttpClient} so there are
 * no extra HTTP-library dependencies.</p>
 */
public class HetznerApiClient {

    private static final String BASE_URL = "https://api.hetzner.cloud/v1";

    /**
     * Cloud-init script that installs MySQL, secures it, and creates a
     * dedicated {@code cloudnetwork} user + database on first boot.
     *
     * <p><b>Note:</b> The automatic setup mode always uses MySQL because this
     * script is what provisions the database server.  When using an existing
     * server, MongoDB can be selected instead via the manual setup.</p>
     *
     * <p>Security measures applied by cloud-init:</p>
     * <ul>
     *   <li>UFW is configured to allow SSH (22/tcp) from everywhere and
     *       MySQL (3306/tcp) only from RFC-1918 private address ranges
     *       (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16).  All other
     *       inbound connections are denied by default.</li>
     *   <li>MySQL binds to 0.0.0.0 so it is reachable inside the private
     *       network, but the firewall prevents public access.</li>
     * </ul>
     *
     * <p>For production deployments it is strongly recommended to place the
     * database server inside a Hetzner Private Network and restrict the UFW
     * rule further to that network's CIDR.</p>
     */
    private static final String CLOUD_INIT_SCRIPT = """
            #!/bin/bash
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -y
            apt-get install -y mysql-server ufw

            # ── Firewall (UFW) ──────────────────────────────────────────────
            ufw --force reset
            ufw default deny incoming
            ufw default allow outgoing
            ufw allow 22/tcp                    # SSH
            ufw allow from 10.0.0.0/8     to any port 3306 proto tcp
            ufw allow from 172.16.0.0/12  to any port 3306 proto tcp
            ufw allow from 192.168.0.0/16 to any port 3306 proto tcp
            ufw --force enable

            # ── MySQL setup ─────────────────────────────────────────────────
            systemctl enable mysql
            systemctl start mysql

            DB_PASS=$(openssl rand -hex 32)

            mysql -e "CREATE DATABASE IF NOT EXISTS cloudnetwork CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
            mysql -e "CREATE USER IF NOT EXISTS 'cloudnetwork'@'%' IDENTIFIED BY '$DB_PASS';"
            mysql -e "GRANT ALL PRIVILEGES ON cloudnetwork.* TO 'cloudnetwork'@'%';"
            mysql -e "FLUSH PRIVILEGES;"

            # Bind MySQL to all interfaces (UFW restricts external access above)
            sed -i 's/bind-address.*=.*/bind-address = 0.0.0.0/' /etc/mysql/mysql.conf.d/mysqld.cnf
            systemctl restart mysql

            # Write credentials to a file readable only by root.
            # NOTE: Retrieve the password via SSH (ssh root@<ip> cat /root/db_credentials.txt)
            # and then delete the file: ssh root@<ip> 'shred -u /root/db_credentials.txt'
            echo "DB_USER=cloudnetwork"  > /root/db_credentials.txt
            echo "DB_PASS=$DB_PASS"     >> /root/db_credentials.txt
            echo "DB_NAME=cloudnetwork" >> /root/db_credentials.txt
            chmod 600 /root/db_credentials.txt
            """;

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
     * Creates a CX22 cloud server in the Hetzner nbg1 datacenter, installs
     * MySQL via cloud-init, and returns a {@link HetznerServer} with the new
     * server's id and public IPv4 address.
     *
     * @param serverName human-readable name for the server
     * @throws IOException          on HTTP / network errors
     * @throws InterruptedException if the thread is interrupted
     */
    public HetznerServer createServer(String serverName)
            throws IOException, InterruptedException {

        JsonObject body = new JsonObject();
        body.addProperty("name",         serverName);
        body.addProperty("server_type",  "cx22");
        body.addProperty("image",        "ubuntu-24.04");
        body.addProperty("location",     "nbg1");
        body.addProperty("user_data",    CLOUD_INIT_SCRIPT);
        body.addProperty("start_after_create", true);

        HttpResponse<String> response = post("/servers", body.toString());

        if (response.statusCode() != 201) {
            throw new IOException("Server konnte nicht erstellt werden (HTTP "
                    + response.statusCode() + "): " + response.body());
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
