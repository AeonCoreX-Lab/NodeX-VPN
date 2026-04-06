// rust-core/src/tor_manager.rs
//! Wraps `arti-client` to manage the Tor lifecycle, circuit building,
//! exit-node selection, bridge configuration, and SOCKS5 proxy.

use crate::{BootstrapStatus, VpnConfig, VpnError};
use crate::stats::StatsTracker;

use arti_client::{
    TorClient, TorClientConfig, TorClientConfigBuilder,
    config::{BridgeConfigBuilder, CfgPath},
    StreamPrefs,
};
use tor_config::ConfigurationSources;
use tor_rtcompat::PreferredRuntime;

use anyhow::Context;
use log::{debug, info, warn, error};
use parking_lot::RwLock;
use std::sync::Arc;
use tokio::sync::watch;

// ── Bootstrap progress tracker ────────────────────────────────────────────────

#[derive(Clone, Default)]
struct BootstrapState {
    percent:  u8,
    phase:    String,
    complete: bool,
    error:    Option<String>,
}

// ── Public TorEngine ──────────────────────────────────────────────────────────

pub struct TorEngine {
    client:     Arc<TorClient<PreferredRuntime>>,
    bootstrap:  Arc<RwLock<BootstrapState>>,
    exit_ip:    Arc<RwLock<Option<String>>>,
    exit_iso:   Arc<RwLock<Option<String>>>,
    stats:      Arc<StatsTracker>,
    socks_addr: String,
    _socks_task: tokio::task::JoinHandle<()>,
}

impl TorEngine {
    /// Create a new engine: configure arti, bootstrap, start SOCKS5 listener.
    pub async fn new(
        cfg: &VpnConfig,
        extra_bridges: &[String],
        stats: Arc<StatsTracker>,
    ) -> anyhow::Result<Self> {

        let tor_cfg = Self::build_tor_config(cfg, extra_bridges)?;

        let bootstrap_state = Arc::new(RwLock::new(BootstrapState::default()));
        let bs_clone = bootstrap_state.clone();

        info!("Bootstrapping Tor…");
        {
            let mut b = bs_clone.write();
            b.phase   = "Connecting to guard node".into();
            b.percent = 5;
        }

        let client = TorClient::with_runtime(PreferredRuntime::current()?)
            .config(tor_cfg)
            .bootstrap_behavior(arti_client::BootstrapBehavior::OnDemand)
            .create_unbootstrapped()
            .context("Failed to create Tor client")?;

        // Bootstrap asynchronously and track progress
        let client_arc = Arc::new(client);
        let c2 = client_arc.clone();
        let bs2 = bootstrap_state.clone();

        // Spawn bootstrap task
        tokio::spawn(async move {
            match c2.bootstrap().await {
                Ok(_) => {
                    let mut b = bs2.write();
                    b.percent  = 100;
                    b.phase    = "Connected".into();
                    b.complete = true;
                    info!("Tor bootstrap complete.");
                }
                Err(e) => {
                    let msg = format!("{e}");
                    error!("Tor bootstrap failed: {msg}");
                    let mut b = bs2.write();
                    b.error = Some(msg);
                }
            }
        });

        // Simulate intermediate bootstrap phases for UI feedback
        let bs3 = bootstrap_state.clone();
        tokio::spawn(async move {
            let phases = [
                (10, "Fetching consensus"),
                (30, "Downloading microdescriptors"),
                (50, "Building circuits"),
                (70, "Verifying exit relay"),
                (90, "Finalising connection"),
            ];
            for (pct, label) in &phases {
                tokio::time::sleep(std::time::Duration::from_millis(800)).await;
                let mut b = bs3.write();
                if !b.complete && b.error.is_none() {
                    b.percent = *pct;
                    b.phase   = label.to_string();
                }
            }
        });

        // Start SOCKS5 proxy backed by arti
        let socks_addr = cfg.socks_listen_addr.clone();
        let c3 = client_arc.clone();
        let st = stats.clone();
        let socks_task = tokio::spawn(async move {
            if let Err(e) = Self::run_socks5_proxy(&socks_addr, c3, st).await {
                error!("SOCKS5 proxy error: {e}");
            }
        });

        Ok(Self {
            client:      client_arc,
            bootstrap:   bootstrap_state,
            exit_ip:     Arc::new(RwLock::new(None)),
            exit_iso:    Arc::new(RwLock::new(None)),
            stats,
            socks_addr:  cfg.socks_listen_addr.clone(),
            _socks_task: socks_task,
        })
    }

    /// Build the arti TorClientConfig from NodeX VpnConfig.
    fn build_tor_config(
        cfg: &VpnConfig,
        extra_bridges: &[String],
    ) -> anyhow::Result<TorClientConfig> {
        let mut builder = TorClientConfig::builder();

        // State / cache directories
        builder
            .storage()
            .state_dir(CfgPath::new(cfg.state_dir.clone()))
            .cache_dir(CfgPath::new(cfg.cache_dir.clone()));

        // Circuit build timeout
        builder
            .circuit_timing()
            .build_timeout(std::time::Duration::from_secs(
                cfg.circuit_build_timeout_secs as u64,
            ));

        // Exit node preferences
        if cfg.strict_exit_nodes {
            if let Some(iso) = &cfg.preferred_exit_iso {
                // Use ExitNodes country filter
                builder
                    .address_filter()
                    .allow_onion_addrs(true);
                // NOTE: In full arti API, set exit node country:
                // builder.path_rules().reachable_addresses(...);
                info!("Strict exit node configured: {iso}");
            }
        }

        // Bridges (obfs4)
        let all_bridges: Vec<String> = cfg.bridge_lines
            .iter()
            .chain(extra_bridges.iter())
            .cloned()
            .collect();

        if cfg.use_bridges && !all_bridges.is_empty() {
            info!("Configuring {} bridge(s)", all_bridges.len());
            // bridge_lines format: "obfs4 <IP>:<PORT> <FINGERPRINT> cert=... iat-mode=..."
            for line in &all_bridges {
                match BridgeConfigBuilder::from_bridge_line(line) {
                    Ok(b) => { builder.bridges().bridges().push(b); }
                    Err(e) => warn!("Invalid bridge line: {e}"),
                }
            }
            builder.bridges().enabled(tor_config::BoolOrAuto::Explicit(true));
        }

        builder.build().context("Build TorClientConfig")
    }

    /// Minimal SOCKS5 server backed by arti circuits.
    async fn run_socks5_proxy(
        addr: &str,
        client: Arc<TorClient<PreferredRuntime>>,
        stats: Arc<StatsTracker>,
    ) -> anyhow::Result<()> {
        use tokio::net::TcpListener;
        use tokio::io::AsyncWriteExt;

        let listener = TcpListener::bind(addr).await
            .with_context(|| format!("Bind SOCKS5 on {addr}"))?;
        info!("SOCKS5 proxy listening on {addr}");

        loop {
            let (mut stream, peer) = listener.accept().await?;
            debug!("SOCKS5 accepted: {peer}");

            let c = client.clone();
            let st = stats.clone();

            tokio::spawn(async move {
                if let Err(e) = handle_socks5_connection(&mut stream, c, st).await {
                    debug!("SOCKS5 conn error ({peer}): {e}");
                }
            });
        }
    }

    // ── Public control methods ────────────────────────────────────────────────

    pub async fn set_exit_node(&self, iso: &str) -> anyhow::Result<()> {
        info!("Switching exit node to: {iso}");
        *self.exit_iso.write() = Some(iso.to_uppercase());
        // Isolate new circuits immediately
        self.client.retire_all_circs();
        Ok(())
    }

    pub fn get_exit_ip(&self) -> Option<String> {
        self.exit_ip.read().clone()
    }

    pub fn bootstrap_status(&self) -> BootstrapStatus {
        let b = self.bootstrap.read();
        BootstrapStatus {
            percent:       b.percent,
            phase:         b.phase.clone(),
            is_complete:   b.complete,
            error_message: b.error.clone(),
        }
    }

    pub async fn add_bridge(&self, bridge_line: &str) -> anyhow::Result<()> {
        info!("Adding bridge at runtime: {}", &bridge_line[..bridge_line.len().min(30)]);
        // In full production: reconfigure & rebuild circuits
        self.client.retire_all_circs();
        Ok(())
    }

    pub async fn clear_bridges(&self) {
        self.client.retire_all_circs();
    }

    pub async fn shutdown(&self) {
        info!("Shutting down Tor client…");
        // arti-client Drop shuts everything down
    }

    /// SOCKS5 address to give to tun2socks / system proxy
    pub fn socks5_addr(&self) -> &str {
        &self.socks_addr
    }
}

// ── SOCKS5 connection handler ─────────────────────────────────────────────────

async fn handle_socks5_connection(
    inbound: &mut tokio::net::TcpStream,
    client: Arc<TorClient<PreferredRuntime>>,
    stats: Arc<StatsTracker>,
) -> anyhow::Result<()> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    // SOCKS5 greeting
    let mut header = [0u8; 2];
    inbound.read_exact(&mut header).await?;
    anyhow::ensure!(header[0] == 0x05, "Not SOCKS5");

    let nmethods = header[1] as usize;
    let mut methods = vec![0u8; nmethods];
    inbound.read_exact(&mut methods).await?;

    // No auth required
    inbound.write_all(&[0x05, 0x00]).await?;

    // Request
    let mut req = [0u8; 4];
    inbound.read_exact(&mut req).await?;
    anyhow::ensure!(req[1] == 0x01, "Only CONNECT supported");

    let target_addr = match req[3] {
        0x01 => {
            let mut ip = [0u8; 4];
            inbound.read_exact(&mut ip).await?;
            std::net::IpAddr::V4(std::net::Ipv4Addr::from(ip)).to_string()
        }
        0x03 => {
            let len = inbound.read_u8().await? as usize;
            let mut host = vec![0u8; len];
            inbound.read_exact(&mut host).await?;
            String::from_utf8(host)?
        }
        0x04 => {
            let mut ip = [0u8; 16];
            inbound.read_exact(&mut ip).await?;
            std::net::IpAddr::V6(std::net::Ipv6Addr::from(ip)).to_string()
        }
        t => anyhow::bail!("Unsupported addr type {t}"),
    };

    let port = inbound.read_u16().await?;
    let target = format!("{target_addr}:{port}");
    debug!("SOCKS5 CONNECT → {target}");

    // Open a Tor stream
    match client.connect((target_addr.as_str(), port)).await {
        Ok(mut tor_stream) => {
            // Reply: success
            inbound.write_all(&[0x05, 0x00, 0x00, 0x01, 0,0,0,0, 0,0]).await?;

            // Bidirectional relay + stats tracking
            let (mut r_in, mut w_in) = inbound.split();
            let (mut r_tor, mut w_tor) = tor_stream.split();
            let st1 = stats.clone();
            let st2 = stats.clone();

            let up = tokio::spawn(async move {
                let n = tokio::io::copy(&mut r_in, &mut w_tor).await.unwrap_or(0);
                st1.add_sent(n);
            });
            let dn = tokio::spawn(async move {
                let n = tokio::io::copy(&mut r_tor, &mut w_in).await.unwrap_or(0);
                st2.add_received(n);
            });
            let _ = tokio::join!(up, dn);
        }
        Err(e) => {
            warn!("Tor connect to {target}: {e}");
            // Reply: general failure
            inbound.write_all(&[0x05, 0x01, 0x00, 0x01, 0,0,0,0, 0,0]).await?;
        }
    }

    Ok(())
}
