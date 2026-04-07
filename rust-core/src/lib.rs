// rust-core/src/lib.rs — NodeX VPN Core v0.5.0 (Production)
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

// ── Public types ──────────────────────────────────────────────────────────────
#[derive(Debug, Clone)]
pub struct VpnConfig {
    pub socks_listen_addr:          String,
    pub dns_listen_addr:            String,
    pub use_bridges:                bool,
    pub bridge_lines:               Vec<String>,
    pub strict_exit_nodes:          bool,
    pub preferred_exit_iso:         Option<String>,
    pub circuit_build_timeout_secs: u32,
    pub state_dir:                  String,
    pub cache_dir:                  String,
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
    pub id:             String,
    pub country_code:   String,
    pub country_name:   String,
    pub city:           String,
    pub latency_ms:     f64,
    pub load_percent:   u8,
    pub is_bridge:      bool,
    pub supports_obfs4: bool,
}

#[derive(Debug, Clone, Default)]
pub struct BootstrapStatus {
    pub percent:       u8,
    pub phase:         String,
    pub is_complete:   bool,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub enum LogLevel { Error, Warn, Info, Debug, Trace }

// ── Engine state ──────────────────────────────────────────────────────────────
struct EngineState {
    runtime:    Arc<Runtime>,
    tor:        Arc<tor_manager::TorEngine>,
    stats:      Arc<stats::StatsTracker>,
    config:     VpnConfig,
    started_at: std::time::Instant,
}

static ENGINE: OnceCell<Arc<RwLock<Option<EngineState>>>> = OnceCell::new();
fn engine() -> &'static Arc<RwLock<Option<EngineState>>> {
    ENGINE.get_or_init(|| Arc::new(RwLock::new(None)))
}

// ── UniFFI API ────────────────────────────────────────────────────────────────

pub fn start_nodex(config: VpnConfig) -> Result<(), VpnError> {
    if is_running() { return Err(VpnError::AlreadyRunning); }

    logging::init(&LogLevel::Info);
    info!("NodeX VPN Core v{} starting…", env!("CARGO_PKG_VERSION"));

    let rt = Arc::new(
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .enable_all()
            .thread_name("nodex-worker")
            .build()
            .map_err(|e| { error!("Tokio runtime: {e}"); VpnError::TorInitFailed })?
    );

    let stats = Arc::new(stats::StatsTracker::new());

    // Start Tor engine
    let cfg = config.clone();
    let st  = stats.clone();
    let tor = rt.block_on(async move {
        tor_manager::TorEngine::new(&cfg, st).await
    }).map_err(|e| { error!("Tor init: {e}"); VpnError::TorInitFailed })?;

    let tor = Arc::new(tor);

    // Set initial exit node preference
    if let Some(ref iso) = config.preferred_exit_iso {
        let _ = rt.block_on(tor.set_exit_node(iso));
    }

    // Start platform tunnel (non-blocking — waits for Tor to bootstrap internally)
    let socks   = config.socks_listen_addr.clone();
    let tor_tun = tor.clone();
    let st2     = stats.clone();
    rt.block_on(async move {
        tunnel::platform::start_tunnel(&socks, tor_tun, st2).await
    }).map_err(|e| { error!("Tunnel: {e}"); VpnError::TunnelCreationFailed })?;

    // DNS-over-Tor listener
    let dns_addr = config.dns_listen_addr.clone();
    let tor_dns  = tor.clone();
    rt.spawn(async move {
        if let Err(e) = dns::start_dns_listener(&dns_addr, tor_dns).await {
            warn!("DNS listener: {e}");
        }
    });

    // Latency prober
    let st3 = stats.clone();
    rt.spawn(stats::latency_probe_loop(st3));

    *engine().write() = Some(EngineState {
        runtime:    rt,
        tor,
        stats,
        config,
        started_at: std::time::Instant::now(),
    });

    info!("NodeX VPN engine started");
    Ok(())
}

pub fn stop_nodex() -> Result<(), VpnError> {
    let mut guard = engine().write();
    match guard.take() {
        None => Err(VpnError::NotRunning),
        Some(state) => {
            info!("Stopping NodeX VPN…");
            state.runtime.block_on(async {
                tunnel::platform::stop_tunnel().await;
                dns::stop_dns_listener().await;
                state.tor.shutdown().await;
            });
            info!("NodeX VPN stopped");
            Ok(())
        }
    }
}

pub fn is_running() -> bool { engine().read().is_some() }

pub fn set_exit_node(iso_code: String) -> Result<(), VpnError> {
    let guard = engine().read();
    let state = guard.as_ref().ok_or(VpnError::NotRunning)?;
    state.runtime
        .block_on(state.tor.set_exit_node(&iso_code))
        .map_err(|_| VpnError::TorInitFailed)
}

pub fn get_current_exit_ip() -> Option<String> {
    engine().read().as_ref()?.tor.get_exit_ip()
}

pub fn get_available_nodes() -> Vec<ServerNode> {
    node_registry::NODES.iter().cloned().collect()
}

pub fn get_real_time_stats() -> VpnStats {
    let guard = engine().read();
    match guard.as_ref() {
        None    => VpnStats::default(),
        Some(s) => {
            let mut st = s.stats.snapshot();
            st.uptime_secs = s.started_at.elapsed().as_secs();
            st
        }
    }
}

pub fn get_bootstrap_status() -> BootstrapStatus {
    engine().read()
        .as_ref()
        .map(|s| s.tor.bootstrap_status())
        .unwrap_or(BootstrapStatus { phase: "Idle".into(), ..Default::default() })
}

pub fn add_bridge(bridge_line: String) -> Result<(), VpnError> {
    let guard = engine().read();
    let state = guard.as_ref().ok_or(VpnError::NotRunning)?;
    state.runtime
        .block_on(state.tor.add_bridge(&bridge_line))
        .map_err(|_| VpnError::BridgeConnectionFailed)
}

pub fn clear_bridges() {
    if let Some(state) = engine().read().as_ref() {
        state.runtime.block_on(state.tor.clear_bridges());
    }
}

pub fn set_dns_over_tor(enabled: bool) {
    if let Some(state) = engine().read().as_ref() {
        let addr    = state.config.dns_listen_addr.clone();
        let tor_ref = state.tor.clone();
        state.runtime.spawn(async move {
            if enabled {
                let _ = dns::start_dns_listener(&addr, tor_ref).await;
            } else {
                dns::stop_dns_listener().await;
            }
        });
    }
}

pub fn set_log_level(level: LogLevel)     { logging::set_level(&level); }
pub fn get_recent_logs(max_lines: u32) -> String { logging::recent_logs(max_lines as usize) }
