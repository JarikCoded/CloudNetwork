# CloudNetwork

## Vollautomatischer Erststart

`start.sh start` startet weiterhin nur die Master-JAR. Die automatische Erstkonfiguration läuft jetzt in der JAR selbst, wenn noch keine `CloudConfig.json` existiert und passende Umgebungsvariablen gesetzt sind.

### Unterstützte Variablen

- `CLOUDNETWORK_AUTO_SETUP=true` oder `CLOUDNETWORK_SETUP_MODE=automatisch`
- `HETZNER_API_KEY` oder `CLOUDNETWORK_HETZNER_API_KEY`
- optional `CLOUDNETWORK_GATEWAY_HOST`
- optional `CLOUDNETWORK_GATEWAY_PORT` (Standard `9876`)
- optional `CLOUDNETWORK_WORKER_JAR_URL`
- optional `CLOUDNETWORK_PROXY_GATEWAY_JAR_URL`
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
export CLOUDNETWORK_WORKER_JAR_URL=https://example.invalid/worker-1.0.0.jar
export CLOUDNETWORK_PROXY_GATEWAY_JAR_URL=https://example.invalid/proxy-gateway-1.0.0.jar
./start.sh start
```

Beim ersten Start werden damit automatisch MongoDB, der erste Worker, das Proxy-Gateway und optional die Storage-Box-Konfiguration erzeugt. Worker und Proxy-Gateway laden ihre JARs selbstständig per Cloud-Init und starten ohne manuelles Hochladen, sobald die jeweilige URL erreichbar ist.