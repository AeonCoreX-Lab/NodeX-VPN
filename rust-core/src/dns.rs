// rust-core/src/dns.rs
//! Production DNS-over-Tor listener.
//!
//! Binds UDP on 127.0.0.1:5353. All DNS queries (redirected by iptables/pfctl)
//! arrive here and are resolved via the Tor network to prevent DNS leaks.
//!
//! Implementation:
//!   1. Parse incoming DNS wire-format query (RFC 1035)
//!   2. Extract domain name
//!   3. Resolve via arti-client's TorClient.connect() to a DNS-over-TCP server
//!      (9.9.9.9:53 via Tor — Quad9, privacy-respecting)
//!   4. Forward raw DNS query over the Tor TCP stream
//!   5. Return response to original requester

use crate::tor_manager::TorEngine;

use anyhow::Context;
use log::{debug, info, warn, error};
use once_cell::sync::OnceCell;
use parking_lot::Mutex;
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::UdpSocket;
use tokio::sync::oneshot;
use tokio::time::{timeout, Duration};

// DNS-over-TCP upstream — resolved via Tor circuit
// Quad9 (9.9.9.9:53) — privacy-focused, no logging
const DNS_UPSTREAM_HOST: &str = "9.9.9.9";
const DNS_UPSTREAM_PORT: u16  = 53;
const DNS_TIMEOUT_SECS:  u64  = 10;

static STOP_TX: OnceCell<Mutex<Option<oneshot::Sender<()>>>> = OnceCell::new();

pub async fn start_dns_listener(
    bind_addr: &str,
    tor:       Arc<TorEngine>,
) -> anyhow::Result<()> {
    // Wait for Tor to bootstrap before starting DNS
    let mut waited = 0u32;
    while !tor.is_bootstrapped() && waited < 30 {
        tokio::time::sleep(Duration::from_secs(1)).await;
        waited += 1;
    }
    if !tor.is_bootstrapped() {
        warn!("DNS: Tor not bootstrapped after 30s, starting anyway");
    }

    let socket = Arc::new(
        UdpSocket::bind(bind_addr).await
            .with_context(|| format!("Bind DNS listener on {bind_addr}"))?
    );
    info!("DNS-over-Tor listener on {bind_addr} → {DNS_UPSTREAM_HOST}:{DNS_UPSTREAM_PORT} via Tor");

    let (stop_tx, stop_rx) = oneshot::channel::<()>();
    STOP_TX.get_or_init(|| Mutex::new(None));
    *STOP_TX.get().unwrap().lock() = Some(stop_tx);

    tokio::spawn(dns_listener_loop(socket, tor, stop_rx));
    Ok(())
}

pub async fn stop_dns_listener() {
    if let Some(cell) = STOP_TX.get() {
        if let Some(tx) = cell.lock().take() { let _ = tx.send(()); }
    }
}

async fn dns_listener_loop(
    socket: Arc<UdpSocket>,
    tor:    Arc<TorEngine>,
    mut stop: oneshot::Receiver<()>,
) {
    let mut buf = [0u8; 512];
    loop {
        tokio::select! {
            _ = &mut stop => {
                info!("DNS listener stopped");
                break;
            }
            result = socket.recv_from(&mut buf) => {
                match result {
                    Err(e) => { warn!("DNS recv: {e}"); continue; }
                    Ok((n, src)) => {
                        let query  = buf[..n].to_vec();
                        let sock   = socket.clone();
                        let tor_c  = tor.clone();

                        tokio::spawn(async move {
                            match resolve_via_tor(query, tor_c).await {
                                Ok(response) => {
                                    if let Err(e) = sock.send_to(&response, src).await {
                                        warn!("DNS reply to {src}: {e}");
                                    }
                                }
                                Err(e) => {
                                    warn!("DNS resolution failed for {src}: {e}");
                                    // Send SERVFAIL so client knows to retry
                                    let servfail = make_servfail(&buf[..n.min(buf.len())]);
                                    let _ = sock.send_to(&servfail, src).await;
                                }
                            }
                        });
                    }
                }
            }
        }
    }
}

/// Resolve a DNS query over Tor using DNS-over-TCP.
/// Opens a fresh Tor stream to the upstream DNS server for each query.
async fn resolve_via_tor(query: Vec<u8>, tor: Arc<TorEngine>) -> anyhow::Result<Vec<u8>> {
    if query.len() < 12 {
        anyhow::bail!("DNS query too short: {} bytes", query.len());
    }

    let domain = extract_domain(&query).unwrap_or_else(|| "<unknown>".to_string());
    debug!("DNS resolving: {domain} via Tor → {DNS_UPSTREAM_HOST}:{DNS_UPSTREAM_PORT}");

    // DNS-over-TCP: 2-byte length prefix + query
    let tcp_query = {
        let len = query.len() as u16;
        let mut v = Vec::with_capacity(2 + query.len());
        v.push((len >> 8) as u8);
        v.push((len & 0xFF) as u8);
        v.extend_from_slice(&query);
        v
    };

    // Open TCP stream through Tor to upstream DNS server
    let mut stream = timeout(
        Duration::from_secs(DNS_TIMEOUT_SECS),
        tor.client.connect((DNS_UPSTREAM_HOST, DNS_UPSTREAM_PORT)),
    ).await
    .context("DNS Tor connect timeout")?
    .context("DNS Tor connect failed")?;

    // Send query
    stream.write_all(&tcp_query).await.context("DNS send")?;

    // Read 2-byte length prefix
    let mut len_buf = [0u8; 2];
    timeout(
        Duration::from_secs(DNS_TIMEOUT_SECS),
        stream.read_exact(&mut len_buf),
    ).await
    .context("DNS response length timeout")?
    .context("DNS response length read")?;

    let resp_len = u16::from_be_bytes(len_buf) as usize;
    if resp_len == 0 || resp_len > 65535 {
        anyhow::bail!("DNS response length invalid: {resp_len}");
    }

    // Read response
    let mut response = vec![0u8; resp_len];
    timeout(
        Duration::from_secs(DNS_TIMEOUT_SECS),
        stream.read_exact(&mut response),
    ).await
    .context("DNS response timeout")?
    .context("DNS response read")?;

    debug!("DNS resolved {domain}: {} bytes response", response.len());
    Ok(response)
}

/// Extract the first domain name from a DNS wire-format query.
fn extract_domain(query: &[u8]) -> Option<String> {
    if query.len() < 13 { return None; }
    let mut pos = 12; // Skip DNS header
    let mut labels = Vec::new();

    loop {
        if pos >= query.len() { break; }
        let len = query[pos] as usize;
        if len == 0 { break; }
        if (len & 0xC0) == 0xC0 { break; } // pointer — skip
        pos += 1;
        if pos + len > query.len() { break; }
        if let Ok(s) = std::str::from_utf8(&query[pos..pos + len]) {
            labels.push(s.to_string());
        }
        pos += len;
    }

    if labels.is_empty() { None } else { Some(labels.join(".")) }
}

/// Build a SERVFAIL response for a given query.
fn make_servfail(query: &[u8]) -> Vec<u8> {
    let mut resp = query.to_vec();
    if resp.len() >= 4 {
        resp[2] = (resp[2] & 0x00) | 0x81; // QR=1, AA=0, TC=0, RD=1
        resp[3] = (resp[3] & 0xF0) | 0x02; // RCODE=2 (SERVFAIL)
    }
    resp
}

// Re-export the client field access for dns_loop
impl TorEngine {
    pub fn client(&self) -> Arc<arti_client::TorClient<tor_rtcompat::PreferredRuntime>> {
        self.client.clone()
    }
}
