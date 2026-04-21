#!/bin/sh
# ─────────────────────────────────────────────────────────────────────────────
# NodeX VPN — GL.iNet / Asus Merlin Setup
#
# GL.iNet runs OpenWrt under the hood — this script wraps setup_openwrt.sh
# and adds GL.iNet-specific paths and Merlin jffs support.
# ─────────────────────────────────────────────────────────────────────────────

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { printf "${GREEN}[NodeX]${NC} %s\n" "$*"; }
warn()  { printf "${YELLOW}[NodeX]${NC} %s\n" "$*"; }
error() { printf "${RED}[NodeX]${NC} %s\n" "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || error "Must run as root"

detect_variant() {
    if [ -f /etc/glversion ] || [ -f /etc/gl_version ]; then
        echo "glinet"
    elif [ -f /etc/asuswrt-merlin.version ] || [ -f /etc/asuswrt_merlin.version ]; then
        echo "merlin"
    elif [ -f /etc/openwrt_release ]; then
        echo "openwrt"
    else
        echo "unknown"
    fi
}

setup_glinet() {
    info "GL.iNet detected"
    info "GL.iNet runs OpenWrt — using OpenWrt setup ..."

    # GL.iNet sometimes uses eth0 as LAN bridge device
    if ip link show gl-lan >/dev/null 2>&1; then
        export LAN_IFACE="gl-lan"
    fi

    sh "$SCRIPT_DIR/setup_openwrt.sh"

    info ""
    info "GL.iNet specific: NodeX VPN will appear in the GL.iNet web panel"
    info "under Network → VPN if you install the gl-vpn package."
}

setup_merlin() {
    info "Asus Merlin detected"

    # Merlin uses JFFS2 for persistent scripts
    JFFS="/jffs"
    if [ ! -d "$JFFS" ]; then
        warn "JFFS2 not mounted. Enable JFFS2 in router admin panel first."
        warn "Administration → System → Persistent JFFS2 partition → Enable"
        exit 1
    fi

    SCRIPTS_DIR="$JFFS/scripts"
    mkdir -p "$SCRIPTS_DIR"

    # Copy binary to JFFS (persists across firmware updates)
    if [ -f /usr/bin/nodex-vpn ]; then
        cp /usr/bin/nodex-vpn "$JFFS/nodex-vpn"
        chmod +x "$JFFS/nodex-vpn"
        info "Binary copied to $JFFS/nodex-vpn (persists firmware updates)"
    fi

    # Merlin firewall script (runs on every boot)
    cat > "$SCRIPTS_DIR/firewall-start" << 'MERLIN_FW'
#!/bin/sh
# NodeX VPN — Asus Merlin firewall hook

# Wait for network
sleep 5

# Start NodeX VPN
/jffs/nodex-vpn --config /etc/nodex/nodex.conf --daemon

# Redirect LAN traffic through Tor
iptables -t nat -A PREROUTING -i br0 -p tcp \
    -j REDIRECT --to-ports 9040
iptables -t nat -A PREROUTING -i br0 -p udp --dport 53 \
    -j REDIRECT --to-ports 5353

logger "NodeX VPN started"
MERLIN_FW
    chmod +x "$SCRIPTS_DIR/firewall-start"

    info "Merlin firewall hook installed at $SCRIPTS_DIR/firewall-start"
    info "NodeX VPN will start automatically on every boot."
    info ""
    info "To start now: $JFFS/nodex-vpn --config /etc/nodex/nodex.conf --daemon"
}

main() {
    VARIANT=$(detect_variant)
    info "Detected variant: $VARIANT"

    case "$VARIANT" in
        glinet)   setup_glinet ;;
        merlin)   setup_merlin ;;
        openwrt)  sh "$SCRIPT_DIR/setup_openwrt.sh" ;;
        *)
            warn "Unknown firmware variant. Trying generic OpenWrt setup ..."
            sh "$SCRIPT_DIR/setup_openwrt.sh"
            ;;
    esac
}

main
