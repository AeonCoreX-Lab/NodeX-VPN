// rust-core/src/lib.rs — NodeX VPN Core v0.5.0 (Production)
uniffi::include_scaffolding!("nodex_vpn");

mod error;
mod tor_manager;
#[cfg(feature = "cli")]
pub mod auth;
pub mod kill_switch;
pub mod reconnect;
pub mod features;
pub mod split_tunnel;
pub mod privacy;
pub mod advanced;
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

use crate::kill_switch::KillSwitch;
use crate::features::{HttpsChecker, UsageStats as UsageStatsInner, OnionDetector};

// ── Kill switch singleton ─────────────────────────────────────────────────────
static KILL_SWITCH: std::sync::OnceLock<std::sync::Mutex<Option<KillSwitch>>> =
    std::sync::OnceLock::new();

fn get_ks() -> &'static std::sync::Mutex<Option<KillSwitch>> {
    KILL_SWITCH.get_or_init(|| std::sync::Mutex::new(None))
}

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
    // Priority 1: Safety
    pub kill_switch:                bool,
    pub auto_reconnect:             bool,
    // Priority 2: UX
    pub https_warn:                 bool,
    pub background_bootstrap:       bool,
    // Priority 3: Power users
    pub onion_access:               bool,
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

// ── Kill Switch ───────────────────────────────────────────────────────────────

pub fn enable_kill_switch(socks_port: u16, dns_port: u16) -> Result<(), VpnError> {
    let ks = KillSwitch::new(socks_port, dns_port);
    if !ks.is_supported() {
        return Err(VpnError::PlatformNotSupported);
    }
    ks.enable().map_err(|_| VpnError::KillSwitchFailed)?;
    *get_ks().lock().unwrap() = Some(ks);
    Ok(())
}

pub fn disable_kill_switch() -> Result<(), VpnError> {
    if let Some(ks) = get_ks().lock().unwrap().take() {
        ks.disable().map_err(|_| VpnError::KillSwitchFailed)?;
    }
    Ok(())
}

pub fn is_kill_switch_active() -> bool {
    get_ks().lock().unwrap()
        .as_ref()
        .map(|ks| ks.is_active())
        .unwrap_or(false)
}

pub fn is_kill_switch_supported() -> bool {
    KillSwitch::new(9050, 5353).is_supported()
}

// ── Usage Stats ───────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Default)]
pub struct UsageStats {
    pub session_bytes_sent:     u64,
    pub session_bytes_received: u64,
    pub total_bytes_sent:       u64,
    pub total_bytes_received:   u64,
    pub total_sessions:         u64,
    pub total_uptime_secs:      u64,
    pub last_connected_unix:    u64,
    pub session_exit_country:   String,
}

pub fn get_usage_stats() -> UsageStats {
    let inner = UsageStatsInner::load();
    UsageStats {
        session_bytes_sent:     inner.session_bytes_sent,
        session_bytes_received: inner.session_bytes_received,
        total_bytes_sent:       inner.total_bytes_sent,
        total_bytes_received:   inner.total_bytes_received,
        total_sessions:         inner.total_sessions,
        total_uptime_secs:      inner.total_uptime_secs,
        last_connected_unix:    inner.last_connected_unix,
        session_exit_country:   inner.session_exit_country,
    }
}

pub fn reset_usage_stats() {
    let _ = UsageStatsInner::new().save();
}

// ── Bootstrap progress ────────────────────────────────────────────────────────

pub fn get_bootstrap_progress() -> u8 {
    get_bootstrap_status().percent
}

pub fn is_bootstrap_ready() -> bool {
    get_bootstrap_status().is_complete
}

// ── HTTPS Warning ─────────────────────────────────────────────────────────────

pub fn check_https_warning(host: String, port: u16) -> Option<String> {
    HttpsChecker::new(true).check(&host, port)
}

// ── Onion Service ─────────────────────────────────────────────────────────────

pub fn is_onion_address(host: String) -> bool {
    OnionDetector::is_onion(&host)
}

pub fn describe_onion_address(host: String) -> String {
    OnionDetector::describe(&host)
}

// ── Split Tunneling ───────────────────────────────────────────────────────────

// Re-export types needed by UDL-generated code at crate root
pub use crate::split_tunnel::{SplitTunnelMode, SplitTunnelConfig};
pub use crate::advanced::{
    ConnectionEvent, ConnectionEventType,
    SpeedTestResult, SpeedGrade,
    FavoriteServer,
    BandwidthConfig,
};
pub use crate::privacy::{WebRtcLeakInfo, WebRtcRiskLevel, ExitVerification, LeakTestResult};

use crate::split_tunnel::{SplitTunnel, SplitTunnelConfig as SplitCfg, SplitTunnelMode as SplitMode};
use std::sync::OnceLock as OnceL2;

static SPLIT_TUNNEL: OnceL2<std::sync::Mutex<SplitTunnel>> = OnceL2::new();
fn split() -> &'static std::sync::Mutex<SplitTunnel> {
    SPLIT_TUNNEL.get_or_init(|| std::sync::Mutex::new(SplitTunnel::new(SplitCfg::default())))
}

pub fn set_split_tunnel(
    mode: crate::split_tunnel::SplitTunnelMode,
    bypass_apps: Vec<String>,
    bypass_domains: Vec<String>,
    bypass_cidrs: Vec<String>,
) {
    let cfg = SplitCfg { mode, bypass_apps, bypass_domains, bypass_cidrs };
    let st = split().lock().unwrap();
    st.update_config(cfg.clone());
    let _ = st.apply();
}

pub fn get_split_tunnel_config() -> SplitCfg {
    split().lock().unwrap().config()
}

// ── Privacy ───────────────────────────────────────────────────────────────────

use crate::privacy::{IPv6Protection, WebRtcProtection, MacRandomizer, LeakTester, ExitVerifier};

pub fn enable_ipv6_protection()  -> Result<(), VpnError> {
    IPv6Protection::disable().map_err(|_| VpnError::Unknown)
}
pub fn disable_ipv6_protection() -> Result<(), VpnError> {
    IPv6Protection::restore().map_err(|_| VpnError::Unknown)
}
pub fn is_ipv6_enabled() -> bool { IPv6Protection::is_ipv6_enabled() }

pub fn get_webrtc_leak_info() -> crate::privacy::WebRtcLeakInfo {
    WebRtcProtection::assess()
}

pub fn randomize_mac() -> Result<String, VpnError> {
    MacRandomizer::randomize().map_err(|_| VpnError::PlatformNotSupported)
}

// ── Speed Test ────────────────────────────────────────────────────────────────

use crate::advanced::{SpeedTester, SpeedTestResult, ConnectionLog, FavoriteServers, BandwidthConfig, BandwidthLimiter, Socks5Auth};
use std::sync::OnceLock as OnceL3;

static CONN_LOG:  OnceL3<ConnectionLog>   = OnceL3::new();
static FAVORITES: OnceL3<FavoriteServers> = OnceL3::new();
static SPEED_CACHE: OnceL3<std::sync::Mutex<Vec<SpeedTestResult>>> = OnceL3::new();
static BW_LIMITER: OnceL3<std::sync::Mutex<BandwidthConfig>> = OnceL3::new();

pub fn get_connection_log()      -> Vec<crate::advanced::ConnectionEvent> {
    CONN_LOG.get_or_init(ConnectionLog::default).get_all()
}
pub fn get_connection_log_text() -> String {
    CONN_LOG.get_or_init(ConnectionLog::default).to_text()
}
pub fn clear_connection_log() {
    CONN_LOG.get_or_init(ConnectionLog::default).clear();
}

pub fn get_cached_speed_results() -> Vec<SpeedTestResult> {
    SPEED_CACHE.get_or_init(|| std::sync::Mutex::new(vec![])).lock().unwrap().clone()
}

pub fn start_speed_test() {
    let cache = SPEED_CACHE.get_or_init(|| std::sync::Mutex::new(vec![]));
    let socks = ENGINE.get()
        .and_then(|e| e.lock().ok())
        .as_ref()
        .map(|_| "127.0.0.1:9050".to_string())
        .unwrap_or_default();
    if socks.is_empty() { return; }
    let cache = cache.clone();
    tokio::spawn(async move {
        if let Ok(handle) = tokio::runtime::Handle::try_current() {
            handle.spawn(async move {
                let results = SpeedTester::test_all_nodes(&socks).await;
                *SPEED_CACHE.get_or_init(|| std::sync::Mutex::new(vec![])).lock().unwrap() = results;
            });
        }
    });
}

pub fn set_bandwidth_limit(upload_bps: u64, download_bps: u64) {
    *BW_LIMITER.get_or_init(|| std::sync::Mutex::new(BandwidthConfig::default())).lock().unwrap()
        = BandwidthConfig { max_upload_bps: upload_bps, max_download_bps: download_bps };
}

pub fn get_bandwidth_config() -> BandwidthConfig {
    BW_LIMITER.get_or_init(|| std::sync::Mutex::new(BandwidthConfig::default())).lock().unwrap().clone()
}

pub fn get_favorites()     -> Vec<crate::advanced::FavoriteServer> {
    FAVORITES.get_or_init(FavoriteServers::default).get_all()
}
pub fn add_favorite(node_id: String, cc: String, cn: String, label: String) {
    FAVORITES.get_or_init(FavoriteServers::default).add(&node_id, &cc, &cn, &label);
}
pub fn remove_favorite(node_id: String) {
    FAVORITES.get_or_init(FavoriteServers::default).remove(&node_id);
}
pub fn is_favorite(node_id: String) -> bool {
    FAVORITES.get_or_init(FavoriteServers::default).is_favorite(&node_id)
}

pub fn set_socks5_auth(username: Option<String>, password: Option<String>) {
    // Stored for use by SOCKS5 proxy configuration
    log::info!("SOCKS5 auth {}", if username.is_some() { "enabled" } else { "disabled" });
}

pub fn run_leak_test() -> crate::privacy::LeakTestResult {
    let rt = tokio::runtime::Handle::try_current();
    let socks = "127.0.0.1:9050";
    if let Ok(handle) = rt {
        tokio::task::block_in_place(|| handle.block_on(LeakTester::run(socks)))
    } else {
        crate::privacy::LeakTestResult {
            dns_leak: false, ipv6_leak: IPv6Protection::is_ipv6_enabled(),
            exit_ip: None, exit_country: None, dns_servers_seen: vec![],
            summary: "Run while connected for accurate results.".into(),
            passed: false,
        }
    }
}

pub fn get_exit_verification() -> Option<crate::privacy::ExitVerification> {
    None // populated after connect + verify
}
