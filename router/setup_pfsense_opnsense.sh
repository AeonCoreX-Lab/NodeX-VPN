#!/bin/sh
# ─────────────────────────────────────────────────────────────────────────────
# NodeX VPN — pfSense / OPNsense Setup
#
# Installs NodeX VPN on pfSense 2.7+ or OPNsense 24+ (FreeBSD 14 base).
#
# What this does:
#   1. Detects pfSense vs OPNsense and shell environment
#   2. Loads the if_tun kernel module (kldload)
#   3. Installs the FreeBSD nodex-vpn binary to /usr/local/bin/
#   4. Creates /etc/nodex/nodex.conf with your LAN interface / subnet
#   5. Writes a pf(4) anchor file: /etc/nodex/nodex.pf
#   6. Patches /etc/pf.conf (or OPNsense backend) to load the anchor
#   7. Installs an rc.d service: /usr/local/etc/rc.d/nodex_vpn
#   8. Optionally integrates with pfSense/OPNsense shell hooks
#
# Usage:
#   sh setup_pfsense_opnsense.sh                  # auto-detect LAN
#   sh setup_pfsense_opnsense.sh --lan igb1       # specify LAN iface
#   sh setup_pfsense_opnsense.sh --uninstall
#
# Requirements:
#   • pfSense 2.7+ / OPNsense 24.1+  (FreeBSD 14.x base)
#   • x86_64 architecture (pfSense/OPNsense do not run on ARM in practice)
#   • Root / sudo privileges
# ─────────────────────────────────────────────────────────────────────────────

set -e

NODEX_VERSION="${NODEX_VERSION:-latest}"
GITHUB_REPO="AeonCoreX-Lab/NodeX-VPN"
BINARY_TARGET="x86_64-unknown-freebsd"
INSTALL_DIR="/usr/local/bin"
CONFIG_DIR="/etc/nodex"
PF_ANCHOR_FILE="${CONFIG_DIR}/nodex.pf"
PF_ANCHOR_NAME="nodex"
RC_SCRIPT="/usr/local/etc/rc.d/nodex_vpn"
TUN_IFACE="tun0"
TUN_LOCAL="10.66.0.1"
TUN_PEER="10.66.0.2"
TOR_SOCKS="127.0.0.1:9050"
TOR_TRANS="9040"
TOR_DNS="5353"

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { printf "${GREEN}[NodeX]${NC} %s\n" "$*"; }
warn()  { printf "${YELLOW}[NodeX]${NC} %s\n" "$*"; }
error() { printf "${RED}[NodeX ERROR]${NC} %s\n" "$*" >&2; exit 1; }

# ── Guards ────────────────────────────────────────────────────────────────────
[ "$(id -u)" -eq 0 ] || error "Must run as root."
uname -s | grep -qi "freebsd" || error "This script requires FreeBSD (pfSense / OPNsense)."

# ── Argument parsing ──────────────────────────────────────────────────────────
LAN_IFACE=""
LAN_NET=""
UNINSTALL=0
while [ $# -gt 0 ]; do
    case "$1" in
        --lan)       LAN_IFACE="$2"; shift 2 ;;
        --lan-net)   LAN_NET="$2";   shift 2 ;;
        --version)   NODEX_VERSION="$2"; shift 2 ;;
        --uninstall) UNINSTALL=1; shift ;;
        *) warn "Unknown argument: $1"; shift ;;
    esac
done

# ── Detect pfSense vs OPNsense ────────────────────────────────────────────────
detect_platform() {
    if [ -f /etc/platform ] && grep -qi "pfSense" /etc/platform 2>/dev/null; then
        echo "pfsense"
    elif [ -d /usr/local/opnsense ] || [ -f /usr/local/sbin/opnsense-version ]; then
        echo "opnsense"
    elif uname -r | grep -q "RELEASE"; then
        echo "freebsd"
    else
        echo "freebsd"
    fi
}

# ── Detect LAN interface ──────────────────────────────────────────────────────
detect_lan() {
    [ -n "$LAN_IFACE" ] && return

    PLATFORM="$1"
    case "$PLATFORM" in
        pfsense)
            # pfSense stores config in /cf/conf/config.xml
            if [ -f /cf/conf/config.xml ] && command -v php >/dev/null 2>&1; then
                LAN_IFACE=$(php -r "
                    \$xml = simplexml_load_file('/cf/conf/config.xml');
                    echo (string)\$xml->interfaces->lan->if;
                " 2>/dev/null || echo "")
            fi
            ;;
        opnsense)
            # OPNsense: /conf/config.xml
            if [ -f /conf/config.xml ] && command -v php >/dev/null 2>&1; then
                LAN_IFACE=$(php -r "
                    \$xml = simplexml_load_file('/conf/config.xml');
                    echo (string)\$xml->interfaces->lan->if;
                " 2>/dev/null || echo "")
            fi
            ;;
    esac

    # Fallback: pick first non-loopback, non-WAN interface
    if [ -z "$LAN_IFACE" ]; then
        LAN_IFACE=$(ifconfig -l | tr ' ' '\n' | grep -v "^lo" | grep -v "^em0\|^igb0\|^vtnet0" | head -1)
        [ -z "$LAN_IFACE" ] && LAN_IFACE="em1"
        warn "Could not auto-detect LAN interface. Using $LAN_IFACE"
        warn "Override with: --lan <iface>"
    fi
}

# Derive LAN subnet from interface address
detect_lan_net() {
    [ -n "$LAN_NET" ] && return
    ADDR=$(ifconfig "$LAN_IFACE" 2>/dev/null | awk '/inet /{print $2; exit}')
    if [ -n "$ADDR" ]; then
        # Convert x.x.x.x to x.x.x.0/24 (assumes /24 — adjust if needed)
        LAN_NET=$(echo "$ADDR" | sed 's/\.[0-9]*$/.0/')"/24"
        info "Detected LAN subnet: $LAN_NET (from $LAN_IFACE)"
    else
        LAN_NET="192.168.1.0/24"
        warn "Could not detect LAN subnet. Defaulting to $LAN_NET"
        warn "Override with: --lan-net <cidr>"
    fi
}

# ── Download binary ───────────────────────────────────────────────────────────
download_binary() {
    info "Downloading NodeX VPN for FreeBSD ($BINARY_TARGET) ..."

    if [ "$NODEX_VERSION" = "latest" ]; then
        URL="https://github.com/${GITHUB_REPO}/releases/latest/download/nodex-vpn-router-${BINARY_TARGET}.tar.gz"
    else
        URL="https://github.com/${GITHUB_REPO}/releases/download/${NODEX_VERSION}/nodex-vpn-router-${BINARY_TARGET}.tar.gz"
    fi

    TMP=$(mktemp -d)
    trap 'rm -rf "$TMP"' EXIT

    if command -v fetch >/dev/null 2>&1; then
        fetch -q -o "$TMP/nodex.tar.gz" "$URL" || error "Download failed: $URL"
    elif command -v curl >/dev/null 2>&1; then
        curl -fsSL "$URL" -o "$TMP/nodex.tar.gz" || error "Download failed: $URL"
    else
        error "No download tool found (fetch/curl). Install curl: pkg install curl"
    fi

    tar -xzf "$TMP/nodex.tar.gz" -C "$TMP"
    install -m 755 "$TMP/nodex-vpn" "${INSTALL_DIR}/nodex-vpn"
    info "Binary installed: ${INSTALL_DIR}/nodex-vpn"
}

# ── Create config ─────────────────────────────────────────────────────────────
create_config() {
    mkdir -p "$CONFIG_DIR"
    cat > "${CONFIG_DIR}/nodex.conf" << CONF
# NodeX VPN — FreeBSD (pfSense / OPNsense) Configuration
# Generated by setup_pfsense_opnsense.sh

[vpn]
tun_name    = ${TUN_IFACE}
tun_addr    = ${TUN_LOCAL}
tun_peer    = ${TUN_PEER}
socks5_addr = ${TOR_SOCKS}

[tor]
data_dir    = /etc/nodex/tor-data
log_level   = warn

[router]
platform        = freebsd
route_lan       = true
lan_iface       = ${LAN_IFACE}
lan_net         = ${LAN_NET}
trans_port      = ${TOR_TRANS}
dns_port        = ${TOR_DNS}
# Exclude router management traffic from Tor
exclude_local   = true
CONF
    info "Config written: ${CONFIG_DIR}/nodex.conf"
}

# ── Write pf anchor ───────────────────────────────────────────────────────────
write_pf_anchor() {
    cat > "$PF_ANCHOR_FILE" << PF
# NodeX VPN — pf(4) anchor rules
# Generated by setup_pfsense_opnsense.sh
# Loaded by: pfctl -a ${PF_ANCHOR_NAME} -f ${PF_ANCHOR_FILE}
#
# Redirects all LAN TCP traffic through NodeX TransPort (transparent Tor proxy)
# Redirects all LAN DNS  traffic through NodeX DNSPort  (DNS-over-Tor)

# ── Tables ────────────────────────────────────────────────────────────────────
# Bypass table: these destinations are NOT routed through Tor
# Includes: loopback, RFC-1918 private, and the router itself
table <nodex_bypass> persist { 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, \
                                192.168.0.0/16, ${TUN_LOCAL}/32 }

# ── Redirect rules ────────────────────────────────────────────────────────────
# LAN TCP  →  NodeX TransPort (9040) which forwards to Tor SOCKS5
rdr pass on ${LAN_IFACE} proto tcp \
    from ${LAN_NET} to !<nodex_bypass> \
    -> 127.0.0.1 port ${TOR_TRANS}

# LAN DNS UDP  →  NodeX DNSPort (5353) which resolves via Tor
rdr pass on ${LAN_IFACE} proto udp \
    from ${LAN_NET} to any port 53 \
    -> 127.0.0.1 port ${TOR_DNS}

# LAN DNS TCP  →  NodeX DNSPort (5353)
rdr pass on ${LAN_IFACE} proto tcp \
    from ${LAN_NET} to any port 53 \
    -> 127.0.0.1 port ${TOR_DNS}
PF
    info "pf anchor rules written: $PF_ANCHOR_FILE"
}

# ── Patch pf.conf ─────────────────────────────────────────────────────────────
patch_pf_conf() {
    PLATFORM="$1"

    case "$PLATFORM" in
        pfsense)
            # pfSense manages pf.conf via PHP — we use the pfSense filter hook
            PF_CUSTOM="/etc/pfSense-custom.conf"
            if [ -f /etc/pf.conf ] && ! grep -q "$PF_ANCHOR_NAME" /etc/pf.conf; then
                # Append anchor include at the end of the rdr section
                printf '\n# NodeX VPN — Tor redirect anchor\nrdr-anchor "%s"\nanchor "%s"\n' \
                    "$PF_ANCHOR_NAME" "$PF_ANCHOR_NAME" >> /etc/pf.conf
                info "Patched /etc/pf.conf with NodeX anchor"
            fi

            # pfSense regenerates pf.conf on filter reload — write a persistent hook
            HOOK_DIR="/etc/rc.filter_configure_sync.d"
            mkdir -p "$HOOK_DIR"
            cat > "${HOOK_DIR}/10_nodex_anchor.sh" << 'HOOK'
#!/bin/sh
# Re-apply NodeX anchor after pfSense regenerates pf.conf
sleep 2
pfctl -a nodex -f /etc/nodex/nodex.pf 2>/dev/null || true
HOOK
            chmod +x "${HOOK_DIR}/10_nodex_anchor.sh"
            info "pfSense filter hook installed: ${HOOK_DIR}/10_nodex_anchor.sh"
            ;;

        opnsense)
            # OPNsense: use the configd / template mechanism
            CONFIGD_DIR="/usr/local/opnsense/service/conf/actions.d"
            mkdir -p "$CONFIGD_DIR"
            cat > "${CONFIGD_DIR}/actions_nodex.conf" << 'CONFIGD'
[reload_anchor]
command:/usr/local/bin/nodex-vpn-pf-hook.sh
parameters:
type:script
message:reload NodeX pf anchor
CONFIGD

            cat > "/usr/local/bin/nodex-vpn-pf-hook.sh" << 'HOOK'
#!/bin/sh
pfctl -a nodex -f /etc/nodex/nodex.pf 2>/dev/null || true
HOOK
            chmod +x "/usr/local/bin/nodex-vpn-pf-hook.sh"
            info "OPNsense configd action installed"

            # Also patch pf.conf directly for immediate effect
            if [ -f /tmp/rules.debug ] || [ -f /etc/pf.conf ]; then
                PFCONF="${/etc/pf.conf:-/tmp/rules.debug}"
                if ! grep -q "$PF_ANCHOR_NAME" "$PFCONF" 2>/dev/null; then
                    printf '\n# NodeX VPN\nrdr-anchor "%s"\nanchor "%s"\n' \
                        "$PF_ANCHOR_NAME" "$PF_ANCHOR_NAME" >> "$PFCONF"
                    info "Patched $PFCONF with NodeX anchor"
                fi
            fi
            ;;

        *)
            # Generic FreeBSD: patch /etc/pf.conf
            if [ -f /etc/pf.conf ] && ! grep -q "$PF_ANCHOR_NAME" /etc/pf.conf; then
                printf '\n# NodeX VPN\nrdr-anchor "%s"\nanchor "%s"\n' \
                    "$PF_ANCHOR_NAME" "$PF_ANCHOR_NAME" >> /etc/pf.conf
                info "Patched /etc/pf.conf with NodeX anchor"
            fi
            ;;
    esac

    # Load anchor immediately
    pfctl -a "$PF_ANCHOR_NAME" -f "$PF_ANCHOR_FILE" 2>/dev/null || \
        warn "pfctl anchor load deferred (pf may not be running yet)"
    pfctl -e 2>/dev/null || true
}

# ── Load kernel module ────────────────────────────────────────────────────────
setup_kmod() {
    info "Loading if_tun kernel module ..."
    kldload if_tun 2>/dev/null || true
    # Persist across reboots
    if ! grep -q "if_tun" /boot/loader.conf 2>/dev/null; then
        echo 'if_tun_load="YES"' >> /boot/loader.conf
        info "if_tun added to /boot/loader.conf"
    fi
}

# ── Install rc.d service ──────────────────────────────────────────────────────
install_rcd() {
    cat > "$RC_SCRIPT" << 'RCD'
#!/bin/sh
# PROVIDE: nodex_vpn
# REQUIRE: NETWORKING pf
# KEYWORD: shutdown

. /etc/rc.subr

name="nodex_vpn"
rcvar="nodex_vpn_enable"
command="/usr/local/bin/nodex-vpn"
command_args="--config /etc/nodex/nodex.conf --daemon"
pidfile="/var/run/nodex_vpn.pid"
start_precmd="nodex_precmd"

nodex_precmd() {
    # Ensure if_tun is loaded
    kldload if_tun 2>/dev/null || true
    # Load pf anchor
    pfctl -a nodex -f /etc/nodex/nodex.pf 2>/dev/null || true
    return 0
}

load_rc_config "$name"
: ${nodex_vpn_enable:="NO"}

run_rc_command "$1"
RCD
    chmod +x "$RC_SCRIPT"

    # Enable in rc.conf (if not already)
    if ! grep -q "nodex_vpn_enable" /etc/rc.conf 2>/dev/null; then
        echo 'nodex_vpn_enable="YES"' >> /etc/rc.conf
    fi
    info "rc.d service installed: $RC_SCRIPT"
    info "Enabled in /etc/rc.conf"
}

# ── Uninstall ─────────────────────────────────────────────────────────────────
uninstall() {
    info "Removing NodeX VPN ..."
    service nodex_vpn stop 2>/dev/null || killall nodex-vpn 2>/dev/null || true

    # Remove pf anchor
    pfctl -a "$PF_ANCHOR_NAME" -F all 2>/dev/null || true

    # Remove anchor lines from pf.conf
    for PFCONF in /etc/pf.conf /tmp/rules.debug; do
        [ -f "$PFCONF" ] && sed -i '' "/NodeX VPN/d;/anchor \"${PF_ANCHOR_NAME}\"/d;/rdr-anchor \"${PF_ANCHOR_NAME}\"/d" "$PFCONF" 2>/dev/null || true
    done

    # Remove rc.conf entry
    sed -i '' '/nodex_vpn_enable/d' /etc/rc.conf 2>/dev/null || true

    # Remove files
    rm -f "$RC_SCRIPT"
    rm -f "${INSTALL_DIR}/nodex-vpn"
    rm -f "/usr/local/bin/nodex-vpn-pf-hook.sh"
    rm -f "/etc/rc.filter_configure_sync.d/10_nodex_anchor.sh"
    rm -rf "$CONFIG_DIR"

    # Remove loader.conf entry
    sed -i '' '/if_tun_load/d' /boot/loader.conf 2>/dev/null || true

    info "NodeX VPN removed."
}

# ── Main ──────────────────────────────────────────────────────────────────────
main() {
    if [ "$UNINSTALL" -eq 1 ]; then
        uninstall
        exit 0
    fi

    PLATFORM=$(detect_platform)
    detect_lan "$PLATFORM"
    detect_lan_net

    info "NodeX VPN — pfSense / OPNsense Installer"
    info "========================================="
    info "Platform    : $PLATFORM"
    info "LAN iface   : $LAN_IFACE"
    info "LAN subnet  : $LAN_NET"
    info "Version     : $NODEX_VERSION"
    echo ""

    setup_kmod
    download_binary
    create_config
    write_pf_anchor
    patch_pf_conf "$PLATFORM"
    install_rcd

    echo ""
    info "========================================="
    info "Installation complete!"
    info ""
    info "Start NodeX VPN:"
    info "  service nodex_vpn start"
    info ""
    info "Or start manually:"
    info "  /usr/local/bin/nodex-vpn --config /etc/nodex/nodex.conf"
    info ""
    info "All LAN devices will be routed through Tor."
    info "Test from a LAN client: curl https://check.torproject.org/api/ip"
    info ""
    info "pfSense/OPNsense note:"
    info "  If pf rules reset after a firewall reload, run:"
    info "  pfctl -a nodex -f /etc/nodex/nodex.pf"
    info ""
    info "Uninstall: sh setup_pfsense_opnsense.sh --uninstall"
}

main
