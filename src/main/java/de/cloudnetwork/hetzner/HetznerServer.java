package de.cloudnetwork.hetzner;

/**
 * Minimal value object returned after a Hetzner server has been created.
 */
public class HetznerServer {

    private final long   id;
    private final String ipv4;
    private final String privateIpv4;

    public HetznerServer(long id, String ipv4) {
        this(id, ipv4, "");
    }

    public HetznerServer(long id, String ipv4, String privateIpv4) {
        this.id = id;
        this.ipv4 = ipv4;
        this.privateIpv4 = privateIpv4;
    }

    public long getId() { return id; }
    public String getIpv4() { return ipv4; }
    public String getPrivateIpv4() { return privateIpv4; }
}
