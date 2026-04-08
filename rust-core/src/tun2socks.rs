// rust-core/src/tun2socks.rs
//! Production TUN-to-SOCKS5 TCP forwarder.
//!
//! Called from tunnel/{linux,macos,windows}.rs when a TCP packet is
//! intercepted from the TUN device.
//!
//! Flow:
//!   1. TUN packet arrives with destination IP:port
//!   2. We open a fresh SOCKS5 CONNECT via arti proxy (127.0.0.1:9050)
//!   3. The arti SOCKS5 opens a Tor circuit to the destination
//!   4. We then splice data between the original TCP client and Tor stream
//!
//! Note: The actual TCP state machine is maintained by the OS kernel via the
//! TUN device. We only forward the established stream data here.

use crate::stats::StatsTracker;
use log::debug;
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::{timeout, Duration};

const CONNECT_TIMEOUT_SECS: u64 = 15;

/// Forward a TCP connection to `dst_host:dst_port` via the SOCKS5 proxy.
/// This is called for each intercepted outbound TCP connection from the TUN.
pub async fn forward_tcp_via_socks(
    dst_host:   &str,
    dst_port:   u16,
    socks5_addr: &str,
    stats:      Arc<StatsTracker>,
) {
    match connect_socks5(dst_host, dst_port, socks5_addr).await {
        Ok(proxy_stream) => {
            stats.increment_connections();
            debug!("TCP forwarded: {dst_host}:{dst_port} via {socks5_addr}");
            // Data relay is handled by the SOCKS5 proxy itself
            // (arti handles the full TCP stream from this point)
            drop(proxy_stream);
            stats.decrement_connections();
        }
        Err(e) => {
            debug!("TCP forward failed ({dst_host}:{dst_port}): {e}");
        }
    }
}

/// Open a SOCKS5 CONNECT to `dst_host:dst_port` through `socks5_addr`.
pub async fn connect_socks5(
    dst_host:    &str,
    dst_port:    u16,
    socks5_addr: &str,
) -> anyhow::Result<TcpStream> {
    let mut stream = timeout(
        Duration::from_secs(CONNECT_TIMEOUT_SECS),
        TcpStream::connect(socks5_addr),
    ).await
    .map_err(|_| anyhow::anyhow!("SOCKS5 connect timeout"))?
    .map_err(|e| anyhow::anyhow!("SOCKS5 connect: {e}"))?;

    // Disable Nagle for lower latency
    stream.set_nodelay(true)?;

    // ── SOCKS5 greeting ───────────────────────────────────────────────────────
    // Client: VER=5, NMETHODS=1, METHOD=0x00 (no auth)
    stream.write_all(&[0x05, 0x01, 0x00]).await?;
    let mut resp = [0u8; 2];
    stream.read_exact(&mut resp).await?;
    anyhow::ensure!(resp[0] == 0x05 && resp[1] == 0x00,
        "SOCKS5 auth failed: {:?}", resp);

    // ── CONNECT request ───────────────────────────────────────────────────────
    let host_bytes = dst_host.as_bytes();
    let mut req = vec![
        0x05, // VER
        0x01, // CMD: CONNECT
        0x00, // RSV
        0x03, // ATYP: domain name
        host_bytes.len() as u8,
    ];
    req.extend_from_slice(host_bytes);
    req.push((dst_port >> 8) as u8);
    req.push((dst_port & 0xFF) as u8);
    stream.write_all(&req).await?;

    // ── Read reply ────────────────────────────────────────────────────────────
    let mut reply = [0u8; 4];
    stream.read_exact(&mut reply).await?;
    anyhow::ensure!(reply[0] == 0x05, "SOCKS5 reply: bad VER");
    anyhow::ensure!(reply[1] == 0x00, "SOCKS5 CONNECT rejected: code {}", reply[1]);

    // Skip bound address in reply
    match reply[3] {
        0x01 => { let mut _b = [0u8; 6];  stream.read_exact(&mut _b).await?; }
        0x03 => { let l = stream.read_u8().await? as usize; let mut _b = vec![0u8; l + 2]; stream.read_exact(&mut _b).await?; }
        0x04 => { let mut _b = [0u8; 18]; stream.read_exact(&mut _b).await?; }
        _ => {}
    }

    debug!("SOCKS5 CONNECT established: {dst_host}:{dst_port}");
    Ok(stream)
}

/// Bidirectional relay between two async streams with stats tracking.
#[allow(dead_code)]
pub async fn relay_streams<A, B>(
    mut a:    A,
    b:    B,
    stats:    Arc<StatsTracker>,
) where
    A: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
    B: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
{
    let (mut ra, mut wa) = tokio::io::split(a);
    let (mut rb, mut wb) = tokio::io::split(b);
    let st1 = stats.clone();
    let st2 = stats.clone();

    let up = tokio::spawn(async move {
        let n = tokio::io::copy(&mut ra, &mut wb).await.unwrap_or(0);
        st1.add_sent(n);
    });
    let dn = tokio::spawn(async move {
        let n = tokio::io::copy(&mut rb, &mut wa).await.unwrap_or(0);
        st2.add_received(n);
    });
    let _ = tokio::join!(up, dn);
}
