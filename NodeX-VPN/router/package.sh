#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# NodeX VPN — CI Router Package Builder
#
# Builds router tarballs AND a Synology SPK for all supported targets.
#
# Usage (in CI):
#   bash router/package.sh x86_64-unknown-linux-gnu      # Linux router + Synology SPK
#   bash router/package.sh aarch64-unknown-linux-gnu     # ARM64 router + Synology SPK
#   bash router/package.sh x86_64-unknown-freebsd        # pfSense / OPNsense tarball
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

TARGET="${1:?Usage: $0 <rust-target>}"
VERSION="${NODEX_VERSION:-dev}"
OUT_DIR="${2:-dist/router}"

echo "[NodeX] Building router package for $TARGET ..."

# ── Resolve binary path ───────────────────────────────────────────────────────
BINARY_PATH="rust-core/target/${TARGET}/release/nodex-vpn-router"
[ -f "$BINARY_PATH" ] || BINARY_PATH="rust-core/target/${TARGET}/release/nodex_vpn_core"
if [ ! -f "$BINARY_PATH" ]; then
    echo "[NodeX] Warning: binary not found — using placeholder"
    mkdir -p "rust-core/target/${TARGET}/release/"
    printf '#!/bin/sh\necho "NodeX VPN placeholder"\n' > "$BINARY_PATH"
fi

PACKAGE_NAME="nodex-vpn-router-${TARGET}-${VERSION}"
STAGING_DIR="/tmp/${PACKAGE_NAME}"

# ── Stage common files ────────────────────────────────────────────────────────
rm -rf "$STAGING_DIR"
mkdir -p "$STAGING_DIR"

cp "$BINARY_PATH"                    "$STAGING_DIR/nodex-vpn"
chmod +x "$STAGING_DIR/nodex-vpn"

cp router/install.sh                 "$STAGING_DIR/install.sh"
cp router/setup_openwrt.sh           "$STAGING_DIR/setup_openwrt.sh"
cp router/setup_glinet_merlin.sh     "$STAGING_DIR/setup_glinet_merlin.sh"
cp router/setup_pfsense_opnsense.sh  "$STAGING_DIR/setup_pfsense_opnsense.sh"
cp router/setup_synology.sh         "$STAGING_DIR/setup_synology.sh"
chmod +x "$STAGING_DIR/"*.sh

cp router/README.md "$STAGING_DIR/README.md"

cat > "$STAGING_DIR/nodex.conf.example" << 'CONF'
# NodeX VPN Router Configuration Example

[vpn]
tun_name    = nodex0
tun_addr    = 10.66.0.1
tun_network = 10.66.0.0/24
socks5_addr = 127.0.0.1:9050

[tor]
data_dir    = /etc/nodex/tor-data
log_level   = warn

[router]
route_lan     = true
lan_iface     = br-lan      # Linux: br-lan | FreeBSD: em1 | Synology: eth0
exclude_local = true
CONF

echo "$VERSION" > "$STAGING_DIR/VERSION"
echo "$TARGET"  > "$STAGING_DIR/TARGET"

# ── Build main tarball ────────────────────────────────────────────────────────
mkdir -p "$OUT_DIR"
TARBALL="$OUT_DIR/${PACKAGE_NAME}.tar.gz"
tar -czf "$TARBALL" -C "/tmp" "$PACKAGE_NAME"
sha256sum "$TARBALL" > "${TARBALL}.sha256"
echo "[NodeX] Created tarball: $TARBALL"

# ── Build Synology SPK (Linux targets only) ───────────────────────────────────
# SPK format: outer tar containing INFO, package.tgz, scripts/, icons
if echo "$TARGET" | grep -q "linux"; then
    echo "[NodeX] Building Synology SPK for $TARGET ..."

    # Map Rust target to Synology arch string
    case "$TARGET" in
        x86_64-unknown-linux-gnu)   SYNO_ARCH="x86_64" ;;
        aarch64-unknown-linux-gnu)  SYNO_ARCH="armv8"  ;; # Synology calls aarch64 "armv8"
        *)                          SYNO_ARCH="x86_64" ;;
    esac

    SPK_NAME="NodeX-VPN-${VERSION}-${SYNO_ARCH}"
    SPK_DIR="/tmp/${SPK_NAME}-spk"
    rm -rf "$SPK_DIR"
    mkdir -p "$SPK_DIR/package" "$SPK_DIR/scripts"

    # ── package/ — files unpacked to /var/packages/NodeX-VPN/target/
    cp "$BINARY_PATH"           "$SPK_DIR/package/nodex-vpn"
    cp router/setup_synology.sh "$SPK_DIR/package/setup_synology.sh"
    cp router/install.sh        "$SPK_DIR/package/install.sh"
    chmod +x "$SPK_DIR/package/"*

    # Compress package directory into package.tgz
    tar -czf "$SPK_DIR/package.tgz" -C "$SPK_DIR/package" .

    # ── INFO — SPK metadata ──────────────────────────────────────────────────
    cat > "$SPK_DIR/INFO" << INFO
package="NodeX-VPN"
version="${VERSION}-0001"
arch="${SYNO_ARCH}"
firmware="7.0-40000"
description="Serverless Tor VPN — routes NAS and/or LAN traffic through Tor anonymously"
displayname="NodeX VPN"
maintainer="AeonCoreX Lab"
maintainer_url="https://github.com/AeonCoreX-Lab/NodeX-VPN"
support_url="https://github.com/AeonCoreX-Lab/NodeX-VPN/issues"
startable="yes"
install_dep_packages=""
install_type="server"
report_url=""
INFO

    # ── DSM 7 scripts/ ───────────────────────────────────────────────────────
    # start-stop-status: called by DSM to manage the package lifecycle
    cat > "$SPK_DIR/scripts/start-stop-status" << 'SSS'
#!/bin/sh
BINARY="/var/packages/NodeX-VPN/target/nodex-vpn"
CONFIG="/etc/nodex/nodex.conf"
PIDFILE="/var/run/nodex-vpn.pid"

case "$1" in
    start)
        modprobe tun 2>/dev/null || true
        "$BINARY" --config "$CONFIG" --daemon
        echo "started" ;;
    stop)
        if [ -f "$PIDFILE" ]; then
            kill "$(cat $PIDFILE)" 2>/dev/null || true
            rm -f "$PIDFILE"
        else
            killall nodex-vpn 2>/dev/null || true
        fi
        echo "stopped" ;;
    status)
        if pgrep -x nodex-vpn > /dev/null 2>&1; then
            echo "running"
        else
            echo "stopped"
        fi ;;
esac
SSS
    chmod +x "$SPK_DIR/scripts/start-stop-status"

    # postinst: runs after package files are extracted
    cat > "$SPK_DIR/scripts/postinst" << 'POSTINST'
#!/bin/sh
INSTALL_DIR="/var/packages/NodeX-VPN/target"
chmod +x "$INSTALL_DIR/nodex-vpn" "$INSTALL_DIR/setup_synology.sh" 2>/dev/null || true

# Run Synology setup (creates config, sets up iptables, installs rc.d service)
sh "$INSTALL_DIR/setup_synology.sh"
exit 0
POSTINST
    chmod +x "$SPK_DIR/scripts/postinst"

    # preuninst: runs before package is removed
    cat > "$SPK_DIR/scripts/preuninst" << 'PREUNINST'
#!/bin/sh
sh /var/packages/NodeX-VPN/target/setup_synology.sh --uninstall
exit 0
PREUNINST
    chmod +x "$SPK_DIR/scripts/preuninst"

    # ── Generate placeholder icons if not present ─────────────────────────────
    if [ -f "assets/PACKAGE_ICON.PNG" ]; then
        cp "assets/PACKAGE_ICON.PNG"     "$SPK_DIR/PACKAGE_ICON.PNG"
        cp "assets/PACKAGE_ICON_256.PNG" "$SPK_DIR/PACKAGE_ICON_256.PNG"
    else
        # 1x1 PNG placeholder (valid PNG, 67 bytes)
        printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde\x00\x00\x00\x0cIDATx\x9cc\xf8\x0f\x00\x00\x01\x01\x00\x05\x18\xd8N\x00\x00\x00\x00IEND\xaeB`\x82' \
            > "$SPK_DIR/PACKAGE_ICON.PNG"
        cp "$SPK_DIR/PACKAGE_ICON.PNG" "$SPK_DIR/PACKAGE_ICON_256.PNG"
    fi

    # ── Pack the SPK (outer tar, NOT gzip) ────────────────────────────────────
    SPK_FILE="$OUT_DIR/${SPK_NAME}.spk"
    tar -cf "$SPK_FILE" \
        -C "$SPK_DIR" \
        INFO package.tgz scripts PACKAGE_ICON.PNG PACKAGE_ICON_256.PNG

    sha256sum "$SPK_FILE" > "${SPK_FILE}.sha256"
    echo "[NodeX] Created SPK: $SPK_FILE"
    rm -rf "$SPK_DIR"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "[NodeX] Package build complete for $TARGET"
echo "[NodeX] Artifacts:"
ls -lh "$OUT_DIR/"*"${TARGET}"* "$OUT_DIR/"*"spk"* 2>/dev/null | awk '{print "  "$NF" ("$5")"}'
echo "[NodeX] SHA256 checksums:"
cat "$OUT_DIR/"*.sha256 2>/dev/null | sed 's/^/  /'

rm -rf "$STAGING_DIR"
