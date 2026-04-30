// src/auth/mod.rs
// Public auth API used by CLI commands.
// Reads Google OAuth2 credentials from environment variables or
// bundled build-time constants (injected by build.rs from GitHub Secrets).

pub mod oauth;
pub mod token;

use anyhow::{anyhow, Result};
use token::StoredToken;

// ── Credential sources ────────────────────────────────────────────────────────
// Priority:
//   1. NODEX_GOOGLE_CLIENT_ID / NODEX_GOOGLE_CLIENT_SECRET env vars (dev/test)
//   2. Build-time constants injected by build.rs (production release)

fn client_id() -> Result<String> {
    if let Ok(v) = std::env::var("NODEX_GOOGLE_CLIENT_ID") {
        return Ok(v);
    }
    let id = env!("NODEX_GOOGLE_CLIENT_ID");
    if id.is_empty() || id == "PLACEHOLDER" {
        return Err(anyhow!(
            "Google OAuth2 Client ID not configured.\n\
             Set NODEX_GOOGLE_CLIENT_ID env var or rebuild with secrets."
        ));
    }
    Ok(id.to_string())
}

fn client_secret() -> Result<String> {
    if let Ok(v) = std::env::var("NODEX_GOOGLE_CLIENT_SECRET") {
        return Ok(v);
    }
    let secret = env!("NODEX_GOOGLE_CLIENT_SECRET");
    if secret.is_empty() || secret == "PLACEHOLDER" {
        return Err(anyhow!(
            "Google OAuth2 Client Secret not configured.\n\
             Set NODEX_GOOGLE_CLIENT_SECRET env var or rebuild with secrets."
        ));
    }
    Ok(secret.to_string())
}

// ── Public API ────────────────────────────────────────────────────────────────

/// Run the full login flow. Opens browser, waits for callback, saves token.
/// Returns the stored token on success.
pub fn login() -> Result<StoredToken> {
    let cid = client_id()?;
    let csecret = client_secret()?;

    let auth_req = oauth::build_auth_url(&cid);

    // Open browser
    if open::that(&auth_req.url).is_err() {
        // Fallback: print URL for manual open (headless/Termux)
        println!("\n  Open this URL in your browser:\n\n  {}\n", auth_req.url);
    }

    // Wait for callback
    let code = oauth::wait_for_callback(&auth_req.state)?;

    // Exchange code for tokens
    let stored = oauth::exchange_code(&cid, &csecret, &code, &auth_req.code_verifier)?;

    // Save
    token::save_token(&stored)?;

    Ok(stored)
}

/// Load and return the current token, refreshing if expired.
/// Returns None if not logged in.
pub fn get_valid_token() -> Option<StoredToken> {
    let mut stored = token::load_token()?;

    if stored.is_expired() {
        // Try to refresh
        let cid     = client_id().ok()?;
        let csecret = client_secret().ok()?;
        match oauth::refresh_token(&cid, &csecret, &stored.refresh_token) {
            Ok(refreshed) => {
                let _ = token::save_token(&refreshed);
                stored = refreshed;
            }
            Err(_) => {
                // Refresh failed — token is dead, delete it
                let _ = token::delete_token();
                return None;
            }
        }
    }

    Some(stored)
}

/// Returns true if a valid (non-expired or refreshable) token exists.
pub fn is_authenticated() -> bool {
    get_valid_token().is_some()
}

/// Delete saved credentials.
pub fn logout() -> Result<()> {
    token::delete_token()
}

/// Return current user info without refreshing.
pub fn current_user() -> Option<StoredToken> {
    token::load_token()
}
