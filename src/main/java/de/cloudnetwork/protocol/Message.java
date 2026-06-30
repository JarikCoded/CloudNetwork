package de.cloudnetwork.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

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

    public static Message register(String workerId, String authToken) {
        JsonObject payload = new JsonObject();
        payload.addProperty("authToken", authToken);
        return new Message(MessageType.REGISTER, workerId, payload.toString(), System.currentTimeMillis());
    }

    public static Message proxyRegister(String gatewayId, String authToken) {
        JsonObject payload = new JsonObject();
        payload.addProperty("authToken", authToken);
        payload.addProperty("role", "proxy_gateway");
        return new Message(MessageType.REGISTER, gatewayId, payload.toString(), System.currentTimeMillis());
    }

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

    public static Message heartbeat(String workerId) {
        return new Message(MessageType.HEARTBEAT, workerId, "{}", System.currentTimeMillis());
    }

    public static Message metrics(String workerId, double cpuPercent, double ramPercent, int playerCount) {
        JsonObject payload = new JsonObject();
        payload.addProperty("cpuPercent", cpuPercent);
        payload.addProperty("ramPercent", ramPercent);
        payload.addProperty("playerCount", playerCount);
        return new Message(MessageType.METRICS, workerId, payload.toString(), System.currentTimeMillis());
    }

    public static Message command(String workerId, String cmd) {
        JsonObject payload = new JsonObject();
        payload.addProperty("command", cmd);
        return new Message(MessageType.COMMAND, workerId, payload.toString(), System.currentTimeMillis());
    }

    public static Message commandResult(String workerId, String result) {
        JsonObject payload = new JsonObject();
        payload.addProperty("result", result);
        return new Message(MessageType.COMMAND_RESULT, workerId, payload.toString(), System.currentTimeMillis());
    }

    public static Message shutdown(String workerId) {
        return new Message(MessageType.SHUTDOWN, workerId, "{}", System.currentTimeMillis());
    }

    /**
     * LOG_LINE: sent by Worker/ProxyGW to the Gateway with a single log line from a
     * managed process or from the component itself.
     *
     * @param workerId   the sender's ID
     * @param source     instance ID (e.g. "velocity-01") or "worker" / "proxy-gateway"
     * @param line       the log line
     */
    public static Message logLine(String workerId, String source, String line) {
        JsonObject payload = new JsonObject();
        payload.addProperty("source", source != null ? source : "worker");
        payload.addProperty("line", line != null ? line : "");
        return new Message(MessageType.LOG_LINE, workerId, payload.toString(), System.currentTimeMillis());
    }

    /** CONSOLE_ATTACH: Gateway asks a Worker to begin streaming stdout for {@code instanceId}. */
    public static Message consoleAttach(String workerId, String instanceId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("instanceId", instanceId != null ? instanceId : "");
        return new Message(MessageType.CONSOLE_ATTACH, workerId, payload.toString(), System.currentTimeMillis());
    }

    /** CONSOLE_DETACH: Gateway asks a Worker to stop the interactive console session. */
    public static Message consoleDetach(String workerId, String instanceId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("instanceId", instanceId != null ? instanceId : "");
        return new Message(MessageType.CONSOLE_DETACH, workerId, payload.toString(), System.currentTimeMillis());
    }

    /** CONSOLE_INPUT: Gateway forwards a stdin line to the specified process on the Worker. */
    public static Message consoleInput(String workerId, String instanceId, String line) {
        JsonObject payload = new JsonObject();
        payload.addProperty("instanceId", instanceId != null ? instanceId : "");
        payload.addProperty("line", line != null ? line : "");
        return new Message(MessageType.CONSOLE_INPUT, workerId, payload.toString(), System.currentTimeMillis());
    }

    /** CONSOLE_OUTPUT: Worker forwards a stdout/stderr line from an attached process to the Gateway. */
    public static Message consoleOutput(String workerId, String instanceId, String line) {
        JsonObject payload = new JsonObject();
        payload.addProperty("instanceId", instanceId != null ? instanceId : "");
        payload.addProperty("line", line != null ? line : "");
        return new Message(MessageType.CONSOLE_OUTPUT, workerId, payload.toString(), System.currentTimeMillis());
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
