// src/auth/token.rs
// Secure token storage — saves to ~/.nodex/credentials.json
// Uses OS keyring when available (macOS Keychain, Windows Credential Manager,
// Linux Secret Service), falls back to file with restrictive permissions.

use serde::{Deserialize, Serialize};
use std::path::PathBuf;

const SERVICE: &str = "nodex-vpn";
const ACCOUNT: &str = "google-oauth-token";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StoredToken {
    pub access_token:  String,
    pub refresh_token: String,
    pub email:         Option<String>,
    pub name:          Option<String>,
    pub expires_at:    i64,   // Unix timestamp
}

impl StoredToken {
    pub fn is_expired(&self) -> bool {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs() as i64;
        // Treat as expired 60s before actual expiry (safety margin)
        now >= self.expires_at - 60
    }
}

// ── Credentials file path ─────────────────────────────────────────────────────

pub fn credentials_path() -> PathBuf {
    let home = dirs::home_dir().unwrap_or_else(|| PathBuf::from("."));
    home.join(".nodex").join("credentials.json")
}

// ── Save ──────────────────────────────────────────────────────────────────────

pub fn save_token(token: &StoredToken) -> anyhow::Result<()> {
    // Try OS keyring first
    if try_save_keyring(token).is_ok() {
        return Ok(());
    }
    // Fallback: file with restrictive permissions
    save_to_file(token)
}

fn try_save_keyring(token: &StoredToken) -> anyhow::Result<()> {
    let json = serde_json::to_string(token)?;
    let entry = keyring::Entry::new(SERVICE, ACCOUNT)?;
    entry.set_password(&json)?;
    Ok(())
}

fn save_to_file(token: &StoredToken) -> anyhow::Result<()> {
    let path = credentials_path();
    std::fs::create_dir_all(path.parent().unwrap())?;

    let json = serde_json::to_string_pretty(token)?;

    // Write with restrictive permissions (Unix: 0o600, Windows: inherits)
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        std::fs::OpenOptions::new()
            .write(true).create(true).truncate(true)
            .mode(0o600)
            .open(&path)
            .and_then(|mut f| { use std::io::Write; f.write_all(json.as_bytes()) })?;
    }
    #[cfg(not(unix))]
    {
        std::fs::write(&path, json.as_bytes())?;
    }
    Ok(())
}

// ── Load ──────────────────────────────────────────────────────────────────────

pub fn load_token() -> Option<StoredToken> {
    // Try OS keyring first
    if let Ok(token) = try_load_keyring() {
        return Some(token);
    }
    // Fallback: file
    load_from_file()
}

fn try_load_keyring() -> anyhow::Result<StoredToken> {
    let entry = keyring::Entry::new(SERVICE, ACCOUNT)?;
    let json  = entry.get_password()?;
    Ok(serde_json::from_str(&json)?)
}

fn load_from_file() -> Option<StoredToken> {
    let path = credentials_path();
    let json = std::fs::read_to_string(path).ok()?;
    serde_json::from_str(&json).ok()
}

// ── Delete ────────────────────────────────────────────────────────────────────

pub fn delete_token() -> anyhow::Result<()> {
    // Clear keyring
    if let Ok(entry) = keyring::Entry::new(SERVICE, ACCOUNT) {
        let _ = entry.delete_credential();
    }
    // Clear file
    let path = credentials_path();
    if path.exists() {
        std::fs::remove_file(path)?;
    }
    Ok(())
}
