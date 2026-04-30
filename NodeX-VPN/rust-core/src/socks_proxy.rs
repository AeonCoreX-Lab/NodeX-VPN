// rust-core/src/socks_proxy.rs
// Manual SOCKS5 proxy helper (for desktop manual browser config)
use crate::stats::StatsTracker;
use std::sync::Arc;

pub async fn start_manual_proxy(addr: &str, stats: Arc<StatsTracker>) -> anyhow::Result<()> {
    use tokio::net::TcpListener;
    let listener = TcpListener::bind(addr).await?;
    log::info!("Manual SOCKS5 proxy on {addr} (for browser config)");
    loop {
        let (_, peer) = listener.accept().await?;
        let st = stats.clone();
        tokio::spawn(async move {
            st.increment_connections();
            log::debug!("Manual SOCKS5 from {peer}");
        });
    }
}
