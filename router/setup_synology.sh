#!/bin/sh
# ─────────────────────────────────────────────────────────────────────────────
# NodeX VPN — Synology NAS Setup (DSM 7.x)
#
# Routes all outbound NAS traffic through Tor AND optionally shares the Tor
# SOCKS5 / transparent proxy to LAN clients via iptables + Docker network.
#
# What this does:
#   1. Detects DSM version and CPU architecture (x86_64 / aarch64)
#   2. Downloads the correct Linux binary from GitHub Releases
#      (Synology DSM runs a Linux 4.4+ kernel — standard Linux binaries work)
#   3. Installs to /usr/local/bin/nodex-vpn
#   4. Creates /etc/nodex/nodex.conf
#   5. Installs a DSM-compatible start-stop service:
#      • DSM 7.x: /usr/local/etc/rc.d/nodex-vpn.sh  (run on boot by synoinit)
#      • DSM 6.x: /etc/init/nodex-vpn.conf           (Upstart)
#   6. Configures TUN device + iptables transparent proxy for LAN routing
#   7. Optionally: Creates an SPK-compatible package descriptor for GUI install
#
# Usage:
#   sh setup_synology.sh                # auto-detect everything
#   sh setup_synology.sh --lan-only     # protect LAN clients only (NAS as gateway)
#   sh setup_synology.sh --nas-only     # protect NAS outbound only
#   sh setup_synology.sh --uninstall
#
# Requirements:
#   • DSM 6.2+ or DSM 7.x
#   • x86_64 (DS920+, DS1621+, DS923+) or aarch64 (DS220+, DS420+)
#   • Enable SSH: DSM → Control Panel → Terminal & SNMP → Enable SSH
#   • Run as root: sudo -i (or use admin account with sudo)
#   • TUN/TAP kernel module: opkg install tun (if not present)
#
# Architecture note:
#   Synology CPUs fall into these Rust targets:
#     x86_64  (Intel/AMD)  → x86_64-unknown-linux-gnu
#     aarch64 (Realtek RTD1619B, A55) → aarch64-unknown-linux-gnu
#     armv7   (older DS2xx)  → NOT SUPPORTED (32-bit)
# ─────────────────────────────────────────────────────────────────────────────

set -e

NODEX_VERSION="${NODEX_VERSION:-latest}"
GITHUB_REPO="AeonCoreX-Lab/NodeX-VPN"
INSTALL_DIR="/usr/local/bin"
CONFIG_DIR="/etc/nodex"
LOG_FILE="/var/log/nodex-vpn.log"
TUN_IFACE="nodex0"
TUN_ADDR="10.66.0.1"
TUN_NETWORK="10.66.0.0/24"
TOR_SOCKS="127.0.0.1:9050"
TOR_TRANS="9040"
TOR_DNS="5353"

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { printf "${GREEN}[NodeX]${NC} %s\n" "$*"; }
warn()  { printf "${YELLOW}[NodeX]${NC} %s\n" "$*"; }
error() { printf "${RED}[NodeX ERROR]${NC} %s\n" "$*" >&2; exit 1; }

# ── Guards ────────────────────────────────────────────────────────────────────
[ "$(id -u)" -eq 0 ] || error "Must run as root (sudo -i)"
uname -s | grep -qi "linux" || error "This script requires a Linux-based Synology DSM."

# ── Arguments ─────────────────────────────────────────────────────────────────
MODE="both"      # both | lan-only | nas-only
LAN_IFACE=""
UNINSTALL=0
while [ $# -gt 0 ]; do
    case "$1" in
        --lan-only)  MODE="lan-only";  shift ;;
        --nas-only)  MODE="nas-only";  shift ;;
        --lan)       LAN_IFACE="$2";  shift 2 ;;
        --version)   NODEX_VERSION="$2"; shift 2 ;;
        --uninstall) UNINSTALL=1; shift ;;
        *) warn "Unknown argument: $1"; shift ;;
    esac
done

# ── Detect DSM version ────────────────────────────────────────────────────────
detect_dsm() {
    if [ -f /etc/synoinfo.conf ]; then
        DSM_MAJOR=$(grep -i "majorversion" /etc/synoinfo.conf | cut -d'"' -f2)
        DSM_MINOR=$(grep -i "minorversion" /etc/synoinfo.conf | cut -d'"' -f2)
        echo "${DSM_MAJOR:-7}.${DSM_MINOR:-0}"
    elif [ -f /proc/syno_version ]; then
        cat /proc/syno_version | grep -oP '\d+\.\d+'
    else
        echo "7.0"
    fi
}

# ── Detect CPU arch and Rust target ──────────────────────────────────────────
detect_arch() {
    ARCH=$(uname -m)
    case "$ARCH" in
        x86_64)        echo "x86_64-unknown-linux-gnu" ;;
        aarch64|arm64) echo "aarch64-unknown-linux-gnu" ;;
        armv7*|armv6*) error "armv7/v6 is not supported. Your NAS must be 64-bit (DS220+ or newer x86_64 models)." ;;
        *)             error "Unsupported architecture: $ARCH" ;;
    esac
}

# ── Detect LAN interface ──────────────────────────────────────────────────────
detect_lan() {
    [ -n "$LAN_IFACE" ] && return
    # Synology: usually eth0 or bond0
    LAN_IFACE=$(ip route | awk '/default/{print $5; exit}')
    [ -z "$LAN_IFACE" ] && LAN_IFACE="eth0"
    info "Detected LAN interface: $LAN_IFACE"
}

# ── Load TUN kernel module ────────────────────────────────────────────────────
setup_tun() {
    info "Loading TUN kernel module ..."
    if [ -e /dev/net/tun ]; then
        info "TUN device already exists: /dev/net/tun"
    else
        modprobe tun 2>/dev/null || insmod /lib/modules/$(uname -r)/kernel/drivers/net/tun.ko 2>/dev/null || \
            warn "Could not load TUN module automatically. You may need to install it via Package Center → SynoCommunity → tun."
    fi

    # Persist across reboots via /etc/rc.local equivalent
    MODPROBE_CONF="/etc/modprobe.d/nodex.conf"
    if ! grep -q "^tun" "$MODPROBE_CONF" 2>/dev/null; then
        echo "# NodeX VPN" > "$MODPROBE_CONF"
        echo "tun" >> "$MODPROBE_CONF"
        info "TUN module persistence configured: $MODPROBE_CONF"
    fi
}

# ── Download binary ───────────────────────────────────────────────────────────
download_binary() {
    TARGET="$1"
    info "Downloading NodeX VPN binary ($TARGET) ..."

    if [ "$NODEX_VERSION" = "latest" ]; then
        URL="https://github.com/${GITHUB_REPO}/releases/latest/download/nodex-vpn-router-${TARGET}.tar.gz"
    else
        URL="https://github.com/${GITHUB_REPO}/releases/download/${NODEX_VERSION}/nodex-vpn-router-${TARGET}.tar.gz"
    fi

    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' EXIT

    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$URL" -o "$TMP/nodex.tar.gz" || error "Download failed: $URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -qO "$TMP/nodex.tar.gz" "$URL" || error "Download failed: $URL"
    else
        error "No download tool (curl/wget). Enable curl in DSM package or install via opkg."
    fi

    tar -xzf "$TMP/nodex.tar.gz" -C "$TMP"
    install -m 755 "$TMP/nodex-vpn" "${INSTALL_DIR}/nodex-vpn"
    info "Binary installed: ${INSTALL_DIR}/nodex-vpn"
}

# ── Create config ─────────────────────────────────────────────────────────────
create_config() {
    mkdir -p "$CONFIG_DIR"
    cat > "${CONFIG_DIR}/nodex.conf" << CONF
# NodeX VPN — Synology DSM Configuration
# Generated by setup_synology.sh

[vpn]
tun_name    = ${TUN_IFACE}
tun_addr    = ${TUN_ADDR}
tun_network = ${TUN_NETWORK}
socks5_addr = ${TOR_SOCKS}

[tor]
data_dir    = /etc/nodex/tor-data
log_level   = warn

[router]
platform        = synology
route_lan       = true
lan_iface       = ${LAN_IFACE}
mode            = ${MODE}
trans_port      = ${TOR_TRANS}
dns_port        = ${TOR_DNS}
exclude_local   = true
CONF
    info "Config written: ${CONFIG_DIR}/nodex.conf"
}

# ── Configure iptables ────────────────────────────────────────────────────────
setup_iptables() {
    info "Configuring iptables transparent proxy ..."

    # Clear existing NodeX rules (idempotent)
    iptables -t nat -D PREROUTING -i "$LAN_IFACE" -p tcp \
        -m comment --comment "nodex-tcp" \
        -j REDIRECT --to-ports "$TOR_TRANS" 2>/dev/null || true
    iptables -t nat -D PREROUTING -i "$LAN_IFACE" -p udp --dport 53 \
        -m comment --comment "nodex-dns" \
        -j REDIRECT --to-ports "$TOR_DNS" 2>/dev/null || true
    iptables -t mangle -D OUTPUT -j NODEX_MARK 2>/dev/null || true
    iptables -t mangle -F NODEX_MARK 2>/dev/null || true
    iptables -t mangle -X NODEX_MARK 2>/dev/null || true

    if [ "$MODE" = "lan-only" ] || [ "$MODE" = "both" ]; then
        info "  LAN transparent proxy: $LAN_IFACE → Tor"

        # Redirect LAN TCP to TransPort
        iptables -t nat -A PREROUTING \
            -i "$LAN_IFACE" -p tcp \
            -m comment --comment "nodex-tcp" \
            -j REDIRECT --to-ports "$TOR_TRANS"

        # Redirect LAN DNS to DNSPort
        iptables -t nat -A PREROUTING \
            -i "$LAN_IFACE" -p udp --dport 53 \
            -m comment --comment "nodex-dns" \
            -j REDIRECT --to-ports "$TOR_DNS"

        # Enable IP forwarding for LAN routing
        sysctl -w net.ipv4.ip_forward=1 > /dev/null
        # Persist in sysctl.conf
        if ! grep -q "net.ipv4.ip_forward" /etc/sysctl.conf 2>/dev/null; then
            echo "net.ipv4.ip_forward=1" >> /etc/sysctl.conf
        fi
    fi

    if [ "$MODE" = "nas-only" ] || [ "$MODE" = "both" ]; then
        info "  NAS outbound routing: all NAS traffic → Tor"

        # Mark all outbound NAS packets except loopback + private ranges
        iptables -t mangle -N NODEX_MARK 2>/dev/null || true
        iptables -t mangle -A OUTPUT -j NODEX_MARK
        iptables -t mangle -A NODEX_MARK -d 127.0.0.0/8 -j RETURN
        iptables -t mangle -A NODEX_MARK -d 10.0.0.0/8 -j RETURN
        iptables -t mangle -A NODEX_MARK -d 192.168.0.0/16 -j RETURN
        iptables -t mangle -A NODEX_MARK -d 172.16.0.0/12 -j RETURN
        iptables -t mangle -A NODEX_MARK -j MARK --set-mark 0x65

        # Policy route marked packets via TUN
        ip rule add fwmark 0x65 lookup 200 priority 100 2>/dev/null || true
        ip route add default via "$TUN_ADDR" dev "$TUN_IFACE" table 200 2>/dev/null || true
    fi

    info "iptables configured"
}

# ── DSM 7.x service (rc.d script) ────────────────────────────────────────────
install_dsm7_service() {
    RCD="/usr/local/etc/rc.d/nodex-vpn.sh"
    cat > "$RCD" << 'RCD_SCRIPT'
#!/bin/sh
# NodeX VPN — Synology DSM 7 rc.d service
# DSM runs scripts in /usr/local/etc/rc.d/ at boot

BINARY="/usr/local/bin/nodex-vpn"
PIDFILE="/var/run/nodex-vpn.pid"
CONFIG="/etc/nodex/nodex.conf"

start() {
    echo "Starting NodeX VPN ..."
    modprobe tun 2>/dev/null || true
    if [ -f "$PIDFILE" ] && kill -0 "$(cat $PIDFILE)" 2>/dev/null; then
        echo "NodeX VPN already running (PID $(cat $PIDFILE))"
        return 0
    fi
    "$BINARY" --config "$CONFIG" --daemon &
    echo $! > "$PIDFILE"
    echo "NodeX VPN started (PID $(cat $PIDFILE))"
}

stop() {
    echo "Stopping NodeX VPN ..."
    if [ -f "$PIDFILE" ]; then
        kill "$(cat $PIDFILE)" 2>/dev/null || true
        rm -f "$PIDFILE"
    else
        killall nodex-vpn 2>/dev/null || true
    fi
    # Clean up iptables rules
    iptables -t nat -F PREROUTING 2>/dev/null || true
    iptables -t mangle -D OUTPUT -j NODEX_MARK 2>/dev/null || true
    iptables -t mangle -F NODEX_MARK 2>/dev/null || true
    iptables -t mangle -X NODEX_MARK 2>/dev/null || true
    ip rule del fwmark 0x65 lookup 200 2>/dev/null || true
    echo "NodeX VPN stopped"
}

status() {
    if [ -f "$PIDFILE" ] && kill -0 "$(cat $PIDFILE)" 2>/dev/null; then
        echo "NodeX VPN is running (PID $(cat $PIDFILE))"
    else
        echo "NodeX VPN is not running"
    fi
}

case "$1" in
    start)   start   ;;
    stop)    stop    ;;
    restart) stop; sleep 1; start ;;
    status)  status  ;;
    *)       echo "Usage: $0 {start|stop|restart|status}"; exit 1 ;;
esac
RCD_SCRIPT
    chmod +x "$RCD"
    info "DSM 7 service installed: $RCD"
}

# ── DSM 6.x service (Upstart) ────────────────────────────────────────────────
install_dsm6_service() {
    UPSTART="/etc/init/nodex-vpn.conf"
    cat > "$UPSTART" << 'UPSTART_SCRIPT'
# NodeX VPN — Synology DSM 6 Upstart service
description "NodeX VPN Tor Router"
author "NodeX Project"

start on started network
stop on runlevel [016]

respawn
respawn limit 5 30

pre-start script
    modprobe tun 2>/dev/null || true
end script

exec /usr/local/bin/nodex-vpn --config /etc/nodex/nodex.conf
UPSTART_SCRIPT
    info "DSM 6 Upstart service installed: $UPSTART"
    initctl reload-configuration 2>/dev/null || true
}

# ── Create SPK metadata ───────────────────────────────────────────────────────
# This is a lightweight INFO file; full SPK packaging is done by package.sh
create_spk_info() {
    cat > "${CONFIG_DIR}/PACKAGE_INFO" << INFO
package="NodeX-VPN"
version="${NODEX_VERSION}"
maintainer="AeonCoreX Lab"
description="Serverless Tor VPN — routes all LAN traffic through Tor"
arch="$(uname -m)"
os_min_ver="7.0-40000"
install_dep_packages=""
startable="yes"
support_conf_folder="yes"
install_type="server"
INFO
    info "SPK package info written: ${CONFIG_DIR}/PACKAGE_INFO"
}

# ── Uninstall ─────────────────────────────────────────────────────────────────
uninstall() {
    info "Removing NodeX VPN from Synology DSM ..."

    # Stop service
    /usr/local/etc/rc.d/nodex-vpn.sh stop 2>/dev/null || \
    initctl stop nodex-vpn 2>/dev/null || \
    killall nodex-vpn 2>/dev/null || true

    # Remove iptables rules
    iptables -t nat -D PREROUTING -i "$LAN_IFACE" -p tcp \
        -m comment --comment "nodex-tcp" \
        -j REDIRECT --to-ports "$TOR_TRANS" 2>/dev/null || true
    iptables -t nat -D PREROUTING -i "$LAN_IFACE" -p udp --dport 53 \
        -m comment --comment "nodex-dns" \
        -j REDIRECT --to-ports "$TOR_DNS" 2>/dev/null || true
    iptables -t mangle -D OUTPUT -j NODEX_MARK 2>/dev/null || true
    iptables -t mangle -F NODEX_MARK 2>/dev/null || true
    iptables -t mangle -X NODEX_MARK 2>/dev/null || true
    ip rule del fwmark 0x65 lookup 200 2>/dev/null || true
    ip route flush table 200 2>/dev/null || true
    ip link del "$TUN_IFACE" 2>/dev/null || true

    # Remove files
    rm -f "${INSTALL_DIR}/nodex-vpn"
    rm -f "/usr/local/etc/rc.d/nodex-vpn.sh"
    rm -f "/etc/init/nodex-vpn.conf"
    rm -f "/etc/modprobe.d/nodex.conf"
    sed -i '/net.ipv4.ip_forward=1/d' /etc/sysctl.conf 2>/dev/null || true
    rm -rf "$CONFIG_DIR"

    info "NodeX VPN removed from Synology DSM."
}

# ── Main ──────────────────────────────────────────────────────────────────────
main() {
    if [ "$UNINSTALL" -eq 1 ]; then
        detect_lan
        uninstall
        exit 0
    fi

    DSM_VER=$(detect_dsm)
    TARGET=$(detect_arch)
    detect_lan

    info "NodeX VPN — Synology DSM Installer"
    info "===================================="
    info "DSM Version  : $DSM_VER"
    info "Architecture : $TARGET"
    info "LAN iface    : $LAN_IFACE"
    info "Mode         : $MODE"
    info "Version      : $NODEX_VERSION"
    echo ""

    setup_tun
    download_binary "$TARGET"
    create_config
    setup_iptables

    # Install service based on DSM version
    DSM_MAJOR=$(echo "$DSM_VER" | cut -d'.' -f1)
    if [ "$DSM_MAJOR" -ge 7 ]; then
        install_dsm7_service
    else
        install_dsm6_service
    fi

    create_spk_info

    echo ""
    info "===================================="
    info "Installation complete!"
    info ""
    info "Start NodeX VPN:"
    if [ "$DSM_MAJOR" -ge 7 ]; then
        info "  /usr/local/etc/rc.d/nodex-vpn.sh start"
    else
        info "  initctl start nodex-vpn"
    fi
    info ""
    info "Or start manually:"
    info "  /usr/local/bin/nodex-vpn --config /etc/nodex/nodex.conf"
    info ""
    case "$MODE" in
        both)     info "All NAS traffic AND LAN client traffic → Tor" ;;
        lan-only) info "LAN client traffic → Tor (NAS traffic unchanged)" ;;
        nas-only) info "NAS outbound traffic → Tor (LAN clients unchanged)" ;;
    esac
    info ""
    info "Test: curl --socks5 127.0.0.1:9050 https://check.torproject.org/api/ip"
    info "Or from a LAN client: curl https://check.torproject.org/api/ip"
    info ""
    info "Uninstall: sh setup_synology.sh --uninstall"
}

main
