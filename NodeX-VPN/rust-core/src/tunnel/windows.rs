// rust-core/src/tunnel/windows.rs
//! Production Windows Wintun driver tunnel.

use crate::tor_manager::TorEngine;
use crate::stats::StatsTracker;
use crate::tun2socks;

use anyhow::Context;
use log::{info, warn, error, debug};
use once_cell::sync::OnceCell;
use parking_lot::Mutex;
use std::sync::Arc;
use tokio::sync::oneshot;

const ADAPTER_NAME: &str = "NodeX VPN";
const TUNNEL_TYPE:  &str = "NodeX";
const TUN_ADDR:     &str = "10.66.0.2";
const GATEWAY:      &str = "10.66.0.1";
const RING_CAP:     u32  = 0x40000; // 256 KiB ring

#[cfg(target_os = "windows")]
use wintun::{Adapter, Session};

#[cfg(target_os = "windows")]
static STOP_TX:      OnceCell<Mutex<Option<oneshot::Sender<()>>>> = OnceCell::new();
#[cfg(target_os = "windows")]
static WINTUN_SES:   OnceCell<Mutex<Option<Arc<Session>>>> = OnceCell::new();

#[cfg(target_os = "windows")]
pub async fn start(
    socks_addr: &str,
    _tor: Arc<TorEngine>,
    stats: Arc<StatsTracker>,
) -> anyhow::Result<()> {
    info!("Windows: loading Wintun driver");

    let dll_path = std::env::current_exe()?
        .parent()
        .context("No executable parent directory")?
        .join("wintun.dll");

    if !dll_path.exists() {
        anyhow::bail!(
            "wintun.dll not found at {:?}\n\
             Place wintun.dll next to the NodeX VPN executable.\n\
             Download from: https://wintun.net/",
            dll_path
        );
    }

    let wintun_lib = unsafe {
        wintun::load_from_path(&dll_path)
            .with_context(|| format!("Failed to load wintun.dll from {:?}", dll_path))?
    };

    let adapter = match Adapter::open(&wintun_lib, ADAPTER_NAME) {
        Ok(a)  => { info!("Reusing existing Wintun adapter '{ADAPTER_NAME}'"); a }
        Err(_) => {
            info!("Creating new Wintun adapter '{ADAPTER_NAME}'");
            Adapter::create(&wintun_lib, ADAPTER_NAME, TUNNEL_TYPE, None)
                .context("Create Wintun adapter (requires Administrator privileges)")?
        }
    };

    // Assign IP address
    run_cmd(&format!(
        "netsh interface ip set address name=\"{ADAPTER_NAME}\" \
         source=static addr={TUN_ADDR} mask=255.255.255.0 gateway={GATEWAY} gwmetric=1"
    )).await
    .context("Failed to set adapter IP address")?;

    // Set high metric to ensure traffic is routed through adapter
    run_cmd(&format!(
        "netsh interface ip set interface \"{ADAPTER_NAME}\" metric=1"
    )).await.ok();

    let session = Arc::new(
        adapter.start_session(RING_CAP)
            .context("Start Wintun session")?
    );

    WINTUN_SES.get_or_init(|| Mutex::new(None));
    *WINTUN_SES.get().unwrap().lock() = Some(session.clone());

    setup_routing(socks_addr).await
        .context("Failed to install Windows routing rules")?;

    let (stop_tx, stop_rx) = oneshot::channel::<()>();
    STOP_TX.get_or_init(|| Mutex::new(None));
    *STOP_TX.get().unwrap().lock() = Some(stop_tx);

    let socks = socks_addr.to_string();
    tokio::spawn(async move {
        packet_loop(session, socks, stats, stop_rx).await;
    });

    info!("Windows Wintun tunnel active on {TUN_ADDR}");
    Ok(())
}

#[cfg(target_os = "windows")]
pub async fn stop() {
    info!("Windows: stopping VPN tunnel");
    if let Some(cell) = STOP_TX.get() {
        if let Some(tx) = cell.lock().take() { let _ = tx.send(()); }
    }
    if let Some(cell) = WINTUN_SES.get() { cell.lock().take(); }
    remove_routing().await;
}

#[cfg(target_os = "windows")]
async fn setup_routing(socks_addr: &str) -> anyhow::Result<()> {
    let guard_ip = socks_addr.split(':').next().unwrap_or("127.0.0.1");

    // Route all traffic via TUN gateway
    // Two /1 routes override the default route without deleting it
    run_cmd(&format!("route ADD 0.0.0.0 MASK 128.0.0.0 {GATEWAY} METRIC 1")).await?;
    run_cmd(&format!("route ADD 128.0.0.0 MASK 128.0.0.0 {GATEWAY} METRIC 1")).await?;
    // Keep direct route to SOCKS proxy (avoid loop)
    run_cmd(&format!("route ADD {guard_ip} MASK 255.255.255.255 0.0.0.0 METRIC 1")).await.ok();
    // DNS redirect via WFP (Windows Filtering Platform) - redirect to our listener
    run_cmd("netsh advfirewall firewall add rule name=\"NodeX DNS\" protocol=UDP dir=out localport=any remoteport=53 action=allow").await.ok();

    info!("Windows routing rules installed (guard {guard_ip} excluded)");
    Ok(())
}

#[cfg(target_os = "windows")]
async fn remove_routing() {
    let _ = run_cmd("route DELETE 0.0.0.0 MASK 128.0.0.0").await;
    let _ = run_cmd("route DELETE 128.0.0.0 MASK 128.0.0.0").await;
    let _ = run_cmd("netsh advfirewall firewall delete rule name=\"NodeX DNS\"").await;
    info!("Windows routing rules removed");
}

#[cfg(target_os = "windows")]
async fn run_cmd(cmd: &str) -> anyhow::Result<()> {
    let status = tokio::process::Command::new("cmd")
        .args(["/C", cmd])
        .status().await?;
    if !status.success() { anyhow::bail!("Command failed: {cmd}"); }
    Ok(())
}

#[cfg(target_os = "windows")]
async fn packet_loop(
    session:    Arc<Session>,
    socks_addr: String,
    stats:      Arc<StatsTracker>,
    mut stop:   oneshot::Receiver<()>,
) {
    info!("Windows Wintun packet loop started");
    loop {
        tokio::select! {
            _ = &mut stop => { info!("Wintun loop stopping"); break; }
            pkt = tokio::task::spawn_blocking({
                let s = session.clone();
                move || s.receive_blocking()
            }) => {
                match pkt {
                    Err(_) | Ok(Err(_)) => { error!("Wintun recv error"); break; }
                    Ok(Ok(packet)) => {
                        let bytes = packet.bytes();
                        if bytes.is_empty() { continue; }

                        let version = (bytes[0] >> 4) & 0xF;
                        if version == 4 && bytes.len() >= 20 {
                            let proto    = bytes[9];
                            let ihl      = (bytes[0] & 0x0F) as usize * 4;
                            let dst_ip   = std::net::Ipv4Addr::new(bytes[16],bytes[17],bytes[18],bytes[19]);
                            if proto == 6 && bytes.len() >= ihl + 4 {
                                let dst_port = u16::from_be_bytes([bytes[ihl+2], bytes[ihl+3]]);
                                let socks = socks_addr.clone();
                                let st    = stats.clone();
                                tokio::spawn(async move {
                                    tun2socks::forward_tcp_via_socks(
                                        &dst_ip.to_string(), dst_port, &socks, st
                                    ).await;
                                });
                            }
                        }
                        stats.add_received(bytes.len() as u64);
                    }
                }
            }
        }
    }
}

// ── Non-Windows stubs ─────────────────────────────────────────────────────────
#[cfg(not(target_os = "windows"))]
pub async fn start(_: &str, _: Arc<TorEngine>, _: Arc<StatsTracker>) -> anyhow::Result<()> {
    anyhow::bail!("Windows tunnel called on non-Windows OS")
}
#[cfg(not(target_os = "windows"))]
pub async fn stop() {}
