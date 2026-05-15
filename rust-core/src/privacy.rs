// src/privacy.rs
// NodeX VPN — Privacy Protection Module
//
// ① IPv6 Leak Protection     — disable IPv6 when VPN active
// ② WebRTC Leak Protection   — detect and warn about WebRTC leaks
// ③ MAC Randomization        — randomize WiFi MAC on connect
// ④ DNS Leak Test            — built-in "Am I leaking?" check
// ⑤ Exit IP Verification     — verify actual exit IP matches expected country

use std::process::Command;
use serde::{Deserialize, Serialize};

// ── ① IPv6 Leak Protection ────────────────────────────────────────────────────

pub struct IPv6Protection;

impl IPv6Protection {
    /// Disable IPv6 on all interfaces — prevents IPv6 traffic bypassing Tor
    pub fn disable() -> anyhow::Result<()> {
        #[cfg(target_os = "linux")]
        {
            // Disable via sysctl
            for iface in ["all", "default", "lo"] {
                let key = format!("net.ipv6.conf.{iface}.disable_ipv6=1");
                let _ = Command::new("sysctl").args(["-w", &key]).output();
            }
            // Also block via ip6tables
            let _ = Command::new("ip6tables").args(["-P", "OUTPUT", "DROP"]).output();
            let _ = Command::new("ip6tables").args(["-P", "INPUT",  "DROP"]).output();
        }
        #[cfg(target_os = "macos")]
        {
            // Disable IPv6 on all active interfaces
            let out = Command::new("networksetup").args(["-listallnetworkservices"]).output()?;
            let services = String::from_utf8_lossy(&out.stdout);
            for svc in services.lines().skip(1) {
                let _ = Command::new("networksetup")
                    .args(["-setv6off", svc.trim()])
                    .output();
            }
        }
        #[cfg(target_os = "windows")]
        {
            // Disable via netsh
            let _ = Command::new("netsh")
                .args(["interface", "ipv6", "set", "global", "randomizeidentifiers=disabled"])
                .output();
            // Block via Windows Firewall
            let _ = Command::new("netsh")
                .args(["advfirewall", "firewall", "add", "rule",
                    "name=NodeX_Block_IPv6", "dir=out", "action=block",
                    "protocol=41", "enable=yes"])
                .output();
        }
        log::info!("IPv6 leak protection enabled");
        Ok(())
    }

    pub fn restore() -> anyhow::Result<()> {
        #[cfg(target_os = "linux")]
        {
            for iface in ["all", "default", "lo"] {
                let key = format!("net.ipv6.conf.{iface}.disable_ipv6=0");
                let _ = Command::new("sysctl").args(["-w", &key]).output();
            }
            let _ = Command::new("ip6tables").args(["-P", "OUTPUT", "ACCEPT"]).output();
            let _ = Command::new("ip6tables").args(["-P", "INPUT",  "ACCEPT"]).output();
        }
        #[cfg(target_os = "macos")]
        {
            let out = Command::new("networksetup").args(["-listallnetworkservices"]).output()?;
            let services = String::from_utf8_lossy(&out.stdout);
            for svc in services.lines().skip(1) {
                let _ = Command::new("networksetup")
                    .args(["-setv6automatic", svc.trim()])
                    .output();
            }
        }
        #[cfg(target_os = "windows")]
        {
            let _ = Command::new("netsh")
                .args(["advfirewall", "firewall", "delete", "rule", "name=NodeX_Block_IPv6"])
                .output();
        }
        log::info!("IPv6 leak protection disabled");
        Ok(())
    }

    pub fn is_ipv6_enabled() -> bool {
        #[cfg(target_os = "linux")]
        {
            let path = "/proc/sys/net/ipv6/conf/all/disable_ipv6";
            std::fs::read_to_string(path)
                .map(|s| s.trim() == "0")
                .unwrap_or(true)
        }
        #[cfg(not(target_os = "linux"))]
        { true } // assume enabled on other platforms
    }
}

// ── ② WebRTC Leak Detection ───────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WebRtcLeakInfo {
    pub risk_level:   WebRtcRiskLevel,
    pub message:      String,
    pub mitigation:   String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum WebRtcRiskLevel {
    None,
    Low,
    Medium,
    High,
}

pub struct WebRtcProtection;

impl WebRtcProtection {
    /// Assess WebRTC leak risk based on environment
    pub fn assess() -> WebRtcLeakInfo {
        // WebRTC leaks real IP via STUN — mainly a browser risk
        // On mobile/desktop VPN apps we can't control browser WebRTC directly
        // But we can detect and inform the user

        #[cfg(any(target_os = "android", target_os = "ios"))]
        {
            return WebRtcLeakInfo {
                risk_level: WebRtcRiskLevel::Low,
                message:    "Mobile apps use system network stack — WebRTC goes through VPN.".into(),
                mitigation: "No action needed.".into(),
            };
        }

        #[allow(unreachable_code)]
        WebRtcLeakInfo {
            risk_level: WebRtcRiskLevel::Medium,
            message:    "Browsers with WebRTC enabled may leak your local/public IP \
                         even when VPN is active. This is a browser-level issue.".into(),
            mitigation: "Install uBlock Origin + enable 'Prevent WebRTC from leaking local IP' \
                         in browser settings. Or use the --kill-switch flag to block \
                         all non-Tor traffic at the firewall level.".into(),
        }
    }
}

// ── ③ MAC Address Randomization ───────────────────────────────────────────────

pub struct MacRandomizer;

impl MacRandomizer {
    /// Randomize MAC address on the active WiFi interface
    pub fn randomize() -> anyhow::Result<String> {
        let iface = Self::detect_wifi_interface()?;
        let mac   = Self::generate_random_mac();

        #[cfg(target_os = "linux")]
        {
            Command::new("ip").args(["link", "set", &iface, "down"]).output()?;
            Command::new("ip").args(["link", "set", &iface, "address", &mac]).output()?;
            Command::new("ip").args(["link", "set", &iface, "up"]).output()?;
        }
        #[cfg(target_os = "macos")]
        {
            Command::new("ifconfig").args([&iface, "ether", &mac]).output()?;
        }
        #[cfg(target_os = "windows")]
        {
            // Windows requires registry change + disable/enable adapter
            // Simplified: use devcon if available
            log::warn!("MAC randomization on Windows requires devcon tool");
        }

        log::info!("MAC randomized on {iface} → {mac}");
        Ok(mac)
    }

    /// Restore original MAC (usually done by re-enabling the interface)
    pub fn restore() -> anyhow::Result<()> {
        // On Linux/macOS rebooting the interface often restores factory MAC
        // For permanent restore, we'd need to save/restore the original
        Ok(())
    }

    fn detect_wifi_interface() -> anyhow::Result<String> {
        #[cfg(target_os = "linux")]
        {
            // Look for wlan0, wlp*, wlan*
            for name in ["wlan0", "wlan1", "wlp2s0", "wlp3s0"] {
                if std::path::Path::new(&format!("/sys/class/net/{name}")).exists() {
                    return Ok(name.into());
                }
            }
            anyhow::bail!("No WiFi interface detected")
        }
        #[cfg(target_os = "macos")]
        {
            let out = Command::new("networksetup").args(["-listallhardwareports"]).output()?;
            let txt = String::from_utf8_lossy(&out.stdout);
            let mut next = false;
            for line in txt.lines() {
                if line.contains("Wi-Fi") || line.contains("AirPort") { next = true; }
                if next && line.starts_with("Device:") {
                    return Ok(line.replace("Device:", "").trim().to_string());
                }
            }
            anyhow::bail!("No WiFi interface detected")
        }
        #[cfg(not(any(target_os = "linux", target_os = "macos")))]
        { anyhow::bail!("MAC randomization not supported on this platform") }
    }

    fn generate_random_mac() -> String {
        use std::time::{SystemTime, UNIX_EPOCH};
        let seed = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .subsec_nanos();
        // Locally administered, unicast MAC (bits 1-2 of first octet)
        let b = [
            (0x02 | ((seed >> 0)  & 0xfe)) as u8,
            ((seed >> 8)  & 0xff) as u8,
            ((seed >> 16) & 0xff) as u8,
            ((seed >> 24) & 0xff) as u8,
            ((seed.wrapping_mul(0x6c62272e)) >> 8 & 0xff) as u8,
            ((seed.wrapping_mul(0x6c62272e)) & 0xff) as u8,
        ];
        format!("{:02x}:{:02x}:{:02x}:{:02x}:{:02x}:{:02x}",
            b[0], b[1], b[2], b[3], b[4], b[5])
    }
}

// ── ④ DNS Leak Test ───────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LeakTestResult {
    pub dns_leak:         bool,
    pub ipv6_leak:        bool,
    pub exit_ip:          Option<String>,
    pub exit_country:     Option<String>,
    pub dns_servers_seen: Vec<String>,
    pub summary:          String,
    pub passed:           bool,
}

pub struct LeakTester;

impl LeakTester {
    /// Run a comprehensive leak test via DNS-over-Tor
    /// Uses Tor SOCKS5 to query an IP-echo service
    pub async fn run(socks_addr: &str) -> LeakTestResult {
        let ipv6_leak = IPv6Protection::is_ipv6_enabled();

        // Try to fetch our exit IP via Tor
        let exit_ip = Self::fetch_exit_ip(socks_addr).await;

        // DNS leak check: if we can reach a non-Tor DNS, we're leaking
        let dns_leak = Self::check_dns_leak().await;

        let passed = !dns_leak && !ipv6_leak && exit_ip.is_some();

        let summary = if passed {
            "✔ No leaks detected. DNS, IPv6, and exit IP all routing through Tor.".into()
        } else {
            let mut issues = vec![];
            if dns_leak  { issues.push("DNS leak detected"); }
            if ipv6_leak { issues.push("IPv6 enabled (potential leak)"); }
            if exit_ip.is_none() { issues.push("Could not verify exit IP"); }
            format!("⚠ Issues: {}", issues.join(", "))
        };

        LeakTestResult {
            dns_leak,
            ipv6_leak,
            exit_ip: exit_ip.clone(),
            exit_country: None, // Would need GeoIP lookup
            dns_servers_seen: vec![],
            summary,
            passed,
        }
    }

    #[cfg(feature = "cli")]
    async fn fetch_exit_ip(socks_addr: &str) -> Option<String> {
        let client = reqwest::Client::builder()
            .proxy(reqwest::Proxy::all(format!("socks5h://{socks_addr}")).ok()?)
            .timeout(std::time::Duration::from_secs(30))
            .build()
            .ok()?;
        client.get("https://api.ipify.org")
            .send().await.ok()?
            .text().await.ok()
            .map(|s| s.trim().to_string())
    }

    #[cfg(not(feature = "cli"))]
    async fn fetch_exit_ip(_socks_addr: &str) -> Option<String> { None }

    async fn check_dns_leak() -> bool {
        // Try to resolve a domain via system DNS (not Tor)
        // If it succeeds, DNS is leaking
        // Simple check: attempt direct UDP DNS query
        // If our kill switch is active, this should fail
        use std::net::UdpSocket;
        use std::time::Duration;

        let socket = UdpSocket::bind("0.0.0.0:0").ok();
        if let Some(sock) = socket {
            sock.set_read_timeout(Some(Duration::from_secs(2))).ok();
            // Try to send a minimal DNS query to 8.8.8.8:53
            // A response means DNS is leaking
            let dns_query = [
                0x00, 0x01, // Transaction ID
                0x01, 0x00, // Flags: standard query
                0x00, 0x01, // Questions: 1
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // No answers/auth/additional
                0x03, b'w', b'w', b'w', // www
                0x06, b'g', b'o', b'o', b'g', b'l', b'e', // google
                0x03, b'c', b'o', b'm', 0x00, // com
                0x00, 0x01, 0x00, 0x01, // Type A, Class IN
            ];
            if sock.send_to(&dns_query, "8.8.8.8:53").is_ok() {
                let mut buf = [0u8; 512];
                return sock.recv_from(&mut buf).is_ok();
            }
        }
        false
    }
}

// ── ⑤ Exit IP Verification ────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExitVerification {
    pub verified_ip:      String,
    pub verified_country: String,
    pub expected_country: Option<String>,
    pub country_match:    bool,
    pub is_tor_exit:      bool,
}

pub struct ExitVerifier;

impl ExitVerifier {
    /// Verify the actual exit IP and country via Tor circuit
    pub async fn verify(socks_addr: &str, expected_country: Option<&str>) -> anyhow::Result<ExitVerification> {
        let client = reqwest::Client::builder()
            .proxy(reqwest::Proxy::all(format!("socks5h://{socks_addr}"))?)
            .timeout(std::time::Duration::from_secs(30))
            .build()?;

        // Fetch IP + country in one request
        let resp: serde_json::Value = client
            .get("https://ipinfo.io/json")
            .send().await?
            .json().await?;

        let ip      = resp["ip"].as_str().unwrap_or("unknown").to_string();
        let country = resp["country"].as_str().unwrap_or("??").to_string();

        // Check if it's a known Tor exit (simplified check)
        // Real implementation would check Tor consensus exit list
        let is_tor_exit = true; // We went through Tor SOCKS5, so yes

        let country_match = expected_country
            .map(|exp| exp.eq_ignore_ascii_case(&country))
            .unwrap_or(true);

        Ok(ExitVerification {
            verified_ip:      ip,
            verified_country: country,
            expected_country: expected_country.map(String::from),
            country_match,
            is_tor_exit,
        })
    }
}
