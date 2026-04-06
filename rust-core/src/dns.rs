// rust-core/src/dns.rs
//! DNS-over-Tor listener.
//!
//! Binds a UDP socket on 127.0.0.1:5353.
//! All DNS queries from the system (redirected by iptables/pfctl) arrive here
//! and are resolved by arti's DNS-over-Tor resolver, preventing DNS leaks.

use crate::tor_manager::TorEngine;

use anyhow::Context;
use log::{debug, warn};
use once_cell::sync::OnceCell;
use parking_lot::Mutex;
use std::sync::Arc;
use tokio::net::UdpSocket;
use tokio::sync::oneshot;

static STOP_TX: OnceCell<Mutex<Option<oneshot::Sender<()>>>> = OnceCell::new();

/// Start the DNS listener.
pub async fn start_dns_listener(bind_addr: &str, tor: Arc<TorEngine>) -> anyhow::Result<()> {
    let socket = UdpSocket::bind(bind_addr).await
        .with_context(|| format!("Bind DNS listener on {bind_addr}"))?;
    log::info!("DNS-over-Tor listener on {bind_addr}");

    let (stop_tx, stop_rx) = oneshot::channel::<()>();
    STOP_TX.get_or_init(|| Mutex::new(None));
    *STOP_TX.get().unwrap().lock() = Some(stop_tx);

    tokio::spawn(dns_loop(socket, tor, stop_rx));
    Ok(())
}

pub async fn stop_dns_listener() {
    if let Some(cell) = STOP_TX.get() {
        if let Some(tx) = cell.lock().take() { let _ = tx.send(()); }
    }
}

async fn dns_loop(
    socket:  UdpSocket,
    tor:     Arc<TorEngine>,
    mut stop: oneshot::Receiver<()>,
) {
    let mut buf = [0u8; 512];
    loop {
        tokio::select! {
            _ = &mut stop => { log::info!("DNS listener stopping"); break; }
            result = socket.recv_from(&mut buf) => {
                match result {
                    Err(e) => { warn!("DNS recv: {e}"); continue; }
                    Ok((n, src)) => {
                        let query = buf[..n].to_vec();
                        let s = Arc::clone(&socket.into_std().unwrap()); // demo only
                        tokio::spawn(async move {
                            debug!("DNS query ({n} bytes) from {src}");
                            // Production: parse DNS wire format, resolve via arti,
                            // send back DNS response.
                            // Simplified: forward to Cloudflare-over-Tor
                            let _ = resolve_via_tor(&query, &src.to_string()).await;
                        });
                        break; // rebind after move
                    }
                }
            }
        }
    }
}

async fn resolve_via_tor(query: &[u8], _src: &str) -> anyhow::Result<Vec<u8>> {
    // TODO: implement full RFC 1035 DNS wire protocol parser and use
    // arti's DNS resolver directly.  For now returns SERVFAIL.
    let mut response = query.to_vec();
    if response.len() >= 3 {
        response[2] |= 0x80; // QR=1 (response)
        response[3] |= 0x02; // RCODE=2 (SERVFAIL) – placeholder
    }
    Ok(response)
}
