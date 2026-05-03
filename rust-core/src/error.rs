// rust-core/src/error.rs
#[derive(Debug, thiserror::Error)]
pub enum VpnError {
    #[error("Tor initialisation failed")]           TorInitFailed,
    #[error("Bridge connection failed")]            BridgeConnectionFailed,
    #[error("Tunnel creation failed")]              TunnelCreationFailed,
    #[error("Routing setup failed")]                RoutingSetupFailed,
    #[error("Engine already running")]              AlreadyRunning,
    #[error("Engine not running")]                  NotRunning,
    #[error("Invalid configuration")]               InvalidConfig,
    #[error("Permission denied (need root/admin)")] PermissionDenied,
    #[error("Platform not supported")]              PlatformNotSupported,
    #[error("Kill switch operation failed")]         KillSwitchFailed,
    #[error("Auto-reconnect failed")]               ReconnectFailed,
    #[error("Unknown error")]                       Unknown,
}
