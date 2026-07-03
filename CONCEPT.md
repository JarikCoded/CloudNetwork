# CloudNetwork – Konzept

## Ziel

CloudNetwork ist ein automatisiertes Verwaltungssystem für Minecraft-Netzwerke auf der Hetzner Cloud. Es provisioniert, skaliert und überwacht Minecraft-Worker-Server sowie die gesamte Infrastruktur vollständig selbstständig – ohne manuellen Eingriff nach dem Erststart.

---

## Rollen und Komponenten

| Rolle | Beschreibung |
|---|---|
| **Master** | Zentraler Steuerungsknoten; läuft auf dem Host, auf dem `CloudNetwork-1.0.0.jar` gestartet wird. Koordiniert alle anderen Komponenten, verwaltet die Datenbank und stellt das Gateway bereit. |
| **Worker** | Minecraft-Game-Server, die automatisch auf privaten Hetzner-Cloud-Servern provisioniert werden. Melden Metriken ans Gateway und empfangen Steuerbefehle. |
| **ProxyGateway / ClientGateway** | Öffentlicher Einstiegspunkt für Minecraft-Spieler (Velocity-basiert). Einziger Server mit öffentlicher IP; leitet Verbindungen an Worker weiter. |
| **Datenbank (MongoDB)** | Speichert alle Konfigurationen, Worker-Zustände und Credentials. Nur über das interne Hetzner-Privatnetz (und WireGuard VPN) erreichbar. |
| **Storage Box** | Hetzner Storage Box als zentrales Dateisystem für Templates, Static-Files, JARs und Backups. Wird per SFTP angebunden. |

---

## Netzwerktopologie

```
Internet
   │
   ▼
ProxyGateway (öffentliche IP, Port 25565)
   │  Velocity-TCP-Relay
   ▼
Worker-01 / Worker-02 / ... (nur Hetzner-Privatnetz, keine öffentliche IP)
   │  Socket (mTLS, Port 9876)
   ▼
Master / GatewaySocketServer (öffentliche IP + Privatnetz-IP)
   │  MongoDB
   ▼
Datenbank-Server (nur Privatnetz, kein öffentliches Interface)
```

- Worker und Datenbank werden **ohne öffentliche IPv4/IPv6** angelegt.
- Nur Master und ProxyGateway sind öffentlich erreichbar.
- Datenbankserver ist zusätzlich über **WireGuard VPN** gesichert.
- Für ausgehenden Traffic der privaten Server (z. B. JAR-Downloads via Cloud-Init) ist ein **NAT/Egress-Pfad** über den Master oder einen separaten Gateway-Host erforderlich.

---

## Kommunikationsprotokoll (Gateway-Socket)

Der Master betreibt einen **TCP-Socket-Server** (Standard-Port `9876`), über den alle Worker kommunizieren.

**Verbindungsablauf:**
1. Worker stellt TLS-Verbindung her (mTLS – gegenseitige Zertifikatsprüfung).
2. Worker sendet `REGISTER` mit Auth-Token zur Authentifizierung.
3. Nach erfolgreicher Registrierung sendet der Worker alle **10 Sekunden** `HEARTBEAT` + `METRICS`.
4. Master kann jederzeit `COMMAND`-Nachrichten senden (z. B. `SCALING_CHECK`, `SHUTDOWN`).

**Nachrichtentypen:**

| Typ | Richtung | Beschreibung |
|---|---|---|
| `REGISTER` | Worker → Master | Initiale Registrierung mit Auth-Token |
| `HEARTBEAT` | Worker → Master | Lebenszeichen alle 10 Sekunden |
| `METRICS` | Worker → Master | CPU/RAM-Auslastung, Spieleranzahl |
| `COMMAND` | Master → Worker | Steuerbefehl (z. B. `SCALING_CHECK`, `SHUTDOWN`) |
| `COMMAND_RESULT` | Worker → Master | Antwort auf einen Steuerbefehl |
| `SHUTDOWN` | Master → Worker | Graceful-Shutdown-Anweisung |
| `PROXY_UPDATE` | Master → ProxyGateway | Aktualisierung der Backend-Endpunkte |
| `LOG_LINE` | Worker → Master | Log-Zeile eines verwalteten Prozesses |
| `CONSOLE_ATTACH` | Master → Worker | Interaktive Konsole für einen Prozess starten |
| `CONSOLE_DETACH` | Master → Worker | Interaktive Konsole beenden |
| `CONSOLE_INPUT` | Master → Worker | Stdin-Eingabe für einen Prozess |
| `CONSOLE_OUTPUT` | Worker → Master | Stdout/Stderr aus einem Prozess |

---

## Sicherheit

### mTLS (Mutual TLS)
- Alle Worker↔Gateway- und ProxyGateway↔Gateway-Verbindungen sind **gegenseitig TLS-authentifiziert**.
- Der `TlsManager` erzeugt beim ersten Start eine eigene **CA** sowie Server- und Client-Zertifikate.
- Zertifikate werden als Datenbank-Keys gespeichert (`tls_ca_cert`, `tls_server_cert`, `tls_client_cert` + `_key`).
- Konsolenbefehle: `tls status` / `tls renew`

### WireGuard VPN
- Der Datenbankserver wird automatisch mit WireGuard abgesichert.
- MongoDB und mongo-express sind **ausschließlich über das interne Netz bzw. WireGuard** erreichbar.
- Die vollständige Client-Konfiguration (Key/Endpoint) wird nach dem Setup in der Konsole ausgegeben.
- Das WireGuard-CIDR kann über `CLOUDNETWORK_WIREGUARD_CIDR` konfiguriert werden.

### Netzwerk-Firewall
- Worker-Firewall erlaubt Minecraft-Backend-Traffic nur aus privaten Netzen.
- ProxyGateway nutzt `ufw limit 25565/tcp` und aktiviert `fail2ban`.
- SSH-Zugriff kann über `CLOUDNETWORK_WIREGUARD_CIDR` auf VPN-Adressen begrenzt werden.
- Auth-Tokens für Worker werden kryptografisch zufällig generiert (48-stelliger Hex-String).

---

## Autoscaling

Der `ScalingMonitor` prüft alle **15 Sekunden** die durchschnittliche Auslastung aller Online-Worker.

**Auslastungsberechnung:** Durchschnitt aus CPU-% und RAM-% aller Worker, geglättet über ein konfigurierbares Zeitfenster.

**Scale-Up-Logik:**
- Auslöser: Geglättete Last > `scaling_high_load_threshold` (Standard: 80 %) für **4 Minuten** ununterbrochen.
- Aktion: Neuer Hetzner-Worker-Server wird provisioniert und im privaten Netz gestartet.
- Worker-Namen: `CloudNetwork-Worker-XX` (sequenziell, persistent in der DB).

**Scale-Down-Logik:**
- Auslöser: Geglättete Last < `scaling_low_load_threshold` (Standard: 40 %) für **15 Minuten** ununterbrochen.
- Bedingung: Mindestens 2 Online-Worker, Kandidat hat keine aktiven Spieler.
- Aktion: `SHUTDOWN`-Befehl an den Worker, anschließend Hetzner-Server-Löschung.

**Konfigurierbare DB-Keys:**

| Key | Standard | Beschreibung |
|---|---|---|
| `scaling_high_load_threshold` | `80.0` | Auslastung ab der Scale-Up ausgelöst wird (%) |
| `scaling_low_load_threshold` | `40.0` | Auslastung unter der Scale-Down ausgelöst wird (%) |
| `scaling_target_min` | `60.0` | Unteres Ziel-Lastniveau (%) |
| `scaling_target_max` | `70.0` | Oberes Ziel-Lastniveau (%) |
| `scaling_window_minutes` | `4` | Glättungsfenster in Minuten (1–10) |

Konsolenbefehl: `scale set <high> <low> <targetMin> <targetMax> <windowMinutes>`

---

## Erststart und Setup-Wizard

Beim ersten Start (keine `CloudConfig.json` vorhanden) startet automatisch der interaktive Setup-Wizard. Er kann auch vollständig nicht-interaktiv über Umgebungsvariablen gesteuert werden.

**Wizard-Schritte (interaktiv):**
1. Hetzner API-Key eingeben
2. Bestehendes Hetzner-Privatnetz wählen oder neues erstellen
3. Interne und öffentliche Master-Adresse festlegen
4. Optionale Storage-Box-Zugangsdaten
5. Lobby + Proxy initial vorbereiten (ja/nein)
6. JAR-URLs und Anzeigenamen für Proxy/Lobby

**Provisionierte Ressourcen:**
- Privates MongoDB-Datenbank-Netz (inkl. mongo-express, WireGuard)
- Erster privater Worker-Server
- Öffentlicher ProxyGateway-Server
- Optional: `velocity-01` + `lobby-01` für automatischen Erststart

**Nicht-interaktiv (Umgebungsvariablen):**
```bash
CLOUDNETWORK_AUTO_SETUP=true
HETZNER_API_KEY=...
CLOUDNETWORK_HETZNER_NETWORK_ID=...
CLOUDNETWORK_GATEWAY_PRIVATE_HOST=10.10.0.2
CLOUDNETWORK_GATEWAY_PUBLIC_HOST=master.example.com
CLOUDNETWORK_WORKER_JAR_URL=https://...
CLOUDNETWORK_PROXY_GATEWAY_JAR_URL=https://...
```

---

## Datenbank

Unterstützte Backends:
- **MongoDB** (Standard, empfohlen)
- MySQL/MariaDB

Die Datenbank speichert:
- Alle Konfigurations-Key-Value-Paare (`gateway_port`, `hetzner_api_key`, TLS-Zertifikate, Scaling-Einstellungen, …)
- Worker-Zustände (`id`, `authToken`, `status`, `ipv4`, `hetznerServerId`, `playerCount`, `cpuPercent`, `ramPercent`, …)
- Storage-Box-Zugangsdaten

---

## Storage Box

Die Hetzner Storage Box dient als zentrales Dateisystem für alle Worker und den Master.

**Verzeichnisstruktur:**
```
CloudNetwork/
├── Templates/    # Server-Templates (Konfiguration, Plugins, Mods)
├── Static/       # Statische Dateien (global für alle Worker)
├── Jars/
│   ├── minecraft/  # Minecraft-Server-JARs
│   ├── velocity/   # Velocity-Proxy-JARs
│   └── plugins/    # Plugin-JARs
└── Backups/      # Automatische Server-Backups
```

Konsolenbefehle: `storagebox setup`, `storagebox status`

---

## Build und Start

```bash
# Bauen
mvn package -q

# Starten (im Vordergrund)
java -jar target/CloudNetwork-1.0.0.jar

# Über start.sh (empfohlen, startet in screen-Session)
./start.sh start
./start.sh status
./start.sh attach
./start.sh stop
```

`start.sh` installiert automatisch OpenJDK (≥ 17), `screen` und die JAR, falls noch nicht vorhanden.

**Erzeugte JARs:**
- `CloudNetwork-1.0.0.jar` – Master-Prozess
- `worker-1.0.0.jar` – Worker-Agent (läuft auf Hetzner-Worker-Servern)
- `proxy-gateway-1.0.0.jar` – ProxyGateway-Prozess

---

## Wichtige Konsolen-Befehle

| Befehl | Beschreibung |
|---|---|
| `workers` | Liste aller Worker anzeigen |
| `worker add` | Worker manuell provisionieren |
| `worker remove <id>` | Worker entfernen |
| `scale set ...` | Autoscaling-Schwellwerte setzen |
| `scale status` | Autoscaling-Status anzeigen |
| `tls status` | TLS-Zertifikatsstatus anzeigen |
| `tls renew` | Zertifikate erneuern |
| `storagebox setup` | Storage Box einrichten |
| `storagebox status` | Storage-Box-Status anzeigen |
| `jar set worker <url>` | Worker-JAR-URL setzen |
| `help` | Alle Befehle anzeigen |
