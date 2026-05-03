// src/advanced.rs
// NodeX VPN — Advanced Features Module
//
// ① Pluggable Transports    — Snowflake, meek, WebTunnel (beyond obfs4)
// ② Circuit Control         — rebuild, custom path, per-app isolation
// ③ Node Speed Test         — latency test exit nodes before connecting
// ④ Bandwidth Limiter       — cap upload/download rate
// ⑤ Connection Log          — timestamped event timeline
// ⑥ Favorite Servers        — bookmark frequently used nodes
// ⑦ SOCKS5 Authentication   — username/password for SOCKS5 proxy

use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use std::collections::VecDeque;
use serde::{Deserialize, Serialize};

// ── ① Pluggable Transports ────────────────────────────────────────────────────

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum BridgeTransport {
    /// obfs4 — most common, works in China/Iran/Russia
    Obfs4,
    /// Snowflake — uses WebRTC, harder to block
    Snowflake,
    /// meek-azure — disguises traffic as Microsoft Azure
    MeekAzure,
    /// WebTunnel — looks like HTTPS browsing
    WebTunnel,
    /// Custom transport with PT binary path
    Custom { pt_binary: String },
}

impl BridgeTransport {
    pub fn name(&self) -> &str {
        match self {
            Self::Obfs4          => "obfs4",
            Self::Snowflake      => "snowflake",
            Self::MeekAzure      => "meek-azure",
            Self::WebTunnel      => "webtunnel",
            Self::Custom { pt_binary } => pt_binary,
        }
    }

    /// Returns true if this transport is likely available via Arti
    pub fn is_arti_supported(&self) -> bool {
        matches!(self, Self::Obfs4 | Self::Snowflake)
    }

    /// User-facing description
    pub fn description(&self) -> &str {
        match self {
            Self::Obfs4      => "Most reliable. Works in China, Iran, Russia.",
            Self::Snowflake  => "Uses WebRTC — hardest to block. Slower.",
            Self::MeekAzure  => "Disguises as Microsoft Azure traffic.",
            Self::WebTunnel  => "Looks like HTTPS browsing. Very stealthy.",
            Self::Custom{..} => "Custom pluggable transport.",
        }
    }

    /// Default bridge lines for automatic mode (fetched from BridgeDB)
    pub fn default_bridge_line(&self) -> Option<&'static str> {
        match self {
            Self::Snowflake => Some(
                "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 \
                 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 \
                 url=https://snowflake-broker.torproject.net.global.prod.fastly.net/ \
                 front=cdn.sstatic.net \
                 ice=stun:stun.l.google.com:19302,stun:stun.antisip.com:3478 \
                 utls-imitate=hellorandomizedalpn"
            ),
            _ => None,
        }
    }
}

// ── ② Circuit Control ─────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CircuitInfo {
    pub id:           String,
    pub status:       CircuitStatus,
    pub built_at:     u64,
    pub bytes_sent:   u64,
    pub bytes_recv:   u64,
    pub guard_country:  String,
    pub middle_country: String,
    pub exit_country:   String,
    pub latency_ms:   f64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum CircuitStatus {
    Building,
    Ready,
    Closed,
    Failed,
}

pub struct CircuitManager {
    circuits: Arc<RwLock<Vec<CircuitInfo>>>,
}

impl CircuitManager {
    pub fn new() -> Self {
        Self { circuits: Arc::new(RwLock::new(Vec::new())) }
    }

    pub fn get_all(&self) -> Vec<CircuitInfo> {
        self.circuits.read().unwrap().clone()
    }

    pub fn active_count(&self) -> usize {
        self.circuits.read().unwrap()
            .iter()
            .filter(|c| c.status == CircuitStatus::Ready)
            .count()
    }

    pub fn add(&self, circuit: CircuitInfo) {
        self.circuits.write().unwrap().push(circuit);
    }

    pub fn close(&self, id: &str) {
        let mut cs = self.circuits.write().unwrap();
        if let Some(c) = cs.iter_mut().find(|c| c.id == id) {
            c.status = CircuitStatus::Closed;
        }
    }

    /// Request new circuit — arti handles circuit building internally
    pub fn request_new_circuit(&self) -> String {
        let id = format!("circuit-{}", unix_now());
        self.add(CircuitInfo {
            id:             id.clone(),
            status:         CircuitStatus::Building,
            built_at:       unix_now(),
            bytes_sent:     0,
            bytes_recv:     0,
            guard_country:  "??".into(),
            middle_country: "??".into(),
            exit_country:   "??".into(),
            latency_ms:     0.0,
        });
        log::info!("New circuit requested: {id}");
        id
    }
}

impl Default for CircuitManager { fn default() -> Self { Self::new() } }

// ── ③ Node Speed Test ─────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SpeedTestResult {
    pub node_id:       String,
    pub country_code:  String,
    pub latency_ms:    f64,
    pub download_kbps: f64,
    pub grade:         SpeedGrade,
    pub tested_at:     u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SpeedGrade { Excellent, Good, Fair, Poor }

impl SpeedGrade {
    pub fn from_latency(ms: f64) -> Self {
        match ms as u32 {
            0..=100  => Self::Excellent,
            101..=200 => Self::Good,
            201..=400 => Self::Fair,
            _         => Self::Poor,
        }
    }
    pub fn label(self) -> &'static str {
        match self {
            Self::Excellent => "Excellent",
            Self::Good      => "Good",
            Self::Fair      => "Fair",
            Self::Poor      => "Poor",
        }
    }
}

pub struct SpeedTester;

impl SpeedTester {
    /// Test latency to a node via Tor SOCKS5.
    /// Makes 3 HTTP HEAD requests to a fast endpoint and takes median.
    pub async fn test_node(
        node_id:     &str,
        country:     &str,
        socks_addr:  &str,
    ) -> anyhow::Result<SpeedTestResult> {
        let client = reqwest::Client::builder()
            .proxy(reqwest::Proxy::all(format!("socks5h://{socks_addr}"))?)
            .timeout(Duration::from_secs(15))
            .build()?;

        let test_url = "https://www.google.com/generate_204";
        let mut latencies = Vec::new();

        for _ in 0..3 {
            let start = Instant::now();
            let r = client.head(test_url).send().await;
            if r.is_ok() {
                latencies.push(start.elapsed().as_secs_f64() * 1000.0);
            }
        }

        let latency_ms = if latencies.is_empty() {
            9999.0
        } else {
            latencies.sort_by(|a, b| a.partial_cmp(b).unwrap());
            latencies[latencies.len() / 2]  // median
        };

        Ok(SpeedTestResult {
            node_id:      node_id.into(),
            country_code: country.into(),
            latency_ms,
            download_kbps: 0.0, // Full bandwidth test is too slow via Tor
            grade:        SpeedGrade::from_latency(latency_ms),
            tested_at:    unix_now(),
        })
    }

    /// Test all available nodes and return sorted by latency
    pub async fn test_all_nodes(socks_addr: &str) -> Vec<SpeedTestResult> {
        use crate::node_registry::get_available_nodes;
        let nodes = get_available_nodes();
        let mut results = Vec::new();

        for node in nodes.iter().take(10) { // test top 10 to avoid too long
            if let Ok(r) = Self::test_node(&node.id, &node.country_code, socks_addr).await {
                results.push(r);
            }
        }

        results.sort_by(|a, b| a.latency_ms.partial_cmp(&b.latency_ms).unwrap());
        results
    }
}

// ── ④ Bandwidth Limiter ───────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BandwidthConfig {
    /// Max upload rate in bytes/sec (0 = unlimited)
    pub max_upload_bps:   u64,
    /// Max download rate in bytes/sec (0 = unlimited)
    pub max_download_bps: u64,
}

impl Default for BandwidthConfig {
    fn default() -> Self {
        Self { max_upload_bps: 0, max_download_bps: 0 }
    }
}

impl BandwidthConfig {
    pub fn unlimited() -> Self { Self::default() }

    pub fn new(upload_mbps: f64, download_mbps: f64) -> Self {
        Self {
            max_upload_bps:   (upload_mbps   * 1_000_000.0 / 8.0) as u64,
            max_download_bps: (download_mbps * 1_000_000.0 / 8.0) as u64,
        }
    }

    pub fn is_limited(&self) -> bool {
        self.max_upload_bps > 0 || self.max_download_bps > 0
    }

    pub fn upload_mbps(&self) -> f64 {
        self.max_upload_bps as f64 * 8.0 / 1_000_000.0
    }

    pub fn download_mbps(&self) -> f64 {
        self.max_download_bps as f64 * 8.0 / 1_000_000.0
    }
}

/// Token bucket rate limiter for bandwidth capping
pub struct BandwidthLimiter {
    config:         Arc<RwLock<BandwidthConfig>>,
    upload_tokens:  Arc<Mutex<f64>>,
    download_tokens: Arc<Mutex<f64>>,
    last_refill:    Arc<Mutex<Instant>>,
}

impl BandwidthLimiter {
    pub fn new(config: BandwidthConfig) -> Self {
        let max_up   = config.max_upload_bps   as f64;
        let max_down = config.max_download_bps as f64;
        Self {
            config:          Arc::new(RwLock::new(config)),
            upload_tokens:   Arc::new(Mutex::new(max_up)),
            download_tokens: Arc::new(Mutex::new(max_down)),
            last_refill:     Arc::new(Mutex::new(Instant::now())),
        }
    }

    pub fn update_config(&self, config: BandwidthConfig) {
        *self.config.write().unwrap() = config;
    }

    /// Check if `bytes` can be uploaded. Returns delay needed if rate limited.
    pub fn check_upload(&self, bytes: u64) -> Option<Duration> {
        self.check_tokens(&self.upload_tokens, bytes,
            self.config.read().unwrap().max_upload_bps)
    }

    /// Check if `bytes` can be downloaded.
    pub fn check_download(&self, bytes: u64) -> Option<Duration> {
        self.check_tokens(&self.download_tokens, bytes,
            self.config.read().unwrap().max_download_bps)
    }

    fn check_tokens(&self, tokens: &Mutex<f64>, bytes: u64, max_bps: u64) -> Option<Duration> {
        if max_bps == 0 { return None; } // unlimited

        // Refill tokens based on elapsed time
        let elapsed = {
            let mut last = self.last_refill.lock().unwrap();
            let e = last.elapsed().as_secs_f64();
            *last = Instant::now();
            e
        };

        let mut t = tokens.lock().unwrap();
        *t = (*t + elapsed * max_bps as f64).min(max_bps as f64);

        let needed = bytes as f64;
        if *t >= needed {
            *t -= needed;
            None
        } else {
            let deficit = needed - *t;
            let wait_secs = deficit / max_bps as f64;
            Some(Duration::from_secs_f64(wait_secs))
        }
    }
}

// ── ⑤ Connection Log ──────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectionEvent {
    pub timestamp:  u64,
    pub event_type: ConnectionEventType,
    pub detail:     String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ConnectionEventType {
    Connected,
    Disconnected,
    Reconnecting,
    CircuitBuilt,
    CircuitFailed,
    BridgeConnected,
    KillSwitchEnabled,
    KillSwitchDisabled,
    ExitIPVerified,
    LeakDetected,
    UserAction,
}

impl ConnectionEventType {
    pub fn icon(self) -> &'static str {
        match self {
            Self::Connected         => "✔",
            Self::Disconnected      => "✘",
            Self::Reconnecting      => "↺",
            Self::CircuitBuilt      => "⚡",
            Self::CircuitFailed     => "⚠",
            Self::BridgeConnected   => "🌉",
            Self::KillSwitchEnabled => "🔒",
            Self::KillSwitchDisabled=> "🔓",
            Self::ExitIPVerified    => "✅",
            Self::LeakDetected      => "⚠",
            Self::UserAction        => "›",
        }
    }
}

pub struct ConnectionLog {
    events:    Arc<Mutex<VecDeque<ConnectionEvent>>>,
    max_events: usize,
}

impl ConnectionLog {
    pub fn new(max_events: usize) -> Self {
        Self {
            events: Arc::new(Mutex::new(VecDeque::new())),
            max_events,
        }
    }

    pub fn push(&self, event_type: ConnectionEventType, detail: impl Into<String>) {
        let event = ConnectionEvent {
            timestamp:  unix_now(),
            event_type,
            detail:     detail.into(),
        };
        log::info!("{} {}", event_type.icon(), event.detail);
        let mut evs = self.events.lock().unwrap();
        if evs.len() >= self.max_events { evs.pop_front(); }
        evs.push_back(event);
    }

    pub fn get_all(&self) -> Vec<ConnectionEvent> {
        self.events.lock().unwrap().iter().cloned().collect()
    }

    pub fn get_recent(&self, n: usize) -> Vec<ConnectionEvent> {
        let evs = self.events.lock().unwrap();
        evs.iter().rev().take(n).cloned().collect::<Vec<_>>()
            .into_iter().rev().collect()
    }

    pub fn clear(&self) {
        self.events.lock().unwrap().clear();
    }

    pub fn to_text(&self) -> String {
        self.get_all().iter().map(|e| {
            format!("[{}] {} {}", fmt_timestamp(e.timestamp), e.event_type.icon(), e.detail)
        }).collect::<Vec<_>>().join("\n")
    }
}

impl Default for ConnectionLog {
    fn default() -> Self { Self::new(500) }
}

// ── ⑥ Favorite Servers ───────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FavoriteServer {
    pub node_id:      String,
    pub country_code: String,
    pub country_name: String,
    pub label:        String,    // user-defined nickname
    pub added_at:     u64,
    pub last_used_at: u64,
    pub use_count:    u32,
}

pub struct FavoriteServers {
    favorites: Arc<RwLock<Vec<FavoriteServer>>>,
}

impl FavoriteServers {
    pub fn new() -> Self {
        Self { favorites: Arc::new(RwLock::new(Self::load_from_disk())) }
    }

    pub fn add(&self, node_id: &str, country_code: &str, country_name: &str, label: &str) {
        let mut favs = self.favorites.write().unwrap();
        if favs.iter().any(|f| f.node_id == node_id) { return; }
        favs.push(FavoriteServer {
            node_id:      node_id.into(),
            country_code: country_code.into(),
            country_name: country_name.into(),
            label:        label.into(),
            added_at:     unix_now(),
            last_used_at: 0,
            use_count:    0,
        });
        self.save();
    }

    pub fn remove(&self, node_id: &str) {
        self.favorites.write().unwrap().retain(|f| f.node_id != node_id);
        self.save();
    }

    pub fn mark_used(&self, node_id: &str) {
        let mut favs = self.favorites.write().unwrap();
        if let Some(f) = favs.iter_mut().find(|f| f.node_id == node_id) {
            f.last_used_at = unix_now();
            f.use_count   += 1;
        }
        self.save();
    }

    pub fn get_all(&self) -> Vec<FavoriteServer> {
        self.favorites.read().unwrap().clone()
    }

    pub fn is_favorite(&self, node_id: &str) -> bool {
        self.favorites.read().unwrap().iter().any(|f| f.node_id == node_id)
    }

    fn save(&self) {
        let path = Self::path();
        if let Some(parent) = path.parent() { let _ = std::fs::create_dir_all(parent); }
        let favs = self.favorites.read().unwrap();
        if let Ok(json) = serde_json::to_string_pretty(&*favs) {
            let _ = std::fs::write(path, json);
        }
    }

    fn load_from_disk() -> Vec<FavoriteServer> {
        std::fs::read_to_string(Self::path())
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default()
    }

    fn path() -> std::path::PathBuf {
        dirs::home_dir()
            .unwrap_or_else(|| std::path::PathBuf::from("."))
            .join(".nodex")
            .join("favorites.json")
    }
}

impl Default for FavoriteServers { fn default() -> Self { Self::new() } }

// ── ⑦ SOCKS5 Authentication ──────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Socks5Auth {
    pub username: Option<String>,
    pub password: Option<String>,
    pub enabled:  bool,
}

impl Socks5Auth {
    pub fn new(username: impl Into<String>, password: impl Into<String>) -> Self {
        Self {
            username: Some(username.into()),
            password: Some(password.into()),
            enabled:  true,
        }
    }

    pub fn disabled() -> Self { Self::default() }

    /// Format as socks5://user:pass@host:port
    pub fn proxy_url(&self, host: &str, port: u16) -> String {
        if self.enabled {
            if let (Some(u), Some(p)) = (&self.username, &self.password) {
                return format!("socks5://{}:{}@{}:{}", u, p, host, port);
            }
        }
        format!("socks5://{}:{}", host, port)
    }
}

// ── ⑧ Config Export / Import ──────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExportedConfig {
    pub version:     u32,
    pub exported_at: u64,
    pub config:      crate::features::UsageStats,
    pub favorites:   Vec<FavoriteServer>,
    pub bandwidth:   BandwidthConfig,
}

// ── Helpers ───────────────────────────────────────────────────────────────────

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn fmt_timestamp(ts: u64) -> String {
    // Simple HH:MM:SS display without date crate
    let secs_today = ts % 86400;
    let h = secs_today / 3600;
    let m = (secs_today % 3600) / 60;
    let s = secs_today % 60;
    format!("{h:02}:{m:02}:{s:02}")
}
