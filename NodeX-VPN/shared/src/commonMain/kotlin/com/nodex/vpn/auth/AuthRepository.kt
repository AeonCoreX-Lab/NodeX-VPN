// shared/src/commonMain/kotlin/com/nodex/vpn/auth/AuthRepository.kt
package com.nodex.vpn.auth

import kotlinx.coroutines.flow.Flow

/**
 * expect/actual pattern — each platform provides Firebase Auth implementation.
 * Android  → Firebase SDK (google-services.json injected via CI secret)
 * iOS      → Firebase SDK (GoogleService-Info.plist injected via CI secret)
 * Desktop  → Firebase REST API (no native SDK needed)
 */
expect class AuthRepository() {

    /** Emits the current auth state; updates on sign-in/out. */
    val authState: Flow<AuthState>

    /** Current user or null. */
    fun currentUser(): AuthUser?

    /** Sign in with email + password. */
    suspend fun signInWithEmail(email: String, password: String): AuthResult

    /** Create account with email + password. */
    suspend fun signUpWithEmail(email: String, password: String): AuthResult

    /** Sign in / up with Google (platform-specific OAuth flow). */
    suspend fun signInWithGoogle(): AuthResult

    /** Send password reset email. */
    suspend fun resetPassword(email: String): AuthResult

    /** Sign out. */
    suspend fun signOut()

    /** Delete current user account. */
    suspend fun deleteAccount(): AuthResult
}
