// shared/src/desktopMain/kotlin/com/nodex/vpn/auth/AuthRepository.desktop.kt
package com.nodex.vpn.auth

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.awt.Desktop
import java.net.ServerSocket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// ── Constants ─────────────────────────────────────────────────────────────────

private const val CALLBACK_PORT   = 9006   // 9005 = CLI, 9006 = Desktop app
private const val AUTH_URL        = "https://accounts.google.com/o/oauth2/v2/auth"
private const val TOKEN_URL       = "https://oauth2.googleapis.com/token"
private const val USERINFO_URL    = "https://www.googleapis.com/oauth2/v3/userinfo"
private const val FIREBASE_BASE   = "https://identitytoolkit.googleapis.com/v1/accounts"

// Injected by build.gradle.kts from CI secrets / local.properties
private val GOOGLE_CLIENT_ID     = System.getenv("NODEX_DESKTOP_GOOGLE_CLIENT_ID")     ?: BuildConfig.GOOGLE_CLIENT_ID
private val GOOGLE_CLIENT_SECRET = System.getenv("NODEX_DESKTOP_GOOGLE_CLIENT_SECRET") ?: BuildConfig.GOOGLE_CLIENT_SECRET
private val FIREBASE_API_KEY     = System.getenv("FIREBASE_WEB_API_KEY")               ?: BuildConfig.FIREBASE_API_KEY

// ── AuthRepository ────────────────────────────────────────────────────────────

actual class AuthRepository actual constructor() {

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    actual val authState: Flow<AuthState> = _authState.asStateFlow()

    private var _currentUser: AuthUser? = null

    init {
        val saved = DesktopSession.load()
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
        val resp: FirebaseAuthResponse = http.post("$FIREBASE_BASE:signInWithPassword?key=$FIREBASE_API_KEY") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to email, "password" to password, "returnSecureToken" to true))
        }.body()
        handleFirebaseResponse(resp, AuthProvider.Email)
    } catch (e: Exception) {
        AuthResult.Failure(parseFirebaseError(e.message ?: ""))
    }

    // ── Email Sign Up ─────────────────────────────────────────────────────────

    actual suspend fun signUpWithEmail(email: String, password: String): AuthResult = try {
        val resp: FirebaseAuthResponse = http.post("$FIREBASE_BASE:signUp?key=$FIREBASE_API_KEY") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to email, "password" to password, "returnSecureToken" to true))
        }.body()
        val result = handleFirebaseResponse(resp, AuthProvider.Email)
        // Send email verification
        resp.idToken?.let { sendEmailVerification(it) }
        result
    } catch (e: Exception) {
        AuthResult.Failure(parseFirebaseError(e.message ?: ""))
    }

    // ── Google Sign In (OAuth2 PKCE — browser-based) ──────────────────────────

    actual suspend fun signInWithGoogle(): AuthResult = withContext(Dispatchers.IO) {
        try {
            if (GOOGLE_CLIENT_ID.isBlank() || GOOGLE_CLIENT_ID == "PLACEHOLDER") {
                return@withContext AuthResult.Failure(
                    "Google Sign-In not configured. Contact support."
                )
            }

            // 1. Generate PKCE
            val codeVerifier  = generateCodeVerifier()
            val codeChallenge = generateCodeChallenge(codeVerifier)
            val state         = generateState()
            val redirectUri   = "http://localhost:$CALLBACK_PORT/callback"

            // 2. Build auth URL
            val authUrl = buildAuthUrl(codeChallenge, state, redirectUri)

            // 3. Open browser
            openBrowser(authUrl)

            // 4. Wait for callback on local server
            val code = waitForCallback(state, redirectUri)
                ?: return@withContext AuthResult.Failure("Login cancelled or timed out")

            // 5. Exchange code → Google tokens
            val tokenResp = exchangeCodeForTokens(code, codeVerifier, redirectUri)
                ?: return@withContext AuthResult.Failure("Failed to exchange authorization code")

            // 6. Exchange Google ID token → Firebase token
            val firebaseResp = exchangeGoogleTokenWithFirebase(tokenResp.idToken)
                ?: return@withContext AuthResult.Failure("Failed to sign in with Firebase")

            handleFirebaseResponse(firebaseResp, AuthProvider.Google)

        } catch (e: Exception) {
            AuthResult.Failure(e.message ?: "Google Sign-In failed")
        }
    }

    // ── Password Reset ────────────────────────────────────────────────────────

    actual suspend fun resetPassword(email: String): AuthResult = try {
        http.post("$FIREBASE_BASE:sendOobCode?key=$FIREBASE_API_KEY") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("requestType" to "PASSWORD_RESET", "email" to email))
        }
        AuthResult.Success(AuthUser("", email, null, null))
    } catch (e: Exception) {
        AuthResult.Failure(parseFirebaseError(e.message ?: ""))
    }

    // ── Sign Out ──────────────────────────────────────────────────────────────

    actual suspend fun signOut() {
        _currentUser = null
        DesktopSession.clear()
        _authState.value = AuthState.Unauthenticated
    }

    // ── Delete Account ────────────────────────────────────────────────────────

    actual suspend fun deleteAccount(): AuthResult = try {
        val idToken = DesktopSession.loadIdToken()
            ?: return AuthResult.Failure("Not signed in")
        http.post("$FIREBASE_BASE:delete?key=$FIREBASE_API_KEY") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("idToken" to idToken))
        }
        signOut()
        AuthResult.Success(AuthUser("", null, null, null))
    } catch (e: Exception) {
        AuthResult.Failure(e.message ?: "Delete failed")
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun handleFirebaseResponse(
        resp: FirebaseAuthResponse,
        provider: AuthProvider,
    ): AuthResult {
        val user = AuthUser(
            uid         = resp.localId ?: "",
            email       = resp.email,
            displayName = resp.displayName,
            photoUrl    = resp.photoUrl,
            provider    = provider,
        )
        _currentUser = user
        DesktopSession.save(user, resp.idToken, resp.refreshToken)
        _authState.value = AuthState.Authenticated(user)
        return AuthResult.Success(user)
    }

    private suspend fun sendEmailVerification(idToken: String) = try {
        http.post("$FIREBASE_BASE:sendOobCode?key=$FIREBASE_API_KEY") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("requestType" to "VERIFY_EMAIL", "idToken" to idToken))
        }
    } catch (_: Exception) {}

    private suspend fun exchangeGoogleTokenWithFirebase(
        googleIdToken: String,
    ): FirebaseAuthResponse? = try {
        http.post("$FIREBASE_BASE:signInWithIdp?key=$FIREBASE_API_KEY") {
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "postBody"           to "id_token=$googleIdToken&providerId=google.com",
                "requestUri"         to "http://localhost",
                "returnSecureToken"  to true,
                "returnIdpCredential" to true,
            ))
        }.body<FirebaseAuthResponse>()
    } catch (_: Exception) { null }

    private suspend fun exchangeCodeForTokens(
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): GoogleTokenResponse? = try {
        http.submitForm(
            url = TOKEN_URL,
            formParameters = parameters {
                append("client_id",     GOOGLE_CLIENT_ID)
                append("client_secret", GOOGLE_CLIENT_SECRET)
                append("code",          code)
                append("code_verifier", codeVerifier)
                append("redirect_uri",  redirectUri)
                append("grant_type",    "authorization_code")
            }
        ).body<GoogleTokenResponse>()
    } catch (_: Exception) { null }

    private fun buildAuthUrl(challenge: String, state: String, redirectUri: String): String {
        val params = listOf(
            "client_id"             to GOOGLE_CLIENT_ID,
            "redirect_uri"          to redirectUri,
            "response_type"         to "code",
            "scope"                 to "openid email profile",
            "code_challenge"        to challenge,
            "code_challenge_method" to "S256",
            "state"                 to state,
            "access_type"           to "offline",
            "prompt"                to "consent",
        ).joinToString("&") { (k, v) -> "$k=${v.encodeURL()}" }
        return "$AUTH_URL?$params"
    }

    private fun openBrowser(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        } catch (_: Exception) {}
        // Fallback for Linux without GNOME/KDE desktop
        try { Runtime.getRuntime().exec(arrayOf("xdg-open", url)) } catch (_: Exception) {}
        try { Runtime.getRuntime().exec(arrayOf("open", url)) } catch (_: Exception) {}
    }

    /** Spin up a local HTTP server and wait for Google's redirect. */
    private fun waitForCallback(expectedState: String, redirectUri: String): String? {
        return try {
            ServerSocket(CALLBACK_PORT).use { server ->
                server.soTimeout = 300_000 // 5 minutes
                val socket = server.accept()
                socket.use { s ->
                    val request = s.getInputStream()
                        .bufferedReader()
                        .readLine() ?: return null

                    // GET /callback?code=XXX&state=YYY HTTP/1.1
                    val queryString = request
                        .removePrefix("GET /callback?")
                        .substringBefore(" HTTP")

                    val params = queryString.split("&").associate {
                        val (k, v) = it.split("=", limit = 2).let { p ->
                            p[0] to (p.getOrNull(1) ?: "")
                        }
                        k to v.decodeURL()
                    }

                    // Respond with success page
                    val html = successHtml()
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\n\r\n$html"
                    s.getOutputStream().write(response.toByteArray())

                    if (params["state"] != expectedState) return null
                    params["error"]?.let { return null }
                    params["code"]
                }
            }
        } catch (_: Exception) { null }
    }

    private fun parseFirebaseError(msg: String): String = when {
        msg.contains("EMAIL_EXISTS")       -> "This email is already registered"
        msg.contains("INVALID_EMAIL")      -> "Invalid email address"
        msg.contains("INVALID_PASSWORD")   -> "Incorrect password"
        msg.contains("INVALID_LOGIN_CREDENTIALS") -> "Incorrect email or password"
        msg.contains("EMAIL_NOT_FOUND")    -> "No account found with this email"
        msg.contains("WEAK_PASSWORD")      -> "Password must be at least 6 characters"
        msg.contains("TOO_MANY_ATTEMPTS")  -> "Too many attempts. Try again later"
        msg.contains("USER_DISABLED")      -> "This account has been disabled"
        else                               -> "Authentication failed. Check your connection."
    }
}

// ── PKCE helpers ──────────────────────────────────────────────────────────────

private fun generateCodeVerifier(): String {
    val bytes = ByteArray(64)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun generateCodeChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private fun generateState(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun String.encodeURL(): String = java.net.URLEncoder.encode(this, "UTF-8")
private fun String.decodeURL(): String = java.net.URLDecoder.decode(this, "UTF-8")

// ── Data models ───────────────────────────────────────────────────────────────

@Serializable
private data class FirebaseAuthResponse(
    val localId:      String? = null,
    val email:        String? = null,
    val displayName:  String? = null,
    val photoUrl:     String? = null,
    val idToken:      String? = null,
    val refreshToken: String? = null,
    val expiresIn:    String? = null,
)

@Serializable
private data class GoogleTokenResponse(
    @SerialName("id_token")      val idToken:      String? = null,
    @SerialName("access_token")  val accessToken:  String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in")    val expiresIn:    Int     = 3600,
)

// ── Session persistence ───────────────────────────────────────────────────────

private object DesktopSession {
    private val dir  = java.io.File(System.getProperty("user.home"), ".config/nodex-vpn")
    private val file = java.io.File(dir, "session.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class SessionData(
        val uid:          String,
        val email:        String?,
        val displayName:  String?,
        val photoUrl:     String?,
        val provider:     String?,
        val idToken:      String?,
        val refreshToken: String?,
    )

    fun save(user: AuthUser, idToken: String?, refreshToken: String?) = try {
        dir.mkdirs()
        file.writeText(json.encodeToString(SessionData(
            uid          = user.uid,
            email        = user.email,
            displayName  = user.displayName,
            photoUrl     = user.photoUrl,
            provider     = user.provider?.name,
            idToken      = idToken,
            refreshToken = refreshToken,
        )))
    } catch (_: Exception) {}

    fun load(): AuthUser? = try {
        val d = json.decodeFromString<SessionData>(file.readText())
        AuthUser(
            uid         = d.uid,
            email       = d.email,
            displayName = d.displayName,
            photoUrl    = d.photoUrl,
            provider    = d.provider?.let { runCatching { AuthProvider.valueOf(it) }.getOrNull() },
        )
    } catch (_: Exception) { null }

    fun loadIdToken(): String? = try {
        json.decodeFromString<SessionData>(file.readText()).idToken
    } catch (_: Exception) { null }

    fun clear() = try { file.delete() } catch (_: Exception) {}
}

// ── Success HTML page shown in browser after login ────────────────────────────

private fun successHtml() = """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>NodeX VPN</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
      background: #0a0a0f;
      color: #fff;
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
    }
    .card {
      text-align: center;
      padding: 48px 64px;
      background: #12121a;
      border-radius: 16px;
      border: 1px solid #1e1e2e;
    }
    .icon { font-size: 56px; margin-bottom: 24px; }
    h1 { font-size: 24px; font-weight: 600; margin-bottom: 8px; }
    p  { color: #888; font-size: 15px; }
    .brand { color: #00d4ff; font-weight: 700; }
  </style>
</head>
<body>
  <div class="card">
    <div class="icon">✓</div>
    <h1>Signed in successfully</h1>
    <p>Return to <span class="brand">NodeX VPN</span> to continue.</p>
    <p style="margin-top:12px">You can close this tab.</p>
  </div>
  <script>setTimeout(() => window.close(), 2000);</script>
</body>
</html>
""".trimIndent()
