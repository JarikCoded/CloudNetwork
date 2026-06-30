package de.cloudnetwork.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Thin wrapper around the Hetzner Robot REST API v1 for Storage Box management.
 *
 * <p>Authentication uses HTTP Basic Auth with Hetzner Robot account credentials
 * (not the Cloud API key).</p>
 *
 * <p>Relevant endpoints used:
 * <ul>
 *   <li>GET  /storagebox        – list all storage boxes</li>
 *   <li>GET  /storagebox/{id}   – details incl. disk usage</li>
 * </ul>
 * </p>
 */
public class HetznerRobotApiClient {

    private static final String BASE_URL = "https://robot.hetzner.com";

    private final String    basicAuth;
    private final HttpClient httpClient;

    public HetznerRobotApiClient(String username, String password) {
        byte[] creds = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
        this.basicAuth  = Base64.getEncoder().encodeToString(creds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Validates the Robot API credentials by performing a lightweight request.
     */
    public boolean validateCredentials() {
        try {
            return get("/storagebox").statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Lists all Storage Boxes accessible with the configured credentials.
     */
    public List<StorageBoxInfo> listStorageBoxes() throws IOException, InterruptedException {
        HttpResponse<String> response = get("/storagebox");
        if (response.statusCode() != 200) {
            throw new IOException("Storage Boxes konnten nicht abgerufen werden (HTTP "
                    + response.statusCode() + "): " + response.body());
        }
        List<StorageBoxInfo> boxes = new ArrayList<>();
        JsonArray arr = JsonParser.parseString(response.body()).getAsJsonArray();
        for (JsonElement element : arr) {
            if (!element.isJsonObject()) continue;
            JsonObject wrapper = element.getAsJsonObject();
            JsonObject obj = wrapper.getAsJsonObject("storagebox");
            if (obj == null) continue;
            boxes.add(parseStorageBox(obj));
        }
        return boxes;
    }

    /**
     * Fetches the current details (including disk usage) for a single Storage Box.
     */
    public StorageBoxInfo getStorageBox(long id) throws IOException, InterruptedException {
        HttpResponse<String> response = get("/storagebox/" + id);
        if (response.statusCode() != 200) {
            throw new IOException("Storage Box konnte nicht abgerufen werden (HTTP "
                    + response.statusCode() + "): " + response.body());
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject obj  = root.getAsJsonObject("storagebox");
        if (obj == null) {
            throw new IOException("Ungültige API-Antwort: 'storagebox'-Schlüssel fehlt.");
        }
        return parseStorageBox(obj);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private StorageBoxInfo parseStorageBox(JsonObject obj) {
        long   id      = readLong(obj, "id");
        String login   = readString(obj, "login");
        String host    = login + ".your-storagebox.de";
        String product = readString(obj, "product");
        long   quota   = readLong(obj, "disk_quota");
        long   usage   = readLong(obj, "disk_usage");
        return new StorageBoxInfo(id, login, host, product, quota, usage);
    }

    private String readString(JsonObject json, String key) {
        JsonElement v = json.get(key);
        return (v == null || v.isJsonNull()) ? "" : v.getAsString();
    }

    private long readLong(JsonObject json, String key) {
        JsonElement v = json.get(key);
        return (v == null || v.isJsonNull()) ? 0L : v.getAsLong();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("Authorization", "Basic " + basicAuth)
                .header("Accept", "application/json")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
