// rust-core/src/stats.rs — Production stats tracker with EMA
use crate::VpnStats;
use std::sync::atomic::{AtomicU64, AtomicU32, Ordering};
use std::time::{Duration, Instant};
use parking_lot::Mutex;
use std::sync::Arc;

const EMA_ALPHA: f64 = 0.25;

pub struct StatsTracker {
    bytes_sent:         AtomicU64,
    bytes_received:     AtomicU64,
    active_conns:       AtomicU32,
    last_at:            Mutex<Instant>,
    last_sent:          AtomicU64,
    last_recv:          AtomicU64,
    ema_send:           Mutex<f64>,
    ema_recv:           Mutex<f64>,
    latency_us:         AtomicU64,
    exit_country:       Mutex<String>,
    exit_ip:            Mutex<String>,
}

impl StatsTracker {
    pub fn new() -> Self {
        Self {
            bytes_sent:     AtomicU64::new(0),
            bytes_received: AtomicU64::new(0),
            active_conns:   AtomicU32::new(0),
            last_at:        Mutex::new(Instant::now()),
            last_sent:      AtomicU64::new(0),
            last_recv:      AtomicU64::new(0),
            ema_send:       Mutex::new(0.0),
            ema_recv:       Mutex::new(0.0),
            latency_us:     AtomicU64::new(0),
            exit_country:   Mutex::new("—".to_string()),
            exit_ip:        Mutex::new("0.0.0.0".to_string()),
        }
    }

    #[inline] pub fn add_sent(&self, n: u64)      { self.bytes_sent.fetch_add(n, Ordering::Relaxed); }
    #[inline] pub fn add_received(&self, n: u64)  { self.bytes_received.fetch_add(n, Ordering::Relaxed); }
    #[inline] pub fn increment_connections(&self) { self.active_conns.fetch_add(1, Ordering::Relaxed); }
    #[inline] pub fn decrement_connections(&self) { self.active_conns.fetch_sub(1, Ordering::Relaxed); }

    pub fn set_latency_ms(&self, ms: f64) {
        self.latency_us.store((ms * 1000.0) as u64, Ordering::Relaxed);
    }

    #[allow(dead_code)]
    pub fn set_exit_info(&self, country: String, ip: String) {
        *self.exit_country.lock() = country;
        *self.exit_ip.lock()      = ip;
    }

    pub fn snapshot(&self) -> VpnStats {
        let now  = Instant::now();
        let sent = self.bytes_sent.load(Ordering::Relaxed);
        let recv = self.bytes_received.load(Ordering::Relaxed);

        let (send_rate, recv_rate) = {
            let mut last_at = self.last_at.lock();
            let elapsed = now.duration_since(*last_at).as_secs_f64().max(0.001);
            *last_at = now;

            let ps = self.last_sent.swap(sent, Ordering::Relaxed);
            let pr = self.last_recv.swap(recv, Ordering::Relaxed);

            let is = sent.saturating_sub(ps) as f64 / elapsed;
            let ir = recv.saturating_sub(pr) as f64 / elapsed;

            let mut es = self.ema_send.lock();
            let mut er = self.ema_recv.lock();
            *es = EMA_ALPHA * is + (1.0 - EMA_ALPHA) * *es;
            *er = EMA_ALPHA * ir + (1.0 - EMA_ALPHA) * *er;
            (*es as u64, *er as u64)
        };

        VpnStats {
            bytes_sent:           sent,
            bytes_received:       recv,
            send_rate_bps:        send_rate,
            recv_rate_bps:        recv_rate,
            active_circuits:      self.active_conns.load(Ordering::Relaxed),
            pending_circuits:     0,
            latency_ms:           self.latency_us.load(Ordering::Relaxed) as f64 / 1000.0,
            current_exit_country: self.exit_country.lock().clone(),
            current_exit_ip:      self.exit_ip.lock().clone(),
            uptime_secs:          0,
        }
    }
}

/// Periodic latency probe — measures round-trip to Tor SOCKS proxy
pub async fn latency_probe_loop(stats: Arc<StatsTracker>) {
    use tokio::net::TcpStream;
    let mut interval = tokio::time::interval(Duration::from_secs(30));
    loop {
        interval.tick().await;
        let start = Instant::now();
        // Probe the SOCKS5 proxy port (which is our arti connection)
        if TcpStream::connect("127.0.0.1:9050").await.is_ok() {
            let ms = start.elapsed().as_secs_f64() * 1000.0;
            stats.set_latency_ms(ms);
        }
    }
}
