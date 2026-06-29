package de.cloudnetwork.worker;

public class WorkerInfo {
    public enum WorkerStatus {
        PROVISIONING,
        ONLINE,
        OFFLINE,
        DELETED
    }

    private String id;
    private String ipv4;
    private long hetznerServerId;
    private WorkerStatus status = WorkerStatus.PROVISIONING;
    private double cpuPercent;
    private double ramPercent;
    private int playerCount;
    private long lastHeartbeatMs;
    private String authToken;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getIpv4() {
        return ipv4;
    }

    public void setIpv4(String ipv4) {
        this.ipv4 = ipv4;
    }

    public long getHetznerServerId() {
        return hetznerServerId;
    }

    public void setHetznerServerId(long hetznerServerId) {
        this.hetznerServerId = hetznerServerId;
    }

    public WorkerStatus getStatus() {
        return status;
    }

    public void setStatus(WorkerStatus status) {
        this.status = status;
    }

    public double getCpuPercent() {
        return cpuPercent;
    }

    public void setCpuPercent(double cpuPercent) {
        this.cpuPercent = cpuPercent;
    }

    public double getRamPercent() {
        return ramPercent;
    }

    public void setRamPercent(double ramPercent) {
        this.ramPercent = ramPercent;
    }

    public int getPlayerCount() {
        return playerCount;
    }

    public void setPlayerCount(int playerCount) {
        this.playerCount = playerCount;
    }

    public long getLastHeartbeatMs() {
        return lastHeartbeatMs;
    }

    public void setLastHeartbeatMs(long lastHeartbeatMs) {
        this.lastHeartbeatMs = lastHeartbeatMs;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }
}
