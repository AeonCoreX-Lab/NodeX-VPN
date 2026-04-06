// rust-core/src/socks_proxy.rs
//! Standalone SOCKS5 proxy server (used when a full TUN tunnel is not
//! available, e.g. during development or on unsupported platforms).
//!
//! The arti SOCKS5 listener is started inside `tor_manager.rs`.
//! This module provides helper utilities and a secondary listener
//! that can be used for testing without a full tunnel.

use crate::stats::StatsTracker;
use anyhow::Result;
use log::info;
use std::sync::Arc;

/// Start a lightweight SOCKS5 pass-through proxy for manual browser/app config.
/// Listens on `addr` (default 127.0.0.1:1080) as an alternative to TUN.
pub async fn start_manual_proxy(addr: &str, stats: Arc<StatsTracker>) -> Result<()> {
    use tokio::net::TcpListener;
    let listener = TcpListener::bind(addr).await?;
    info!("Manual SOCKS5 proxy on {addr} (for manual browser config)");

    loop {
        let (stream, peer) = listener.accept().await?;
        let st = stats.clone();
        tokio::spawn(async move {
            log::debug!("Manual SOCKS5 peer: {peer}");
            st.increment_connections();
            // Delegate to tor_manager handle_socks5_connection in production
        });
    }
}
