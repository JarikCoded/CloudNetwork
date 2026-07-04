# CloudNetwork – Konzept

## Überblick

CloudNetwork ist ein Java-basiertes Master-/Gateway-System für ein Minecraft-Cloudnetzwerk auf Hetzner. Es provisioniert, skaliert und überwacht Minecraft-Worker-Server sowie die gesamte Infrastruktur vollständig selbstständig – ohne manuellen Eingriff nach dem Erststart.

Der Master verwaltet Worker-Server **direkt per SSH/SFTP** – ohne separaten Worker-Agent-Prozess. Minecraft-Instanzen werden auf den Worker-Servern über SSH gestartet, gestoppt und überwacht.

---

## Rollen und Komponenten

| Rolle | Beschreibung |
|---|---|
| **Master / Hauptprogramm** | Startet lokal auf dem Hauptserver. Verwaltet Datenbank, Worker (via SSH), ProxyGateway, Autoscaling, TLS und Konsole. |
| **Worker** | Einfache Hetzner-Server mit Java + SSH. Kein Worker-Agent-Prozess. Der Master verbindet sich via SSH und verwaltet Minecraft-Instanzen direkt. |
| **ProxyGateway** | Öffentlicher Einstiegspunkt für Minecraft-Clients. Nimmt Client-Verbindungen auf Port 25565 an und leitet sie per Round-Robin an verfügbare Velocity-Proxys weiter. Verbindet sich per Socket (mTLS) zum Master-Gateway. |
| **Datenbank-/Setup-Infrastruktur** | Automatische Hetzner-Provisionierung, MongoDB + mongo-express, verpflichtendes WireGuard für externen Zugriff auf das Privatnetz, optionale Storage Box. |

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
   │  SSH (Port 22, RSA 4096-Bit, Master→Worker)
   ▼
Master / GatewaySocketServer (öffentliche IP + Privatnetz-IP)
   │  MongoDB
   ▼
Datenbank-Server (nur Privatnetz, kein öffentliches Interface)
```

- Worker und Datenbank werden **ohne öffentliche IPv4/IPv6** angelegt.
- Nur Master und ProxyGateway sind öffentlich erreichbar.
- Für externen Zugriff auf Server im Hetzner-Privatnetz ist **WireGuard VPN verpflichtend** eingerichtet.
- Der Master kommuniziert mit Worker-Servern ausschließlich via **SSH** (kein Worker-Agent-Prozess).
- ProxyGateway verbindet sich per **mTLS-Socket** zum Master-Gateway-Port 9876.

---

## Ordnerstruktur auf Worker-Servern

```
/home/cloudnetwork/
├── instances/       # Pro Instanz ein Unterordner: <instanceId>/
│   └── <id>/
│       ├── server.jar   # Minecraft/Velocity-JAR
│       ├── eula.txt
│       ├── pid          # PID des laufenden Prozesses
│       └── stdout.log   # Ausgabe (screen-Session)
├── jars/            # Gemeinsam genutzte JARs
├── templates/       # Serverkonfiguration-Templates
└── backups/         # Sicherungen
```

Der Master erstellt und befüllt diese Struktur per SSH/SFTP.

---

## Hauptaufbau nach Klassen und Paketen

### `de.cloudnetwork.Main`

Startpunkt des Hauptprogramms. Ablauf:

1. Banner ausgeben
2. `CloudConfig.json` laden
3. Wenn nicht vorhanden: `SetupWizard` starten
4. Datenbank verbinden
5. `gateway_port` lesen/speichern
6. `gateway_host` sicherstellen (automatische Erkennung der öffentlichen IP)
7. Hetzner API Key aus DB lesen
8. Storage Box initialisieren
9. Bekannte Worker aus DB ins RAM-Register laden
10. TLS-Zertifikate erzeugen/laden
11. `GatewaySocketServer` starten (nur noch für ProxyGateway-Sessions)
12. **SSH-Schlüsselpaar erzeugen/laden** (`SshManager.ensureKeysExist`)
13. **`InstanceManager` erstellen** (Instanz-Lifecycle, Konfiggenerierung)
14. `ScalingMonitor` starten (SSH-basierte Metriken + Instanzstart auf neuen Workern)
15. **`InstanceMonitor` starten** (periodische PID-Prüfung, Auto-Restart)
16. `ConsoleHandler` starten (interaktive Konsole)

---

### `de.cloudnetwork.setup.SetupWizard`

Startet, wenn keine `CloudConfig.json` existiert. Unterstützt zwei Modi:

**A) Automatisch**
- Validiert den Hetzner API Key
- Bestimmt öffentliche und interne Master-IP
- Erstellt oder verwendet ein Hetzner-Privatnetz und hängt den Master daran
- Erzeugt einen privaten Datenbankserver und installiert per cloud-init:
  - MongoDB, mongo-express, UFW und WireGuard
- Speichert DB-Zugangsdaten lokal in `CloudConfig.json`
- Speichert zentrale Werte in der DB: Hetzner API Key, Netz-ID/Name/CIDR, Gateway Host/Port, WireGuard-Daten
- Richtet optional eine Storage Box ein
- Erstellt den ersten Worker-Server (**SSH-public-key** aus DB wird in `authorized_keys` eingetragen)
- Legt optional direkt `velocity-01` und `lobby-01` an
- Erstellt zusätzlich einen ProxyGateway-Server

**B) Manuell**
- Verbindet vorhandene MySQL- oder MongoDB-Datenbank
- Testet Verbindung und initialisiert Schema
- Speichert Hetzner API Key, falls noch nicht vorhanden
- Schreibt `CloudConfig.json`

---

### `de.cloudnetwork.database`

Abstraktion für die Datenhaltung.

**`DatabaseManager` (Interface)**
- `connect` / `close` / `isConnected`
- `initSchema`
- Konfigurationswerte lesen/schreiben
- Worker speichern/laden

**`MongoDbDatabaseManager`**

Verwendete Collections: `cloud_config`, `workers`, `minecraft_instances`

Zusätzliche Funktionen:
- Alle Minecraft-Instanzen lesen
- Online-Velocity-Instanzen lesen
- Minecraft-Instanz upserten
- Status einzelner Instanzen ändern

**`MysqlDatabaseManager`**

Speichert nur Konfiguration und Worker. Viele Funktionen im restlichen Code sind **nur für MongoDB implementiert** (z. B. `server list`, Proxy-Liste, Instanzverwaltung, Peer-Konsole auf Instanzen).

---

### `de.cloudnetwork.worker`

**`WorkerInfo`** – Speichert pro Worker:

| Feld | Beschreibung |
|---|---|
| `id` | Eindeutige Worker-ID |
| `ipv4` | Private IP-Adresse |
| `hetznerServerId` | Hetzner-Server-ID |
| `status` | `PROVISIONING` / `ONLINE` / `OFFLINE` / `DELETED` |
| `cpuPercent` / `ramPercent` | Aktuelle Auslastung (via SSH gesammelt) |
| `playerCount` | Anzahl aktiver Spieler |
| `lastHeartbeatMs` | Timestamp der letzten SSH-Metrik-Abfrage |

**`WorkerRegistry`** – RAM-Register für live bekannte Worker:
- Registrieren / Entfernen
- Online/Offline markieren
- Metriken aktualisieren
- Durchschnittslast berechnen

---

### `de.cloudnetwork.ssh`

**`SshManager`** – SSH/SFTP-Client für alle Master→Worker-Operationen.

| Methode | Beschreibung |
|---|---|
| `ensureKeysExist(db)` | Generiert RSA-4096-Schlüsselpaar einmalig, speichert in DB |
| `getPublicKey(db)` | Liest den gespeicherten öffentlichen Schlüssel (für cloud-init) |
| `fromDb(db)` | Erstellt `SshManager`-Instanz aus DB-Private-Key |
| `executeCommand(host, cmd)` | Führt Shell-Befehl auf Worker per SSH aus |
| `uploadBytes(host, data, path)` | Datei via SFTP hochladen |
| `downloadBytes(host, path)` | Datei via SFTP herunterladen |
| `collectMetrics(host)` | CPU/RAM via `top`/`free` per SSH abrufen |
| `waitForSsh(host, timeoutMs)` | Wartet bis SSH erreichbar ist |
| `tailLog(host, path, consumer)` | Streamt Log-Datei per `tail -f` SSH |

**DB-Keys:**
- `worker_ssh_private_key` – PEM-kodierter RSA-4096-Private-Key
- `worker_ssh_public_key` – OpenSSH-Format-Public-Key (wird in `authorized_keys` eingetragen)

---

### `de.cloudnetwork.gateway`

**`GatewaySocketServer`**

Interner Socketserver des Masters:
- Lauscht standardmäßig auf Port `9876` (optional mit mTLS)
- Nimmt **nur noch ProxyGateway-Verbindungen** an (Worker verbinden sich nicht mehr)
- Broadcastet `PROXY_UPDATE` an alle ProxyGateways
- Verwaltet Peer-Konsolen-Ausgabe (für ProxyGateway-Logs)

**`WorkerSession`** – Behandelt eine ProxyGateway-Socket-Verbindung.

Nachrichtentypen: `REGISTER` (role=proxy_gateway), `LOG_LINE`, `CONSOLE_OUTPUT`

- ProxyGateways werden gegen `proxy_gateway_auth_token` aus der DB geprüft
- Log-Zeilen werden lokal gespeichert unter `logs/proxygateways/…`

---

### `de.cloudnetwork.protocol`

**`MessageType`** – Alle Nachrichtentypen des internen Protokolls.

**`Message`** – JSON-Hülle für das interne Protokoll.

**`ProxyEndpoint`** – Repräsentiert einen erreichbaren Velocity-Endpunkt: Instanz-ID, Host, Port.

---

### `de.cloudnetwork.proxygw`

**`ProxyGatewayMain`** – Startpunkt des ProxyGateway-Prozesses.

**`ProxyGatewayServer`** – Lauscht auf Port 25565, nimmt Minecraft-Client-Verbindungen an.

**`ProxyGatewayClient`** – Verbindet sich zum Master-Gateway, empfängt `PROXY_UPDATE`-Nachrichten.

**`TcpRelay`** – Leitet eingehende Client-Verbindungen per Round-Robin an die verfügbaren Velocity-Endpunkte weiter.

---

### `de.cloudnetwork.scaling`

**`ScalingMonitor`** – Überwacht die Gesamtauslastung und löst automatisch Scale-Up bzw. Scale-Down aus.

- Metriken werden alle 15 Sekunden **per SSH** von jedem Online-Worker gesammelt (`SshManager.collectMetrics`)
- Scale-Up: Neuer Worker ohne Worker-Agent; Master trägt SSH-Key ein, wartet auf SSH-Erreichbarkeit
- Nach SSH-Erreichbarkeit: **Startet automatisch Default-Instanzen** auf dem neuen Worker (konfigurierbar via `scaling_default_velocity_count` / `scaling_default_lobby_count`, Standard je 1)
- Scale-Down: Alle Instanzen per SSH beenden (`kill` via PID-Datei), dann Hetzner-Server löschen

---

### `de.cloudnetwork.instance`

**`InstanceManager`** – Kernkomponente für den vollständigen Instanz-Lifecycle.

| Methode | Beschreibung |
|---|---|
| `allocatePort(workerId)` | Nächsten freien Port ab 25577 auf dem Worker ermitteln |
| `startInstance(doc)` | Instanz starten: JAR herunterladen, Konfiguration generieren + hochladen, screen starten, PID-Waiter, `PROXY_UPDATE` broadcasten |
| `stopInstance(doc)` | Instanz graceful stoppen, PID-Datei löschen, Status → OFFLINE, `PROXY_UPDATE` broadcasten |
| `reloadVelocityInstances()` | Alle laufenden Velocity-Instanzen mit neuer `velocity.toml` aktualisieren (ohne Restart) |
| `generateVelocityToml(port, lobbies)` | `velocity.toml` mit allen aktiven Lobby-Backends |
| `generateServerProperties(port)` | `server.properties` mit `online-mode=false` und Port |
| `generatePaperGlobalYml(secret)` | `config/paper-global.yml` mit Velocity-Forwarding-Secret |
| `getOrGenerateForwardingSecret()` | Shared Velocity-Forwarding-Secret aus DB oder neu generieren |

**Instanz-Lebenszyklus:**

```
PENDING_START → STARTING → ONLINE → OFFLINE
```

- Hintergrund-Thread wartet nach dem Start auf PID-Entstehung (max. 5 Min) → Status `ONLINE` + `PROXY_UPDATE`
- Wenn eine Lobby `ONLINE`/`OFFLINE` geht: `reloadVelocityInstances()` aufrufen

**Relevante DB-Keys:**

| Key | Beschreibung |
|---|---|
| `velocity_forwarding_secret` | Gemeinsames Forwarding-Secret für Velocity + Paper |
| `default_velocity_url` | Standard-JAR-URL für neue Velocity-Instanzen |
| `default_paper_url` | Standard-JAR-URL für neue Paper/Lobby-Instanzen |
| `scaling_default_velocity_count` | Velocity-Instanzen pro neuem Scale-Up-Worker (Standard: 1) |
| `scaling_default_lobby_count` | Lobby-Instanzen pro neuem Scale-Up-Worker (Standard: 1) |

**`InstanceMonitor`** – Periodische Gesundheitsprüfung aller Instanzen (alle 30 Sekunden).

- Prüft per SSH ob PID-Datei + Prozess noch existieren
- Bei Absturz: Status → `OFFLINE`, `PROXY_UPDATE` broadcasten
- Bei `autoStart=true`: Instanz automatisch neu starten

---

### `de.cloudnetwork.tls`

**`TlsManager`** – Erzeugt und verwaltet die mTLS-Infrastruktur (CA, Server-Zert, Client-Zert). Zertifikate werden in der DB gespeichert. Gilt nur für die ProxyGateway↔Gateway-Verbindung.

---

### `de.cloudnetwork.storage`

**`StorageBoxManager`** – Verwaltet die Hetzner Storage Box (SFTP).

**`StorageBoxMonitor`** – Überwacht die Storage Box im Hintergrund.

**`HetznerRobotApiClient`** – Client für die Hetzner Robot API.

**`StorageBoxInfo`** – Datenhaltungsklasse für Storage-Box-Metadaten.

---

### `de.cloudnetwork.hetzner`

**`HetznerApiClient`** – Zentraler Client für alle Hetzner Cloud-API-Aufrufe: Server erstellen/löschen, Netzwerke verwalten, Server-Details abfragen, cloud-init konfigurieren.

Worker cloud-init: Installiert Java, screen, ufw; richtet `/home/cloudnetwork/`-Ordnerstruktur ein; trägt SSH-Public-Key in `authorized_keys` ein. **Kein Worker-Agent-Service.**

**`HetznerServer`** – Datenhaltungsklasse für einen Hetzner-Server (ID, Name, IPs, Status).

---

## Kommunikationsprotokoll (Gateway-Socket)

Der Master betreibt einen **TCP-Socket-Server** (Standard-Port `9876`) für ProxyGateway-Verbindungen.

**Verbindungsablauf (ProxyGateway):**
1. ProxyGateway stellt mTLS-Verbindung her.
2. Sendet `REGISTER` mit `role=proxy_gateway` und Auth-Token.
3. Empfängt sofort den aktuellen `PROXY_UPDATE` mit allen Velocity-Endpunkten.
4. Sendet `LOG_LINE` für eigene Konsolausgaben.

**Aktive Nachrichtentypen:**

| Typ | Richtung | Beschreibung |
|---|---|---|
| `REGISTER` | ProxyGateway → Master | Initiale Registrierung |
| `PROXY_UPDATE` | Master → ProxyGateway | Aktualisierung der Backend-Endpunkte |
| `LOG_LINE` | ProxyGateway → Master | Log-Zeile des ProxyGateway-Prozesses |
| `CONSOLE_OUTPUT` | ProxyGateway → Master | Konsolenausgabe für Peer-Session |

---

## Sicherheit

### SSH-Schlüsselmanagement
- Master generiert **einmalig** ein RSA-4096-Schlüsselpaar (gespeichert in DB).
- Public Key wird beim Worker-Provisioning in `~/.ssh/authorized_keys` eingetragen.
- Kein Passwort-Auth; nur Key-basierte SSH-Verbindungen.

### mTLS (Mutual TLS)
- Alle ProxyGateway↔Gateway-Verbindungen sind **gegenseitig TLS-authentifiziert**.
- Der `TlsManager` erzeugt beim ersten Start eine eigene **CA** sowie Server- und Client-Zertifikate.
- Zertifikate werden als Datenbank-Keys gespeichert (`tls_ca_cert`, `tls_server_cert`, `tls_client_cert` + `_key`).
- Konsolenbefehle: `tls status` / `tls renew`

### WireGuard VPN
- WireGuard ist **kein optionales Extra**, sondern der vorgesehene externe Admin-Zugang ins Hetzner-Privatnetz.
- Innerhalb des Hetzner-Privatnetzes kommunizieren die Server direkt über ihre privaten IPs; **von außen** erreichst du diese privaten Server nur über den eingerichteten WireGuard-Zugang.
- MongoDB und mongo-express sind **ausschließlich über das interne Netz bzw. WireGuard** erreichbar.
- Die vollständige Client-Konfiguration (Key/Endpoint) wird nach dem Setup in der Konsole ausgegeben.

### Netzwerk-Firewall
- Worker-Firewall erlaubt nur SSH (Port 22) und Minecraft-Backend-Traffic aus privaten Netzen.
- ProxyGateway nutzt `ufw limit 25565/tcp` und aktiviert `fail2ban`.

---

## Autoscaling

Der `ScalingMonitor` prüft alle **15 Sekunden** die durchschnittliche Auslastung aller Online-Worker.

**Metrik-Erfassung:** Per SSH (`top -bn1` + `free -m`) von jedem Online-Worker.

**Scale-Up-Logik:**
- Auslöser: Geglättete Last > `scaling_high_load_threshold` (Standard: 80 %) für **4 Minuten** ununterbrochen.
- Aktion: Neuer Hetzner-Worker-Server wird provisioniert (SSH-Key eingetragen, kein Worker-Agent).
- Master wartet per `SshManager.waitForSsh()` auf SSH-Erreichbarkeit, dann Status → `ONLINE`.
- **Automatischer Instanzstart:** Nach SSH-Erreichbarkeit startet der `InstanceManager` automatisch Default-Instanzen (1× Velocity + 1× Lobby, konfigurierbar).

**Scale-Down-Logik:**
- Auslöser: Geglättete Last < `scaling_low_load_threshold` (Standard: 40 %) für **15 Minuten** ununterbrochen.
- Bedingung: Mindestens 2 Online-Worker, Kandidat hat keine aktiven Spieler.
- Aktion: SSH-Shutdown aller Instanzen (`kill` via `/home/cloudnetwork/instances/*/pid`), dann Hetzner-Server-Löschung.

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
- Privates MongoDB-Datenbank-Netz (inkl. mongo-express und WireGuard)
- Erster privater Worker-Server (SSH-Key eingetragen, kein Worker-Agent)
- Öffentlicher ProxyGateway-Server
- Optional: `velocity-01` + `lobby-01` für automatischen Erststart

**Nicht-interaktiv (Umgebungsvariablen):**
```bash
CLOUDNETWORK_AUTO_SETUP=true
HETZNER_API_KEY=...
CLOUDNETWORK_HETZNER_NETWORK_ID=...
CLOUDNETWORK_GATEWAY_PRIVATE_HOST=10.10.0.2
CLOUDNETWORK_GATEWAY_PUBLIC_HOST=master.example.com
CLOUDNETWORK_WORKER_JAR_URL=https://...         # JAR für neu provisionierte Worker
CLOUDNETWORK_PROXY_GATEWAY_JAR_URL=https://...  # JAR für den öffentlichen ProxyGateway-Server
```

---

## Datenbank

Unterstützte Backends:
- **MongoDB** (Standard, empfohlen)
- MySQL/MariaDB

Die Datenbank speichert:
- Alle Konfigurations-Key-Value-Paare (`gateway_port`, `hetzner_api_key`, TLS-Zertifikate, SSH-Schlüssel, Scaling-Einstellungen, …)
- Worker-Zustände (`id`, `status`, `ipv4`, `hetznerServerId`, `playerCount`, `cpuPercent`, `ramPercent`, …)
- Storage-Box-Zugangsdaten

---

## Storage Box

Die Hetzner Storage Box dient als zentrales Dateisystem für Templates, JARs und Backups.

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

`start.sh` installiert automatisch OpenJDK (≥ 17) und `screen`; die JAR muss bereits in `target/` oder neben `start.sh` vorhanden sein.

**Erzeugte JARs:**
- `CloudNetwork-1.0.0.jar` – Master-Prozess
- `proxy-gateway-1.0.0.jar` – ProxyGateway-Prozess

---

## Wichtige Konsolen-Befehle

| Befehl | Beschreibung |
|---|---|
| `worker list` | Liste aller Worker anzeigen |
| `worker create` | Worker manuell provisionieren |
| `worker remove <id\|*>` | Worker per SSH herunterfahren und löschen |
| `server list` | Minecraft-Instanzen anzeigen (ID, Typ, Worker, Port, Status) |
| `server create <name> <velocity\|lobby> <worker-id>` | Neue Instanz anlegen, Port zuweisen, sofort starten |
| `server start <id>` | Instanz per SSH auf Worker starten |
| `server stop <id>` | Instanz per SSH auf Worker stoppen |
| `server seturl <id> <url>` | Download-URL für Instanz setzen |
| `scale status` | Autoscaling-Status anzeigen |
| `scale set ...` | Autoscaling-Schwellwerte setzen |
| `tls status` | TLS-Zertifikatsstatus anzeigen |
| `tls renew` | Zertifikate erneuern |
| `storagebox setup` | Storage Box einrichten |
| `storagebox status` | Storage-Box-Status anzeigen |
| `jar set <typ> <url>` | JAR-URLs setzen (`typ`: `velocity`, `paper`/`minecraft`, `proxy-gateway`) |
| `peer <id>` | SSH-Log-Stream einer Instanz/Worker (ProxyGateway: Socket-Stream) |
| `help` | Alle Befehle anzeigen |
