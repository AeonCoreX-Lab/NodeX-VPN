// rust-core/src/tunnel/mod.rs
//! Platform-specific TUN device management and routing.
//!
//! Each sub-module sets up a virtual network interface and routes all system
//! traffic through the arti SOCKS5 proxy using the tun2socks technique.
//!
//! Platform dispatch:
//!   Linux   → linux.rs  (TUN via /dev/net/tun, ip-route2)
//!   macOS   → macos.rs  (utun via macOS system API, route add)
//!   Windows → windows.rs (Wintun driver)
//!   Android → (handled from Kotlin VpnService – FD passed in)
//!   iOS     → (handled from Swift NetworkExtension)

pub mod platform {
    use crate::tor_manager::TorEngine;
    use crate::stats::StatsTracker;
    use std::sync::Arc;

    // ── Linux ─────────────────────────────────────────────────────────────────
    #[cfg(target_os = "linux")]
    mod inner {
        use super::*;
        use crate::tunnel::linux;

        pub async fn start_tunnel(
            socks_addr: &str,
            tor: Arc<TorEngine>,
            stats: Arc<StatsTracker>,
        ) -> anyhow::Result<()> {
            linux::start(socks_addr, tor, stats).await
        }

        pub async fn stop_tunnel() {
            linux::stop().await;
        }
    }

    // ── macOS ─────────────────────────────────────────────────────────────────
    #[cfg(target_os = "macos")]
    mod inner {
        use super::*;
        use crate::tunnel::macos;

        pub async fn start_tunnel(
            socks_addr: &str,
            tor: Arc<TorEngine>,
            stats: Arc<StatsTracker>,
        ) -> anyhow::Result<()> {
            macos::start(socks_addr, tor, stats).await
        }

        pub async fn stop_tunnel() {
            macos::stop().await;
        }
    }

    // ── Windows ───────────────────────────────────────────────────────────────
    #[cfg(target_os = "windows")]
    mod inner {
        use super::*;
        use crate::tunnel::windows;

        pub async fn start_tunnel(
            socks_addr: &str,
            tor: Arc<TorEngine>,
            stats: Arc<StatsTracker>,
        ) -> anyhow::Result<()> {
            windows::start(socks_addr, tor, stats).await
        }

        pub async fn stop_tunnel() {
            windows::stop().await;
        }
    }

    // ── Android / iOS – TUN FD managed by the OS, tunnel handled in Kotlin/Swift
    #[cfg(any(target_os = "android", target_os = "ios"))]
    mod inner {
        use super::*;

        pub async fn start_tunnel(
            _socks_addr: &str,
            _tor: Arc<TorEngine>,
            _stats: Arc<StatsTracker>,
        ) -> anyhow::Result<()> {
            // The platform VPN service (VpnService / PacketTunnelProvider)
            // owns the TUN fd.  We just make sure the SOCKS proxy is running.
            log::info!("Mobile tunnel: managed by OS VPN service");
            Ok(())
        }

        pub async fn stop_tunnel() {}
    }

    pub use inner::{start_tunnel, stop_tunnel};
}

// ── Platform sub-modules ─────────────────────────────────────────────────────

#[cfg(target_os = "linux")]
pub mod linux;

#[cfg(target_os = "macos")]
pub mod macos;

#[cfg(target_os = "windows")]
pub mod windows;
