// rust-core/src/lib.rs
//! NodeX VPN Core Engine
//!
//! Architecture:
//!   ┌─────────────────────────────────────────────────────┐
//!   │  Kotlin / Swift UI  (Compose Multiplatform)         │
//!   │         │  UniFFI auto-generated bindings            │
//!   ├─────────────────────────────────────────────────────┤
//!   │  lib.rs  (this file – public API)                   │
//!   │    ├── tor_manager.rs  (arti-client lifecycle)      │
//!   │    ├── socks_proxy.rs  (SOCKS5 listener)            │
//!   │    ├── tunnel/         (TUN device per platform)    │
//!   │    │     ├── linux.rs                               │
//!   │    │     ├── macos.rs                               │
//!   │    │     └── windows.rs                             │
//!   │    ├── tun2socks.rs    (IP→SOCKS bridge)            │
//!   │    └── stats.rs        (bandwidth / latency meter)  │
//!   └─────────────────────────────────────────────────────┘

uniffi::include_scaffolding!("nodex_vpn");

mod error;
mod tor_manager;
mod socks_proxy;
mod tun2socks;
mod tunnel;
mod stats;
mod node_registry;
mod dns;
mod logging;

pub use error::VpnError;

use std::sync::Arc;
use once_cell::sync::OnceCell;
use parking_lot::RwLock;
use tokio::runtime::Runtime;
use log::{info, warn, error};

// ── Public types re-exported for UniFFI ──────────────────────────────────────

#[derive(Debug, Clone)]
pub struct VpnConfig {
    pub socks_listen_addr:         String,
    pub dns_listen_addr:           String,
    pub use_bridges:               bool,
    pub bridge_lines:              Vec<String>,
    pub strict_exit_nodes:         bool,
    pub preferred_exit_iso:        Option<String>,
    pub circuit_build_timeout_secs: u32,
    pub state_dir:                 String,
    pub cache_dir:                 String,
}

#[derive(Debug, Clone, Default)]
pub struct VpnStats {
    pub bytes_sent:           u64,
    pub bytes_received:       u64,
    pub send_rate_bps:        u64,
    pub recv_rate_bps:        u64,
    pub active_circuits:      u32,
    pub pending_circuits:     u32,
    pub latency_ms:           f64,
    pub current_exit_country: String,
    pub current_exit_ip:      String,
    pub uptime_secs:          u64,
}

#[derive(Debug, Clone)]
pub struct ServerNode {
    pub id:              String,
    pub country_code:    String,
    pub country_name:    String,
    pub city:            String,
    pub latency_ms:      f64,
    pub load_percent:    u8,
    pub is_bridge:       bool,
    pub supports_obfs4:  bool,
}

#[derive(Debug, Clone, Default)]
pub struct BootstrapStatus {
    pub percent:       u8,
    pub phase:         String,
    pub is_complete:   bool,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum LogLevel {
    Error,
    Warn,
    Info,
    Debug,
    Trace,
}

// ── Global engine state ───────────────────────────────────────────────────────

struct EngineState {
    runtime:       Arc<Runtime>,
    tor:           Arc<tor_manager::TorEngine>,
    stats_tracker: Arc<stats::StatsTracker>,
    config:        VpnConfig,
    extra_bridges: Vec<String>,
    dns_over_tor:  bool,
    started_at:    std::time::Instant,
}

static ENGINE: OnceCell<Arc<RwLock<Option<EngineState>>>> = OnceCell::new();

fn engine_cell() -> &'static Arc<RwLock<Option<EngineState>>> {
    ENGINE.get_or_init(|| Arc::new(RwLock::new(None)))
}

// ── UniFFI-exported functions ─────────────────────────────────────────────────

/// Initialize and start the VPN engine.
/// Creates a Tokio runtime, bootstraps Tor, and brings up the TUN tunnel.
pub fn start_nodex(config: VpnConfig) -> Result<(), VpnError> {
    if is_running() {
        return Err(VpnError::AlreadyRunning);
    }

    logging::init(&LogLevel::Info);
    info!("NodeX VPN Core v{} starting…", env!("CARGO_PKG_VERSION"));

    let rt = Arc::new(
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .enable_all()
            .thread_name("nodex-worker")
            .build()
            .map_err(|e| { error!("Tokio runtime: {e}"); VpnError::TorInitFailed })?,
    );

    let stats_tracker = Arc::new(stats::StatsTracker::new());

    // Clone for async block
    let cfg = config.clone();
    let extra_bridges: Vec<String> = vec![];
    let st = stats_tracker.clone();

    let tor = rt.block_on(async move {
        tor_manager::TorEngine::new(&cfg, &extra_bridges, st).await
    }).map_err(|e| { error!("Tor init: {e}"); VpnError::TorInitFailed })?;

    let tor = Arc::new(tor);

    // Bring up the platform tunnel (TUN → SOCKS)
    let socks_addr = config.socks_listen_addr.clone();
    let tor_for_tunnel = tor.clone();
    let st2 = stats_tracker.clone();

    rt.block_on(async move {
        tunnel::platform::start_tunnel(&socks_addr, tor_for_tunnel, st2).await
    }).map_err(|e| { error!("Tunnel: {e}"); VpnError::TunnelCreationFailed })?;

    // DNS-over-Tor listener
    if config.use_bridges || true {
        let dns_addr = config.dns_listen_addr.clone();
        let tor_dns = tor.clone();
        rt.spawn(async move {
            if let Err(e) = dns::start_dns_listener(&dns_addr, tor_dns).await {
                warn!("DNS listener error: {e}");
            }
        });
    }

    let state = EngineState {
        runtime: rt,
        tor,
        stats_tracker,
        config,
        extra_bridges: vec![],
        dns_over_tor: true,
        started_at: std::time::Instant::now(),
    };

    *engine_cell().write() = Some(state);
    info!("NodeX VPN started successfully.");
    Ok(())
}

/// Gracefully shut down the VPN engine, tear down the tunnel, and close Tor.
pub fn stop_nodex() -> Result<(), VpnError> {
    let mut guard = engine_cell().write();
    match guard.take() {
        None => Err(VpnError::NotRunning),
        Some(state) => {
            info!("NodeX VPN stopping…");
            state.runtime.block_on(async {
                tunnel::platform::stop_tunnel().await;
                state.tor.shutdown().await;
            });
            // Runtime drops here – all async tasks are cancelled.
            info!("NodeX VPN stopped.");
            Ok(())
        }
    }
}

/// Returns true if the engine is currently running.
pub fn is_running() -> bool {
    engine_cell().read().is_some()
}

/// Request a specific exit country by ISO code (e.g. "US", "DE").
pub fn set_exit_node(iso_code: String) -> Result<(), VpnError> {
    let guard = engine_cell().read();
    match guard.as_ref() {
        None => Err(VpnError::NotRunning),
        Some(state) => {
            state.runtime.block_on(async {
                state.tor.set_exit_node(&iso_code).await
            }).map_err(|_| VpnError::TorInitFailed)
        }
    }
}

/// Returns the current exit relay's IP address as seen by the destination.
pub fn get_current_exit_ip() -> Option<String> {
    engine_cell().read().as_ref()?.tor.get_exit_ip()
}

/// Returns the pre-loaded list of available exit node countries.
pub fn get_available_nodes() -> Vec<ServerNode> {
    node_registry::NODES.to_vec()
}

/// Snapshot of real-time bandwidth and circuit statistics.
pub fn get_real_time_stats() -> VpnStats {
    let guard = engine_cell().read();
    match guard.as_ref() {
        None => VpnStats::default(),
        Some(state) => {
            let mut s = state.stats_tracker.snapshot();
            s.uptime_secs = state.started_at.elapsed().as_secs();
            s
        }
    }
}

/// Current Tor bootstrap progress (0–100%).
pub fn get_bootstrap_status() -> BootstrapStatus {
    let guard = engine_cell().read();
    match guard.as_ref() {
        None => BootstrapStatus {
            percent: 0,
            phase: "Idle".into(),
            is_complete: false,
            error_message: None,
        },
        Some(state) => state.tor.bootstrap_status(),
    }
}

/// Add an obfs4 bridge line at runtime (takes effect on next circuit build).
pub fn add_bridge(bridge_line: String) -> Result<(), VpnError> {
    let mut guard = engine_cell().write();
    match guard.as_mut() {
        None => Err(VpnError::NotRunning),
        Some(state) => {
            state.extra_bridges.push(bridge_line.clone());
            state.runtime.block_on(async {
                state.tor.add_bridge(&bridge_line).await
            }).map_err(|_| VpnError::BridgeConnectionFailed)
        }
    }
}

/// Remove all configured bridges.
pub fn clear_bridges() {
    if let Some(state) = engine_cell().write().as_mut() {
        state.extra_bridges.clear();
        state.runtime.block_on(async { state.tor.clear_bridges().await });
    }
}

/// Enable or disable DNS resolution via Tor (prevents DNS leaks).
pub fn set_dns_over_tor(enabled: bool) {
    if let Some(state) = engine_cell().write().as_mut() {
        state.dns_over_tor = enabled;
        let addr = state.config.dns_listen_addr.clone();
        let tor = state.tor.clone();
        state.runtime.spawn(async move {
            if enabled {
                let _ = dns::start_dns_listener(&addr, tor).await;
            } else {
                dns::stop_dns_listener().await;
            }
        });
    }
}

/// Set the logging verbosity.
pub fn set_log_level(level: LogLevel) {
    logging::set_level(&level);
}

/// Return the last `max_lines` log entries as a newline-delimited string.
pub fn get_recent_logs(max_lines: u32) -> String {
    logging::recent_logs(max_lines as usize)
}
