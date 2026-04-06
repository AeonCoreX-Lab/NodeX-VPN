// shared/src/desktopMain/kotlin/com/nodex/vpn/auth/AuthRepository.desktop.kt
package com.nodex.vpn.auth

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*

/**
 * Desktop implementation uses Firebase Auth REST API directly.
 * No native Firebase SDK required on desktop.
 * API key is read from environment or bundled config.
 */
actual class AuthRepository actual constructor() {

    // Firebase API key — injected at build time via environment variable
    // Set FIREBASE_API_KEY in CI secrets or local .env
    private val apiKey: String =
        System.getenv("FIREBASE_WEB_API_KEY") ?: "YOUR_FIREBASE_WEB_API_KEY"

    private val baseUrl = "https://identitytoolkit.googleapis.com/v1/accounts"

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    actual val authState: Flow<AuthState> = _authState.asStateFlow()

    private var _currentUser: AuthUser? = null

    init {
        // Check persisted session (store idToken in local prefs/keychain)
        val saved = DesktopAuthPrefs.load()
        if (saved != null) {
            _currentUser = saved
            _authState.value = AuthState.Authenticated(saved)
        } else {
            _authState.value = AuthState.Unauthenticated
        }
    }

    actual fun currentUser(): AuthUser? = _currentUser

    // ── Email Sign In ─────────────────────────────────────────────────────────
    actual suspend fun signInWithEmail(email: String, password: String): AuthResult = try {
        val resp: FirebaseSignInResponse = client.post("$baseUrl:signInWithPassword?key=$apiKey") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to email, "password" to password, "returnSecureToken" to true))
        }.body()
        val user = resp.toAuthUser()
        _currentUser = user
        DesktopAuthPrefs.save(user)
        _authState.value = AuthState.Authenticated(user)
        AuthResult.Success(user)
    } catch (e: Exception) {
        AuthResult.Failure(parseFirebaseError(e))
    }

    // ── Email Sign Up ─────────────────────────────────────────────────────────
    actual suspend fun signUpWithEmail(email: String, password: String): AuthResult = try {
        val resp: FirebaseSignInResponse = client.post("$baseUrl:signUp?key=$apiKey") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to email, "password" to password, "returnSecureToken" to true))
        }.body()
        val user = resp.toAuthUser()
        _currentUser = user
        DesktopAuthPrefs.save(user)
        _authState.value = AuthState.Authenticated(user)
        // Send verification email
        sendVerificationEmail(resp.idToken ?: "")
        AuthResult.Success(user)
    } catch (e: Exception) {
        AuthResult.Failure(parseFirebaseError(e))
    }

    // ── Google (Desktop: open browser OAuth) ─────────────────────────────────
    actual suspend fun signInWithGoogle(): AuthResult {
        // Desktop Google OAuth: open browser → local redirect server → get token
        // Full implementation uses OAuth2 PKCE flow
        return AuthResult.Failure(
            "Google Sign-In on desktop: open browser flow not yet implemented. Use email/password."
        )
    }

    // ── Password reset ────────────────────────────────────────────────────────
    actual suspend fun resetPassword(email: String): AuthResult = try {
        client.post("$baseUrl:sendOobCode?key=$apiKey") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("requestType" to "PASSWORD_RESET", "email" to email))
        }
        AuthResult.Success(AuthUser("", email, null, null))
    } catch (e: Exception) {
        AuthResult.Failure(parseFirebaseError(e))
    }

    actual suspend fun signOut() {
        _currentUser = null
        DesktopAuthPrefs.clear()
        _authState.value = AuthState.Unauthenticated
    }

    actual suspend fun deleteAccount(): AuthResult = try {
        val idToken = DesktopAuthPrefs.loadIdToken() ?: return AuthResult.Failure("Not signed in")
        client.post("$baseUrl:delete?key=$apiKey") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("idToken" to idToken))
        }
        signOut()
        AuthResult.Success(AuthUser("", null, null, null))
    } catch (e: Exception) {
        AuthResult.Failure(e.message ?: "Delete failed")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private suspend fun sendVerificationEmail(idToken: String) = try {
        client.post("$baseUrl:sendOobCode?key=$apiKey") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("requestType" to "VERIFY_EMAIL", "idToken" to idToken))
        }
    } catch (_: Exception) {}

    private fun parseFirebaseError(e: Exception): String {
        val msg = e.message ?: return "Authentication failed"
        return when {
            msg.contains("EMAIL_EXISTS")         -> "This email is already registered"
            msg.contains("INVALID_EMAIL")        -> "Invalid email address"
            msg.contains("INVALID_PASSWORD")     -> "Incorrect password"
            msg.contains("EMAIL_NOT_FOUND")      -> "No account found with this email"
            msg.contains("WEAK_PASSWORD")        -> "Password must be at least 6 characters"
            msg.contains("TOO_MANY_ATTEMPTS")    -> "Too many attempts. Try again later"
            msg.contains("USER_DISABLED")        -> "This account has been disabled"
            else                                 -> "Authentication failed. Check your connection."
        }
    }
}

@Serializable
private data class FirebaseSignInResponse(
    val localId:      String? = null,
    val email:        String? = null,
    val displayName:  String? = null,
    val idToken:      String? = null,
    val refreshToken: String? = null,
    val expiresIn:    String? = null,
)

private fun FirebaseSignInResponse.toAuthUser() = AuthUser(
    uid         = localId ?: "",
    email       = email,
    displayName = displayName,
    photoUrl    = null,
    provider    = AuthProvider.Email,
)

// ── Simple local session persistence ─────────────────────────────────────────
private object DesktopAuthPrefs {
    private val file = java.io.File(
        System.getProperty("user.home"), ".config/nodex-vpn/session.json"
    )
    private val json = Json { ignoreUnknownKeys = true }

    fun save(user: AuthUser) = try {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(SessionData(user.uid, user.email, user.displayName)))
    } catch (_: Exception) {}

    fun load(): AuthUser? = try {
        val data = json.decodeFromString<SessionData>(file.readText())
        AuthUser(data.uid, data.email, data.displayName, null)
    } catch (_: Exception) { null }

    fun loadIdToken(): String? = null  // Would store encrypted token in production

    fun clear() = try { file.delete() } catch (_: Exception) {}

    @Serializable
    private data class SessionData(val uid: String, val email: String?, val displayName: String?)
}
