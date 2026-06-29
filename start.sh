#!/usr/bin/env bash
# start.sh – Starts CloudNetwork.
# Checks for Java 17+; installs it automatically when missing.

set -e

REQUIRED_JAVA_VERSION=17
JAR="$(dirname "$(realpath "$0")")/target/CloudNetwork-1.0.0.jar"

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
        sudo apt-get install -y "openjdk-${REQUIRED_JAVA_VERSION}-jre-headless"

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

# ── JAR check ─────────────────────────────────────────────────────────────────

if [ ! -f "$JAR" ]; then
    print_error "JAR nicht gefunden: $JAR"
    print_error "Bitte zuerst bauen: mvn package -q"
    exit 1
fi

# ── Start ─────────────────────────────────────────────────────────────────────

print_info "Starte CloudNetwork..."
exec "$JAVA_CMD" -jar "$JAR" "$@"
