// src/kill_switch.rs
// NodeX VPN Kill Switch — blocks all non-Tor traffic if VPN drops
//
// Platform support:
//   Linux (desktop, router, Termux) — nftables with iptables fallback
//   macOS                           — pfctl (pf firewall)
//   FreeBSD / OpenWrt               — pfctl / iptables
//   Windows                         — Windows Firewall (netsh advfirewall)
//
// Design:
//   enable()  → add firewall rules that only allow Tor SOCKS5 port + loopback
//   disable() → remove those rules
//   is_active() → check rules exist
//
// The VPN engine calls enable() after connect and disable() on disconnect/drop.
// If the process crashes, rules persist until disable() is called or OS reboot.

use std::process::Command;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KillSwitchPlatform {
    LinuxNftables,
    LinuxIptables,
    MacOS,
    FreeBSD,
    Windows,
    Unsupported,
}

#[derive(Debug)]
pub struct KillSwitch {
    platform:   KillSwitchPlatform,
    socks_port: u16,
    dns_port:   u16,
    enabled:    std::sync::atomic::AtomicBool,
}

impl KillSwitch {
    pub fn new(socks_port: u16, dns_port: u16) -> Self {
        Self {
            platform: detect_platform(),
            socks_port,
            dns_port,
            enabled: std::sync::atomic::AtomicBool::new(false),
        }
    }

    pub fn is_supported(&self) -> bool {
        self.platform != KillSwitchPlatform::Unsupported
    }

    pub fn is_active(&self) -> bool {
        self.enabled.load(std::sync::atomic::Ordering::Relaxed)
    }

    /// Enable kill switch — blocks all non-Tor traffic.
    /// Requires elevated privileges on most platforms.
    pub fn enable(&self) -> anyhow::Result<()> {
        match self.platform {
            KillSwitchPlatform::LinuxNftables  => self.enable_nftables(),
            KillSwitchPlatform::LinuxIptables  => self.enable_iptables(),
            KillSwitchPlatform::MacOS |
            KillSwitchPlatform::FreeBSD        => self.enable_pf(),
            KillSwitchPlatform::Windows        => self.enable_windows(),
            KillSwitchPlatform::Unsupported    => anyhow::bail!(
                "Kill switch is not supported on this platform."
            ),
        }?;
        self.enabled.store(true, std::sync::atomic::Ordering::Relaxed);
        log::info!("Kill switch enabled ({})", platform_name(self.platform));
        Ok(())
    }

    /// Disable kill switch — restore normal traffic flow.
    pub fn disable(&self) -> anyhow::Result<()> {
        let r = match self.platform {
            KillSwitchPlatform::LinuxNftables  => self.disable_nftables(),
            KillSwitchPlatform::LinuxIptables  => self.disable_iptables(),
            KillSwitchPlatform::MacOS |
            KillSwitchPlatform::FreeBSD        => self.disable_pf(),
            KillSwitchPlatform::Windows        => self.disable_windows(),
            KillSwitchPlatform::Unsupported    => Ok(()),
        };
        self.enabled.store(false, std::sync::atomic::Ordering::Relaxed);
        log::info!("Kill switch disabled");
        r
    }

    // ── Linux — nftables ──────────────────────────────────────────────────────

    fn enable_nftables(&self) -> anyhow::Result<()> {
        let socks = self.socks_port;
        let dns   = self.dns_port;

        // Create a dedicated nodex table so we don't touch the user's existing rules
        let rules = format!(r#"
table inet nodex_kill_switch {{
    chain output {{
        type filter hook output priority 0; policy drop;
        # Allow loopback (127.0.0.1) — needed for local SOCKS5
        oifname "lo" accept
        # Allow established connections (Tor internal)
        ct state established,related accept
        # Allow connections to our local SOCKS5 and DNS ports
        tcp dport {socks} accept
        udp dport {dns} accept
        # Allow Tor's own outbound (port 9001, 9030 for OR, directory)
        # These go through our SOCKS5 proxy already but Tor needs direct access
        tcp dport {{ 80, 443, 8080, 8443, 9001, 9030, 9050, 9150 }} accept
        # Drop everything else
        drop
    }}
    chain input {{
        type filter hook input priority 0; policy drop;
        iifname "lo" accept
        ct state established,related accept
        drop
    }}
}}
"#);

        // Write to temp file and apply
        let tmp = "/tmp/nodex_kill_switch.nft";
        std::fs::write(tmp, rules.trim())?;

        run_cmd("nft", &["-f", tmp])?;
        std::fs::remove_file(tmp).ok();
        Ok(())
    }

    fn disable_nftables(&self) -> anyhow::Result<()> {
        // Delete our table — silently ignore if it doesn't exist
        let _ = run_cmd("nft", &["delete", "table", "inet", "nodex_kill_switch"]);
        Ok(())
    }

    // ── Linux — iptables (fallback) ───────────────────────────────────────────

    fn enable_iptables(&self) -> anyhow::Result<()> {
        let socks = self.socks_port.to_string();
        let dns   = self.dns_port.to_string();

        // Create a NODEX chain
        let _ = run_cmd("iptables", &["-N", "NODEX_KS"]);

        // Rules: allow lo, established, SOCKS5, DNS, drop rest
        run_cmd("iptables", &["-A", "NODEX_KS", "-o", "lo", "-j", "ACCEPT"])?;
        run_cmd("iptables", &["-A", "NODEX_KS", "-m", "state",
            "--state", "ESTABLISHED,RELATED", "-j", "ACCEPT"])?;
        run_cmd("iptables", &["-A", "NODEX_KS", "-p", "tcp",
            "--dport", &socks, "-j", "ACCEPT"])?;
        run_cmd("iptables", &["-A", "NODEX_KS", "-p", "udp",
            "--dport", &dns, "-j", "ACCEPT"])?;
        run_cmd("iptables", &["-A", "NODEX_KS", "-p", "tcp",
            "--dport", "9001", "-j", "ACCEPT"])?;  // Tor OR port
        run_cmd("iptables", &["-A", "NODEX_KS", "-p", "tcp",
            "--dport", "9030", "-j", "ACCEPT"])?;  // Tor directory port
        run_cmd("iptables", &["-A", "NODEX_KS", "-j", "DROP"])?;

        // Insert NODEX chain at top of OUTPUT
        run_cmd("iptables", &["-I", "OUTPUT", "1", "-j", "NODEX_KS"])?;
        Ok(())
    }

    fn disable_iptables(&self) -> anyhow::Result<()> {
        let _ = run_cmd("iptables", &["-D", "OUTPUT", "-j", "NODEX_KS"]);
        let _ = run_cmd("iptables", &["-F", "NODEX_KS"]);
        let _ = run_cmd("iptables", &["-X", "NODEX_KS"]);
        Ok(())
    }

    // ── macOS / FreeBSD — pf ──────────────────────────────────────────────────

    fn enable_pf(&self) -> anyhow::Result<()> {
        let socks = self.socks_port;
        let dns   = self.dns_port;

        let rules = format!(r#"
# NodeX VPN Kill Switch
# Allow loopback
pass quick on lo0 all
# Allow Tor's own traffic on established connections
pass out quick proto tcp from any to any port {{ 9001, 9030, 80, 443, {socks} }} keep state
pass out quick proto udp from any to any port {dns} keep state
# Block everything else outbound
block drop out all
# Block inbound (except established)
block drop in all
pass in quick proto tcp from any to any flags S/SA keep state
"#);

        let anchor = "nodex_kill_switch";
        let tmp = "/tmp/nodex_pf.conf";
        std::fs::write(tmp, rules.trim())?;

        run_cmd("pfctl", &["-a", anchor, "-f", tmp])?;
        run_cmd("pfctl", &["-e"])?;  // Enable pf if not already
        std::fs::remove_file(tmp).ok();
        Ok(())
    }

    fn disable_pf(&self) -> anyhow::Result<()> {
        let _ = run_cmd("pfctl", &["-a", "nodex_kill_switch", "-F", "rules"]);
        Ok(())
    }

    // ── Windows — netsh advfirewall ───────────────────────────────────────────

    fn enable_windows(&self) -> anyhow::Result<()> {
        let socks = self.socks_port.to_string();

        // Block all outbound by default in our group
        run_cmd("netsh", &[
            "advfirewall", "firewall", "add", "rule",
            "name=NodeX_KS_Block_Out",
            "dir=out", "action=block", "protocol=any",
            "remoteport=any", "enable=yes",
            "description=NodeX VPN Kill Switch - block all outbound",
        ])?;

        // Allow SOCKS5 traffic
        run_cmd("netsh", &[
            "advfirewall", "firewall", "add", "rule",
            "name=NodeX_KS_Allow_SOCKS",
            "dir=out", "action=allow", "protocol=tcp",
            &format!("remoteport={socks}"), "enable=yes",
        ])?;

        // Allow Tor OR/directory ports
        run_cmd("netsh", &[
            "advfirewall", "firewall", "add", "rule",
            "name=NodeX_KS_Allow_Tor",
            "dir=out", "action=allow", "protocol=tcp",
            "remoteport=9001,9030,80,443", "enable=yes",
        ])?;

        Ok(())
    }

    fn disable_windows(&self) -> anyhow::Result<()> {
        for rule in ["NodeX_KS_Block_Out", "NodeX_KS_Allow_SOCKS", "NodeX_KS_Allow_Tor"] {
            let _ = run_cmd("netsh", &[
                "advfirewall", "firewall", "delete", "rule",
                &format!("name={rule}"),
            ]);
        }
        Ok(())
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

fn detect_platform() -> KillSwitchPlatform {
    #[cfg(target_os = "linux")]
    {
        if cmd_exists("nft")      { return KillSwitchPlatform::LinuxNftables; }
        if cmd_exists("iptables") { return KillSwitchPlatform::LinuxIptables; }
        return KillSwitchPlatform::Unsupported;
    }
    #[cfg(target_os = "macos")]
    { return KillSwitchPlatform::MacOS; }
    #[cfg(target_os = "freebsd")]
    { return KillSwitchPlatform::FreeBSD; }
    #[cfg(target_os = "windows")]
    { return KillSwitchPlatform::Windows; }
    #[allow(unreachable_code)]
    KillSwitchPlatform::Unsupported
}

fn cmd_exists(name: &str) -> bool {
    Command::new("which").arg(name).output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

fn run_cmd(cmd: &str, args: &[&str]) -> anyhow::Result<()> {
    let out = Command::new(cmd).args(args).output()
        .map_err(|e| anyhow::anyhow!("Failed to run {cmd}: {e}"))?;
    if !out.status.success() {
        let stderr = String::from_utf8_lossy(&out.stderr);
        anyhow::bail!("{cmd} failed: {stderr}");
    }
    Ok(())
}

fn platform_name(p: KillSwitchPlatform) -> &'static str {
    match p {
        KillSwitchPlatform::LinuxNftables => "nftables",
        KillSwitchPlatform::LinuxIptables => "iptables",
        KillSwitchPlatform::MacOS         => "pf (macOS)",
        KillSwitchPlatform::FreeBSD       => "pf (FreeBSD)",
        KillSwitchPlatform::Windows       => "Windows Firewall",
        KillSwitchPlatform::Unsupported   => "unsupported",
    }
}
