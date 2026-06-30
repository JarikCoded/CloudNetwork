#!/usr/bin/env bash
# start.sh – Starts CloudNetwork.
# Checks for Java 17+; installs it automatically when missing.

apt update && apt upgrade -y

set -e

REQUIRED_JAVA_VERSION=17
SCRIPT_DIR="$(dirname "$(realpath "$0")")"
JAR_NAME="CloudNetwork-1.0.0.jar"
JAR="$SCRIPT_DIR/target/$JAR_NAME"
SCREEN_SESSION="cloudnetwork"

# ── Helpers ───────────────────────────────────────────────────────────────────

print_info()    { echo "[INFO]  $*"; }
print_ok()      { echo "[OK]    $*"; }
print_error()   { echo "[ERROR] $*" >&2; }

java_major_version() {
    # Works for both "java version \"1.8.x\"" and "openjdk version \"17.x\""
    local raw
    raw=$("$1" -version 2>&1 | head -1 | grep -oE '"[^"]+"' | tr -d '"')
    local major
    major=$(echo "$raw" | cut -d'.' -f1)
    # Java 8 and earlier report "1.8", so strip the leading "1."
    if [ "$major" = "1" ]; then
        echo "$raw" | cut -d'.' -f2
    else
        echo "$major"
    fi
}

java_ok() {
    local cmd="${1:-java}"
    command -v "$cmd" >/dev/null 2>&1 || return 1
    local ver
    ver=$(java_major_version "$cmd")
    [ "$ver" -ge "$REQUIRED_JAVA_VERSION" ] 2>/dev/null
}

find_apt_java_package() {
    apt-cache pkgnames 2>/dev/null \
        | awk -v min="$REQUIRED_JAVA_VERSION" '
            match($0, /^openjdk-([0-9]+)-(jre-headless|jre|jdk-headless)$/, m) {
                version = m[1] + 0
                if (version < min) {
                    next
                }

                package_type = m[2]
                priority = (package_type == "jre-headless" ? 3 : (package_type == "jre" ? 2 : 1))
                printf "%d %d %s\n", version, priority, $0
            }
        ' \
        | sort -k1,1nr -k2,2nr \
        | head -n1 \
        | awk '{ print $3 }'
}

screen_installed() {
    command -v screen >/dev/null 2>&1
}

install_screen() {
    if screen_installed; then
        return 0
    fi

    print_info "screen nicht gefunden. Installiere screen..."
    if command -v apt-get >/dev/null 2>&1; then
        sudo apt-get update -y
        sudo apt-get install -y screen
    elif command -v dnf >/dev/null 2>&1; then
        sudo dnf install -y screen
    elif command -v yum >/dev/null 2>&1; then
        sudo yum install -y screen
    elif command -v brew >/dev/null 2>&1; then
        brew install screen
    else
        print_error "Kein unterstützter Paketmanager für screen gefunden (apt, dnf, yum, brew)."
        exit 1
    fi

    if ! screen_installed; then
        print_error "screen konnte nicht installiert werden."
        exit 1
    fi
    print_ok "screen erfolgreich installiert."
}

screen_session_running() {
    screen -ls 2>/dev/null | grep -q "[.]${SCREEN_SESSION}[[:space:]]"
}

# ── Java detection ────────────────────────────────────────────────────────────

JAVA_CMD="java"

if java_ok "$JAVA_CMD"; then
    print_ok "Java $(java_major_version "$JAVA_CMD") gefunden."
else
    print_info "Java $REQUIRED_JAVA_VERSION (oder höher) nicht gefunden. Installiere Java..."

    if command -v apt-get >/dev/null 2>&1; then
        # Debian / Ubuntu
        print_info "Erkanntes System: Debian/Ubuntu (apt)"
        sudo apt-get update -y
        apt_java_package=$(find_apt_java_package)

        if [ -z "$apt_java_package" ]; then
            print_error "Kein OpenJDK-Paket ab Version $REQUIRED_JAVA_VERSION über apt gefunden."
            exit 1
        fi

        print_info "Installiere $apt_java_package"
        sudo apt-get install -y "$apt_java_package"

    elif command -v dnf >/dev/null 2>&1; then
        # Fedora / RHEL 8+ / Amazon Linux 2023
        print_info "Erkanntes System: Fedora/RHEL (dnf)"
        sudo dnf install -y "java-${REQUIRED_JAVA_VERSION}-openjdk-headless"

    elif command -v yum >/dev/null 2>&1; then
        # CentOS / older RHEL / Amazon Linux 2
        print_info "Erkanntes System: CentOS/RHEL (yum)"
        sudo yum install -y "java-${REQUIRED_JAVA_VERSION}-openjdk-headless"

    elif command -v brew >/dev/null 2>&1; then
        # macOS (Homebrew)
        print_info "Erkanntes System: macOS (Homebrew)"
        brew install "openjdk@${REQUIRED_JAVA_VERSION}"
        # Homebrew does not symlink JDKs automatically – add it to PATH
        BREW_JAVA_HOME="$(brew --prefix "openjdk@${REQUIRED_JAVA_VERSION}")/bin"
        export PATH="$BREW_JAVA_HOME:$PATH"

    else
        print_error "Kein bekannter Paketmanager gefunden (apt, dnf, yum, brew)."
        print_error "Bitte Java $REQUIRED_JAVA_VERSION manuell installieren:"
        print_error "  https://adoptium.net"
        exit 1
    fi

    # Re-check after installation
    if ! java_ok "$JAVA_CMD"; then
        print_error "Java-Installation fehlgeschlagen oder Java $REQUIRED_JAVA_VERSION+ nicht im PATH."
        exit 1
    fi
    print_ok "Java $(java_major_version "$JAVA_CMD") erfolgreich installiert."
fi

ACTION="start"
if [ $# -gt 0 ]; then
    case "$1" in
        start|stop|status|attach)
            ACTION="$1"
            shift
            ;;
    esac
fi

case "$ACTION" in
    status)
        if ! screen_installed; then
            print_info "screen ist nicht installiert. Es läuft keine verwaltete Session."
            exit 0
        fi
        if screen_session_running; then
            print_ok "CloudNetwork läuft in screen-Session '$SCREEN_SESSION'."
        else
            print_info "CloudNetwork läuft aktuell nicht in screen-Session '$SCREEN_SESSION'."
        fi
        exit 0
        ;;
    stop)
        if ! screen_installed || ! screen_session_running; then
            print_info "Keine laufende screen-Session '$SCREEN_SESSION' gefunden."
            exit 0
        fi
        print_info "Sende stop-Befehl an CloudNetwork..."
        screen -S "$SCREEN_SESSION" -p 0 -X stuff $'stop\r'
        for _ in $(seq 1 30); do
            if ! screen_session_running; then
                print_ok "CloudNetwork wurde gestoppt."
                exit 0
            fi
            sleep 1
        done
        print_info "Graceful Stop dauert zu lange. Beende screen-Session..."
        screen -S "$SCREEN_SESSION" -X quit
        print_ok "screen-Session '$SCREEN_SESSION' beendet."
        exit 0
        ;;
    attach)
        install_screen
        if ! screen_session_running; then
            print_info "Keine laufende screen-Session '$SCREEN_SESSION' gefunden."
            exit 1
        fi
        exec screen -r "$SCREEN_SESSION"
        ;;
esac

install_screen
if screen_session_running; then
    print_error "CloudNetwork läuft bereits in screen-Session '$SCREEN_SESSION'."
    print_error "Nutze '$0 attach' zum Verbinden oder '$0 stop' zum Stoppen."
    exit 1
fi

# ── JAR check ─────────────────────────────────────────────────────────────────

if [ ! -f "$JAR" ]; then
    ALT_JAR="$SCRIPT_DIR/$JAR_NAME"
    if [ -f "$ALT_JAR" ]; then
        JAR="$ALT_JAR"
    fi
fi

if [ ! -f "$JAR" ]; then
    print_error "JAR nicht gefunden: $JAR"
    print_error "Erwartete Orte:"
    print_error "  $SCRIPT_DIR/target/$JAR_NAME"
    print_error "  $SCRIPT_DIR/$JAR_NAME"
    print_error "Bitte zuerst bauen: mvn package -q oder die JAR neben start.sh ablegen."
    exit 1
fi

# ── Start ─────────────────────────────────────────────────────────────────────

print_info "Starte CloudNetwork in screen-Session '$SCREEN_SESSION'..."
screen -dmS "$SCREEN_SESSION" "$JAVA_CMD" -jar "$JAR" "$@"
print_ok "CloudNetwork wurde gestartet."
print_info "Nützlich: '$0 status' | '$0 attach' | '$0 stop'"
