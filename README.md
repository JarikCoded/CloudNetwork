# CloudNetwork

## Vollautomatischer Erststart

`start.sh start` startet weiterhin nur die Master-JAR. Wenn noch keine `CloudConfig.json` existiert, startet beim ersten Lauf automatisch die Hetzner-Erstkonfiguration. Interaktiv wird nur der Hetzner-API-Key abgefragt; danach werden Datenbank, Netzwerk, Worker und ProxyGateway automatisch eingerichtet.

### Unterstützte Variablen

- `CLOUDNETWORK_AUTO_SETUP=true` oder `CLOUDNETWORK_SETUP_MODE=automatisch` (optional, ist beim Erststart bereits Standard)
- `HETZNER_API_KEY` oder `CLOUDNETWORK_HETZNER_API_KEY`
- optional `CLOUDNETWORK_GATEWAY_HOST`
- optional `CLOUDNETWORK_GATEWAY_PORT` (Standard `9876`)
- optional `CLOUDNETWORK_WORKER_JAR_URL`
- optional `CLOUDNETWORK_PROXY_GATEWAY_JAR_URL`
- optional für Private-Netz-Architektur:
  - `CLOUDNETWORK_HETZNER_NETWORK_ID` (Hetzner Private-Network-ID für DB/Worker/ProxyGateway)
  - `CLOUDNETWORK_WORKER_PRIVATE_ONLY=true` (deaktiviert Public IPv4/IPv6 auf Worker-Servern)
  - `CLOUDNETWORK_WIREGUARD_CIDR` (z. B. `10.200.0.0/24`, limitiert SSH auf dieses VPN-Netz)
  - `CLOUDNETWORK_PRIVATE_NETWORK_CIDR` (zusätzliche private CIDR für Backend-Portfreigaben)
- optional Storage Box:
  - `CLOUDNETWORK_STORAGEBOX_HOST`
  - `CLOUDNETWORK_STORAGEBOX_USER`
  - `CLOUDNETWORK_STORAGEBOX_PASS`
  - `CLOUDNETWORK_STORAGEBOX_ROBOT_USER`
  - `CLOUDNETWORK_STORAGEBOX_ROBOT_PASS`

### Beispiel

```bash
export CLOUDNETWORK_AUTO_SETUP=true
export HETZNER_API_KEY=...
export CLOUDNETWORK_HETZNER_NETWORK_ID=123456
export CLOUDNETWORK_WORKER_PRIVATE_ONLY=true
export CLOUDNETWORK_WIREGUARD_CIDR=10.200.0.0/24
export CLOUDNETWORK_PRIVATE_NETWORK_CIDR=10.0.0.0/16
export CLOUDNETWORK_WORKER_JAR_URL=https://example.invalid/worker-1.0.0.jar
export CLOUDNETWORK_PROXY_GATEWAY_JAR_URL=https://example.invalid/proxy-gateway-1.0.0.jar
./start.sh start
```

Beim ersten Start werden damit automatisch MongoDB, der erste Worker, das Proxy-Gateway und optional die Storage-Box-Konfiguration erzeugt. Worker und Proxy-Gateway laden ihre JARs selbstständig per Cloud-Init und starten ohne manuelles Hochladen, sobald die jeweilige URL erreichbar ist.

Zusätzlich wird für den Datenbankserver automatisch WireGuard vorbereitet. MongoDB und mongo-express sind dann nur über das WireGuard-Netz erreichbar. Die vollständige Client-Konfiguration (inkl. Key/Endpoint) wird nach dem Setup direkt in der Konsole ausgegeben.

## Netzwerk-Härtung (WireGuard + Gateway)

- Worker-Firewall erlaubt Minecraft-Backend-Traffic nur aus privaten Netzen (und optional `CLOUDNETWORK_WIREGUARD_CIDR`), nicht mehr global aus dem Internet.
- SSH kann über `CLOUDNETWORK_WIREGUARD_CIDR` auf VPN-Zugriffe begrenzt werden.
- ProxyGateway nutzt `ufw limit 25565/tcp` und aktiviert `fail2ban`.
- Für ein vollständig isoliertes Backend (`CLOUDNETWORK_WORKER_PRIVATE_ONLY=true`) muss dein Routing/NAT im privaten Netz bereits vorhanden sein (z. B. über einen WireGuard-/Gateway-Host).