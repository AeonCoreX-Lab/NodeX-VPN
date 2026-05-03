// src/features.rs
// NodeX VPN — Priority 2 & 3 Features
//
// ① HTTPS-Only Warning  — warn when connecting to HTTP (non-HTTPS) sites
// ② Background Bootstrap — pre-build Tor circuit before user requests connect
// ③ Usage Stats          — persistent per-session data counters
// ④ Onion Service Access — .onion address detection and routing

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use serde::{Deserialize, Serialize};

// ── ① HTTPS-Only Warning ──────────────────────────────────────────────────────

/// Checks if a destination address is an HTTP (not HTTPS) connection
/// that could expose traffic to the exit relay.
#[derive(Debug, Clone)]
pub struct HttpsChecker {
    warn_on_http: bool,
}

impl HttpsChecker {
    pub fn new(enabled: bool) -> Self {
        Self { warn_on_http: enabled }
    }

    /// Returns Some(warning_message) if this destination is HTTP-only risk.
    pub fn check(&self, host: &str, port: u16) -> Option<String> {
        if !self.warn_on_http { return None; }

        // Port 80 = HTTP, port 8080 = HTTP alternative
        // Ignore .onion — those are end-to-end encrypted
        if (port == 80 || port == 8080) && !host.ends_with(".onion") {
            return Some(format!(
                "⚠  Connecting to {} over HTTP (port {}). \
                 The Tor exit relay can read this traffic. \
                 Use HTTPS (port 443) for end-to-end encryption.",
                host, port
            ));
        }
        None
    }

    /// Check a SOCKS5 destination request
    pub fn check_socks_dest(&self, host: &str, port: u16) -> Option<HttpsWarning> {
        self.check(host, port).map(|msg| HttpsWarning {
            host: host.to_string(),
            port,
            message: msg,
            timestamp: unix_now(),
        })
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HttpsWarning {
    pub host:      String,
    pub port:      u16,
    pub message:   String,
    pub timestamp: u64,
}

// ── ② Background Bootstrap ────────────────────────────────────────────────────

/// Pre-warms the Tor circuit so that `connect` is instant for the user.
/// Call `start_prewarming()` on app launch. When user clicks Connect,
/// the circuit is already built.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PreheatState {
    Idle,
    Building,
    Ready,
    Failed,
}

pub struct BackgroundBootstrap {
    state:      Arc<Mutex<PreheatState>>,
    progress:   Arc<Mutex<u8>>,         // 0-100
    started_at: Arc<Mutex<Option<Instant>>>,
}

impl BackgroundBootstrap {
    pub fn new() -> Self {
        Self {
            state:      Arc::new(Mutex::new(PreheatState::Idle)),
            progress:   Arc::new(Mutex::new(0)),
            started_at: Arc::new(Mutex::new(None)),
        }
    }

    pub fn state(&self) -> PreheatState {
        *self.state.lock().unwrap()
    }

    pub fn progress(&self) -> u8 {
        *self.progress.lock().unwrap()
    }

    pub fn is_ready(&self) -> bool {
        self.state() == PreheatState::Ready
    }

    /// Update progress (called by the bootstrap event listener)
    pub fn update_progress(&self, pct: u8) {
        *self.progress.lock().unwrap() = pct;
        if pct >= 100 {
            *self.state.lock().unwrap() = PreheatState::Ready;
        } else {
            *self.state.lock().unwrap() = PreheatState::Building;
        }
    }

    pub fn set_failed(&self) {
        *self.state.lock().unwrap() = PreheatState::Failed;
    }

    pub fn mark_started(&self) {
        *self.state.lock().unwrap() = PreheatState::Building;
        *self.started_at.lock().unwrap() = Some(Instant::now());
    }

    pub fn build_time_secs(&self) -> Option<f64> {
        self.started_at.lock().unwrap()
            .map(|t| t.elapsed().as_secs_f64())
    }

    pub fn reset(&self) {
        *self.state.lock().unwrap()    = PreheatState::Idle;
        *self.progress.lock().unwrap() = 0;
        *self.started_at.lock().unwrap() = None;
    }
}

impl Default for BackgroundBootstrap {
    fn default() -> Self { Self::new() }
}

// ── ③ Usage Stats ─────────────────────────────────────────────────────────────

/// Per-session and cumulative traffic statistics.
/// Saved to ~/.nodex/stats.json on disconnect.

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct UsageStats {
    // Session stats (reset each connect)
    pub session_bytes_sent:     u64,
    pub session_bytes_received: u64,
    pub session_start_unix:     u64,
    pub session_exit_country:   String,
    pub session_exit_ip:        String,

    // Cumulative (persisted across sessions)
    pub total_bytes_sent:       u64,
    pub total_bytes_received:   u64,
    pub total_sessions:         u64,
    pub total_uptime_secs:      u64,
    pub last_connected_unix:    u64,
}

impl UsageStats {
    pub fn new() -> Self { Self::default() }

    pub fn session_start(&mut self, country: String, ip: String) {
        self.session_bytes_sent     = 0;
        self.session_bytes_received = 0;
        self.session_start_unix     = unix_now();
        self.session_exit_country   = country;
        self.session_exit_ip        = ip;
        self.last_connected_unix    = unix_now();
        self.total_sessions        += 1;
    }

    pub fn update_bytes(&mut self, sent: u64, received: u64) {
        self.session_bytes_sent     = sent;
        self.session_bytes_received = received;
    }

    pub fn session_end(&mut self) {
        // Merge session into cumulative
        self.total_bytes_sent     += self.session_bytes_sent;
        self.total_bytes_received += self.session_bytes_received;
        self.total_uptime_secs    += self.session_uptime_secs();
    }

    pub fn session_uptime_secs(&self) -> u64 {
        let now = unix_now();
        if self.session_start_unix > 0 && now > self.session_start_unix {
            now - self.session_start_unix
        } else {
            0
        }
    }

    /// Human-readable total data transferred
    pub fn total_data_str(&self) -> String {
        fmt_bytes(self.total_bytes_sent + self.total_bytes_received)
    }

    /// Save to disk
    pub fn save(&self) -> anyhow::Result<()> {
        let path = stats_path();
        std::fs::create_dir_all(path.parent().unwrap())?;
        let json = serde_json::to_string_pretty(self)?;
        std::fs::write(&path, json)?;
        Ok(())
    }

    /// Load from disk (returns default if missing)
    pub fn load() -> Self {
        let path = stats_path();
        std::fs::read_to_string(&path)
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default()
    }
}

fn stats_path() -> std::path::PathBuf {
    let home = dirs::home_dir().unwrap_or_else(|| std::path::PathBuf::from("."));
    home.join(".nodex").join("stats.json")
}

// ── ④ Onion Service Access ────────────────────────────────────────────────────

/// Detects .onion addresses and provides routing guidance.
/// Arti supports .onion natively — no extra configuration needed.

#[derive(Debug, Clone)]
pub struct OnionDetector;

impl OnionDetector {
    /// Returns true if this host is a .onion address
    pub fn is_onion(host: &str) -> bool {
        let h = host.to_lowercase();
        let h = h.strip_suffix('.').unwrap_or(&h); // strip trailing dot
        h.ends_with(".onion")
    }

    /// Validate onion address format (v3 = 56 chars + .onion)
    pub fn validate(host: &str) -> OnionValidation {
        if !Self::is_onion(host) {
            return OnionValidation::NotOnion;
        }
        let h = host.to_lowercase();
        let h = h.strip_suffix('.').unwrap_or(&h);
        let label = h.strip_suffix(".onion").unwrap_or("");

        match label.len() {
            56 => OnionValidation::V3Valid,   // v3: 56 base32 chars
            16 => OnionValidation::V2Obsolete, // v2: deprecated, still show warning
            _  => OnionValidation::Invalid,
        }
    }

    /// User-friendly message for an onion destination
    pub fn describe(host: &str) -> String {
        match Self::validate(host) {
            OnionValidation::V3Valid   =>
                format!("🧅 {} — Tor hidden service (v3). End-to-end encrypted.", host),
            OnionValidation::V2Obsolete =>
                format!("⚠  {} — Deprecated v2 onion address. This service may be unreachable.", host),
            OnionValidation::Invalid   =>
                format!("✘  {} — Invalid onion address format.", host),
            OnionValidation::NotOnion  =>
                String::new(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OnionValidation {
    NotOnion,
    V3Valid,
    V2Obsolete,
    Invalid,
}

// ── Helpers ───────────────────────────────────────────────────────────────────

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

pub fn fmt_bytes(b: u64) -> String {
    match b {
        n if n >= 1 << 30 => format!("{:.2} GB", n as f64 / (1u64 << 30) as f64),
        n if n >= 1 << 20 => format!("{:.2} MB", n as f64 / (1u64 << 20) as f64),
        n if n >= 1 << 10 => format!("{:.1} KB", n as f64 / (1u64 << 10) as f64),
        n                 => format!("{n} B"),
    }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_https_checker() {
        let checker = HttpsChecker::new(true);
        assert!(checker.check("example.com", 80).is_some());
        assert!(checker.check("example.com", 443).is_none());
        assert!(checker.check("abc.onion", 80).is_none()); // onion exempt
    }

    #[test]
    fn test_onion_detector() {
        assert!(OnionDetector::is_onion("xyz.onion"));
        assert!(OnionDetector::is_onion("abc.onion."));
        assert!(!OnionDetector::is_onion("google.com"));

        // v3 = 56 chars
        let v3 = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx.onion";
        assert_eq!(OnionDetector::validate(v3), OnionValidation::V3Valid);
    }

    #[test]
    fn test_usage_stats_persist() {
        let mut stats = UsageStats::new();
        stats.session_start("DE".into(), "1.2.3.4".into());
        stats.update_bytes(1024, 4096);
        stats.session_end();
        assert_eq!(stats.total_bytes_sent, 1024);
        assert_eq!(stats.total_sessions, 1);
    }

    #[test]
    fn test_background_bootstrap() {
        let bb = BackgroundBootstrap::new();
        assert_eq!(bb.state(), PreheatState::Idle);
        bb.update_progress(50);
        assert_eq!(bb.state(), PreheatState::Building);
        bb.update_progress(100);
        assert!(bb.is_ready());
    }
}
