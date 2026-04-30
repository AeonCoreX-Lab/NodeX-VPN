// shared/src/commonMain/kotlin/com/nodex/vpn/auth/AuthState.kt
package com.nodex.vpn.auth

// ── Auth user model ───────────────────────────────────────────────────────────
data class AuthUser(
    val uid:          String,
    val email:        String?,
    val displayName:  String?,
    val photoUrl:     String?,
    val isEmailVerified: Boolean = false,
    val provider:     AuthProvider = AuthProvider.Email,
)

enum class AuthProvider { Email, Google }

// ── Auth state machine ────────────────────────────────────────────────────────
sealed interface AuthState {
    data object Loading   : AuthState
    data object Unauthenticated : AuthState
    data class  Authenticated(val user: AuthUser) : AuthState
    data class  Error(val message: String) : AuthState
}

// ── Auth actions ─────────────────────────────────────────────────────────────
sealed interface AuthResult {
    data class Success(val user: AuthUser) : AuthResult
    data class Failure(val message: String) : AuthResult
}
