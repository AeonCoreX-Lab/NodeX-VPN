// rust-core/src/tunnel/bsd.rs
//! FreeBSD TUN device management — pfSense / OPNsense
//!
//! Creates a `tun0` interface, installs `pf(4)` rdr anchors so that all LAN
//! TCP + DNS is transparently forwarded through the Tor SOCKS5 proxy, and
//! relays raw IP packets from the TUN fd to tun2socks.
//!
//! Key differences from Linux:
//!  • No iptables — uses `pfctl` anchors (`nodex` anchor in pf.conf)
//!  • FreeBSD TUN has **no** 4-byte PI header (unlike Linux IFF_TUN)
//!  • Routing via `route add` / `route delete` (not `ip route`)
//!  • Kernel module: `if_tun` loaded via `kldload`
//!  • IP forwarding: `sysctl net.inet.ip.forwarding=1`

use crate::tor_manager::TorEngine;
use crate::stats::StatsTracker;
use crate::tun2socks;

use anyhow::Context;
use log::{error, info, warn};
use once_cell::sync::OnceCell;
use parking_lot::Mutex;
use std::sync::Arc;
use tokio::io::AsyncReadExt;
use tokio::sync::oneshot;

// FreeBSD point-to-point TUN — two addresses required
const TUN_NAME:  &str = "tun0";
const TUN_LOCAL: &str = "10.66.0.1";   // router end (this machine)
const TUN_PEER:  &str = "10.66.0.2";   // tunnel gateway
const TUN_MTU:   u16  = 1500;

// pf anchor name — must match setup_pfsense_opnsense.sh
const PF_ANCHOR: &str = "nodex";
const PF_RULES:  &str = "/etc/nodex/nodex.pf";

static STOP_TX: OnceCell<Mutex<Option<oneshot::Sender<()>>>> = OnceCell::new();

// ─────────────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────────────

pub async fn start(
    socks_addr: &str,
    _tor: Arc<TorEngine>,
    stats: Arc<StatsTracker>,
) -> anyhow::Result<()> {
    info!("FreeBSD: loading if_tun kernel module");
    let _ = run_cmd("kldload if_tun 2>/dev/null || true").await;

    info!("FreeBSD: creating TUN device {TUN_NAME}");
    let mut cfg = tun::Configuration::default();
    cfg.tun_name(TUN_NAME)
        .address(TUN_LOCAL.parse::<std::net::Ipv4Addr>().unwrap())
        .destination(TUN_PEER.parse::<std::net::Ipv4Addr>().unwrap())
        .mtu(TUN_MTU)
        .up();

    let device = tun::create_as_async(&cfg).context(
        "TUN create failed — nodex-vpn must run as root on FreeBSD\n\
         pfSense/OPNsense: run from shell via 'sudo /usr/local/bin/nodex-vpn ...'",
    )?;

    info!("TUN {TUN_NAME} up — {TUN_LOCAL} <-> {TUN_PEER}  MTU={TUN_MTU}");

    setup_routing(socks_addr)
        .await
        .context("Failed to install pf / routing rules")?;

    let (stop_tx, stop_rx) = oneshot::channel::<()>();
    STOP_TX.get_or_init(|| Mutex::new(None));
    *STOP_TX.get().unwrap().lock() = Some(stop_tx);

    let socks = socks_addr.to_string();
    tokio::spawn(async move {
        packet_loop(device, socks, stats, stop_rx).await;
    });

    Ok(())
}

pub async fn stop() {
    info!("FreeBSD: stopping VPN tunnel");
    if let Some(cell) = STOP_TX.get() {
        if let Some(tx) = cell.lock().take() {
            let _ = tx.send(());
        }
    }
    remove_routing().await;
}

// ─────────────────────────────────────────────────────────────────────────────
// Routing / pf setup
// ─────────────────────────────────────────────────────────────────────────────

async fn setup_routing(socks_addr: &str) -> anyhow::Result<()> {
    let guard_ip = socks_addr.split(':').next().unwrap_or("127.0.0.1");

    // Write pf anchor rules that redirect LAN TCP → TransPort and DNS → DNSPort.
    // The anchor file is the source of truth; pfctl loads it at runtime.
    // setup_pfsense_opnsense.sh must have already added `anchor "nodex"` to
    // /etc/pf.conf (or OPNsense equivalent).
    write_pf_rules(guard_ip).await?;

    let cmds: &[&str] = &[
        // 1. Enable IP forwarding
        "sysctl net.inet.ip.forwarding=1",
        // 2. Bring up the TUN interface (tun crate may already do this)
        &format!("ifconfig {TUN_NAME} {TUN_LOCAL} {TUN_PEER} netmask 255.255.255.255 up"),
        // 3. Split-tunnel default route: send 0/1 and 128/1 via tun0
        //    This avoids clobbering the real default route.
        &format!("route add -net 0.0.0.0/1   -interface {TUN_NAME} 2>/dev/null || true"),
        &format!("route add -net 128.0.0.0/1  -interface {TUN_NAME} 2>/dev/null || true"),
        // 4. Keep the SOCKS proxy reachable via the real gateway
        &format!("route add -host {guard_ip} $(netstat -rn | awk '/^default/{{print $2}}' | head -1) 2>/dev/null || true"),
        // 5. Load the pf anchor rules
        &format!("pfctl -a {PF_ANCHOR} -f {PF_RULES} 2>/dev/null || true"),
        // 6. Enable pf if not already running
        "pfctl -e 2>/dev/null || true",
    ];

    for cmd in cmds {
        if let Err(e) = run_cmd(cmd).await {
            warn!("Routing cmd warning: {e}");
        }
    }

    info!("FreeBSD: pf anchor + routing installed (guard={guard_ip} excluded)");
    Ok(())
}

async fn write_pf_rules(guard_ip: &str) -> anyhow::Result<()> {
    // LAN interface and subnet read from /etc/nodex/nodex.conf by the daemon,
    // but we write sane defaults here; setup script overrides as needed.
    let rules = format!(
        r#"# NodeX VPN — pf anchor rules
# Generated by nodex-vpn at startup. Do not edit manually.
# Source: /etc/nodex/nodex.conf → [router] lan_iface / lan_net

# --- Variables ---
int_if = "{{ em0 igb0 vtnet0 }}"   # LAN interface (set by setup script)
lan_net = "192.168.1.0/24"          # LAN subnet  (set by setup script)

# --- Tables ---
table <nodex_bypass> {{ 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, {guard_ip}/32 }}

# --- Redirect rules ---
# TCP: redirect all LAN TCP to NodeX TransPort (transparent proxy)
rdr pass on $int_if proto tcp from $lan_net to !<nodex_bypass> \
    -> 127.0.0.1 port 9040

# DNS: redirect all LAN DNS to NodeX DNSPort (DNS-over-Tor)
rdr pass on $int_if proto udp from $lan_net to any port 53 \
    -> 127.0.0.1 port 5353
rdr pass on $int_if proto tcp from $lan_net to any port 53 \
    -> 127.0.0.1 port 5353
"#,
        guard_ip = guard_ip
    );

    tokio::fs::create_dir_all("/etc/nodex")
        .await
        .context("mkdir /etc/nodex")?;
    tokio::fs::write(PF_RULES, rules)
        .await
        .context("write nodex.pf")?;
    info!("FreeBSD: wrote pf anchor rules → {PF_RULES}");
    Ok(())
}

async fn remove_routing() {
    let cmds: &[&str] = &[
        &format!("pfctl -a {PF_ANCHOR} -F all 2>/dev/null || true"),
        &format!("route delete -net 0.0.0.0/1  2>/dev/null || true"),
        &format!("route delete -net 128.0.0.0/1 2>/dev/null || true"),
        &format!("ifconfig {TUN_NAME} destroy 2>/dev/null || true"),
    ];
    for cmd in cmds {
        let _ = run_cmd(cmd).await;
    }
    info!("FreeBSD: routing rules removed");
}

// ─────────────────────────────────────────────────────────────────────────────
// Packet relay loop
// ─────────────────────────────────────────────────────────────────────────────

async fn packet_loop(
    mut device: impl tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
    socks_addr: String,
    stats: Arc<StatsTracker>,
    mut stop: oneshot::Receiver<()>,
) {
    // FreeBSD TUN: raw IP datagrams — NO 4-byte PI header (unlike Linux IFF_TUN).
    // FreeBSD may prepend a 4-byte address family header on *some* configurations;
    // we detect this by checking whether the first byte looks like an IP version.
    let mut buf = vec![0u8; TUN_MTU as usize + 64];
    info!("FreeBSD packet relay loop started");

    loop {
        tokio::select! {
            _ = &mut stop => {
                info!("FreeBSD packet loop stopping");
                break;
            }
            result = device.read(&mut buf) => {
                match result {
                    Err(e) => { error!("TUN read: {e}"); break; }
                    Ok(0)  => { warn!("TUN EOF"); break; }
                    Ok(n)  => {
                        if n == 0 { continue; }

                        // Auto-detect BSD address-family header (4 bytes, AF_INET = 0x00000002)
                        let pkt = if n >= 4
                            && buf[0] == 0 && buf[1] == 0 && buf[2] == 0
                            && (buf[3] == 2 || buf[3] == 0x1e) // AF_INET or AF_INET6
                        {
                            &buf[4..n]  // strip AF header
                        } else {
                            &buf[..n]
                        };

                        if pkt.is_empty() { continue; }
                        let version = (pkt[0] >> 4) & 0xF;

                        match version {
                            4 => {
                                if let Some((dst_ip, dst_port, proto)) = parse_ipv4(pkt) {
                                    if proto == 6 {
                                        let socks = socks_addr.clone();
                                        let st    = stats.clone();
                                        tokio::spawn(async move {
                                            tun2socks::forward_tcp_via_socks(
                                                &dst_ip, dst_port, &socks, st,
                                            ).await;
                                        });
                                    }
                                }
                                stats.add_received(n as u64);
                            }
                            6 => { stats.add_received(n as u64); }
                            _ => { warn!("FreeBSD TUN: unexpected IP version {version}"); }
                        }
                    }
                }
            }
        }
    }
    info!("FreeBSD packet loop exited");
}

fn parse_ipv4(pkt: &[u8]) -> Option<(String, u16, u8)> {
    if pkt.len() < 20 {
        return None;
    }
    let proto    = pkt[9];
    let dst_ip   = std::net::Ipv4Addr::new(pkt[16], pkt[17], pkt[18], pkt[19]);
    let ihl      = (pkt[0] & 0x0F) as usize * 4;
    if pkt.len() < ihl + 4 {
        return None;
    }
    let dst_port = u16::from_be_bytes([pkt[ihl + 2], pkt[ihl + 3]]);
    Some((dst_ip.to_string(), dst_port, proto))
}

// ─────────────────────────────────────────────────────────────────────────────
// Shell helper
// ─────────────────────────────────────────────────────────────────────────────

async fn run_cmd(cmd: &str) -> anyhow::Result<()> {
    let output = tokio::process::Command::new("sh")
        .args(["-c", cmd])
        .output()
        .await
        .with_context(|| format!("spawn sh -c '{cmd}'"))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        anyhow::bail!("Command failed: {cmd}\n{stderr}");
    }
    Ok(())
}
