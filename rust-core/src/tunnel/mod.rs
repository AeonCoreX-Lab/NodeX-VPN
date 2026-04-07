// rust-core/src/tunnel/mod.rs
pub mod platform {
    use crate::tor_manager::TorEngine;
    use crate::stats::StatsTracker;
    use std::sync::Arc;

    #[cfg(target_os = "linux")]
    mod inner {
        use super::*;
        use crate::tunnel::linux;
        pub async fn start_tunnel(s: &str, t: Arc<TorEngine>, st: Arc<StatsTracker>) -> anyhow::Result<()> { linux::start(s, t, st).await }
        pub async fn stop_tunnel() { linux::stop().await; }
    }

    #[cfg(target_os = "macos")]
    mod inner {
        use super::*;
        use crate::tunnel::macos;
        pub async fn start_tunnel(s: &str, t: Arc<TorEngine>, st: Arc<StatsTracker>) -> anyhow::Result<()> { macos::start(s, t, st).await }
        pub async fn stop_tunnel() { macos::stop().await; }
    }

    #[cfg(target_os = "windows")]
    mod inner {
        use super::*;
        use crate::tunnel::windows;
        pub async fn start_tunnel(s: &str, t: Arc<TorEngine>, st: Arc<StatsTracker>) -> anyhow::Result<()> { windows::start(s, t, st).await }
        pub async fn stop_tunnel() { windows::stop().await; }
    }

    // Mobile: TUN managed by OS (VpnService / NetworkExtension)
    #[cfg(any(target_os = "android", target_os = "ios"))]
    mod inner {
        use super::*;
        pub async fn start_tunnel(_s: &str, _t: Arc<TorEngine>, _st: Arc<StatsTracker>) -> anyhow::Result<()> {
            log::info!("Mobile: TUN managed by OS VPN service");
            Ok(())
        }
        pub async fn stop_tunnel() {}
    }

    #[cfg(not(any(
        target_os = "linux", target_os = "macos", target_os = "windows",
        target_os = "android", target_os = "ios"
    )))]
    mod inner {
        use super::*;
        pub async fn start_tunnel(_: &str, _: Arc<TorEngine>, _: Arc<StatsTracker>) -> anyhow::Result<()> {
            anyhow::bail!("Unsupported platform")
        }
        pub async fn stop_tunnel() {}
    }

    pub use inner::{start_tunnel, stop_tunnel};
}

#[cfg(target_os = "linux")]   pub mod linux;
#[cfg(target_os = "macos")]   pub mod macos;
#[cfg(target_os = "windows")] pub mod windows;
