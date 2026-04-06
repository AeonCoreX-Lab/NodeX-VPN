// rust-core/src/node_registry.rs
//! Static exit-node catalogue (supplemented from Tor consensus at runtime).

use crate::ServerNode;

pub fn all_nodes() -> Vec<ServerNode> {
    vec![
        node("us-1",  "US", "United States", "New York",     45.0,  42, false, false),
        node("de-1",  "DE", "Germany",        "Frankfurt",   22.0,  31, false, false),
        node("nl-1",  "NL", "Netherlands",    "Amsterdam",   18.0,  28, false, false),
        node("jp-1",  "JP", "Japan",          "Tokyo",       120.0, 55, false, false),
        node("gb-1",  "GB", "United Kingdom", "London",      35.0,  38, false, false),
        node("sg-1",  "SG", "Singapore",      "Singapore",   98.0,  47, false, false),
        node("ca-1",  "CA", "Canada",         "Toronto",     52.0,  33, false, false),
        node("fr-1",  "FR", "France",         "Paris",       28.0,  25, false, false),
        node("ch-1",  "CH", "Switzerland",    "Zurich",      25.0,  22, false, false),
        node("au-1",  "AU", "Australia",      "Sydney",      210.0, 60, false, false),
        node("br-1",  "BR", "Brazil",         "São Paulo",   180.0, 70, true,  true),
        node("in-1",  "IN", "India",          "Mumbai",      140.0, 65, true,  true),
        node("se-1",  "SE", "Sweden",         "Stockholm",   30.0,  20, false, false),
        node("no-1",  "NO", "Norway",         "Oslo",        32.0,  18, false, false),
        node("is-1",  "IS", "Iceland",        "Reykjavik",   55.0,  12, false, false),
        node("ro-1",  "RO", "Romania",        "Bucharest",   48.0,  44, false, false),
        node("ua-1",  "UA", "Ukraine",        "Kyiv",        60.0,  50, true,  true),
        node("za-1",  "ZA", "South Africa",   "Cape Town",   190.0, 58, true,  true),
    ]
}

// Convenience for the UniFFI export
pub static NODES: once_cell::sync::Lazy<Vec<ServerNode>> =
    once_cell::sync::Lazy::new(all_nodes);

fn node(
    id: &str, cc: &str, name: &str, city: &str,
    lat: f64, load: u8, bridge: bool, obfs4: bool,
) -> ServerNode {
    ServerNode {
        id:             id.to_string(),
        country_code:   cc.to_string(),
        country_name:   name.to_string(),
        city:           city.to_string(),
        latency_ms:     lat,
        load_percent:   load,
        is_bridge:      bridge,
        supports_obfs4: obfs4,
    }
}
