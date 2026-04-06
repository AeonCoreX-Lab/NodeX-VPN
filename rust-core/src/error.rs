// rust-core/src/error.rs
#[derive(Debug, thiserror::Error)]
pub enum VpnError {
    #[error("Tor initialisation failed")]           TorInitFailed,
    #[error("Bridge connection failed")]            BridgeConnectionFailed,
    #[error("Tunnel creation failed")]              TunnelCreationFailed,
    #[error("Routing setup failed")]                RoutingSetupFailed,
    #[error("Engine is already running")]           AlreadyRunning,
    #[error("Engine is not running")]               NotRunning,
    #[error("Invalid configuration: {0}")]          InvalidConfig(String),
    #[error("Permission denied – need root/CAP_NET_ADMIN")] PermissionDenied,
    #[error("Platform not supported")]              PlatformNotSupported,
    #[error("Unknown error: {0}")]                  Unknown(String),
}

// ─────────────────────────────────────────────────────────────────────────────
// rust-core/src/logging.rs
// ─────────────────────────────────────────────────────────────────────────────
// (Appended here as separate inline module for brevity)

// rust-core/src/node_registry.rs
use crate::ServerNode;

/// Static list of Tor exit country nodes.
/// At runtime this would be supplemented from the Tor consensus document.
pub static NODES: &[ServerNode] = &[
    ServerNode { id: s("us-1"),  country_code: s("US"), country_name: s("United States"), city: s("New York"),    latency_ms: 45.0,  load_percent: 42, is_bridge: false, supports_obfs4: false },
    ServerNode { id: s("de-1"),  country_code: s("DE"), country_name: s("Germany"),        city: s("Frankfurt"),   latency_ms: 22.0,  load_percent: 31, is_bridge: false, supports_obfs4: false },
    ServerNode { id: s("nl-1"),  country_code: s("NL"), country_name: s("Netherlands"),    city: s("Amsterdam"),   latency_ms: 18.0,  load_percent: 28, is_bridge: false, supports_obfs4: false },
    ServerNode { id: s("jp-1"),  country_code: s("JP"), country_name: s("Japan"),          city: s("Tokyo"),       latency_ms: 120.0, load_percent: 55, is_bridge: false, supports_obfs4: false },
    ServerNode { id: s("gb-1"),  country_code: s("GB"), country_name: s("United Kingdom"), city: s("London"),      latency_ms: 35.0,  load_percent: 38, is_bridge: false, supports_obfs4: false },
    ServerNode { id: s("sg-1"),  country_code: s("SG"), country_name: s("Singapore"),      city: s("Singapore"),   latency_ms: 98.0,  load_percent: 47, is_bridge: false, supports_obfs4: false },
    ServerNode { id: s("ca-1"),  country_code: s("CA"), country_name: s("Canada"),         city: s("Toronto"),     latency_ms: 52.0,  load_percent: 33, is_bridge: false, supports_obfs4: false },
    ServerNode { id: s("fr-1"),  country_code: s("FR"), country_name: s("France"),         city: s("Paris"),       latency_ms: 28.0,  load_percent: 25, is_bridge: false, supports_obfs4: false },
    ServerNode { id: s("ch-1"),  country_code: s("CH"), country_name: s("Switzerland"),    city: s("Zurich"),      latency_ms: 25.0,  load_percent: 22, is_bridge: false, supports_obfs4: false },
    ServerNode { id: s("br-1"),  country_code: s("BR"), country_name: s("Brazil"),         city: s("São Paulo"),   latency_ms: 180.0, load_percent: 70, is_bridge: true,  supports_obfs4: true },
    ServerNode { id: s("in-1"),  country_code: s("IN"), country_name: s("India"),          city: s("Mumbai"),      latency_ms: 140.0, load_percent: 65, is_bridge: true,  supports_obfs4: true },
    ServerNode { id: s("au-1"),  country_code: s("AU"), country_name: s("Australia"),      city: s("Sydney"),      latency_ms: 210.0, load_percent: 60, is_bridge: false, supports_obfs4: false },
];

const fn s(v: &'static str) -> String { String::new() } // placeholder – real impl uses &'static str fields
