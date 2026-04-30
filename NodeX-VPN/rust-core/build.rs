// rust-core/build.rs
// 1. Generates Kotlin/Swift/C bindings from src/nodex_vpn.udl via UniFFI.
// 2. Injects BUILD_TARGET and BUILD_DATE env vars for the CLI banner.

use std::process::Command;

fn main() {
    // ── UniFFI scaffolding ────────────────────────────────────────────────────
    println!("cargo:rerun-if-changed=src/nodex_vpn.udl");
    println!("cargo:rerun-if-changed=src/lib.rs");
    uniffi::generate_scaffolding("src/nodex_vpn.udl").unwrap();

    // ── CLI version metadata ──────────────────────────────────────────────────
    // BUILD_TARGET: the Rust target triple being compiled (e.g. aarch64-linux-android)
    let target = std::env::var("TARGET").unwrap_or_else(|_| "unknown".into());
    println!("cargo:rustc-env=BUILD_TARGET={target}");

    // BUILD_DATE: ISO 8601 date of the build (UTC)
    // Use SOURCE_DATE_EPOCH when set (reproducible builds).
    let date = build_date();
    println!("cargo:rustc-env=BUILD_DATE={date}");

    // Re-run if these env vars change
    println!("cargo:rerun-if-env-changed=SOURCE_DATE_EPOCH");

    // ── Google OAuth2 credentials (injected from GitHub Secrets) ─────────────
    // In CI:  set via secrets.GOOGLE_CLIENT_ID / secrets.GOOGLE_CLIENT_SECRET
    // Locally: set NODEX_GOOGLE_CLIENT_ID / NODEX_GOOGLE_CLIENT_SECRET env vars
    let client_id = std::env::var("NODEX_GOOGLE_CLIENT_ID")
        .unwrap_or_else(|_| "PLACEHOLDER".into());
    let client_secret = std::env::var("NODEX_GOOGLE_CLIENT_SECRET")
        .unwrap_or_else(|_| "PLACEHOLDER".into());
    println!("cargo:rustc-env=NODEX_GOOGLE_CLIENT_ID={client_id}");
    println!("cargo:rustc-env=NODEX_GOOGLE_CLIENT_SECRET={client_secret}");
    println!("cargo:rerun-if-env-changed=NODEX_GOOGLE_CLIENT_ID");
    println!("cargo:rerun-if-env-changed=NODEX_GOOGLE_CLIENT_SECRET");
}

fn build_date() -> String {
    // Reproducible-build friendly: honour SOURCE_DATE_EPOCH if set
    if let Ok(epoch) = std::env::var("SOURCE_DATE_EPOCH") {
        if let Ok(secs) = epoch.trim().parse::<i64>() {
            return epoch_to_iso(secs);
        }
    }

    // Try `date -u +%Y-%m-%d` (Linux / macOS / Termux / FreeBSD)
    if let Ok(out) = Command::new("date").args(["-u", "+%Y-%m-%d"]).output() {
        let s = String::from_utf8_lossy(&out.stdout).trim().to_string();
        if s.len() == 10 { return s; }
    }

    // Windows fallback via PowerShell
    if let Ok(out) = Command::new("powershell")
        .args(["-Command", "(Get-Date).ToUniversalTime().ToString('yyyy-MM-dd')"])
        .output()
    {
        let s = String::from_utf8_lossy(&out.stdout).trim().to_string();
        if s.len() == 10 { return s; }
    }

    "unknown".into()
}

/// Convert a Unix timestamp to YYYY-MM-DD (no external crates needed).
fn epoch_to_iso(secs: i64) -> String {
    // Days since 1970-01-01
    let days = (secs / 86_400) as i32;
    let mut y = 1970i32;
    let mut rem = days;
    loop {
        let dy = days_in_year(y);
        if rem < dy { break; }
        rem -= dy;
        y += 1;
    }
    let mut m = 1i32;
    loop {
        let dm = days_in_month(y, m);
        if rem < dm { break; }
        rem -= dm;
        m += 1;
    }
    let d = rem + 1;
    format!("{y:04}-{m:02}-{d:02}")
}

fn is_leap(y: i32) -> bool { (y % 4 == 0 && y % 100 != 0) || y % 400 == 0 }
fn days_in_year(y: i32) -> i32 { if is_leap(y) { 366 } else { 365 } }
fn days_in_month(y: i32, m: i32) -> i32 {
    match m {
        1|3|5|7|8|10|12 => 31,
        4|6|9|11        => 30,
        2               => if is_leap(y) { 29 } else { 28 },
        _               => 0,
    }
}
