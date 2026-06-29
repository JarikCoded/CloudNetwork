package de.cloudnetwork.database;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * {@link DatabaseManager} implementation backed by MongoDB.
 *
 * <p>Config values are stored as documents in the {@code cloud_config}
 * collection with the shape {@code { _id: "<key>", value: "<value>" }}.</p>
 *
 * <p>Authentication is optional: when {@code user} is blank the driver
 * connects without credentials using the URI
 * {@code mongodb://host:port/database}.  When a user is provided the URI
 * takes the form {@code mongodb://user:password@host:port/database}.</p>
 */
public class MongoDbDatabaseManager implements DatabaseManager {

    /** Name of the collection used to persist config values. */
    private static final String CONFIG_COLLECTION = "cloud_config";

    private MongoClient     mongoClient;
    private MongoDatabase   mongoDatabase;
    private boolean         connected = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Opens a connection to the given MongoDB server.
     *
     * <p>When {@code user} is blank, connects without authentication.
     * Otherwise uses a standard authenticated MongoDB URI with the provided
     * username, password, host, port and database.</p>
     *
     * @throws MongoException on connection errors
     */
    @Override
    public void connect(String host, int port, String database,
                        String user, String password) throws Exception {

        String uri;
        if (user == null || user.isBlank()) {
            uri = "mongodb://" + host + ":" + port + "/" + database;
        } else {
            // URL-encode user and password to handle special characters
            String encodedUser = encodeUriComponent(user);
            String encodedPass = encodeUriComponent(password != null ? password : "");
            uri = "mongodb://" + encodedUser + ":" + encodedPass
                    + "@" + host + ":" + port + "/" + database;
        }

        mongoClient   = MongoClients.create(uri);
        mongoDatabase = mongoClient.getDatabase(database);

        // Ping to verify the connection is actually reachable
        mongoDatabase.runCommand(new Document("ping", 1));
        connected = true;
    }

    /** Returns {@code true} when the connection has been successfully established. */
    @Override
    public boolean isConnected() {
        if (!connected || mongoClient == null) return false;
        try {
            mongoDatabase.runCommand(new Document("ping", 1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        connected = false;
        if (mongoClient != null) {
            try {
                mongoClient.close();
            } catch (Exception ignored) {}
            mongoClient = null;
        }
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    /**
     * Ensures the {@code cloud_config} collection exists by creating it when
     * absent.  MongoDB creates collections lazily on first write, so this is
     * only needed to confirm connectivity and have the collection ready.
     */
    @Override
    public void initSchema() {
        // Create the collection explicitly if it does not yet exist.
        boolean exists = false;
        for (String name : mongoDatabase.listCollectionNames()) {
            if (CONFIG_COLLECTION.equals(name)) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            mongoDatabase.createCollection(CONFIG_COLLECTION);
        }
    }

    // ── Config values ─────────────────────────────────────────────────────────

    /**
     * Retrieves a config value by key, or {@code null} when the key is absent.
     */
    @Override
    public String getConfigValue(String key) {
        Document doc = configCollection()
                .find(Filters.eq("_id", key))
                .first();
        return doc != null ? doc.getString("value") : null;
    }

    /**
     * Inserts or updates a config value (upsert).
     */
    @Override
    public void setConfigValue(String key, String value) {
        Bson filter = Filters.eq("_id", key);
        Bson update = Updates.set("value", value);
        configCollection().updateOne(filter, update,
                new UpdateOptions().upsert(true));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private MongoCollection<Document> configCollection() {
        return mongoDatabase.getCollection(CONFIG_COLLECTION);
    }

    /**
     * Percent-encodes characters that are not allowed unescaped in a MongoDB
     * connection URI userinfo component (RFC 3986).
     */
    private static String encodeUriComponent(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (isUnreserved(c)) {
                sb.append(c);
            } else {
                // percent-encode each byte of the UTF-8 representation
                byte[] bytes = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    sb.append(String.format("%%%02X", b & 0xFF));
                }
            }
        }
        return sb.toString();
    }

    /** RFC 3986 unreserved characters: ALPHA / DIGIT / "-" / "." / "_" / "~" */
    private static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }
}
