package de.cloudnetwork.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * JSON-Hülle für das interne Gateway ↔ ProxyGateway-Protokoll.
 *
 * <p>Aktive Nachrichtentypen: {@link MessageType#REGISTER}, {@link MessageType#PROXY_UPDATE},
 * {@link MessageType#LOG_LINE}, {@link MessageType#CONSOLE_OUTPUT}.</p>
 */
public class Message {
    private static final Gson GSON = new Gson();

    private MessageType type;
    private String workerId;
    private String payload;
    private long timestamp;

    public Message() {
    }

    public Message(MessageType type, String workerId, String payload, long timestamp) {
        this.type = type;
        this.workerId = workerId;
        this.payload = payload;
        this.timestamp = timestamp;
    }

    /**
     * REGISTER: sent by a ProxyGateway to authenticate with the Gateway.
     *
     * @param gatewayId  the ProxyGateway's unique ID
     * @param authToken  the shared auth token (DB key: proxy_gateway_auth_token)
     */
    public static Message proxyRegister(String gatewayId, String authToken) {
        JsonObject payload = new JsonObject();
        payload.addProperty("authToken", authToken);
        payload.addProperty("role", "proxy_gateway");
        return new Message(MessageType.REGISTER, gatewayId, payload.toString(), System.currentTimeMillis());
    }

    /**
     * PROXY_UPDATE: sent by the Gateway to all registered ProxyGateways.
     *
     * @param gatewayId  the Gateway's ID (usually "gateway")
     * @param proxies    current list of reachable Velocity endpoints
     */
    public static Message proxyUpdate(String gatewayId, List<ProxyEndpoint> proxies) {
        JsonArray array = new JsonArray();
        for (ProxyEndpoint p : proxies) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", p.instanceId());
            obj.addProperty("host", p.host());
            obj.addProperty("port", p.port());
            array.add(obj);
        }
        JsonObject payload = new JsonObject();
        payload.add("proxies", array);
        return new Message(MessageType.PROXY_UPDATE, gatewayId, payload.toString(), System.currentTimeMillis());
    }

    /**
     * LOG_LINE: sent by a ProxyGateway to the Gateway with a single log line.
     *
     * @param gatewayId  the sender's ID
     * @param source     "proxy-gateway" or a specific component label
     * @param line       the log line
     */
    public static Message logLine(String gatewayId, String source, String line) {
        JsonObject payload = new JsonObject();
        payload.addProperty("source", source != null ? source : "proxy-gateway");
        payload.addProperty("line", line != null ? line : "");
        return new Message(MessageType.LOG_LINE, gatewayId, payload.toString(), System.currentTimeMillis());
    }

    /**
     * CONSOLE_OUTPUT: sent by a ProxyGateway to forward a console line to a peer session.
     *
     * @param gatewayId   the sender's ID
     * @param instanceId  the target instance ID
     * @param line        the console output line
     */
    public static Message consoleOutput(String gatewayId, String instanceId, String line) {
        JsonObject payload = new JsonObject();
        payload.addProperty("instanceId", instanceId != null ? instanceId : "");
        payload.addProperty("line", line != null ? line : "");
        return new Message(MessageType.CONSOLE_OUTPUT, gatewayId, payload.toString(), System.currentTimeMillis());
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static Message fromJson(String json) {
        return GSON.fromJson(json, Message.class);
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
