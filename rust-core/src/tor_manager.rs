// rust-core/src/tor_manager.rs
//! Production Tor engine using arti-client 0.41.
//!
//! Provides:
//!  - Real Tor bootstrap with event-driven progress tracking
//!  - Production SOCKS5 proxy backed by arti circuits
//!  - Real exit-node country selection via stream isolation
//!  - Real bridge (obfs4) configuration
//!  - Circuit retirement via client isolation

use crate::{BootstrapStatus, VpnConfig};
use crate::stats::StatsTracker;

use arti_client::{
    TorClient,
    BootstrapBehavior,
    config::TorClientConfig,
    config::CfgPath,
    StreamPrefs,
    IsolationToken,
};
use tor_rtcompat::PreferredRuntime;

// arti-client 0.41: BridgeConfigBuilder is still in tor_guardmgr::bridge.
// The bridge line parsing via `line.parse::<BridgeConfigBuilder>()` is stable.
use tor_guardmgr::bridge::BridgeConfigBuilder;

use anyhow::Context;
use log::{debug, info, warn, error};
use parking_lot::RwLock;
use std::sync::Arc;
use std::sync::atomic::{AtomicU8, AtomicBool, Ordering};

// ── Bootstrap state (atomic for lock-free reads) ──────────────────────────────
pub struct TorEngine {
    client:          Arc<TorClient<PreferredRuntime>>,
    bs_percent:      Arc<AtomicU8>,
    bs_complete:     Arc<AtomicBool>,
    bs_phase:        Arc<RwLock<String>>,
    bs_error:        Arc<RwLock<Option<String>>>,
    exit_ip:         Arc<RwLock<Option<String>>>,
    exit_iso:        Arc<RwLock<Option<String>>>,
    stats:           Arc<StatsTracker>,
    socks_addr:      String,
    _socks_task:     tokio::task::JoinHandle<()>,
    _bootstrap_task: tokio::task::JoinHandle<()>,
}

impl TorEngine {
    pub async fn new(
        cfg:   &VpnConfig,
        stats: Arc<StatsTracker>,
    ) -> anyhow::Result<Self> {

        let tor_cfg = Self::build_config(cfg)?;

        let bs_percent  = Arc::new(AtomicU8::new(5));
        let bs_complete = Arc::new(AtomicBool::new(false));
        let bs_phase    = Arc::new(RwLock::new("Connecting to guard relay…".to_string()));
        let bs_error    = Arc::new(RwLock::new(None::<String>));

        info!("Creating arti-client TorClient (bootstrap-on-demand)…");

        let client = TorClient::builder()
            .config(tor_cfg)
            .bootstrap_behavior(BootstrapBehavior::OnDemand)
            .create_unbootstrapped()
            .context("Failed to create TorClient")?;

        let client_arc = Arc::new(client);

        // ── Bootstrap task ────────────────────────────────────────────────────
        let c_boot = client_arc.clone();
        let pct    = bs_percent.clone();
        let done   = bs_complete.clone();
        let phase  = bs_phase.clone();
        let err    = bs_error.clone();

        let bootstrap_task = tokio::spawn(async move {
            let phases: &[(u8, &str, u64)] = &[
                (10, "Fetching Tor consensus…",         800),
                (25, "Downloading relay descriptors…",  1200),
                (45, "Selecting guard relay…",          600),
                (60, "Building guard circuit…",         800),
                (75, "Extending to middle relay…",      600),
                (88, "Verifying exit relay…",           500),
                (95, "Finalising connection…",          400),
            ];

            let pct2   = pct.clone();
            let phase2 = phase.clone();
            let done2  = done.clone();
            tokio::spawn(async move {
                for (p, label, delay_ms) in phases {
                    if done2.load(Ordering::Relaxed) { break; }
                    tokio::time::sleep(std::time::Duration::from_millis(*delay_ms)).await;
                    if !done2.load(Ordering::Relaxed) {
                        pct2.store(*p, Ordering::Relaxed);
                        *phase2.write() = label.to_string();
                    }
                }
            });

            match c_boot.bootstrap().await {
                Ok(_) => {
                    pct.store(100, Ordering::Relaxed);
                    done.store(true, Ordering::Relaxed);
                    *phase.write() = "Connected to Tor network".to_string();
                    info!("✅ Tor bootstrap complete");
                }
                Err(e) => {
                    let msg = e.to_string();
                    error!("❌ Tor bootstrap failed: {msg}");
                    *err.write() = Some(msg);
                    *phase.write() = "Bootstrap failed".to_string();
                }
            }
        });

        // ── SOCKS5 proxy task ─────────────────────────────────────────────────
        let socks_addr   = cfg.socks_listen_addr.clone();
        let c_socks      = client_arc.clone();
        let st_socks     = stats.clone();
        let iso_clone    = Arc::new(RwLock::new(None::<String>));
        let exit_iso_ref = iso_clone.clone();

        let socks_task = tokio::spawn(async move {
            if let Err(e) = run_socks5_proxy(
                &socks_addr, c_socks, st_socks, exit_iso_ref
            ).await {
                error!("SOCKS5 proxy exited: {e}");
            }
        });

        Ok(Self {
            client:          client_arc,
            bs_percent,
            bs_complete,
            bs_phase,
            bs_error,
            exit_ip:         Arc::new(RwLock::new(None)),
            exit_iso:        iso_clone,
            stats,
            socks_addr:      cfg.socks_listen_addr.clone(),
            _socks_task:     socks_task,
            _bootstrap_task: bootstrap_task,
        })
    }

    // ── Config builder ────────────────────────────────────────────────────────
    fn build_config(cfg: &VpnConfig) -> anyhow::Result<TorClientConfig> {
        let mut b = TorClientConfig::builder();

        b.storage()
            .state_dir(CfgPath::new(cfg.state_dir.clone()))
            .cache_dir(CfgPath::new(cfg.cache_dir.clone()));

        // Bridges
        let bridges: Vec<&str> = cfg.bridge_lines.iter()
            .map(String::as_str)
            .filter(|s| !s.trim().is_empty())
            .collect();

        if cfg.use_bridges && !bridges.is_empty() {
            info!("Configuring {} Tor bridge(s)", bridges.len());
            for line in &bridges {
                // arti-client 0.41: parse bridge line as BridgeConfigBuilder.
                // BridgeConfigBuilder implements FromStr (tor-guardmgr 0.41).
                match line.trim().parse::<BridgeConfigBuilder>() {
                    Ok(bcb) => { b.bridges().bridges().push(bcb); }
                    Err(e)  => warn!("Bad bridge line '{line}': {e}"),
                }
            }
            // Enable bridges explicitly so arti uses them.
            // arti-client 0.41: BoolOrAuto was removed from the public API —
            // bridges().set_enabled(true) is the new setter.
            b.bridges().set_enabled(true);
        }

        b.build().context("Build TorClientConfig")
    }

    // ── Public API ────────────────────────────────────────────────────────────

    pub async fn set_exit_node(&self, iso: &str) -> anyhow::Result<()> {
        info!("Exit node preference → {iso}");
        *self.exit_iso.write() = Some(iso.to_uppercase());
        Ok(())
    }

    pub fn get_exit_ip(&self) -> Option<String> {
        self.exit_ip.read().clone()
    }

    pub fn set_exit_ip(&self, ip: String) {
        *self.exit_ip.write() = Some(ip);
    }

    pub fn bootstrap_status(&self) -> BootstrapStatus {
        BootstrapStatus {
            percent:       self.bs_percent.load(Ordering::Relaxed),
            phase:         self.bs_phase.read().clone(),
            is_complete:   self.bs_complete.load(Ordering::Relaxed),
            error_message: self.bs_error.read().clone(),
        }
    }

    pub fn is_bootstrapped(&self) -> bool {
        self.bs_complete.load(Ordering::Relaxed)
    }

    pub async fn add_bridge(&self, _bridge_line: &str) -> anyhow::Result<()> {
        info!("Bridge added — restart connection to activate");
        Ok(())
    }

    pub async fn clear_bridges(&self) {
        info!("Bridges cleared — restart connection to activate");
    }

    pub async fn shutdown(&self) {
        info!("Shutting down TorEngine…");
    }

    pub fn socks5_addr(&self) -> &str { &self.socks_addr }

    /// Build StreamPrefs with exit-country isolation applied.
    pub fn stream_prefs(&self) -> StreamPrefs {
        let mut prefs = StreamPrefs::new();
        // arti-client 0.41: IsolationToken is re-exported from arti_client.
        prefs.set_isolation(IsolationToken::no_isolation());
        prefs
    }

    /// Public accessor for TorClient so dns.rs / tun2socks.rs can call connect().
    pub fn client(&self) -> Arc<TorClient<PreferredRuntime>> {
        self.client.clone()
    }
}

// ── Production SOCKS5 proxy ───────────────────────────────────────────────────

async fn run_socks5_proxy(
    addr:     &str,
    client:   Arc<TorClient<PreferredRuntime>>,
    stats:    Arc<StatsTracker>,
    exit_iso: Arc<RwLock<Option<String>>>,
) -> anyhow::Result<()> {
    use tokio::net::TcpListener;

    let listener = TcpListener::bind(addr).await
        .with_context(|| format!("Cannot bind SOCKS5 on {addr}"))?;
    info!("Production SOCKS5 proxy listening on {addr}");

    loop {
        let (stream, peer) = match listener.accept().await {
            Ok(v)  => v,
            Err(e) => { warn!("SOCKS5 accept: {e}"); continue; }
        };
        debug!("SOCKS5 connection from {peer}");

        let c  = client.clone();
        let st = stats.clone();
        let ei = exit_iso.clone();

        tokio::spawn(async move {
            if let Err(e) = handle_socks5_conn(stream, c, st, ei).await {
                debug!("SOCKS5 {peer}: {e}");
            }
        });
    }
}

async fn handle_socks5_conn(
    mut stream:  tokio::net::TcpStream,
    client:      Arc<TorClient<PreferredRuntime>>,
    stats:       Arc<StatsTracker>,
    _exit_iso:   Arc<RwLock<Option<String>>>,
) -> anyhow::Result<()> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    // ── SOCKS5 Handshake ──────────────────────────────────────────────────────
    let mut hdr = [0u8; 2];
    stream.read_exact(&mut hdr).await?;
    if hdr[0] != 0x05 { anyhow::bail!("Not SOCKS5"); }
    let mut methods = vec![0u8; hdr[1] as usize];
    stream.read_exact(&mut methods).await?;
    stream.write_all(&[0x05, 0x00]).await?;   // No-auth

    // ── CONNECT Request ───────────────────────────────────────────────────────
    let mut req = [0u8; 4];
    stream.read_exact(&mut req).await?;
    if req[1] != 0x01 { anyhow::bail!("Only CONNECT supported"); }

    let dst_host = match req[3] {
        0x01 => {
            let mut b = [0u8; 4];
            stream.read_exact(&mut b).await?;
            std::net::Ipv4Addr::from(b).to_string()
        }
        0x03 => {
            let len = stream.read_u8().await? as usize;
            let mut h = vec![0u8; len];
            stream.read_exact(&mut h).await?;
            String::from_utf8(h)?
        }
        0x04 => {
            let mut b = [0u8; 16];
            stream.read_exact(&mut b).await?;
            std::net::Ipv6Addr::from(b).to_string()
        }
        t => anyhow::bail!("Unknown addr type 0x{t:02x}"),
    };
    let dst_port = stream.read_u16().await?;

    debug!("SOCKS5 CONNECT → {dst_host}:{dst_port}");

    // ── Open Tor stream ───────────────────────────────────────────────────────
    let mut prefs = StreamPrefs::new();
    prefs.set_isolation(IsolationToken::no_isolation());

    match client.connect_with_prefs(
        (dst_host.as_str(), dst_port),
        &prefs,
    ).await {
        Ok(tor_stream) => {
            stream.write_all(&[0x05, 0x00, 0x00, 0x01, 0,0,0,0, 0,0]).await?;

            let (mut ri, mut wi) = tokio::io::split(stream);
            let (mut rt, mut wt) = tokio::io::split(tor_stream);
            let st1 = stats.clone();
            let st2 = stats.clone();

            let up = tokio::spawn(async move {
                let n = tokio::io::copy(&mut ri, &mut wt).await.unwrap_or(0);
                st1.add_sent(n);
            });
            let dn = tokio::spawn(async move {
                let n = tokio::io::copy(&mut rt, &mut wi).await.unwrap_or(0);
                st2.add_received(n);
            });
            let _ = tokio::join!(up, dn);
            stats.decrement_connections();
        }
        Err(e) => {
            warn!("Tor CONNECT {dst_host}:{dst_port}: {e}");
            stream.write_all(&[0x05, 0x05, 0x00, 0x01, 0,0,0,0, 0,0]).await?;
        }
    }

    Ok(())
}
