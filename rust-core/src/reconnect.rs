// src/reconnect.rs
// NodeX VPN Auto-reconnect
//
// Monitors the VPN connection and automatically reconnects if it drops.
// Uses exponential backoff: 2s → 4s → 8s → 16s → 32s (max 60s).
// After 5 consecutive failures, emits a ReconnectFailed event.
//
// The kill switch stays ENABLED during reconnect — traffic stays blocked
// until the circuit is restored. This is the correct behavior.

use std::sync::{Arc, atomic::{AtomicBool, AtomicU32, Ordering}};
use std::time::Duration;
use tokio::time::sleep;

const INITIAL_DELAY_SECS: u64 = 2;
const MAX_DELAY_SECS:     u64 = 60;
const MAX_ATTEMPTS:       u32 = 10;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReconnectEvent {
    Attempting { attempt: u32, delay_secs: u64 },
    Success    { attempt: u32 },
    Failed     { total_attempts: u32 },
}

pub struct Reconnector {
    active:   Arc<AtomicBool>,
    attempts: Arc<AtomicU32>,
    stopped:  Arc<AtomicBool>,
}

impl Reconnector {
    pub fn new() -> Self {
        Self {
            active:   Arc::new(AtomicBool::new(false)),
            attempts: Arc::new(AtomicU32::new(0)),
            stopped:  Arc::new(AtomicBool::new(false)),
        }
    }

    /// Total reconnect attempts made since last successful connect
    pub fn attempt_count(&self) -> u32 {
        self.attempts.load(Ordering::Relaxed)
    }

    /// Whether a reconnect loop is currently running
    pub fn is_active(&self) -> bool {
        self.active.load(Ordering::Relaxed)
    }

    /// Stop any running reconnect loop
    pub fn stop(&self) {
        self.stopped.store(true, Ordering::Relaxed);
    }

    /// Reset state after a successful connect
    pub fn reset(&self) {
        self.attempts.store(0, Ordering::Relaxed);
        self.stopped.store(false, Ordering::Relaxed);
        self.active.store(false, Ordering::Relaxed);
    }

    /// Start the reconnect loop.
    /// `connect_fn` — async closure that attempts to reconnect, returns Ok(()) on success.
    /// `on_event`   — callback for UI/log events.
    ///
    /// Returns Ok(()) when reconnect succeeds, Err when max attempts exhausted.
    pub async fn run<F, Fut, E>(
        &self,
        connect_fn: F,
        on_event:   impl Fn(ReconnectEvent) + Send + 'static,
    ) -> Result<(), anyhow::Error>
    where
        F:   Fn() -> Fut + Send + Sync + 'static,
        Fut: std::future::Future<Output = Result<(), E>> + Send,
        E:   std::fmt::Debug,
    {
        self.active.store(true, Ordering::Relaxed);

        let mut delay = INITIAL_DELAY_SECS;
        let mut attempt = 0u32;

        loop {
            if self.stopped.load(Ordering::Relaxed) {
                self.active.store(false, Ordering::Relaxed);
                return Err(anyhow::anyhow!("Reconnect stopped by user"));
            }

            attempt += 1;
            self.attempts.fetch_add(1, Ordering::Relaxed);

            if attempt > MAX_ATTEMPTS {
                on_event(ReconnectEvent::Failed { total_attempts: attempt - 1 });
                self.active.store(false, Ordering::Relaxed);
                return Err(anyhow::anyhow!(
                    "Reconnect failed after {} attempts", attempt - 1
                ));
            }

            on_event(ReconnectEvent::Attempting { attempt, delay_secs: delay });
            log::info!("Reconnect attempt {attempt}/{MAX_ATTEMPTS} in {delay}s…");

            sleep(Duration::from_secs(delay)).await;

            // Check again after sleep in case user stopped us
            if self.stopped.load(Ordering::Relaxed) {
                self.active.store(false, Ordering::Relaxed);
                return Err(anyhow::anyhow!("Reconnect stopped by user"));
            }

            match connect_fn().await {
                Ok(_) => {
                    on_event(ReconnectEvent::Success { attempt });
                    log::info!("Reconnect succeeded on attempt {attempt}");
                    self.reset();
                    return Ok(());
                }
                Err(e) => {
                    log::warn!("Reconnect attempt {attempt} failed: {e:?}");
                    // Exponential backoff, capped at MAX_DELAY_SECS
                    delay = (delay * 2).min(MAX_DELAY_SECS);
                }
            }
        }
    }
}

impl Default for Reconnector {
    fn default() -> Self { Self::new() }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_reconnect_success_on_second_attempt() {
        let reconnector = Reconnector::new();
        let call_count = Arc::new(AtomicU32::new(0));
        let cc = call_count.clone();

        let result = reconnector.run(
            move || {
                let c = cc.clone();
                async move {
                    let n = c.fetch_add(1, Ordering::Relaxed);
                    if n < 1 { Err("not yet") } else { Ok(()) }
                }
            },
            |_event| {},
        ).await;

        assert!(result.is_ok());
        assert_eq!(call_count.load(Ordering::Relaxed), 2);
    }

    #[tokio::test]
    async fn test_stop_cancels_reconnect() {
        let reconnector = Arc::new(Reconnector::new());
        let r2 = reconnector.clone();

        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(100)).await;
            r2.stop();
        });

        let result = reconnector.run(
            || async { Err::<(), &str>("always fails") },
            |_| {},
        ).await;

        assert!(result.is_err());
    }
}
