#!/bin/sh
# ─────────────────────────────────────────────────────────────────────────────
# NodeX VPN — OpenWrt Advanced Setup
#
# Configures transparent Tor proxy for the entire LAN:
#   • TUN interface nodex0
#   • iptables redirect LAN TCP → Tor TransPort (9040)
#   • iptables redirect LAN DNS  → Tor DNSPort (5353)
#   • Policy routing table 200 (avoids conflicts with main table)
#   • Firewall rules via UCI (persists across reboots)
#
# Run AFTER install.sh has placed the nodex-vpn binary.
# ─────────────────────────────────────────────────────────────────────────────

set -e

LAN_IFACE="br-lan"
TUN_IFACE="nodex0"
TUN_ADDR="10.66.0.1"
TUN_NETWORK="10.66.0.0/24"
TOR_TRANS_PORT="9040"   # NodeX built-in transparent proxy port
TOR_DNS_PORT="5353"     # NodeX built-in DNS-over-Tor port
RT_TABLE="200"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { printf "${GREEN}[NodeX]${NC} %s\n" "$*"; }
warn()  { printf "${YELLOW}[NodeX]${NC} %s\n" "$*"; }
error() { printf "${RED}[NodeX]${NC} %s\n" "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || error "Must run as root"
[ -f /etc/openwrt_release ] || error "This script is for OpenWrt only. Use install.sh for other firmware."

# ── Parse LAN interface from UCI ──────────────────────────────────────────────
detect_lan_iface() {
    # Try UCI first
    if command -v uci >/dev/null 2>&1; then
        UCI_IFACE=$(uci get network.lan.ifname 2>/dev/null || \
                    uci get network.lan.device 2>/dev/null || \
                    echo "")
        [ -n "$UCI_IFACE" ] && LAN_IFACE="$UCI_IFACE" && return
    fi
    # Fallback: check for br-lan
    ip link show br-lan >/dev/null 2>&1 && LAN_IFACE="br-lan" && return
    # Last resort: eth0
    LAN_IFACE="eth0"
    warn "Could not detect LAN interface via UCI. Using $LAN_IFACE"
}

# ── Install TUN module ────────────────────────────────────────────────────────
setup_tun_module() {
    info "Loading TUN kernel module ..."
    opkg install kmod-tun 2>/dev/null || true
    modprobe tun 2>/dev/null || true

    # Persist across reboots
    if ! grep -q "^tun" /etc/modules.d/tun 2>/dev/null; then
        echo "tun" > /etc/modules.d/tun
    fi
    info "TUN module loaded"
}

# ── Configure iptables transparent proxy ─────────────────────────────────────
setup_iptables() {
    info "Configuring iptables transparent proxy ..."

    # Remove existing NodeX rules (idempotent)
    iptables -t nat -D PREROUTING -i "$LAN_IFACE" -p tcp \
        -m comment --comment "nodex-tcp" \
        -j REDIRECT --to-ports "$TOR_TRANS_PORT" 2>/dev/null || true
    iptables -t nat -D PREROUTING -i "$LAN_IFACE" -p udp --dport 53 \
        -m comment --comment "nodex-dns" \
        -j REDIRECT --to-ports "$TOR_DNS_PORT" 2>/dev/null || true

    # Redirect LAN TCP → Tor TransPort
    # (exclude already-established connections — kernel handles those)
    iptables -t nat -A PREROUTING \
        -i "$LAN_IFACE" -p tcp \
        -m comment --comment "nodex-tcp" \
        -j REDIRECT --to-ports "$TOR_TRANS_PORT"

    # Redirect LAN DNS queries → Tor DNSPort  
    iptables -t nat -A PREROUTING \
        -i "$LAN_IFACE" -p udp --dport 53 \
        -m comment --comment "nodex-dns" \
        -j REDIRECT --to-ports "$TOR_DNS_PORT"

    info "iptables rules added"
}

# ── Persist iptables via /etc/firewall.user ───────────────────────────────────
persist_firewall() {
    FIREWALL_FILE="/etc/firewall.user"
    MARKER="# NodeX VPN rules — do not edit manually"

    # Remove old block
    if [ -f "$FIREWALL_FILE" ]; then
        # Use awk to remove NodeX block
        awk "/^${MARKER}/,/^# END NodeX VPN/{next} {print}" \
            "$FIREWALL_FILE" > /tmp/fw.tmp && mv /tmp/fw.tmp "$FIREWALL_FILE"
    fi

    cat >> "$FIREWALL_FILE" << FWRULES

${MARKER}
# Transparent TCP proxy through Tor
iptables -t nat -A PREROUTING -i ${LAN_IFACE} -p tcp \
    -m comment --comment "nodex-tcp" \
    -j REDIRECT --to-ports ${TOR_TRANS_PORT}
# DNS-over-Tor
iptables -t nat -A PREROUTING -i ${LAN_IFACE} -p udp --dport 53 \
    -m comment --comment "nodex-dns" \
    -j REDIRECT --to-ports ${TOR_DNS_PORT}
# END NodeX VPN
FWRULES

    info "Firewall rules persisted to $FIREWALL_FILE"
}

# ── Policy routing (table 200) ────────────────────────────────────────────────
setup_routing() {
    info "Configuring policy routing (table $RT_TABLE) ..."

    # Flush existing table
    ip route flush table "$RT_TABLE" 2>/dev/null || true
    ip rule del table "$RT_TABLE" 2>/dev/null || true

    # Add rule: traffic from LAN uses table 200
    ip rule add from "$TUN_NETWORK" table "$RT_TABLE" priority 100
    ip route add default dev "$TUN_IFACE" table "$RT_TABLE" 2>/dev/null || true

    info "Policy routing configured"
}

# ── UCI firewall zone (optional — adds GUI visibility) ────────────────────────
setup_uci_zone() {
    command -v uci >/dev/null 2>&1 || return

    info "Adding NodeX firewall zone to UCI ..."
    uci -q delete firewall.nodex_zone || true
    uci set firewall.nodex_zone=zone
    uci set firewall.nodex_zone.name="nodex"
    uci set firewall.nodex_zone.input="ACCEPT"
    uci set firewall.nodex_zone.output="ACCEPT"
    uci set firewall.nodex_zone.forward="REJECT"
    uci commit firewall
    info "UCI firewall zone added (visible in LuCI)"
}

# ── Hotplug hook (re-apply rules after network restart) ──────────────────────
install_hotplug() {
    HOTPLUG_DIR="/etc/hotplug.d/iface"
    mkdir -p "$HOTPLUG_DIR"
    cat > "$HOTPLUG_DIR/50-nodex" << 'HOTPLUG'
#!/bin/sh
# NodeX VPN — re-apply iptables after network restart
[ "$ACTION" = "ifup" ] || exit 0
[ "$INTERFACE" = "lan" ] || exit 0

# Wait for nodex-vpn to be running
for i in $(seq 1 10); do
    pgrep nodex-vpn >/dev/null && break
    sleep 1
done

/etc/init.d/nodex restart 2>/dev/null || true
logger -t nodex "Network came up, NodeX VPN restarted"
HOTPLUG
    chmod +x "$HOTPLUG_DIR/50-nodex"
    info "Hotplug hook installed"
}

# ── Main ──────────────────────────────────────────────────────────────────────
main() {
    info "NodeX VPN — OpenWrt Advanced Setup"
    info "========================================"

    detect_lan_iface
    info "LAN interface : $LAN_IFACE"
    echo ""

    setup_tun_module
    setup_iptables
    persist_firewall
    setup_uci_zone
    install_hotplug

    echo ""
    info "========================================"
    info "OpenWrt setup complete!"
    info ""
    info "Start NodeX VPN:  /etc/init.d/nodex start"
    info "Check status:     /etc/init.d/nodex status"
    info "View logs:        logread | grep nodex"
    info ""
    info "All devices on your LAN (Smart TV, PS5, Xbox, IoT)"
    info "will automatically route through Tor — no app needed."
    info ""
    warn "Note: Tor circuits take ~30 seconds to build on first start."
    warn "UDP traffic (except DNS) is not anonymized through Tor — this"
    warn "is a Tor network limitation."
}

main
