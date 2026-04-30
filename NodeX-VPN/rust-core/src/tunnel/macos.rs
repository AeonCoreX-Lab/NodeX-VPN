// rust-core/src/tunnel/macos.rs
//! Production macOS utun + pfctl routing.

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

const TUN_NAME: &str = "utun9";
const TUN_ADDR: &str = "10.66.0.1";
// FIX: tun 0.8 Configuration::mtu() expects u16, not u32.
const TUN_MTU:  u16  = 1500;

static STOP_TX: OnceCell<Mutex<Option<oneshot::Sender<()>>>> = OnceCell::new();

pub async fn start(
    socks_addr: &str,
    _tor: Arc<TorEngine>,
    stats: Arc<StatsTracker>,
) -> anyhow::Result<()> {
    info!("macOS: creating utun device");

    let mut cfg = tun::Configuration::default();
    cfg.tun_name(TUN_NAME)
       .address(TUN_ADDR.parse::<std::net::Ipv4Addr>().unwrap())
       .netmask("255.255.255.0".parse::<std::net::Ipv4Addr>().unwrap())
       .mtu(TUN_MTU)
       .up();

    let device = tun::create_as_async(&cfg)
        .context("utun create failed — need root or system extension")?;

    info!("macOS {TUN_NAME} up — {TUN_ADDR}/24  MTU={TUN_MTU}");

    setup_routing(socks_addr).await
        .context("macOS routing setup failed")?;

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
    info!("macOS: stopping VPN tunnel");
    if let Some(cell) = STOP_TX.get() {
        if let Some(tx) = cell.lock().take() { let _ = tx.send(()); }
    }
    remove_routing().await;
}

async fn setup_routing(socks_addr: &str) -> anyhow::Result<()> {
    let guard_ip = socks_addr.split(':').next().unwrap_or("127.0.0.1");

    // Get current default gateway before overriding it
    let gw_output = tokio::process::Command::new("sh")
        .args(["-c", "route -n get default 2>/dev/null | awk '/gateway/{print $2}' | head -1"])
        .output().await
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .unwrap_or_default();
    let default_gw = if gw_output.is_empty() { "192.168.1.1".to_string() } else { gw_output };

    // Route all traffic via TUN (split into two /1 routes to override default)
    let cmds = [
        format!("route add -net 0.0.0.0/1 {TUN_ADDR}"),
        format!("route add -net 128.0.0.0/1 {TUN_ADDR}"),
        // Exclude guard (SOCKS proxy) from tunnel
        format!("route add -host {guard_ip} {default_gw}"),
    ];
    for cmd in &cmds {
        if let Err(e) = run_cmd(cmd).await {
            warn!("Route ({cmd}): {e}");
        }
    }

    // DNS redirect via pf (BSD packet filter)
    setup_pf_dns().await;

    info!("macOS routing installed (default GW={default_gw}, guard={guard_ip} excluded)");
    Ok(())
}

async fn setup_pf_dns() {
    use tokio::io::AsyncWriteExt;
    let rules = format!(
        "rdr pass on lo0 proto udp from any to any port 53 -> 127.0.0.1 port 5353\n\
         rdr pass on {TUN_NAME} proto udp from any to any port 53 -> 127.0.0.1 port 5353\n"
    );
    if let Ok(mut child) = tokio::process::Command::new("pfctl")
        .args(["-ef", "-"])
        .stdin(std::process::Stdio::piped())
        .spawn()
    {
        if let Some(mut stdin) = child.stdin.take() {
            let _ = stdin.write_all(rules.as_bytes()).await;
        }
        // FIX from previous build: use child.wait().await not child.await
        let _ = child.wait().await;
        info!("pf DNS redirect rules loaded");
    }
}

async fn remove_routing() {
    let cmds = [
        "route delete -net 0.0.0.0/1",
        "route delete -net 128.0.0.0/1",
        "pfctl -F all -f /etc/pf.conf 2>/dev/null || true",
    ];
    for cmd in &cmds {
        let _ = run_cmd(cmd).await;
    }
    info!("macOS routing removed");
}

async fn run_cmd(cmd: &str) -> anyhow::Result<()> {
    let output = tokio::process::Command::new("sh")
        .args(["-c", cmd])
        .output().await?;
    if !output.status.success() {
        let err = String::from_utf8_lossy(&output.stderr);
        anyhow::bail!("Command failed: {err}");
    }
    Ok(())
}

async fn packet_loop(
    mut device: impl tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
    socks_addr: String,
    stats:      Arc<StatsTracker>,
    mut stop:   oneshot::Receiver<()>,
) {
    let mut buf = vec![0u8; (TUN_MTU + 4) as usize];
    info!("macOS packet relay loop started");

    loop {
        tokio::select! {
            _ = &mut stop => { info!("macOS packet loop stopping"); break; }
            result = device.read(&mut buf) => {
                match result {
                    Err(e) => { error!("utun read: {e}"); break; }
                    Ok(0)  => { warn!("utun EOF"); break; }
                    Ok(n)  => {
                        // macOS utun: 4-byte AF header (AF_INET=0x00000002)
                        let pkt = if n > 4 { &buf[4..n] } else { continue };
                        if pkt.is_empty() { continue; }

                        let version = (pkt[0] >> 4) & 0xF;
                        if version == 4 && pkt.len() >= 20 {
                            let proto    = pkt[9];
                            let ihl      = (pkt[0] & 0x0F) as usize * 4;
                            let dst_ip   = std::net::Ipv4Addr::new(pkt[16],pkt[17],pkt[18],pkt[19]);
                            if proto == 6 && pkt.len() >= ihl + 4 {
                                let dst_port = u16::from_be_bytes([pkt[ihl+2], pkt[ihl+3]]);
                                let socks = socks_addr.clone();
                                let st    = stats.clone();
                                tokio::spawn(async move {
                                    tun2socks::forward_tcp_via_socks(
                                        &dst_ip.to_string(), dst_port, &socks, st
                                    ).await;
                                });
                            }
                        }
                        stats.add_received(n as u64);
                    }
                }
            }
        }
    }
}
