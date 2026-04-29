// src/bin/nodex.rs — NodeX VPN CLI
// Powered by AeonCoreX
//
// Supported platforms: Linux · macOS · Windows · Termux (Android)
//
// Build:  cargo build --release --features cli --bin nodex
// Usage:  nodex connect [--country DE] [--bridge "obfs4 ..."]

use clap::{Parser, Subcommand};
use colored::Colorize;
use is_terminal::IsTerminal;
use crate::auth;
use crate::{
    start_nodex, stop_nodex, is_running,
    get_bootstrap_status, get_real_time_stats,
    get_available_nodes, set_exit_node,
    get_recent_logs, set_log_level,
    VpnConfig, LogLevel,
};
use std::{
    io::{self, Write},
    time::Duration,
};

// ── Version constants (injected by build.rs) ──────────────────────────────────

const VERSION:      &str = env!("CARGO_PKG_VERSION");
const DESCRIPTION:  &str = env!("CARGO_PKG_DESCRIPTION");
const BUILD_TARGET: &str = env!("BUILD_TARGET");
const BUILD_DATE:   &str = env!("BUILD_DATE");

// ── CLI definition ────────────────────────────────────────────────────────────

#[derive(Parser)]
#[command(
    name    = "nodex",
    version = VERSION,
    about   = "NodeX VPN — Tor-based privacy VPN by AeonCoreX",
    long_about = None,
    disable_version_flag = true,
)]
struct Cli {
    /// Suppress banner and color output (also set NO_COLOR=1)
    #[arg(long, global = true)]
    quiet: bool,

    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Connect to NodeX VPN and show live status
    Connect {
        /// Exit country ISO code (e.g. US, DE, NL, JP, GB)
        #[arg(short, long, value_name = "ISO")]
        country: Option<String>,

        /// Bridge line for censored networks (repeatable)
        /// e.g. "obfs4 1.2.3.4:443 FINGERPRINT cert=... iat-mode=0"
        #[arg(short, long, value_name = "LINE")]
        bridge: Vec<String>,

        /// SOCKS5 proxy listen address
        #[arg(long, default_value = "127.0.0.1:9050", value_name = "ADDR")]
        socks: String,

        /// DNS-over-Tor listen address
        #[arg(long, default_value = "127.0.0.1:5353", value_name = "ADDR")]
        dns: String,

        /// State directory for Tor data
        #[arg(long, default_value = "~/.nodex/state", value_name = "DIR")]
        state_dir: String,

        /// Cache directory for Tor data
        #[arg(long, default_value = "~/.nodex/cache", value_name = "DIR")]
        cache_dir: String,

        /// Enable verbose / debug logging
        #[arg(short, long)]
        verbose: bool,
    },

    /// Show current connection status and live stats
    Status,

    /// List available VPN server nodes
    Nodes {
        /// Filter by country ISO code (e.g. --country NL)
        #[arg(short, long, value_name = "ISO")]
        country: Option<String>,

        /// Show only bridge-capable nodes
        #[arg(short, long)]
        bridges: bool,
    },

    /// Show recent log output
    Logs {
        /// Number of lines to display
        #[arg(short, long, default_value = "50", value_name = "N")]
        lines: u32,
    },

    /// Show version and build information
    Version,

    /// Log in with your Google account
    Login,

    /// Log out and remove saved credentials
    Logout,

    /// Show currently logged-in account
    Whoami,
}

// ── Color support ─────────────────────────────────────────────────────────────

fn colors_enabled() -> bool {
    if std::env::var("NO_COLOR").is_ok() { return false; }
    if !io::stdout().is_terminal()       { return false; }
    #[cfg(windows)] { enable_windows_ansi(); }
    true
}

#[cfg(windows)]
fn enable_windows_ansi() {
    // Enable ENABLE_VIRTUAL_TERMINAL_PROCESSING (0x0004) on Windows console
    use std::os::windows::io::AsRawHandle;
    extern "system" {
        fn GetConsoleMode(h: *mut std::ffi::c_void, m: *mut u32) -> i32;
        fn SetConsoleMode(h: *mut std::ffi::c_void, m: u32) -> i32;
    }
    unsafe {
        let h = io::stdout().as_raw_handle() as *mut std::ffi::c_void;
        let mut mode: u32 = 0;
        if GetConsoleMode(h, &mut mode) != 0 {
            SetConsoleMode(h, mode | 0x0004);
        }
    }
}

// ── Banner ────────────────────────────────────────────────────────────────────

const LOGO: &[&str] = &[
    r"  ███╗   ██╗ ██████╗ ██████╗ ███████╗██╗  ██╗",
    r"  ████╗  ██║██╔═══██╗██╔══██╗██╔════╝╚██╗██╔╝",
    r"  ██╔██╗ ██║██║   ██║██║  ██║█████╗   ╚███╔╝ ",
    r"  ██║╚██╗██║██║   ██║██║  ██║██╔══╝   ██╔██╗ ",
    r"  ██║ ╚████║╚██████╔╝██████╔╝███████╗██╔╝ ██╗",
    r"  ╚═╝  ╚═══╝ ╚═════╝ ╚═════╝ ╚══════╝╚═╝  ╚═╝",
];

fn print_banner(quiet: bool) {
    if quiet { return; }
    let c = colors_enabled();

    // Width to match the logo (46 chars) + 4 padding each side = 54
    let W: usize = 54;
    let border = |ch: &str| format!("  ╠{}╣", ch.repeat(W));
    let top    = format!("  ╔{}╗", "═".repeat(W));
    let bot    = format!("  ╚{}╝", "═".repeat(W));
    let mid    = |s: &str| format!("  ║{:^W$}║", s);
    let div    = border("═");

    macro_rules! ln {
        ($s:expr) => {
            if c { println!("{}", $s.cyan().bold()); }
            else { println!("{}", $s); }
        };
    }

    ln!(top);
    ln!(mid(""));

    for line in LOGO {
        let row = format!("  ║{:<W$}║", format!("    {line}"));
        if c { println!("{}", row.cyan().bold()); }
        else { println!("{row}"); }
    }

    let vpn_row = mid("  ·  V  P  N  ·");
    if c { println!("{}", vpn_row.bright_blue().bold()); }
    else { println!("{vpn_row}"); }

    ln!(mid(""));
    ln!(div);

    let brand_row = mid("Powered by AeonCoreX  ·  Tor-based Privacy VPN");
    if c { println!("{}", brand_row.white()); }
    else { println!("{brand_row}"); }

    let ver_row = mid(&format!("v{}  ·  {}  ·  {}", VERSION, BUILD_TARGET, BUILD_DATE));
    if c { println!("{}", ver_row.bright_black()); }
    else { println!("{ver_row}"); }

    ln!(mid(""));
    ln!(bot);
    println!();
}

// ── Entry point ───────────────────────────────────────────────────────────────

fn main() {
    let cli = Cli::parse();
    let quiet = cli.quiet;

    match cli.command {
        Commands::Version => { print_banner(false); cmd_version(); }
        Commands::Connect { country, bridge, socks, dns, state_dir, cache_dir, verbose } => {
            print_banner(quiet);
            cmd_connect(country, bridge, socks, dns, state_dir, cache_dir, verbose, quiet);
        }
        Commands::Status                       => { print_banner(quiet); cmd_status(); }
        Commands::Nodes { country, bridges }   => { print_banner(quiet); cmd_nodes(country, bridges); }
        Commands::Logs  { lines }              => cmd_logs(lines),
        Commands::Login                        => { print_banner(quiet); cmd_login(quiet); }
        Commands::Logout                       => cmd_logout(),
        Commands::Whoami                       => cmd_whoami(),
    }
}

// ── version ───────────────────────────────────────────────────────────────────

fn cmd_version() {
    let c = colors_enabled();
    let row = |label: &str, value: &str| {
        if c { println!("  {}  {}", label.bright_black(), value.white().bold()); }
        else { println!("  {label}:  {value}"); }
    };
    row("CLI Version ", VERSION);
    row("Build target", BUILD_TARGET);
    row("Build date  ", BUILD_DATE);
    row("Description ", DESCRIPTION);
    row("Vendor      ", "AeonCoreX");
    row("License     ", "MIT");
    println!();
    if c {
        println!("  {}  {}", "Releases".bright_black(),
            "https://github.com/AeonCoreX/NodeX-VPN/releases".cyan());
    } else {
        println!("  Releases:  https://github.com/AeonCoreX/NodeX-VPN/releases");
    }
    println!();
}

// ── connect ───────────────────────────────────────────────────────────────────

fn cmd_connect(
    country: Option<String>, bridges: Vec<String>,
    socks: String, dns: String,
    state_dir: String, cache_dir: String,
    verbose: bool, quiet: bool,
) {
    let c = colors_enabled();

    if is_running() { die("NodeX VPN is already running.", c); }

    // Expand ~ in paths (works on Linux, macOS, Termux)
    let state_dir = expand_tilde(&state_dir);
    let cache_dir = expand_tilde(&cache_dir);

    for dir in [&state_dir, &cache_dir] {
        std::fs::create_dir_all(dir).unwrap_or_else(|e| {
            die(&format!("Cannot create dir {dir}: {e}"), c);
        });
    }

    let use_bridges   = !bridges.is_empty();
    let preferred_iso = country.clone().map(|s| s.to_uppercase());

    let config = VpnConfig {
        socks_listen_addr:          socks.clone(),
        dns_listen_addr:            dns.clone(),
        use_bridges,
        bridge_lines:               bridges.clone(),
        strict_exit_nodes:          preferred_iso.is_some(),
        preferred_exit_iso:         preferred_iso.clone(),
        circuit_build_timeout_secs: 60,
        state_dir, cache_dir,
    };

    set_log_level(if verbose { LogLevel::Debug } else { LogLevel::Warn });

    if !quiet {
        info_row("Exit country", &preferred_iso.clone().unwrap_or_else(|| "automatic".into()), c);
        info_row("SOCKS5 proxy", &socks, c);
        info_row("DNS  (Tor)  ", &dns, c);
        if use_bridges {
            info_row("Bridges     ", &format!("{} line(s) configured", bridges.len()), c);
        }
        println!();
    }

    step("Starting Tor engine…", c);
    if let Err(e) = start_nodex(config) { die(&format!("Engine failed: {e:?}"), c); }
    good("Engine started", c);
    println!();

    // ── Bootstrap progress bar ────────────────────────────────────────────────
    let bootstrap_ok = loop {
        let bs = get_bootstrap_status();

        let filled = (bs.percent as usize) * 30 / 100;
        let bar_inner = format!("{}{}", "█".repeat(filled), "░".repeat(30 - filled));
        let bar_str = if c { format!("[{}]", bar_inner.cyan()) }
                      else { format!("[{bar_inner}]") };
        let pct_str = if c { format!("{:>3}%", bs.percent).bold().to_string() }
                      else { format!("{:>3}%", bs.percent) };

        print!("\r  {} {} {:<40}", bar_str, pct_str, bs.phase);
        io::stdout().flush().ok();

        if bs.is_complete {
            let done = if c { "Connected!".green().bold().to_string() } else { "Connected!".into() };
            println!("\r  [{}] 100%  {:<44}", "█".repeat(30), done);
            break true;
        }
        if let Some(ref e) = bs.error_message {
            println!();
            die(&format!("Bootstrap failed: {e}"), c);
        }

        std::thread::sleep(Duration::from_millis(250));
    };

    if !bootstrap_ok { let _ = stop_nodex(); std::process::exit(1); }

    println!();
    if c {
        println!("  {} Connected via Tor network", "✔".green().bold());
        println!("  {} Press {} to disconnect.", "›".bright_black(), "Ctrl+C".yellow().bold());
    } else {
        println!("  [OK] Connected via Tor network");
        println!("  Press Ctrl+C to disconnect.");
    }
    println!();

    ctrlc::set_handler(move || {
        println!();
        if c { println!("  {} Disconnecting…", "›".bright_black()); }
        else  { println!("  Disconnecting..."); }
        let _ = stop_nodex();
    }).ok();

    // ── Live stats header ─────────────────────────────────────────────────────
    let hdr = format!("  {:<16} {:<16} {:<12} {:<12} {:<8}",
        "↑ Upload", "↓ Download", "Latency", "Uptime", "Country");
    if c { println!("{}", hdr.bright_black()); }
    else { println!("{hdr}"); }

    loop {
        if !is_running() { break; }
        let st = get_real_time_stats();

        let up  = fmt_rate(st.send_rate_bps);
        let dn  = fmt_rate(st.recv_rate_bps);
        let lat = format!("{:.0} ms", st.latency_ms);
        let upt = fmt_duration(st.uptime_secs);
        let cc  = if st.current_exit_country.is_empty() { "—".into() }
                  else { st.current_exit_country.clone() };

        if c {
            print!("\r  {:<16} {:<16} {:<12} {:<12} {:<8}",
                up.green().to_string(),
                dn.cyan().to_string(),
                lat.yellow().to_string(),
                upt,
                cc.white().bold().to_string(),
            );
        } else {
            print!("\r  {:<16} {:<16} {:<12} {:<12} {:<8}", up, dn, lat, upt, cc);
        }
        io::stdout().flush().ok();
        std::thread::sleep(Duration::from_secs(1));
    }

    println!();
    if c { println!("  {} Disconnected.", "✔".green().bold()); }
    else  { println!("  Disconnected."); }
}

// ── status ────────────────────────────────────────────────────────────────────

fn cmd_status() {
    let c = colors_enabled();
    if !is_running() {
        if c { println!("  {} Not connected.", "○".bright_black()); }
        else  { println!("  Status: disconnected"); }
        return;
    }
    let bs = get_bootstrap_status();
    let st = get_real_time_stats();
    let conn = if bs.is_complete {
        if c { "✔ Connected".green().bold().to_string() } else { "Connected".into() }
    } else {
        if c { "⏳ Connecting".yellow().to_string() } else { "Connecting".into() }
    };
    println!();
    status_row("Status      ", &conn, c);
    status_row("Bootstrap   ", &format!("{}%  {}", bs.percent, bs.phase), c);
    if !st.current_exit_country.is_empty() { status_row("Exit country", &st.current_exit_country, c); }
    if !st.current_exit_ip.is_empty()      { status_row("Exit IP     ", &st.current_exit_ip, c); }
    status_row("Uptime      ", &fmt_duration(st.uptime_secs), c);
    status_row("↑ Upload    ", &fmt_rate(st.send_rate_bps), c);
    status_row("↓ Download  ", &fmt_rate(st.recv_rate_bps), c);
    status_row("Sent total  ", &fmt_bytes(st.bytes_sent), c);
    status_row("Recv total  ", &fmt_bytes(st.bytes_received), c);
    status_row("Latency     ", &format!("{:.0} ms", st.latency_ms), c);
    status_row("Circuits    ", &format!("{} active  {} pending",
        st.active_circuits, st.pending_circuits), c);
    println!();
}

// ── nodes ─────────────────────────────────────────────────────────────────────

fn cmd_nodes(filter_country: Option<String>, only_bridges: bool) {
    let c = colors_enabled();
    let nodes = get_available_nodes();
    let filtered: Vec<_> = nodes.iter().filter(|n| {
        if only_bridges && !n.is_bridge { return false; }
        if let Some(ref iso) = filter_country { n.country_code.eq_ignore_ascii_case(iso) }
        else { true }
    }).collect();

    if filtered.is_empty() { println!("  No nodes match the filter."); return; }

    println!();
    let hdr = format!("  {:<10} {:<5} {:<20} {:<16} {:>9}  {:>5}  {:<6}",
        "ID", "CC", "Country", "City", "Latency", "Load", "Bridge");
    if c { println!("{}", hdr.bright_black()); println!("{}", "  ".to_owned() + &"─".repeat(72)); }
    else  { println!("{hdr}"); println!("  {}", "─".repeat(72)); }

    for n in &filtered {
        let lat = format!("{:.0} ms", n.latency_ms);
        let load = match n.load_percent {
            0..=40  => if c { format!("{:>4}%", n.load_percent).green().to_string() }
                       else { format!("{:>4}%", n.load_percent) },
            41..=70 => if c { format!("{:>4}%", n.load_percent).yellow().to_string() }
                       else { format!("{:>4}%", n.load_percent) },
            _       => if c { format!("{:>4}%", n.load_percent).red().to_string() }
                       else { format!("{:>4}%", n.load_percent) },
        };
        let br = if n.is_bridge {
            if c { "  ✔".cyan().to_string() } else { "  Y".into() }
        } else { "   ".into() };
        println!("  {:<10} {:<5} {:<20} {:<16} {:>9}  {}  {}",
            n.id, n.country_code, n.country_name, n.city, lat, load, br);
    }
    let foot = format!("  {} node(s) listed.", filtered.len());
    println!();
    if c { println!("{}", foot.bright_black()); } else { println!("{foot}"); }
    println!();
}

// ── logs ──────────────────────────────────────────────────────────────────────

fn cmd_logs(lines: u32) {
    let logs = get_recent_logs(lines);
    if logs.trim().is_empty() { println!("  (no logs yet)"); }
    else { print!("{logs}"); }
}

// ── helpers ───────────────────────────────────────────────────────────────────

fn info_row(label: &str, value: &str, c: bool) {
    if c { println!("  {}  {}", format!("{label}:").bright_black(), value.white().bold()); }
    else  { println!("  {label}:  {value}"); }
}
fn status_row(label: &str, value: &str, c: bool) {
    if c { println!("  {}  {}", format!("{label}:").bright_black(), value); }
    else  { println!("  {label}:  {value}"); }
}
fn step(msg: &str, c: bool) {
    if c { println!("  {} {msg}", "›".bright_black()); }
    else  { println!("  > {msg}"); }
}
fn good(msg: &str, c: bool) {
    if c { println!("  {} {msg}", "✔".green().bold()); }
    else  { println!("  [OK] {msg}"); }
}
fn die(msg: &str, c: bool) -> ! {
    if c { eprintln!("  {} {msg}", "✘".red().bold()); }
    else  { eprintln!("  [ERROR] {msg}"); }
    std::process::exit(1);
}

fn expand_tilde(path: &str) -> String {
    if path.starts_with('~') {
        let home = std::env::var("HOME")
            .or_else(|_| std::env::var("USERPROFILE"))
            .unwrap_or_else(|_| ".".into());
        return format!("{home}{}", &path[1..]);
    }
    // Windows %APPDATA% / %LOCALAPPDATA% expansion
    #[cfg(windows)]
    {
        return path
            .replace("%APPDATA%",      &std::env::var("APPDATA").unwrap_or_default())
            .replace("%LOCALAPPDATA%", &std::env::var("LOCALAPPDATA").unwrap_or_default());
    }
    #[allow(unreachable_code)]
    path.to_string()
}

fn fmt_bytes(b: u64) -> String {
    match b {
        n if n >= 1 << 30 => format!("{:.2} GB", n as f64 / (1u64 << 30) as f64),
        n if n >= 1 << 20 => format!("{:.2} MB", n as f64 / (1u64 << 20) as f64),
        n if n >= 1 << 10 => format!("{:.1} KB", n as f64 / (1u64 << 10) as f64),
        n => format!("{n} B"),
    }
}
fn fmt_rate(bps: u64) -> String { format!("{}/s", fmt_bytes(bps)) }
fn fmt_duration(secs: u64) -> String {
    let (h, m, s) = (secs / 3600, (secs % 3600) / 60, secs % 60);
    if h > 0 { format!("{h}h {m:02}m {s:02}s") } else { format!("{m:02}m {s:02}s") }
}

// ── login ─────────────────────────────────────────────────────────────────────

fn cmd_login(quiet: bool) {
    let c = colors_enabled();

    if let Some(user) = auth::current_user() {
        if c {
            println!("  {} Already logged in as {}",
                "✔".green().bold(),
                user.email.as_deref().unwrap_or("").cyan().bold());
        } else {
            println!("  Already logged in as {}", user.email.as_deref().unwrap_or(""));
        }
        println!();
        if c { println!("  {} Run {} to switch accounts.", "›".bright_black(), "nodex logout".yellow()); }
        else  { println!("  Run 'nodex logout' to switch accounts."); }
        println!();
        return;
    }

    if !quiet {
        if c {
            println!("  {} Opening browser for Google Sign-In…", "›".bright_black());
            println!("  {} Waiting on localhost:9005…", "›".bright_black());
        } else {
            println!("  Opening browser for Google Sign-In...");
        }
        println!();
    }

    match auth::login() {
        Ok(user) => {
            println!();
            if c {
                println!("  {} Logged in as {}",
                    "✔".green().bold(),
                    user.email.as_deref().unwrap_or("").cyan().bold());
                if let Some(name) = &user.name {
                    println!("  {}    {}", "Name:".bright_black(), name);
                }
            } else {
                println!("  [OK] Logged in as {}", user.email.as_deref().unwrap_or(""));
            }
            println!();
        }
        Err(e) => {
            println!();
            if c { eprintln!("  {} Login failed: {e}", "✘".red().bold()); }
            else  { eprintln!("  [ERROR] Login failed: {e}"); }
            std::process::exit(1);
        }
    }
}

// ── logout ────────────────────────────────────────────────────────────────────

fn cmd_logout() {
    let c = colors_enabled();
    match auth::logout() {
        Ok(_)  => {
            if c { println!("  {} Logged out successfully.", "✔".green().bold()); }
            else  { println!("  [OK] Logged out."); }
        }
        Err(e) => {
            if c { eprintln!("  {} Logout failed: {e}", "✘".red().bold()); }
            else  { eprintln!("  [ERROR] Logout failed: {e}"); }
            std::process::exit(1);
        }
    }
}

// ── whoami ────────────────────────────────────────────────────────────────────

fn cmd_whoami() {
    let c = colors_enabled();
    match auth::current_user() {
        Some(user) => {
            if c {
                println!("  {}  {}",
                    "Email:".bright_black(),
                    user.email.as_deref().unwrap_or("").cyan().bold());
                if let Some(name) = &user.name {
                    println!("  {}   {}", "Name:".bright_black(), name);
                }
                if user.is_expired() {
                    println!("  {} Token expired — will auto-refresh on next connect.", "⚠".yellow());
                } else {
                    println!("  {} Token valid", "✔".green());
                }
            } else {
                println!("  Email: {}", user.email.as_deref().unwrap_or(""));
                if let Some(name) = &user.name { println!("  Name:  {name}"); }
            }
        }
        None => {
            if c {
                println!("  {} Not logged in.", "○".bright_black());
                println!("  Run {} to authenticate.", "nodex login".cyan().bold());
            } else {
                println!("  Not logged in. Run: nodex login");
            }
        }
    }
    println!();
}
