// rust-core/src/tunnel/macos.rs
//! macOS TUN (utun) management.
//!
//! macOS exposes TUN via the `utun` kernel control socket.
//! We use the `tun` crate which handles the utun ioctl internally.
//!
//! Routing: macOS `route` command (no iptables; we use pfctl instead).
//! Traffic redirect: All traffic → utun0 → tun2socks → arti SOCKS5.

use crate::tor_manager::TorEngine;
use crate::stats::StatsTracker;

use anyhow::{Context};
use log::{info, warn, error};
use once_cell::sync::OnceCell;
use parking_lot::Mutex;
use std::sync::Arc;
use tokio::sync::oneshot;

const TUN_NAME: &str = "utun9";      // macOS utun devices
const TUN_ADDR: &str = "10.66.0.1";
const TUN_MTU:  u32  = 1500;

static STOP_TX: OnceCell<Mutex<Option<oneshot::Sender<()>>>> = OnceCell::new();

pub async fn start(
    socks_addr: &str,
    _tor: Arc<TorEngine>,
    stats: Arc<StatsTracker>,
) -> anyhow::Result<()> {
    info!("macOS tunnel: creating utun device");

    let mut cfg = tun::Configuration::default();
    cfg.name(TUN_NAME)
       .address(TUN_ADDR)
       .netmask("255.255.255.0")
       .mtu(TUN_MTU as i32)
       .up();

    let device = tun::create_as_async(&cfg)
        .context("Failed to create utun device")?;

    info!("utun device up at {TUN_ADDR}/24");

    setup_routes(socks_addr).await?;

    let (stop_tx, stop_rx) = oneshot::channel::<()>();
    STOP_TX.get_or_init(|| Mutex::new(None));
    *STOP_TX.get().unwrap().lock() = Some(stop_tx);

    let socks = socks_addr.to_string();
    tokio::spawn(async move {
        macos_io_loop(device, socks, stats, stop_rx).await;
    });

    Ok(())
}

pub async fn stop() {
    info!("macOS tunnel: tearing down utun");
    if let Some(cell) = STOP_TX.get() {
        if let Some(tx) = cell.lock().take() { let _ = tx.send(()); }
    }
    tear_down_routes().await;
}

async fn setup_routes(socks_addr: &str) -> anyhow::Result<()> {
    let guard_ip = socks_addr.split(':').next().unwrap_or("127.0.0.1");

    let cmds = [
        // Default route via our TUN
        format!("route add -net 0.0.0.0/1 {TUN_ADDR}"),
        format!("route add -net 128.0.0.0/1 {TUN_ADDR}"),
        // Exclude loopback + guard from redirect
        format!("route add -host {guard_ip} -gateway $(route -n get default | awk '/gateway/{{print $2}}')"),
    ];

    for cmd in &cmds {
        let parts: Vec<&str> = cmd.split_whitespace().collect();
        let status = tokio::process::Command::new(parts[0])
            .args(&parts[1..])
            .status().await
            .with_context(|| format!("Route cmd: {cmd}"))?;
        if !status.success() {
            warn!("Route cmd non-zero: {cmd}");
        }
    }

    // DNS redirect via pfctl
    let pf_rules = format!(
        "rdr pass on lo0 proto udp from any to any port 53 -> 127.0.0.1 port 5353\n\
         rdr pass on {TUN_NAME} proto udp from any to any port 53 -> 127.0.0.1 port 5353\n"
    );
    let mut child = tokio::process::Command::new("pfctl")
        .args(["-ef", "-"])
        .stdin(std::process::Stdio::piped())
        .spawn()?;
    if let Some(mut stdin) = child.stdin.take() {
        use tokio::io::AsyncWriteExt;
        stdin.write_all(pf_rules.as_bytes()).await?;
    }
    child.await?;

    info!("macOS routing rules installed");
    Ok(())
}

async fn tear_down_routes() {
    let cmds = [
        "route delete -net 0.0.0.0/1",
        "route delete -net 128.0.0.0/1",
        "pfctl -F all -f /etc/pf.conf",  // restore default pf rules
    ];
    for cmd in &cmds {
        let parts: Vec<&str> = cmd.split_whitespace().collect();
        let _ = tokio::process::Command::new(parts[0]).args(&parts[1..]).status().await;
    }
    info!("macOS routing rules removed");
}

async fn macos_io_loop(
    mut device: impl tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
    _socks_addr: String,
    stats: Arc<StatsTracker>,
    mut stop: oneshot::Receiver<()>,
) {
    use tokio::io::AsyncReadExt;
    let mut buf = vec![0u8; TUN_MTU as usize + 4];
    loop {
        tokio::select! {
            _ = &mut stop => { info!("macOS IO loop stopping"); break; }
            result = device.read(&mut buf) => {
                match result {
                    Ok(n) if n > 0 => { stats.add_received(n as u64); }
                    Err(e) => { error!("utun read: {e}"); break; }
                    _ => {}
                }
            }
        }
    }
}
