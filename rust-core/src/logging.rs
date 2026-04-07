// rust-core/src/logging.rs — Ring-buffer logger
use crate::LogLevel;
use log::LevelFilter;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use std::collections::VecDeque;

const MAX_LINES: usize = 1_000;

static BUFFER: Lazy<Mutex<VecDeque<String>>> =
    Lazy::new(|| Mutex::new(VecDeque::with_capacity(MAX_LINES)));

struct BufLogger { inner: env_logger::Logger }

impl log::Log for BufLogger {
    fn enabled(&self, m: &log::Metadata) -> bool { self.inner.enabled(m) }
    fn flush(&self) { self.inner.flush() }
    fn log(&self, r: &log::Record) {
        if !self.enabled(r.metadata()) { return; }
        self.inner.log(r);
        let ts = chrono::Local::now().format("%H:%M:%S%.3f");
        let line = format!("[{ts}] [{:<5}] {}", r.level(), r.args());
        let mut buf = BUFFER.lock();
        if buf.len() >= MAX_LINES { buf.pop_front(); }
        buf.push_back(line);
    }
}

pub fn init(level: &LogLevel) {
    let filter = to_filter(level);
    let inner  = env_logger::Builder::new()
        .filter_level(filter)
        .format_timestamp_millis()
        .build();
    let _ = log::set_boxed_logger(Box::new(BufLogger { inner }));
    log::set_max_level(filter);
}

pub fn set_level(level: &LogLevel) { log::set_max_level(to_filter(level)); }

pub fn recent_logs(n: usize) -> String {
    BUFFER.lock().iter().rev().take(n).cloned()
        .collect::<Vec<_>>().into_iter().rev()
        .collect::<Vec<_>>().join("\n")
}

fn to_filter(l: &LogLevel) -> LevelFilter {
    match l {
        LogLevel::Error => LevelFilter::Error,
        LogLevel::Warn  => LevelFilter::Warn,
        LogLevel::Info  => LevelFilter::Info,
        LogLevel::Debug => LevelFilter::Debug,
        LogLevel::Trace => LevelFilter::Trace,
    }
}
