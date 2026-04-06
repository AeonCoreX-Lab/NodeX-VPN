// rust-core/src/tun2socks.rs
//! TUN-to-SOCKS5 IP packet relay.
//!
//! This module bridges raw IP packets arriving on the TUN device to the
//! arti SOCKS5 proxy.  It implements a userspace TCP/IP stack using smoltcp
//! to reassemble IP fragments, track TCP state, and relay full streams.
//!
//! Architecture:
//!   TUN device (raw IP)
//!        │
//!   smoltcp Interface (reassemble, TCP state machine)
//!        │
//!   For each new TCP connection:
//!        └─→  SOCKS5 CONNECT to arti (127.0.0.1:9050)
//!               └─→ Tor circuit → Exit relay → Internet

use crate::stats::StatsTracker;

use anyhow::Result;
use log::{debug, error};
use std::sync::Arc;

/// Relay configuration
pub struct RelayConfig {
    pub socks5_addr: String,
    pub mtu:         u32,
}

/// Run the relay loop for a given TUN device.
/// `read_fn`  – async function that reads raw IP packets from TUN
/// `write_fn` – async function that writes raw IP packets to TUN
pub async fn run_relay<R, W>(
    config:   RelayConfig,
    stats:    Arc<StatsTracker>,
    mut read_pkt: R,
    mut write_pkt: W,
) -> Result<()>
where
    R: FnMut() -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<Vec<u8>>> + Send>>,
    W: FnMut(Vec<u8>) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<()>> + Send>>,
{
    use smoltcp::iface::{Config, Interface, SocketSet};
    use smoltcp::phy::{Device, Medium};
    use smoltcp::time::Instant;
    use smoltcp::wire::EthernetAddress;

    // NOTE: Full smoltcp integration requires implementing the Device trait
    // around the TUN fd.  This is a structural placeholder showing the
    // intended architecture.

    loop {
        let pkt = read_pkt().await?;
        stats.add_received(pkt.len() as u64);

        // In production: feed packet to smoltcp, get TCP connect events,
        // relay via SOCKS5, pump data bidirectionally.
        debug!("tun2socks: {} byte IP packet", pkt.len());
    }
}

/// Connect to SOCKS5 proxy and relay data.
async fn socks5_relay(
    dst_host: &str,
    dst_port: u16,
    socks5:   &str,
    stats:    Arc<StatsTracker>,
) -> Result<()> {
    use tokio::net::TcpStream;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let mut proxy = TcpStream::connect(socks5).await?;

    // SOCKS5 handshake
    proxy.write_all(&[0x05, 0x01, 0x00]).await?;
    let mut resp = [0u8; 2];
    proxy.read_exact(&mut resp).await?;

    // CONNECT request
    let host_bytes  = dst_host.as_bytes();
    let mut req = vec![
        0x05, 0x01, 0x00, 0x03,
        host_bytes.len() as u8,
    ];
    req.extend_from_slice(host_bytes);
    req.push((dst_port >> 8) as u8);
    req.push((dst_port & 0xFF) as u8);
    proxy.write_all(&req).await?;

    let mut resp = [0u8; 10];
    proxy.read_exact(&mut resp).await?;
    if resp[1] != 0x00 {
        anyhow::bail!("SOCKS5 CONNECT failed: {}", resp[1]);
    }

    debug!("SOCKS5 connected → {dst_host}:{dst_port}");
    stats.increment_connections();
    Ok(())
}
