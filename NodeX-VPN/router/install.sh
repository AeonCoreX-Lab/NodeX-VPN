#!/bin/sh
# ─────────────────────────────────────────────────────────────────────────────
# NodeX VPN — Universal Router Installer
# Supports: OpenWrt, DD-WRT, Tomato, Asus Merlin, GL.iNet
#
# Usage:
#   sh install.sh                      # auto-detect router firmware
#   sh install.sh --firmware openwrt   # force firmware type
#   sh install.sh --uninstall          # remove NodeX VPN
#
# What this does:
#   1. Detects CPU architecture (x86_64 or aarch64)
#   2. Downloads the correct NodeX VPN binary from GitHub Releases
#   3. Installs tun2socks kernel module (if needed)
#   4. Creates /etc/nodex/ config directory
#   5. Installs the init.d / rc.d service
#   6. Routes all LAN traffic through Tor SOCKS5 via TUN interface
# ─────────────────────────────────────────────────────────────────────────────

set -e

NODEX_VERSION="${NODEX_VERSION:-latest}"
GITHUB_REPO="your-org/NodeX-VPN"
INSTALL_DIR="/usr/bin"
CONFIG_DIR="/etc/nodex"
LOG_FILE="/var/log/nodex.log"
TUN_IFACE="nodex0"
TUN_ADDR="10.66.0.1"
TUN_NETWORK="10.66.0.0/24"
TOR_SOCKS="127.0.0.1:9050"

# ── Colour output ─────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()    { printf "${GREEN}[NodeX]${NC} %s\n" "$*"; }
warn()    { printf "${YELLOW}[NodeX]${NC} %s\n" "$*"; }
error()   { printf "${RED}[NodeX ERROR]${NC} %s\n" "$*" >&2; exit 1; }

# ── Root check ────────────────────────────────────────────────────────────────
[ "$(id -u)" -eq 0 ] || error "This script must be run as root."

# ── Parse arguments ───────────────────────────────────────────────────────────
FIRMWARE=""
UNINSTALL=0
while [ $# -gt 0 ]; do
    case "$1" in
        --firmware) FIRMWARE="$2"; shift 2 ;;
        --uninstall) UNINSTALL=1; shift ;;
        --version)  NODEX_VERSION="$2"; shift 2 ;;
        *) warn "Unknown argument: $1"; shift ;;
    esac
done

# ── Detect CPU arch ───────────────────────────────────────────────────────────
detect_arch() {
    ARCH=$(uname -m)
    OS=$(uname -s)
    case "$OS" in
        FreeBSD)
            case "$ARCH" in
                x86_64|amd64) echo "x86_64-unknown-freebsd" ;;
                aarch64|arm64) echo "aarch64-unknown-freebsd" ;;
                *) error "Unsupported FreeBSD arch: $ARCH" ;;
            esac
            ;;
        Linux)
            case "$ARCH" in
                x86_64)         echo "x86_64-unknown-linux-gnu" ;;
                aarch64|arm64)  echo "aarch64-unknown-linux-gnu" ;;
                armv7*|armv6*)  error "armv7/v6 is not supported. NodeX requires a 64-bit router." ;;
                *)              error "Unsupported architecture: $ARCH" ;;
            esac
            ;;
        *)
            case "$ARCH" in
                x86_64)         echo "x86_64-unknown-linux-gnu" ;;
                aarch64|arm64)  echo "aarch64-unknown-linux-gnu" ;;
                *)              error "Unsupported OS/arch: $OS/$ARCH" ;;
            esac
            ;;
    esac
}

# ── Detect firmware ───────────────────────────────────────────────────────────
detect_firmware() {
    [ -n "$FIRMWARE" ] && echo "$FIRMWARE" && return

    OS=$(uname -s)
    case "$OS" in
        FreeBSD)
            if [ -f /etc/platform ] && grep -qi "pfSense" /etc/platform 2>/dev/null; then
                echo "pfsense"
            elif [ -d /usr/local/opnsense ] || [ -f /usr/local/sbin/opnsense-version ]; then
                echo "opnsense"
            else
                echo "freebsd"
            fi
            return
            ;;
    esac

    # Linux-based detection
    if [ -f /etc/synoinfo.conf ] || [ -f /proc/syno_version ]; then
        echo "synology"
    elif [ -f /etc/openwrt_release ]; then
        echo "openwrt"
    elif [ -f /proc/nvram ] && grep -q "DD-WRT" /proc/nvram 2>/dev/null; then
        echo "ddwrt"
    elif [ -f /etc/asuswrt-merlin.version ] || [ -f /etc/asuswrt_merlin.version ]; then
        echo "merlin"
    elif [ -d /etc/config ] && [ -f /etc/board.json ]; then
        echo "openwrt"   # GL.iNet runs OpenWrt
    elif uname -r | grep -q "tomato"; then
        echo "tomato"
    else
        warn "Could not auto-detect firmware. Defaulting to generic Linux."
        echo "linux"
    fi
}

# ── Download binary ───────────────────────────────────────────────────────────
download_binary() {
    TARGET="$1"
    info "Downloading NodeX VPN binary for $TARGET ..."

    if [ "$NODEX_VERSION" = "latest" ]; then
        DOWNLOAD_URL="https://github.com/${GITHUB_REPO}/releases/latest/download/nodex-vpn-router-${TARGET}.tar.gz"
    else
        DOWNLOAD_URL="https://github.com/${GITHUB_REPO}/releases/download/${NODEX_VERSION}/nodex-vpn-router-${TARGET}.tar.gz"
    fi

    TMP_DIR=$(mktemp -d)
    trap 'rm -rf "$TMP_DIR"' EXIT

    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$DOWNLOAD_URL" -o "$TMP_DIR/nodex.tar.gz" || error "Download failed: $DOWNLOAD_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -qO "$TMP_DIR/nodex.tar.gz" "$DOWNLOAD_URL" || error "Download failed: $DOWNLOAD_URL"
    else
        error "Neither curl nor wget found. Install one first:\n  opkg install curl"
    fi

    tar -xzf "$TMP_DIR/nodex.tar.gz" -C "$TMP_DIR"
    install -m 755 "$TMP_DIR/nodex-vpn" "$INSTALL_DIR/nodex-vpn"
    info "Binary installed to $INSTALL_DIR/nodex-vpn"
}

# ── Create config ─────────────────────────────────────────────────────────────
create_config() {
    mkdir -p "$CONFIG_DIR"
    cat > "$CONFIG_DIR/nodex.conf" << CONF
# NodeX VPN Router Configuration
# Generated by install.sh — edit as needed

[vpn]
tun_name    = ${TUN_IFACE}
tun_addr    = ${TUN_ADDR}
tun_network = ${TUN_NETWORK}
socks5_addr = ${TOR_SOCKS}

[tor]
# Tor is embedded in NodeX VPN core — no separate Tor install needed
data_dir    = /etc/nodex/tor-data
log_level   = warn

[router]
# Protect all LAN clients automatically
route_lan   = true
lan_iface   = br-lan
# Exclude router's own management traffic from Tor
exclude_local = true
CONF
    info "Config written to $CONFIG_DIR/nodex.conf"
}

# ── OpenWrt init script ───────────────────────────────────────────────────────
install_openwrt_service() {
    cat > /etc/init.d/nodex << 'INITD'
#!/bin/sh /etc/rc.common
# NodeX VPN — OpenWrt init script
START=90
STOP=10
USE_PROCD=1

start_service() {
    procd_open_instance
    procd_set_param command /usr/bin/nodex-vpn --config /etc/nodex/nodex.conf
    procd_set_param respawn 3600 5 0
    procd_set_param stdout 1
    procd_set_param stderr 1
    procd_set_param file /etc/nodex/nodex.conf
    procd_close_instance
}

service_triggers() {
    procd_add_reload_trigger "network"
}
INITD
    chmod +x /etc/init.d/nodex
    /etc/init.d/nodex enable
    info "OpenWrt service installed and enabled"
}

# ── Generic Linux / DD-WRT / Merlin init ─────────────────────────────────────
install_generic_service() {
    # Try to detect init system
    if [ -d /etc/init.d ] && command -v update-rc.d >/dev/null 2>&1; then
        # Debian-style
        cat > /etc/init.d/nodex-vpn << 'INITD'
#!/bin/sh
### BEGIN INIT INFO
# Provides:          nodex-vpn
# Required-Start:    $network $remote_fs
# Required-Stop:     $network
# Default-Start:     2 3 4 5
# Default-Stop:      0 1 6
# Short-Description: NodeX VPN (Tor router)
### END INIT INFO
case "$1" in
    start) /usr/bin/nodex-vpn --config /etc/nodex/nodex.conf --daemon ;;
    stop)  killall nodex-vpn 2>/dev/null || true ;;
    restart) $0 stop; sleep 1; $0 start ;;
    status) killall -0 nodex-vpn 2>/dev/null && echo "running" || echo "stopped" ;;
esac
INITD
        chmod +x /etc/init.d/nodex-vpn
        update-rc.d nodex-vpn defaults
    else
        # Fallback: add to /etc/rc.local
        if [ -f /etc/rc.local ]; then
            grep -q "nodex-vpn" /etc/rc.local || \
                sed -i 's|^exit 0|/usr/bin/nodex-vpn --config /etc/nodex/nodex.conf --daemon\nexit 0|' /etc/rc.local
            info "Added NodeX VPN to /etc/rc.local"
        else
            warn "Could not install service. Start manually with:"
            warn "  /usr/bin/nodex-vpn --config /etc/nodex/nodex.conf"
        fi
    fi
}

# ── OpenWrt kernel module ─────────────────────────────────────────────────────
install_openwrt_deps() {
    info "Installing kernel TUN module ..."
    opkg update -q 2>/dev/null || warn "opkg update failed (continuing)"
    opkg install kmod-tun 2>/dev/null || warn "kmod-tun already installed"
    modprobe tun 2>/dev/null || true
    info "TUN module loaded"
}

# ── Uninstall ─────────────────────────────────────────────────────────────────
uninstall() {
    info "Removing NodeX VPN ..."
    killall nodex-vpn 2>/dev/null || true
    ip link del "$TUN_IFACE" 2>/dev/null || true
    ip rule del table 200 2>/dev/null || true
    ip route flush table 200 2>/dev/null || true

    # Remove iptables rules
    iptables -t nat -D PREROUTING -i br-lan -p tcp \
        -j REDIRECT --to-ports 9040 2>/dev/null || true
    iptables -t nat -D PREROUTING -i br-lan -p udp --dport 53 \
        -j REDIRECT --to-ports 5353 2>/dev/null || true

    # Remove service
    [ -f /etc/init.d/nodex ]     && { /etc/init.d/nodex disable; rm -f /etc/init.d/nodex; }
    [ -f /etc/init.d/nodex-vpn ] && { rm -f /etc/init.d/nodex-vpn; }

    # Remove files
    rm -f "$INSTALL_DIR/nodex-vpn"
    rm -rf "$CONFIG_DIR"

    info "NodeX VPN removed successfully."
}

# ── Main ──────────────────────────────────────────────────────────────────────
main() {
    if [ "$UNINSTALL" -eq 1 ]; then
        uninstall
        exit 0
    fi

    info "NodeX VPN Router Installer"
    info "========================================"

    TARGET=$(detect_arch)
    FIRMWARE=$(detect_firmware)

    info "Architecture : $TARGET"
    info "Firmware     : $FIRMWARE"
    info "Version      : $NODEX_VERSION"
    echo ""

    download_binary "$TARGET"
    create_config

    case "$FIRMWARE" in
        openwrt)
            install_openwrt_deps
            install_openwrt_service
            ;;
        ddwrt|tomato|merlin|linux)
            install_generic_service
            ;;
        pfsense|opnsense|freebsd)
            info "Delegating to setup_pfsense_opnsense.sh ..."
            SCRIPT_DIR=$(dirname "$0")
            if [ -f "${SCRIPT_DIR}/setup_pfsense_opnsense.sh" ]; then
                sh "${SCRIPT_DIR}/setup_pfsense_opnsense.sh"
            else
                warn "setup_pfsense_opnsense.sh not found. Downloading ..."
                TMP_SCRIPT=$(mktemp)
                if command -v fetch >/dev/null 2>&1; then
                    fetch -q -o "$TMP_SCRIPT" "https://github.com/${GITHUB_REPO}/releases/latest/download/setup_pfsense_opnsense.sh"
                else
                    curl -fsSL "https://github.com/${GITHUB_REPO}/releases/latest/download/setup_pfsense_opnsense.sh" -o "$TMP_SCRIPT"
                fi
                sh "$TMP_SCRIPT"
                rm -f "$TMP_SCRIPT"
            fi
            exit 0
            ;;
        synology)
            info "Delegating to setup_synology.sh ..."
            SCRIPT_DIR=$(dirname "$0")
            if [ -f "${SCRIPT_DIR}/setup_synology.sh" ]; then
                sh "${SCRIPT_DIR}/setup_synology.sh"
            else
                warn "setup_synology.sh not found. Downloading ..."
                TMP_SCRIPT=$(mktemp)
                curl -fsSL "https://github.com/${GITHUB_REPO}/releases/latest/download/setup_synology.sh" -o "$TMP_SCRIPT"
                sh "$TMP_SCRIPT"
                rm -f "$TMP_SCRIPT"
            fi
            exit 0
            ;;
    esac

    echo ""
    info "========================================"
    info "Installation complete!"
    info ""
    info "Start NodeX VPN:"
    case "$FIRMWARE" in
        openwrt) info "  /etc/init.d/nodex start" ;;
        pfsense|opnsense|freebsd) info "  service nodex_vpn start" ;;
        synology) info "  /usr/local/etc/rc.d/nodex-vpn.sh start" ;;
        *)       info "  /usr/bin/nodex-vpn --config /etc/nodex/nodex.conf" ;;
    esac
    info ""
    info "All devices on your LAN will be routed through Tor automatically."
    info "No per-device app needed."
    info ""
    info "To uninstall: sh install.sh --uninstall"
}

main
