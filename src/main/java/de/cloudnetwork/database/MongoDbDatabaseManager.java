package de.cloudnetwork.database;

import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import de.cloudnetwork.worker.WorkerInfo;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link DatabaseManager} implementation backed by MongoDB.
 *
 * <p>Config values are stored as documents in the {@code cloud_config}
 * collection with the shape {@code { _id: "<key>", value: "<value>" }}.</p>
 *
 * <p>Authentication is optional: when {@code user} is blank the driver
 * connects without credentials using the URI
 * {@code mongodb://host:port/database}. When a user is provided the URI
 * takes the form {@code mongodb://user:password@host:port/database}.</p>
 */
public class MongoDbDatabaseManager implements DatabaseManager {
    private static final String CONFIG_COLLECTION = "cloud_config";
    private static final String WORKERS_COLLECTION = "workers";
    private static final String INSTANCES_COLLECTION = "minecraft_instances";

    private MongoClient mongoClient;
    private MongoDatabase mongoDatabase;
    private boolean connected = false;

    @Override
    public void connect(String host, int port, String database,
                        String user, String password) throws Exception {
        close();
        String timeoutParams = "serverSelectionTimeoutMS=10000&connectTimeoutMS=10000";

        String uri;
        if (user == null || user.isBlank()) {
            uri = "mongodb://" + host + ":" + port + "/" + database + "?" + timeoutParams;
        } else {
            String encodedUser = encodeUriComponent(user);
            String encodedPass = encodeUriComponent(password != null ? password : "");
            uri = "mongodb://" + encodedUser + ":" + encodedPass + "@" + host + ":" + port + "/" + database + "?" + timeoutParams;
        }

        mongoClient = MongoClients.create(uri);
        mongoDatabase = mongoClient.getDatabase(database);
        mongoDatabase.runCommand(new Document("ping", 1));
        connected = true;
    }

    @Override
    public boolean isConnected() {
        if (!connected || mongoClient == null) {
            return false;
        }
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
            } catch (Exception ignored) {
            }
            mongoClient = null;
        }
    }

    @Override
    public void initSchema() {
        ensureCollection(CONFIG_COLLECTION);
        ensureCollection(WORKERS_COLLECTION);
        ensureCollection(INSTANCES_COLLECTION);
    }

    @Override
    public String getConfigValue(String key) {
        Document doc = configCollection().find(Filters.eq("_id", key)).first();
        return doc != null ? doc.getString("value") : null;
    }

    @Override
    public void setConfigValue(String key, String value) {
        Bson filter = Filters.eq("_id", key);
        Bson update = Updates.set("value", value);
        configCollection().updateOne(filter, update, new UpdateOptions().upsert(true));
    }

    @Override
    public void saveWorker(WorkerInfo worker) {
        if (worker == null || worker.getId() == null || worker.getId().isBlank()) {
            throw new IllegalArgumentException("Worker-ID darf nicht leer sein.");
        }
        workerCollection().replaceOne(
                Filters.eq("_id", worker.getId()),
                toDocument(worker),
                new ReplaceOptions().upsert(true)
        );
    }

    @Override
    public WorkerInfo getWorker(String workerId) {
        Document doc = workerCollection().find(Filters.eq("_id", workerId)).first();
        return fromDocument(doc);
    }

    @Override
    public List<WorkerInfo> getAllWorkers() {
        List<WorkerInfo> workers = new ArrayList<>();
        for (Document doc : workerCollection().find()) {
            WorkerInfo worker = fromDocument(doc);
            if (worker != null) {
                workers.add(worker);
            }
        }
        return workers;
    }

    @Override
    public void updateWorkerStatus(String workerId, String status) {
        workerCollection().updateOne(
                Filters.eq("_id", workerId),
                Updates.combine(
                        Updates.set("status", status),
                        Updates.set("lastHeartbeatMs", System.currentTimeMillis())
                ),
                new UpdateOptions().upsert(true)
        );
    }

    public List<Document> getOnlineVelocityInstances() {
        return instanceCollection().find(
                Filters.and(
                        Filters.regex("type", "(?i)velocity"),
                        Filters.eq("status", "ONLINE")
                )
        ).into(new ArrayList<>());
    }

    public List<Document> getMinecraftInstances() {
        return instanceCollection().find().into(new ArrayList<>());
    }

    public Document getMinecraftInstance(String instanceId) {
        Document byId = instanceCollection().find(Filters.eq("_id", instanceId)).first();
        if (byId != null) {
            return byId;
        }
        return instanceCollection().find(Filters.eq("id", instanceId)).first();
    }

    public void upsertMinecraftInstance(Document instance) {
        if (instance == null) {
            return;
        }
        String id = instance.getString("_id");
        if (id == null || id.isBlank()) {
            Object idObject = instance.get("id");
            if (idObject != null) {
                id = String.valueOf(idObject);
                instance.put("_id", id);
            }
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Minecraft-Instanz benötigt eine _id.");
        }
        instanceCollection().replaceOne(
                Filters.eq("_id", id),
                instance,
                new ReplaceOptions().upsert(true)
        );
    }

    public void updateMinecraftInstanceStatus(String instanceId, String status) {
        instanceCollection().updateOne(
                Filters.eq("_id", instanceId),
                Updates.set("status", status),
                new UpdateOptions().upsert(false)
        );
    }

    private void ensureCollection(String collectionName) {
        for (String name : mongoDatabase.listCollectionNames()) {
            if (collectionName.equals(name)) {
                return;
            }
        }
        mongoDatabase.createCollection(collectionName);
    }

    private MongoCollection<Document> configCollection() {
        return mongoDatabase.getCollection(CONFIG_COLLECTION);
    }

    private MongoCollection<Document> workerCollection() {
        return mongoDatabase.getCollection(WORKERS_COLLECTION);
    }

    private MongoCollection<Document> instanceCollection() {
        return mongoDatabase.getCollection(INSTANCES_COLLECTION);
    }

    private Document toDocument(WorkerInfo worker) {
        return new Document("_id", worker.getId())
                .append("ipv4", worker.getIpv4())
                .append("hetznerServerId", worker.getHetznerServerId())
                .append("status", worker.getStatus() != null ? worker.getStatus().name() : null)
                .append("cpuPercent", worker.getCpuPercent())
                .append("ramPercent", worker.getRamPercent())
                .append("playerCount", worker.getPlayerCount())
                .append("lastHeartbeatMs", worker.getLastHeartbeatMs())
                .append("authToken", worker.getAuthToken());
    }

    private WorkerInfo fromDocument(Document doc) {
        if (doc == null) {
            return null;
        }
        WorkerInfo worker = new WorkerInfo();
        worker.setId(doc.getString("_id"));
        worker.setIpv4(doc.getString("ipv4"));
        Object serverId = doc.get("hetznerServerId");
        if (serverId instanceof Number number) {
            worker.setHetznerServerId(number.longValue());
        }
        String status = doc.getString("status");
        if (status != null && !status.isBlank()) {
            try {
                worker.setStatus(WorkerInfo.WorkerStatus.valueOf(status));
            } catch (IllegalArgumentException ignored) {
                worker.setStatus(WorkerInfo.WorkerStatus.OFFLINE);
            }
        }
        Object cpu = doc.get("cpuPercent");
        if (cpu instanceof Number number) {
            worker.setCpuPercent(number.doubleValue());
        }
        Object ram = doc.get("ramPercent");
        if (ram instanceof Number number) {
            worker.setRamPercent(number.doubleValue());
        }
        Object players = doc.get("playerCount");
        if (players instanceof Number number) {
            worker.setPlayerCount(number.intValue());
        }
        Object heartbeat = doc.get("lastHeartbeatMs");
        if (heartbeat instanceof Number number) {
            worker.setLastHeartbeatMs(number.longValue());
        }
        worker.setAuthToken(doc.getString("authToken"));
        return worker;
    }

    private static String encodeUriComponent(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (isUnreserved(c)) {
                sb.append(c);
            } else {
                byte[] bytes = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    sb.append(String.format("%%%02X", b & 0xFF));
                }
            }
        }
        return sb.toString();
    }

    private static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~';
    }
}
