# CloudNetwork

## Vollautomatischer Erststart

`start.sh start` startet weiterhin nur die Master-JAR. Wenn noch keine `CloudConfig.json` existiert, startet beim ersten Lauf automatisch die Hetzner-Erstkonfiguration.

Das Setup ist jetzt auf eine feste Rollenaufteilung ausgelegt:

- **Master**: läuft auf dem Host, auf dem du `CloudNetwork` startest; dieser Host benötigt die externe Erreichbarkeit für Updates/Steuerung.
- **ClientGateway / ProxyGateway**: öffentlicher Einstiegspunkt für Minecraft-Spieler.
- **Datenbank**: läuft ausschließlich im Hetzner-Privatnetz.
- **Worker**: laufen ausschließlich im Hetzner-Privatnetz.

Interaktiv fragt der Wizard jetzt nacheinander:

1. Hetzner-API-Key
2. bestehende Netzwerk-ID oder neues privates Hetzner-Netzwerk
3. interne und öffentliche Master-Adresse
4. optionale Storage-Box-Zugangsdaten
5. ob Lobby + Proxy initial vorbereitet werden sollen
6. optionale JAR-URLs und Anzeigenamen für Proxy/Lobby

Anschließend werden Datenbank, Worker und ClientGateway passend zur Private-Network-Architektur provisioniert.

> Wichtig: Für private-only Datenbank- und Worker-Server muss bereits ein **Egress-/NAT-Pfad über den Master oder einen separaten Gateway-Host** existieren, damit Cloud-Init Pakete und JARs herunterladen kann.

### Unterstützte Variablen

- `CLOUDNETWORK_AUTO_SETUP=true` oder `CLOUDNETWORK_SETUP_MODE=automatisch` (optional, ist beim Erststart bereits Standard)
- `HETZNER_API_KEY` oder `CLOUDNETWORK_HETZNER_API_KEY`
- optional `CLOUDNETWORK_GATEWAY_PRIVATE_HOST` (interne Master-Adresse für Worker/ProxyGateway)
- optional `CLOUDNETWORK_GATEWAY_PUBLIC_HOST` (öffentliche Master-Adresse)
- optional `CLOUDNETWORK_GATEWAY_PORT` (Standard `9876`)
- optional `CLOUDNETWORK_HETZNER_NETWORK_ID` (bestehendes Hetzner Private Network)
- optional `CLOUDNETWORK_HETZNER_NETWORK_NAME` (wenn kein bestehendes Netz verwendet wird)
- optional `CLOUDNETWORK_PRIVATE_NETWORK_CIDR` oder `CLOUDNETWORK_HETZNER_NETWORK_CIDR` (Standard `10.10.0.0/16`)
- optional `CLOUDNETWORK_WORKER_JAR_URL`
- optional `CLOUDNETWORK_PROXY_GATEWAY_JAR_URL`
- optional für Lobby/Proxy-Seeding:
  - `CLOUDNETWORK_INSTALL_LOBBY_PROXY=true|false`
  - `CLOUDNETWORK_VELOCITY_JAR_URL`
  - `CLOUDNETWORK_LOBBY_JAR_URL`
  - `CLOUDNETWORK_VELOCITY_NAME`
  - `CLOUDNETWORK_LOBBY_NAME`
- optional für Private-Netz-/WireGuard-Regeln:
  - `CLOUDNETWORK_WORKER_PRIVATE_ONLY=true` (Kompatibilitätsflag für ältere Worker-Provisionierung)
  - `CLOUDNETWORK_WIREGUARD_CIDR` (z. B. `10.200.0.0/24`, limitiert SSH auf dieses VPN-Netz)
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
export CLOUDNETWORK_GATEWAY_PRIVATE_HOST=10.10.0.2
export CLOUDNETWORK_GATEWAY_PUBLIC_HOST=master.example.com
export CLOUDNETWORK_WIREGUARD_CIDR=10.200.0.0/24
export CLOUDNETWORK_WORKER_JAR_URL=https://example.invalid/worker-1.0.0.jar
export CLOUDNETWORK_PROXY_GATEWAY_JAR_URL=https://example.invalid/proxy-gateway-1.0.0.jar
export CLOUDNETWORK_INSTALL_LOBBY_PROXY=true
export CLOUDNETWORK_VELOCITY_JAR_URL=https://example.invalid/velocity.jar
export CLOUDNETWORK_LOBBY_JAR_URL=https://example.invalid/paper.jar
./start.sh start
```

Beim ersten Start werden damit automatisch das Hetzner-Privatnetz, die private MongoDB, der erste private Worker, das öffentliche ProxyGateway und optional die Storage-Box-Konfiguration erzeugt. Worker und Proxy-Gateway laden ihre JARs selbstständig per Cloud-Init und starten ohne manuelles Hochladen, sobald die jeweilige URL erreichbar ist.

Wenn Lobby + Proxy aktiviert wurden, legt das Setup zusätzlich `velocity-01` und `lobby-01` mit den gewünschten Anzeigenamen an und markiert sie für den Auto-Start, sobald der erste Worker online ist. Erst dann wird der öffentliche ClientGateway-Einstieg als spielbereit ausgegeben.

Zusätzlich wird für den Datenbankserver automatisch WireGuard vorbereitet. MongoDB und mongo-express sind nur über das interne Netz bzw. WireGuard erreichbar. Die vollständige Client-Konfiguration (inkl. Key/Endpoint) wird nach dem Setup direkt in der Konsole ausgegeben.

## Netzwerk-Härtung (WireGuard + Gateway)

- Worker-Firewall erlaubt Minecraft-Backend-Traffic nur aus privaten Netzen (und optional `CLOUDNETWORK_WIREGUARD_CIDR`), nicht mehr global aus dem Internet.
- Datenbank und Worker werden ohne Public IPv4/IPv6 angelegt; nur Master und ProxyGateway bleiben öffentlich.
- SSH kann über `CLOUDNETWORK_WIREGUARD_CIDR` auf VPN-Zugriffe begrenzt werden.
- ProxyGateway nutzt `ufw limit 25565/tcp` und aktiviert `fail2ban`.
- Für ein vollständig isoliertes Backend (`CLOUDNETWORK_WORKER_PRIVATE_ONLY=true`) muss dein Routing/NAT im privaten Netz bereits vorhanden sein (z. B. über einen WireGuard-/Gateway-Host).