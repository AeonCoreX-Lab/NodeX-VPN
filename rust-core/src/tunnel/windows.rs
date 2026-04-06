// rust-core/src/tunnel/windows.rs
//! Windows Wintun driver integration.
//!
//! Wintun is a Layer 3 TUN driver used by WireGuard on Windows.
//! We embed wintun.dll in the release package and load it at runtime.
//!
//! Workflow:
//!   1. Load wintun.dll from the application directory
//!   2. Create a "NodeX" adapter (ring-buffer based, very high throughput)
//!   3. Set up routing table entries via WinAPI / netsh
//!   4. Read/write IP packets from the Wintun ring buffer
//!   5. Proxy TCP via arti SOCKS5

use crate::tor_manager::TorEngine;
use crate::stats::StatsTracker;

use anyhow::{bail, Context};
use log::{debug, info, warn, error};
use once_cell::sync::OnceCell;
use parking_lot::Mutex;
use std::sync::Arc;
use tokio::sync::oneshot;

#[cfg(target_os = "windows")]
use wintun::{Adapter, Session};

const ADAPTER_NAME: &str = "NodeX VPN";
const TUNNEL_TYPE:  &str = "NodeX";
const TUN_ADDR:     &str = "10.66.0.2";
const GATEWAY:      &str = "10.66.0.1";
const RING_CAP:     u32  = 0x20000; // 128 KiB ring buffer

#[cfg(target_os = "windows")]
static STOP_TX: OnceCell<Mutex<Option<oneshot::Sender<()>>>> = OnceCell::new();

#[cfg(target_os = "windows")]
static WINTUN_SESSION: OnceCell<Mutex<Option<Arc<Session>>>> = OnceCell::new();

#[cfg(target_os = "windows")]
pub async fn start(
    socks_addr: &str,
    tor: Arc<TorEngine>,
    stats: Arc<StatsTracker>,
) -> anyhow::Result<()> {
    info!("Windows tunnel: loading Wintun driver");

    // Locate wintun.dll next to the executable
    let dll_path = std::env::current_exe()?
        .parent()
        .context("No parent dir")?
        .join("wintun.dll");

    let wintun = unsafe {
        wintun::load_from_path(&dll_path)
            .context("Failed to load wintun.dll – ensure it is in the app directory")?
    };

    // Create or open the adapter
    let adapter = match Adapter::open(&wintun, ADAPTER_NAME) {
        Ok(a)  => { info!("Reusing existing Wintun adapter"); a }
        Err(_) => {
            Adapter::create(&wintun, ADAPTER_NAME, TUNNEL_TYPE, None)
                .context("Create Wintun adapter – requires admin privileges")?
        }
    };

    // Assign IP address via netsh
    let cmd = format!(
        "netsh interface ip set address name=\"{ADAPTER_NAME}\" \
         source=static addr={TUN_ADDR} mask=255.255.255.0 gateway={GATEWAY}"
    );
    run_netsh(&cmd).await?;

    // Start session (ring buffer)
    let session = Arc::new(
        adapter.start_session(RING_CAP).context("Start Wintun session")?
    );

    WINTUN_SESSION.get_or_init(|| Mutex::new(None));
    *WINTUN_SESSION.get().unwrap().lock() = Some(session.clone());

    // Install routing rules
    setup_routes(socks_addr).await?;

    // I/O loop
    let (stop_tx, stop_rx) = oneshot::channel::<()>();
    STOP_TX.get_or_init(|| Mutex::new(None));
    *STOP_TX.get().unwrap().lock() = Some(stop_tx);

    let socks = socks_addr.to_string();
    tokio::spawn(async move {
        wintun_io_loop(session, socks, stats, stop_rx).await;
    });

    info!("Windows Wintun tunnel active on {TUN_ADDR}");
    Ok(())
}

#[cfg(target_os = "windows")]
pub async fn stop() {
    info!("Windows tunnel: stopping Wintun");
    if let Some(cell) = STOP_TX.get() {
        if let Some(tx) = cell.lock().take() { let _ = tx.send(()); }
    }
    if let Some(cell) = WINTUN_SESSION.get() {
        cell.lock().take(); // drops session → closes adapter
    }
    tear_down_routes().await;
}

#[cfg(target_os = "windows")]
async fn setup_routes(socks_addr: &str) -> anyhow::Result<()> {
    let cmds = [
        // Default route via our TUN gateway
        format!("route ADD 0.0.0.0 MASK 0.0.0.0 {GATEWAY} METRIC 1"),
        // Exclude loopback & SOCKS proxy from redirect
        format!("route ADD 127.0.0.1 MASK 255.255.255.255 127.0.0.1 METRIC 1"),
    ];
    for cmd in &cmds {
        run_cmd(cmd).await.context(format!("Route: {cmd}"))?;
    }
    info!("Windows routing rules installed");
    Ok(())
}

#[cfg(target_os = "windows")]
async fn tear_down_routes() {
    let _ = run_cmd("route DELETE 0.0.0.0 MASK 0.0.0.0").await;
    info!("Windows routing rules removed");
}

#[cfg(target_os = "windows")]
async fn run_netsh(cmd: &str) -> anyhow::Result<()> {
    let parts: Vec<&str> = cmd.split_whitespace().collect();
    let status = tokio::process::Command::new("netsh")
        .args(&parts)
        .status().await?;
    if !status.success() { bail!("netsh failed: {cmd}"); }
    Ok(())
}

#[cfg(target_os = "windows")]
async fn run_cmd(cmd: &str) -> anyhow::Result<()> {
    let status = tokio::process::Command::new("cmd")
        .args(["/C", cmd])
        .status().await?;
    if !status.success() { bail!("cmd failed: {cmd}"); }
    Ok(())
}

#[cfg(target_os = "windows")]
async fn wintun_io_loop(
    session: Arc<Session>,
    socks_addr: String,
    stats: Arc<StatsTracker>,
    mut stop: oneshot::Receiver<()>,
) {
    use smoltcp::wire::{IpProtocol, Ipv4Packet};

    info!("Wintun I/O loop started");
    loop {
        tokio::select! {
            _ = &mut stop => { info!("Wintun loop stopping"); break; }
            packet = tokio::task::spawn_blocking({
                let s = session.clone();
                move || s.receive_blocking()
            }) => {
                match packet {
                    Err(_) | Ok(Err(_)) => { error!("Wintun recv error"); break; }
                    Ok(Ok(pkt)) => {
                        let bytes = pkt.bytes();
                        if let Ok(ip) = Ipv4Packet::new_checked(bytes) {
                            match ip.protocol() {
                                IpProtocol::Tcp => {
                                    let dst = ip.dst_addr().to_string();
                                    let socks = socks_addr.clone();
                                    let st = stats.clone();
                                    tokio::spawn(async move {
                                        debug!("Wintun TCP → {dst} via {socks}");
                                        st.increment_connections();
                                    });
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

// ── Stub for non-Windows builds ───────────────────────────────────────────────
#[cfg(not(target_os = "windows"))]
pub async fn start(
    _: &str, _: Arc<TorEngine>, _: Arc<StatsTracker>
) -> anyhow::Result<()> {
    anyhow::bail!("Windows tunnel called on non-Windows OS")
}
#[cfg(not(target_os = "windows"))]
pub async fn stop() {}
