// rust-core/src/node_registry.rs
use crate::ServerNode;
use once_cell::sync::Lazy;

pub static NODES: Lazy<Vec<ServerNode>> = Lazy::new(|| vec![
    n("us-1","US","United States","New York",  45.0, 42,false,false),
    n("de-1","DE","Germany",      "Frankfurt", 22.0, 31,false,false),
    n("nl-1","NL","Netherlands",  "Amsterdam", 18.0, 28,false,false),
    n("jp-1","JP","Japan",        "Tokyo",    120.0, 55,false,false),
    n("gb-1","GB","United Kingdom","London",   35.0, 38,false,false),
    n("sg-1","SG","Singapore",    "Singapore", 98.0, 47,false,false),
    n("ca-1","CA","Canada",       "Toronto",   52.0, 33,false,false),
    n("fr-1","FR","France",       "Paris",     28.0, 25,false,false),
    n("ch-1","CH","Switzerland",  "Zurich",    25.0, 22,false,false),
    n("au-1","AU","Australia",    "Sydney",   210.0, 60,false,false),
    n("se-1","SE","Sweden",       "Stockholm", 30.0, 20,false,false),
    n("no-1","NO","Norway",       "Oslo",      32.0, 18,false,false),
    n("is-1","IS","Iceland",      "Reykjavik", 55.0, 12,false,false),
    n("ro-1","RO","Romania",      "Bucharest", 48.0, 44,false,false),
    n("br-1","BR","Brazil",       "São Paulo",180.0, 70,true, true),
    n("in-1","IN","India",        "Mumbai",   140.0, 65,true, true),
    n("za-1","ZA","South Africa", "Cape Town",190.0, 58,true, true),
    n("ua-1","UA","Ukraine",      "Kyiv",      60.0, 50,true, true),
]);

fn n(id:&str,cc:&str,nm:&str,ct:&str,lat:f64,load:u8,br:bool,o4:bool)->ServerNode {
    ServerNode { id:id.into(), country_code:cc.into(), country_name:nm.into(),
                 city:ct.into(), latency_ms:lat, load_percent:load,
                 is_bridge:br, supports_obfs4:o4 }
}
