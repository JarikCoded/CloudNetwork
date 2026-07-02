package de.cloudnetwork.protocol;

/**
 * Represents a reachable Velocity proxy endpoint.
 *
 * @param instanceId the CloudNetwork instance ID (e.g. "velocity-01")
 * @param host       the public IPv4 address of the worker running this proxy
 * @param port       the TCP port Velocity is bound to (typically 25565)
 */
public record ProxyEndpoint(String instanceId, String host, int port) {
}
