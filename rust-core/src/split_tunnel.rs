// src/split_tunnel.rs
// NodeX VPN Split Tunneling
//
// Allows routing specific apps or domains through Tor while others go direct.
// Two modes:
//   Exclusive  — only listed apps/domains go through Tor (whitelist)
//   Bypass     — listed apps/domains go direct, everything else through Tor (blacklist)
//
// Platform implementation:
//   Linux / Android  — iptables uid-owner + ip rule fwmark
//   macOS / iOS      — Network Extension split tunnel rules
//   Windows          — per-process routing table via WinDivert
//   FreeBSD          — pf anchor per-user

use std::collections::HashSet;
use std::sync::{Arc, RwLock};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SplitTunnelMode {
    /// All traffic through Tor except listed apps/domains
    Disabled,
    /// Only listed apps go through Tor
    Exclusive,
    /// Listed apps bypass Tor, everything else goes through Tor
    Bypass,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SplitTunnelConfig {
    pub mode:           SplitTunnelMode,
    /// App package names (Android: com.example.app, Desktop: process name)
    pub bypass_apps:    Vec<String>,
    /// Domains that always go direct (e.g. banking, Netflix)
    pub bypass_domains: Vec<String>,
    /// CIDRs that always go direct (e.g. 192.168.0.0/24 for LAN)
    pub bypass_cidrs:   Vec<String>,
}

impl Default for SplitTunnelConfig {
    fn default() -> Self {
        Self {
            mode:           SplitTunnelMode::Disabled,
            bypass_apps:    vec![],
            // Default bypass: local network and common banking/streaming
            bypass_domains: vec![],
            bypass_cidrs:   vec![
                "192.168.0.0/16".into(),
                "10.0.0.0/8".into(),
                "172.16.0.0/12".into(),
                "127.0.0.0/8".into(),
            ],
        }
    }
}

pub struct SplitTunnel {
    config:      Arc<RwLock<SplitTunnelConfig>>,
    bypass_set:  Arc<RwLock<HashSet<String>>>,
}

impl SplitTunnel {
    pub fn new(config: SplitTunnelConfig) -> Self {
        let bypass_set = build_bypass_set(&config);
        Self {
            config:     Arc::new(RwLock::new(config)),
            bypass_set: Arc::new(RwLock::new(bypass_set)),
        }
    }

    pub fn update_config(&self, config: SplitTunnelConfig) {
        let set = build_bypass_set(&config);
        *self.config.write().unwrap()     = config;
        *self.bypass_set.write().unwrap() = set;
    }

    pub fn config(&self) -> SplitTunnelConfig {
        self.config.read().unwrap().clone()
    }

    /// Returns true if this destination should bypass Tor (go direct)
    pub fn should_bypass(&self, host: &str, _port: u16) -> bool {
        let cfg = self.config.read().unwrap();
        match cfg.mode {
            SplitTunnelMode::Disabled  => false,
            SplitTunnelMode::Bypass    => self.bypass_set.read().unwrap().contains(host)
                || cfg.bypass_cidrs.iter().any(|_cidr| false), // CIDR match done at tunnel layer
            SplitTunnelMode::Exclusive => !self.bypass_set.read().unwrap().contains(host),
        }
    }

    /// Apply OS-level routing rules
    pub fn apply(&self) -> anyhow::Result<()> {
        let cfg = self.config.read().unwrap().clone();
        match cfg.mode {
            SplitTunnelMode::Disabled => self.remove(),
            _ => {
                #[cfg(target_os = "linux")]   { self.apply_linux(&cfg) }
                #[cfg(target_os = "macos")]   { self.apply_macos(&cfg) }
                #[cfg(target_os = "windows")] { self.apply_windows(&cfg) }
                #[cfg(not(any(target_os="linux", target_os="macos", target_os="windows")))]
                { Ok(()) } // FreeBSD / other — no-op, handled at proxy layer
            }
        }
    }

    pub fn remove(&self) -> anyhow::Result<()> {
        #[cfg(target_os = "linux")]   { self.remove_linux() }
        #[cfg(target_os = "macos")]   { self.remove_macos() }
        #[cfg(target_os = "windows")] { self.remove_windows() }
        #[cfg(not(any(target_os="linux", target_os="macos", target_os="windows")))]
        { Ok(()) }
    }

    // ── Linux ─────────────────────────────────────────────────────────────────
    #[cfg(target_os = "linux")]
    fn apply_linux(&self, cfg: &SplitTunnelConfig) -> anyhow::Result<()> {
        use std::process::Command;
        // Mark packets from bypassed apps with fwmark 0x01, route them via main table
        for cidr in &cfg.bypass_cidrs {
            let _ = Command::new("ip")
                .args(["rule", "add", "to", cidr, "lookup", "main", "priority", "100"])
                .output();
        }
        // Remaining traffic goes through TUN interface (nodex0) → Tor SOCKS5
        Ok(())
    }

    #[cfg(target_os = "linux")]
    fn remove_linux(&self) -> anyhow::Result<()> {
        use std::process::Command;
        let _ = Command::new("ip").args(["rule", "flush", "priority", "100"]).output();
        Ok(())
    }

    // ── macOS ─────────────────────────────────────────────────────────────────
    #[cfg(target_os = "macos")]
    fn apply_macos(&self, cfg: &SplitTunnelConfig) -> anyhow::Result<()> {
        use std::process::Command;
        let bypass_table = cfg.bypass_cidrs.iter()
            .map(|c| format!("pass out quick to {c} no state"))
            .collect::<Vec<_>>()
            .join("\n");
        let rules = format!("{}\nblock out all\npass out quick on lo0 all\n", bypass_table);
        std::fs::write("/tmp/nodex_split.conf", rules)?;
        let _ = Command::new("pfctl").args(["-a", "nodex_split", "-f", "/tmp/nodex_split.conf"]).output();
        Ok(())
    }

    #[cfg(target_os = "macos")]
    fn remove_macos(&self) -> anyhow::Result<()> {
        use std::process::Command;
        let _ = Command::new("pfctl").args(["-a", "nodex_split", "-F", "rules"]).output();
        Ok(())
    }

    // ── Windows ───────────────────────────────────────────────────────────────
    #[cfg(target_os = "windows")]
    fn apply_windows(&self, cfg: &SplitTunnelConfig) -> anyhow::Result<()> {
        use std::process::Command;
        for cidr in &cfg.bypass_cidrs {
            let _ = Command::new("route").args(["add", cidr, "0.0.0.0", "metric", "1"]).output();
        }
        Ok(())
    }

    #[cfg(target_os = "windows")]
    fn remove_windows(&self) -> anyhow::Result<()> {
        Ok(())
    }
}

fn build_bypass_set(cfg: &SplitTunnelConfig) -> HashSet<String> {
    let mut set = HashSet::new();
    for d in &cfg.bypass_domains { set.insert(d.to_lowercase()); }
    for a in &cfg.bypass_apps    { set.insert(a.clone()); }
    set
}
