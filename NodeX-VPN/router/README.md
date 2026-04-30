# NodeX VPN — Router & NAS Support

Route **every device on your network** through Tor — Smart TV, PS5, Xbox, Roku, IoT gadgets, smart bulbs — all protected without installing any app on each device.

```
Your Router / NAS (NodeX VPN)
        │
        ├── Smart TV        → Tor
        ├── PS5 / Xbox      → Tor
        ├── iPhone / Android → Tor
        ├── Smart bulbs     → Tor
        └── Laptop          → Tor
```

## Supported Platforms

| Platform | Status | Notes |
|---|---|---|
| **OpenWrt** | ✅ Full | Recommended. `procd` service, UCI firewall, hotplug |
| **GL.iNet** | ✅ Full | Runs OpenWrt under the hood |
| **Asus Merlin** | ✅ Full | JFFS2 persistent scripts |
| **pfSense 2.7+** | ✅ Full | `x86_64-unknown-freebsd` binary, `pf(4)` anchor rules, rc.d service |
| **OPNsense 24+** | ✅ Full | configd action, pf anchor, identical to pfSense path |
| **Synology DSM 7.x** | ✅ Full | SPK package, DSM 7 rc.d service, x86\_64 + aarch64 |
| **Synology DSM 6.2** | ✅ Full | Upstart service, same Linux binary |
| **DD-WRT** | ⚠️ Partial | Generic Linux init, manual firewall |
| **Tomato** | ⚠️ Partial | rc.local startup |

## Supported Hardware

### Linux Routers (OpenWrt / Merlin / DD-WRT)
- **Architecture**: `x86_64` or `aarch64` (64-bit only)
- **RAM**: ≥ 64 MB free · **Storage**: ≥ 10 MB free · **Kernel**: Linux 4.9+ with TUN
- Devices: GL.iNet GL-MT6000 / GL-AXT1800, Asus RT-AX88U, Linksys WRT3200ACM, x86 mini-PCs

### pfSense / OPNsense
- **Architecture**: `x86_64` only (standard PC hardware)
- **OS**: FreeBSD 14.x (pfSense 2.7 / OPNsense 24.x base)
- **Kernel module**: `if_tun` (loaded automatically via `kldload`)
- **Firewall**: `pf(4)` with NodeX `rdr-anchor` — does **not** replace your existing pf rules

### Synology NAS
- **x86_64**: DS920+, DS923+, DS1621+, DS1821+, DS1522+, RS1221+ (all Intel/AMD NAS)
- **aarch64**: DS220+, DS420+, DS720+, DS920+ (Realtek RTD1619B / ARM Cortex-A55)
- **DSM**: 6.2+ or 7.x
- **Not supported**: armv7 (older DS2xx/DS4xx pre-2020 models)

> ⚠️ **armv7/armv6 (32-bit ARM) is not supported on any platform.**

---

## Quick Install

### OpenWrt
```sh
ssh root@192.168.1.1
wget -O- https://github.com/AeonCoreX-Lab/NodeX-VPN/releases/latest/download/install.sh | sh
sh /tmp/setup_openwrt.sh
/etc/init.d/nodex start
```

### pfSense / OPNsense
```sh
# SSH into pfSense/OPNsense shell (Diagnostics → Command Prompt or SSH)
fetch -o /tmp/setup.sh https://github.com/AeonCoreX-Lab/NodeX-VPN/releases/latest/download/setup_pfsense_opnsense.sh
sh /tmp/setup.sh
service nodex_vpn start
```

### Synology NAS
**Option A — Script (SSH)**
```sh
# Enable SSH: DSM → Control Panel → Terminal & SNMP
ssh admin@your-nas-ip
sudo -i
curl -fsSL https://github.com/AeonCoreX-Lab/NodeX-VPN/releases/latest/download/setup_synology.sh | sh
```

**Option B — SPK Package (GUI)**
1. Download `NodeX-VPN-<version>-x86_64.spk` from [Releases](https://github.com/AeonCoreX-Lab/NodeX-VPN/releases)
2. DSM → Package Center → Manual Install → upload the `.spk`
3. Follow the installation wizard

---

## How It Works

```
LAN Device → Router / NAS iptables PREROUTING (Linux)
             or pf(4) rdr-anchor (FreeBSD/pfSense)
              ├── TCP  → NodeX TransPort :9040 → Tor circuit → Internet
              └── DNS  → NodeX DNSPort   :5353 → DNS-over-Tor → resolved
```

### Linux (OpenWrt / Synology)
1. NodeX VPN starts an embedded Tor node (via `arti`)
2. Creates a TUN interface `nodex0` at `10.66.0.1/24`
3. `iptables` PREROUTING intercepts all LAN TCP traffic
4. Traffic is forwarded through Tor circuits
5. DNS queries go through Tor's DNSPort (prevents DNS leaks)

### FreeBSD (pfSense / OPNsense)
1. NodeX VPN starts via `rc.d` (`/usr/local/etc/rc.d/nodex_vpn`)
2. Loads `if_tun` kernel module, creates `tun0` (10.66.0.1 ↔ 10.66.0.2)
3. Writes `/etc/nodex/nodex.pf` and loads it as a `pf(4)` anchor named `nodex`
4. `rdr-anchor` rules redirect LAN TCP → TransPort `:9040` and DNS → DNSPort `:5353`
5. pfSense: a filter hook re-applies the anchor after firewall reload
6. OPNsense: a `configd` action re-applies the anchor after rule changes

---

## Synology Modes

```sh
sh setup_synology.sh                # Both: NAS outbound + LAN clients → Tor
sh setup_synology.sh --nas-only     # Only NAS itself → Tor
sh setup_synology.sh --lan-only     # Only LAN clients → Tor (NAS as gateway)
```

---

## Configuration

Edit `/etc/nodex/nodex.conf`:

```toml
[vpn]
tun_name    = nodex0          # Linux: nodex0 | FreeBSD: tun0
tun_addr    = 10.66.0.1
tun_network = 10.66.0.0/24
socks5_addr = 127.0.0.1:9050

[tor]
data_dir    = /etc/nodex/tor-data
log_level   = warn

[router]
platform        = linux          # linux | freebsd | synology
route_lan       = true
lan_iface       = br-lan         # OpenWrt: br-lan | pfSense: em1 | Synology: eth0
exclude_local   = true
```

---

## Troubleshooting

### All platforms — test connectivity
```sh
# From a LAN device — should return a Tor exit node IP
curl https://check.torproject.org/api/ip
```

### pfSense / OPNsense
```sh
# Check if pf anchor is loaded
pfctl -a nodex -s all

# Re-apply anchor manually after pf reload
pfctl -a nodex -f /etc/nodex/nodex.pf

# Check rc.d service
service nodex_vpn status
cat /var/run/nodex_vpn.pid

# View logs
tail -f /var/log/nodex-vpn.log
```

### Synology DSM
```sh
# DSM 7 — service status
/usr/local/etc/rc.d/nodex-vpn.sh status

# Check TUN device
ls -la /dev/net/tun
# If missing: modprobe tun

# Check iptables rules
iptables -t nat -L PREROUTING -n --line-numbers

# View logs
tail -f /var/log/nodex-vpn.log
```

### OpenWrt / Linux
```sh
ps | grep nodex
logread | grep nodex    # OpenWrt
iptables -t nat -L PREROUTING -n --line-numbers
```

---

## Uninstall

```sh
sh install.sh --uninstall                          # OpenWrt / Linux
sh setup_pfsense_opnsense.sh --uninstall           # pfSense / OPNsense
sh setup_synology.sh --uninstall                   # Synology DSM
# Or via DSM Package Center → NodeX VPN → Uninstall
```

---

## Scripts

```
router/
├── install.sh                   # Universal: auto-detects all platforms
├── setup_openwrt.sh             # OpenWrt: kmod-tun, iptables, UCI, hotplug
├── setup_glinet_merlin.sh       # GL.iNet + Asus Merlin
├── setup_pfsense_opnsense.sh    # pfSense / OPNsense: pf anchor, rc.d, configd
├── setup_synology.sh            # Synology DSM 6/7: SPK, iptables, rc.d/Upstart
├── package.sh                   # CI: builds .tar.gz (all) + .spk (Synology)
└── README.md
```

---

## Known Limitations

- **UDP (except DNS) is not anonymized** — fundamental Tor limitation. Game consoles using UDP (PSN, Xbox Live) use your real IP for UDP traffic. TCP is fully protected.
- **pfSense firewall reloads** reset pf rules — the installed hook re-applies the NodeX anchor automatically, but there is a brief window (~2s) where the redirect rules are inactive.
- **Tor is slower than regular VPN** — expect 3–10× slower speeds due to multi-hop routing.
- **Streaming services** (Netflix, Disney+) often block known Tor exit nodes.
- **Synology armv7** (DS2xx/DS4xx pre-2020) — not supported; 32-bit ARM only.
