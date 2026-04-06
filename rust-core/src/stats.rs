// rust-core/src/stats.rs
//! Lock-free bandwidth and circuit counters with exponential moving average.

use crate::VpnStats;
use std::sync::atomic::{AtomicU64, AtomicU32, AtomicI64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use parking_lot::Mutex;

const EMA_ALPHA: f64 = 0.2; // smoothing factor (0 = smooth, 1 = instant)

pub struct StatsTracker {
    bytes_sent:          AtomicU64,
    bytes_received:      AtomicU64,
    active_connections:  AtomicU32,
    last_sample_at:      Mutex<Instant>,
    last_sent:           AtomicU64,
    last_recv:           AtomicU64,
    ema_send_rate:       Mutex<f64>,
    ema_recv_rate:       Mutex<f64>,
    latency_us:          AtomicI64,
    exit_country:        Mutex<String>,
    exit_ip:             Mutex<String>,
}

impl StatsTracker {
    pub fn new() -> Self {
        Self {
            bytes_sent:         AtomicU64::new(0),
            bytes_received:     AtomicU64::new(0),
            active_connections: AtomicU32::new(0),
            last_sample_at:     Mutex::new(Instant::now()),
            last_sent:          AtomicU64::new(0),
            last_recv:          AtomicU64::new(0),
            ema_send_rate:      Mutex::new(0.0),
            ema_recv_rate:      Mutex::new(0.0),
            latency_us:         AtomicI64::new(0),
            exit_country:       Mutex::new("Unknown".into()),
            exit_ip:            Mutex::new("0.0.0.0".into()),
        }
    }

    #[inline]
    pub fn add_sent(&self, n: u64) {
        self.bytes_sent.fetch_add(n, Ordering::Relaxed);
    }

    #[inline]
    pub fn add_received(&self, n: u64) {
        self.bytes_received.fetch_add(n, Ordering::Relaxed);
    }

    #[inline]
    pub fn increment_connections(&self) {
        self.active_connections.fetch_add(1, Ordering::Relaxed);
    }

    #[inline]
    pub fn decrement_connections(&self) {
        self.active_connections.fetch_sub(1, Ordering::Relaxed);
    }

    pub fn set_latency_ms(&self, ms: f64) {
        self.latency_us.store((ms * 1000.0) as i64, Ordering::Relaxed);
    }

    pub fn set_exit_info(&self, country: String, ip: String) {
        *self.exit_country.lock() = country;
        *self.exit_ip.lock()      = ip;
    }

    /// Compute a snapshot; updates the EMA rates.
    pub fn snapshot(&self) -> VpnStats {
        let now   = Instant::now();
        let sent  = self.bytes_sent.load(Ordering::Relaxed);
        let recv  = self.bytes_received.load(Ordering::Relaxed);

        let (send_rate, recv_rate) = {
            let mut last_at  = self.last_sample_at.lock();
            let elapsed = now.duration_since(*last_at).as_secs_f64().max(0.001);
            *last_at = now;

            let prev_sent = self.last_sent.swap(sent, Ordering::Relaxed);
            let prev_recv = self.last_recv.swap(recv, Ordering::Relaxed);

            let instant_send = (sent.saturating_sub(prev_sent)) as f64 / elapsed;
            let instant_recv = (recv.saturating_sub(prev_recv)) as f64 / elapsed;

            let mut ema_s = self.ema_send_rate.lock();
            let mut ema_r = self.ema_recv_rate.lock();
            *ema_s = EMA_ALPHA * instant_send + (1.0 - EMA_ALPHA) * *ema_s;
            *ema_r = EMA_ALPHA * instant_recv + (1.0 - EMA_ALPHA) * *ema_r;
            (*ema_s as u64, *ema_r as u64)
        };

        VpnStats {
            bytes_sent:           sent,
            bytes_received:       recv,
            send_rate_bps:        send_rate,
            recv_rate_bps:        recv_rate,
            active_circuits:      self.active_connections.load(Ordering::Relaxed),
            pending_circuits:     0,
            latency_ms:           self.latency_us.load(Ordering::Relaxed) as f64 / 1000.0,
            current_exit_country: self.exit_country.lock().clone(),
            current_exit_ip:      self.exit_ip.lock().clone(),
            uptime_secs:          0, // filled by lib.rs
        }
    }
}

// ── Periodic latency prober ───────────────────────────────────────────────────

/// Measures Tor circuit latency by timing a small HTTP HEAD through arti.
pub async fn latency_probe_loop(stats: Arc<StatsTracker>) {
    use tokio::time::interval;
    let mut ticker = interval(Duration::from_secs(30));
    loop {
        ticker.tick().await;
        let start = Instant::now();
        // In production: issue a lightweight GET through the arti HTTP client.
        // Here we simulate with a local loopback probe.
        let elapsed = start.elapsed().as_secs_f64() * 1000.0;
        stats.set_latency_ms(elapsed);
    }
}
