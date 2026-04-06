// rust-core/src/logging.rs
//! Logging initialisation + ring-buffer for recent log lines.

use crate::LogLevel;

use log::LevelFilter;
use once_cell::sync::Lazy;
use parking_lot::Mutex;
use std::collections::VecDeque;

const MAX_BUFFERED: usize = 1_000;

static LOG_BUFFER: Lazy<Mutex<VecDeque<String>>> =
    Lazy::new(|| Mutex::new(VecDeque::with_capacity(MAX_BUFFERED)));

struct BufferedLogger {
    inner: env_logger::Logger,
}

impl log::Log for BufferedLogger {
    fn enabled(&self, meta: &log::Metadata) -> bool {
        self.inner.enabled(meta)
    }

    fn log(&self, record: &log::Record) {
        if self.enabled(record.metadata()) {
            self.inner.log(record);
            // Also push to ring buffer
            let line = format!(
                "[{}] [{}] {}",
                chrono::Local::now().format("%H:%M:%S"),
                record.level(),
                record.args(),
            );
            let mut buf = LOG_BUFFER.lock();
            if buf.len() >= MAX_BUFFERED { buf.pop_front(); }
            buf.push_back(line);
        }
    }

    fn flush(&self) { self.inner.flush(); }
}

pub fn init(level: &LogLevel) {
    let filter = to_filter(level);
    let inner = env_logger::Builder::new()
        .filter_level(filter)
        .format_timestamp_millis()
        .build();

    let logger = Box::new(BufferedLogger { inner });
    let _ = log::set_boxed_logger(logger);
    log::set_max_level(filter);
}

pub fn set_level(level: &LogLevel) {
    log::set_max_level(to_filter(level));
}

pub fn recent_logs(max_lines: usize) -> String {
    LOG_BUFFER.lock()
        .iter()
        .rev()
        .take(max_lines)
        .cloned()
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect::<Vec<_>>()
        .join("\n")
}

fn to_filter(level: &LogLevel) -> LevelFilter {
    match level {
        LogLevel::Error => LevelFilter::Error,
        LogLevel::Warn  => LevelFilter::Warn,
        LogLevel::Info  => LevelFilter::Info,
        LogLevel::Debug => LevelFilter::Debug,
        LogLevel::Trace => LevelFilter::Trace,
    }
}
