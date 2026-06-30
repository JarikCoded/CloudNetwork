package de.cloudnetwork.protocol;

public enum MessageType {
    REGISTER,
    HEARTBEAT,
    METRICS,
    COMMAND,
    COMMAND_RESULT,
    SHUTDOWN,
    PROXY_UPDATE,
    /** Worker/ProxyGW → Gateway: a log line from a managed process or the component itself. */
    LOG_LINE,
    /** Gateway → Worker: attach an interactive console session to a specific instance. */
    CONSOLE_ATTACH,
    /** Gateway → Worker: detach an existing interactive console session. */
    CONSOLE_DETACH,
    /** Gateway → Worker: stdin line to forward to the attached process. */
    CONSOLE_INPUT,
    /** Worker → Gateway: stdout/stderr line from the attached process. */
    CONSOLE_OUTPUT
}
