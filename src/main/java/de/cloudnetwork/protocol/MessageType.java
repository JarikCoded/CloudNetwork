package de.cloudnetwork.protocol;

/**
 * Active message types for the Gateway ↔ ProxyGateway protocol.
 *
 * <p>Only ProxyGateway instances connect to the Gateway socket server.
 * Worker servers are managed directly via SSH from the master and do not
 * maintain a persistent socket connection.</p>
 *
 * <table>
 *   <caption>Aktive Nachrichtentypen</caption>
 *   <tr><th>Typ</th><th>Richtung</th><th>Beschreibung</th></tr>
 *   <tr><td>REGISTER</td><td>ProxyGateway → Master</td><td>Initiale Registrierung mit Auth-Token</td></tr>
 *   <tr><td>PROXY_UPDATE</td><td>Master → ProxyGateway</td><td>Aktualisierung der Backend-Endpunkte</td></tr>
 *   <tr><td>LOG_LINE</td><td>ProxyGateway → Master</td><td>Log-Zeile des ProxyGateway-Prozesses</td></tr>
 *   <tr><td>CONSOLE_OUTPUT</td><td>ProxyGateway → Master</td><td>Konsolenausgabe für Peer-Session</td></tr>
 * </table>
 */
public enum MessageType {
    /** ProxyGateway → Master: initiale Registrierung (role=proxy_gateway, authToken). */
    REGISTER,
    /** Master → ProxyGateway: aktuelle Liste der verfügbaren Velocity-Endpunkte. */
    PROXY_UPDATE,
    /** ProxyGateway → Master: Log-Zeile des ProxyGateway-Prozesses. */
    LOG_LINE,
    /** ProxyGateway → Master: Konsolenausgabe für Peer-Session. */
    CONSOLE_OUTPUT
}
