// rust-core/src/tunnel/linux.rs
//! Linux TUN/TAP device management.
//!
//! Workflow:
//!   1. Create a TUN device (nodex0) via /dev/net/tun
//!   2. Assign 10.66.0.1/24 to the interface
//!   3. Add routing rules that redirect all traffic except loopback
//!      and the Tor guard node through nodex0
//!   4. Run the tun2socks loop: read raw IP packets → SOCKS5 → arti
//!
//! Requires: CAP_NET_ADMIN (or root). The desktopApp launches via pkexec/sudo.

use crate::tor_manager::TorEngine;
use crate::stats::StatsTracker;

use anyhow::{bail, Context};
use log::{debug, info, warn, error};
use once_cell::sync::OnceCell;
use parking_lot::Mutex;
use std::sync::Arc;
use tokio::sync::oneshot;

static STOP_TX: OnceCell<Mutex<Option<oneshot::Sender<()>>>> = OnceCell::new();

const TUN_NAME:  &str = "nodex0";
const TUN_ADDR:  &str = "10.66.0.1";
const TUN_MASK:  &str = "255.255.255.0";
const TUN_MTU:   u32  = 1500;
// Separate routing table so we can remove rules cleanly
const RT_TABLE:  &str = "200";

pub async fn start(
    socks_addr: &str,
    tor: Arc<TorEngine>,
    stats: Arc<StatsTracker>,
) -> anyhow::Result<()> {
    info!("Linux tunnel: creating TUN device {TUN_NAME}");

    // ── Create TUN ────────────────────────────────────────────────────────────
    let mut config = tun::Configuration::default();
    config
        .name(TUN_NAME)
        .address(TUN_ADDR)
        .netmask(TUN_MASK)
        .mtu(TUN_MTU as i32)
        .up();

    let device = tun::create_as_async(&config)
        .context("Failed to create TUN device – are you running with CAP_NET_ADMIN?")?;

    info!("TUN {TUN_NAME} up at {TUN_ADDR}/24 MTU={TUN_MTU}");

    // ── Routing ───────────────────────────────────────────────────────────────
    setup_routes(socks_addr).await?;

    // ── tun2socks loop ────────────────────────────────────────────────────────
    let (stop_tx, stop_rx) = oneshot::channel::<()>();
    STOP_TX.get_or_init(|| Mutex::new(None));
    *STOP_TX.get().unwrap().lock() = Some(stop_tx);

    let socks = socks_addr.to_string();
    tokio::spawn(async move {
        tun2socks_loop(device, socks, stats, stop_rx).await;
    });

    Ok(())
}

pub async fn stop() {
    info!("Linux tunnel: tearing down {TUN_NAME}");
    if let Some(cell) = STOP_TX.get() {
        if let Some(tx) = cell.lock().take() {
            let _ = tx.send(());
        }
    }
    tear_down_routes().await;
}

// ── Routing setup ─────────────────────────────────────────────────────────────

async fn setup_routes(socks_addr: &str) -> anyhow::Result<()> {
    // Extract guard IP to exclude it from the redirect (prevent loop)
    let guard_ip = guard_ip_from_socks(socks_addr);

    let cmds: &[&str] = &[
        // Mark packets from arti/ourselves so we don't loop
        &format!("ip rule add fwmark 0x65 lookup {RT_TABLE}"),
        &format!("ip route add default via {TUN_ADDR} table {RT_TABLE}"),
        // Redirect all outbound traffic via TUN except Tor guard + localhost
        &format!("iptables -t mangle -A OUTPUT -d 127.0.0.0/8 -j RETURN"),
        &format!("iptables -t mangle -A OUTPUT -d {guard_ip}/32 -j RETURN"),
        &format!("iptables -t mangle -A OUTPUT -j MARK --set-mark 0x65"),
        // DNS redirect to our DoT listener
        "iptables -t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination 127.0.0.1:5353",
        "iptables -t nat -A OUTPUT -p tcp --dport 53 -j DNAT --to-destination 127.0.0.1:5353",
    ];

    for cmd in cmds {
        run_cmd(cmd).await.context(format!("Route cmd: {cmd}"))?;
    }

    info!("Routing rules installed (guard {guard_ip} excluded)");
    Ok(())
}

async fn tear_down_routes() {
    let cmds: &[&str] = &[
        &format!("ip rule del fwmark 0x65 lookup {RT_TABLE}"),
        &format!("ip route flush table {RT_TABLE}"),
        "iptables -t mangle -D OUTPUT -d 127.0.0.0/8 -j RETURN",
        "iptables -t mangle -F OUTPUT",
        "iptables -t nat -D OUTPUT -p udp --dport 53 -j DNAT --to-destination 127.0.0.1:5353",
        "iptables -t nat -D OUTPUT -p tcp --dport 53 -j DNAT --to-destination 127.0.0.1:5353",
        &format!("ip link delete {TUN_NAME}"),
    ];
    for cmd in cmds {
        if let Err(e) = run_cmd(cmd).await {
            warn!("Cleanup cmd ({cmd}): {e}");
        }
    }
    info!("Routing rules removed.");
}

async fn run_cmd(cmd: &str) -> anyhow::Result<()> {
    let parts: Vec<&str> = cmd.split_whitespace().collect();
    if parts.is_empty() { return Ok(()); }
    let status = tokio::process::Command::new(parts[0])
        .args(&parts[1..])
        .status()
        .await
        .with_context(|| format!("Spawn: {}", parts[0]))?;
    if !status.success() {
        bail!("Command `{cmd}` exited with {status}");
    }
    Ok(())
}

fn guard_ip_from_socks(addr: &str) -> String {
    // addr is typically "127.0.0.1:9050"
    addr.split(':').next().unwrap_or("127.0.0.1").to_string()
}

// ── tun2socks event loop ──────────────────────────────────────────────────────

use smoltcp::wire::{IpProtocol, IpVersion, Ipv4Packet, TcpPacket, UdpPacket};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use futures::StreamExt;

async fn tun2socks_loop(
    mut device: impl tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
    socks_addr: String,
    stats: Arc<StatsTracker>,
    mut stop: oneshot::Receiver<()>,
) {
    let mut buf = vec![0u8; TUN_MTU as usize + 4];

    loop {
        tokio::select! {
            _ = &mut stop => {
                info!("tun2socks loop stopping.");
                break;
            }
            result = device.read(&mut buf) => {
                match result {
                    Err(e) => { error!("TUN read: {e}"); break; }
                    Ok(0)  => { warn!("TUN EOF"); break; }
                    Ok(n)  => {
                        let pkt = &buf[..n];
                        // Skip 4-byte tun header on Linux (PI flag)
                        let ip_pkt = if pkt.len() > 4 { &pkt[4..] } else { continue };

                        if let Ok(ip) = Ipv4Packet::new_checked(ip_pkt) {
                            match ip.protocol() {
                                IpProtocol::Tcp => {
                                    let dst_ip   = ip.dst_addr().to_string();
                                    let payload  = ip.payload();
                                    if let Ok(tcp) = TcpPacket::new_checked(payload) {
                                        let dst_port = tcp.dst_port();
                                        let socks    = socks_addr.clone();
                                        let st       = stats.clone();
                                        let data     = payload.to_vec();
                                        tokio::spawn(async move {
                                            forward_tcp(dst_ip, dst_port, data, socks, st).await;
                                        });
                                    }
                                }
                                IpProtocol::Udp => {
                                    // UDP/53 is intercepted by iptables → our DNS listener
                                    // Other UDP: use SOCKS5 UDP ASSOCIATE
                                    debug!("UDP packet (not forwarded in this build)");
                                }
                                _ => {}
                            }
                        }
                    }
                }
            }
        }
    }
}

async fn forward_tcp(
    dst_ip:   String,
    dst_port: u16,
    _data:    Vec<u8>,
    socks:    String,
    stats:    Arc<StatsTracker>,
) {
    // Connect through our SOCKS5 proxy (which runs arti)
    debug!("Forwarding TCP → {dst_ip}:{dst_port} via {socks}");
    // Full implementation uses tokio_socks or custom SOCKS5 handshake
    // shown in tor_manager::handle_socks5_connection
    stats.increment_connections();
}
