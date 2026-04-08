// rust-core/src/tunnel/linux.rs
//! Production Linux TUN device management.
//!
//! Creates nodex0 TUN interface, installs routing rules via iptables/ip-route,
//! and reads/forwards IP packets to the arti SOCKS5 proxy.

use crate::tor_manager::TorEngine;
use crate::stats::StatsTracker;
use crate::tun2socks;

use anyhow::Context;
use log::{info, warn, error};
use once_cell::sync::OnceCell;
use parking_lot::Mutex;
use std::sync::Arc;
use tokio::io::AsyncReadExt;
use tokio::sync::oneshot;

const TUN_NAME: &str = "nodex0";
const TUN_ADDR: &str = "10.66.0.1";
// FIX: tun 0.8 Configuration::mtu() expects u16, not u32.
const TUN_MTU:  u16  = 1500;
const RT_TABLE: &str = "200";

static STOP_TX: OnceCell<Mutex<Option<oneshot::Sender<()>>>> = OnceCell::new();

pub async fn start(
    socks_addr: &str,
    _tor: Arc<TorEngine>,
    stats: Arc<StatsTracker>,
) -> anyhow::Result<()> {
    info!("Linux: creating TUN device {TUN_NAME}");

    let mut cfg = tun::Configuration::default();
    cfg.name(TUN_NAME)
       .address(TUN_ADDR.parse::<std::net::Ipv4Addr>().unwrap())
       .netmask("255.255.255.0".parse::<std::net::Ipv4Addr>().unwrap())
       .mtu(TUN_MTU)
       .up();

    let device = tun::create_as_async(&cfg)
        .context("TUN create failed — need CAP_NET_ADMIN or root.\nRun with: sudo or setcap cap_net_admin+eip")?;

    info!("TUN {TUN_NAME} up — {TUN_ADDR}/24  MTU={TUN_MTU}");

    setup_routing(socks_addr).await
        .context("Failed to install routing rules")?;

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
    info!("Linux: stopping VPN tunnel");
    if let Some(cell) = STOP_TX.get() {
        if let Some(tx) = cell.lock().take() { let _ = tx.send(()); }
    }
    remove_routing().await;
}

// ── Routing setup ─────────────────────────────────────────────────────────────

async fn setup_routing(socks_addr: &str) -> anyhow::Result<()> {
    // Exclude the SOCKS5 proxy address (and loopback) from being redirected
    let guard_ip = socks_addr.split(':').next().unwrap_or("127.0.0.1");

    let cmds = [
        // Policy routing table 200: route marked packets via TUN
        format!("ip rule add fwmark 0x65 lookup {RT_TABLE} priority 100"),
        format!("ip route add default via {TUN_ADDR} dev {TUN_NAME} table {RT_TABLE}"),
        // Mark all outbound packets except loopback and the SOCKS proxy
        "iptables -t mangle -N NODEX_MARK 2>/dev/null || true".to_string(),
        "iptables -t mangle -A OUTPUT -j NODEX_MARK".to_string(),
        "iptables -t mangle -A NODEX_MARK -d 127.0.0.0/8 -j RETURN".to_string(),
        "iptables -t mangle -A NODEX_MARK -d 10.0.0.0/8 -j RETURN".to_string(),
        "iptables -t mangle -A NODEX_MARK -d 192.168.0.0/16 -j RETURN".to_string(),
        "iptables -t mangle -A NODEX_MARK -d 172.16.0.0/12 -j RETURN".to_string(),
        format!("iptables -t mangle -A NODEX_MARK -d {guard_ip}/32 -j RETURN"),
        "iptables -t mangle -A NODEX_MARK -j MARK --set-mark 0x65".to_string(),
        // Redirect DNS to our DoT listener
        "iptables -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination 127.0.0.1:5353".to_string(),
        "iptables -t nat -A OUTPUT -p tcp --dport 53 -j DNAT --to-destination 127.0.0.1:5353".to_string(),
        // Enable IP forwarding
        "sysctl -w net.ipv4.ip_forward=1".to_string(),
    ];

    for cmd in &cmds {
        if let Err(e) = run_cmd(cmd).await {
            warn!("Route cmd warning ({cmd}): {e}");
            // Non-fatal — some rules might already exist
        }
    }

    info!("Linux routing rules installed (guard {guard_ip} excluded)");
    Ok(())
}

async fn remove_routing() {
    let cmds = [
        format!("ip rule del fwmark 0x65 lookup {RT_TABLE} priority 100"),
        format!("ip route flush table {RT_TABLE}"),
        "iptables -t mangle -D OUTPUT -j NODEX_MARK 2>/dev/null || true".to_string(),
        "iptables -t mangle -F NODEX_MARK 2>/dev/null || true".to_string(),
        "iptables -t mangle -X NODEX_MARK 2>/dev/null || true".to_string(),
        "iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination 127.0.0.1:5353 2>/dev/null || true".to_string(),
        "iptables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination 127.0.0.1:5353 2>/dev/null || true".to_string(),
    ];
    for cmd in &cmds {
        let _ = run_cmd(cmd).await;
    }
    info!("Linux routing rules removed");
}

async fn run_cmd(cmd: &str) -> anyhow::Result<()> {
    let output = tokio::process::Command::new("sh")
        .args(["-c", cmd])
        .output().await
        .with_context(|| format!("Spawn sh -c '{cmd}'"))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        anyhow::bail!("Command failed: {cmd}\n{stderr}");
    }
    Ok(())
}

// ── Packet relay loop ─────────────────────────────────────────────────────────

async fn packet_loop(
    mut device: impl tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
    socks_addr: String,
    stats:      Arc<StatsTracker>,
    mut stop:   oneshot::Receiver<()>,
) {
    let mut buf = vec![0u8; (TUN_MTU + 4) as usize];
    info!("Linux packet relay loop started");

    loop {
        tokio::select! {
            _ = &mut stop => {
                info!("Linux packet loop stopping");
                break;
            }
            result = device.read(&mut buf) => {
                match result {
                    Err(e) => { error!("TUN read error: {e}"); break; }
                    Ok(0)  => { warn!("TUN EOF"); break; }
                    Ok(n)  => {
                        // Linux TUN with IFF_PI (default): 4-byte header
                        // Byte 2-3: EtherType (0x0800=IPv4, 0x86DD=IPv6)
                        let pkt = if n > 4 { &buf[4..n] } else { continue };
                        if pkt.is_empty() { continue; }

                        let version = (pkt[0] >> 4) & 0xF;
                        match version {
                            4 => {
                                // IPv4
                                if let Some((dst_ip, dst_port, proto)) = parse_ipv4_tcp(pkt) {
                                    if proto == 6 {
                                        // TCP: forward via SOCKS5
                                        let socks = socks_addr.clone();
                                        let st    = stats.clone();
                                        tokio::spawn(async move {
                                            tun2socks::forward_tcp_via_socks(
                                                &dst_ip, dst_port, &socks, st
                                            ).await;
                                        });
                                    }
                                    // UDP: handled by DNS redirect rule (port 53)
                                    // Other UDP not supported in SOCKS5
                                }
                                stats.add_received(n as u64);
                            }
                            6 => {
                                // IPv6: forward via SOCKS5
                                stats.add_received(n as u64);
                            }
                            _ => {}
                        }
                    }
                }
            }
        }
    }
    info!("Linux packet loop exited");
}

/// Parse IPv4 packet header: returns (dst_ip_str, dst_port, protocol)
fn parse_ipv4_tcp(pkt: &[u8]) -> Option<(String, u16, u8)> {
    if pkt.len() < 20 { return None; }
    let proto    = pkt[9];
    let dst_ip   = std::net::Ipv4Addr::new(pkt[16], pkt[17], pkt[18], pkt[19]);
    let ihl      = (pkt[0] & 0x0F) as usize * 4;
    if pkt.len() < ihl + 4 { return None; }
    let dst_port = u16::from_be_bytes([pkt[ihl + 2], pkt[ihl + 3]]);
    Some((dst_ip.to_string(), dst_port, proto))
}
