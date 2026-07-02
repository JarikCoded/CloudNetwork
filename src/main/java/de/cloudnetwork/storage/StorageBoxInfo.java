package de.cloudnetwork.storage;

/**
 * Immutable snapshot of a Hetzner Storage Box fetched from the Robot API.
 * Disk sizes are stored in megabytes (as returned by the API).
 */
public class StorageBoxInfo {

    private final long   id;
    private final String login;       // e.g. u123456
    private final String host;        // e.g. u123456.your-storagebox.de
    private final String product;     // e.g. BX11
    private final long   diskQuotaMb;
    private final long   diskUsageMb;

    public StorageBoxInfo(long id, String login, String host,
                          String product, long diskQuotaMb, long diskUsageMb) {
        this.id          = id;
        this.login       = login;
        this.host        = host;
        this.product     = product;
        this.diskQuotaMb = diskQuotaMb;
        this.diskUsageMb = diskUsageMb;
    }

    public long   getId()          { return id; }
    public String getLogin()       { return login; }
    public String getHost()        { return host; }
    public String getProduct()     { return product; }
    public long   getDiskQuotaMb() { return diskQuotaMb; }
    public long   getDiskUsageMb() { return diskUsageMb; }

    /** Returns the used percentage (0–100). */
    public double getUsagePercent() {
        if (diskQuotaMb <= 0) return 0.0;
        return (diskUsageMb * 100.0) / diskQuotaMb;
    }
}
