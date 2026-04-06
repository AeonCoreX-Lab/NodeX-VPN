// shared/src/iosMain/kotlin/com/nodex/vpn/auth/AuthRepository.ios.kt
package com.nodex.vpn.auth

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import platform.Foundation.*

// FirebaseAuth iOS SDK is accessed via Objective-C interop
// cocoapods: pod 'Firebase/Auth' + pod 'GoogleSignIn'
import cocoapods.FirebaseAuth.*
import cocoapods.GoogleSignIn.*

actual class AuthRepository actual constructor() {

    private val auth = FIRAuth.auth()

    actual val authState: Flow<AuthState> = callbackFlow {
        var handle: FIRAuthStateDidChangeListenerHandle? = null
        handle = auth.addAuthStateDidChangeListener { _, user ->
            trySend(
                if (user != null) AuthState.Authenticated(user.toAuthUser())
                else              AuthState.Unauthenticated
            )
        }
        awaitClose {
            handle?.let { auth.removeAuthStateDidChangeListener(it) }
        }
    }.distinctUntilChanged()

    actual fun currentUser(): AuthUser? = auth.currentUser()?.toAuthUser()

    actual suspend fun signInWithEmail(email: String, password: String): AuthResult =
        suspendFirebase { cont ->
            auth.signInWithEmail(email, password = password) { result, error ->
                if (error != null) cont(null, error.localizedDescription)
                else cont(result?.user()?.toAuthUser(), null)
            }
        }

    actual suspend fun signUpWithEmail(email: String, password: String): AuthResult =
        suspendFirebase { cont ->
            auth.createUserWithEmail(email, password = password) { result, error ->
                if (error != null) cont(null, error.localizedDescription)
                else {
                    result?.user()?.sendEmailVerificationWithCompletion(null)
                    cont(result?.user()?.toAuthUser(), null)
                }
            }
        }

    actual suspend fun signInWithGoogle(): AuthResult =
        suspendFirebase { cont ->
            // GIDSignIn requires a UIViewController — retrieved from shared iOS context
            val rootVC = UIApplication.sharedApplication.keyWindow?.rootViewController
                ?: run { cont(null, "No root view controller"); return@suspendFirebase }

            GIDSignIn.sharedInstance().signInWithPresentingViewController(rootVC) { result, error ->
                if (error != null) { cont(null, error.localizedDescription); return@signInWithPresentingViewController }
                val idToken = result?.user?.idToken?.tokenString
                    ?: run { cont(null, "Missing Google ID token"); return@signInWithPresentingViewController }
                val accessToken = result?.user?.accessToken?.tokenString ?: ""
                val credential  = FIRGoogleAuthProvider.credentialWithIDToken(idToken, accessToken = accessToken)
                auth.signInWithCredential(credential) { authResult, err ->
                    if (err != null) cont(null, err.localizedDescription)
                    else cont(authResult?.user()?.toAuthUser(), null)
                }
            }
        }

    actual suspend fun resetPassword(email: String): AuthResult =
        suspendFirebase { cont ->
            auth.sendPasswordResetWithEmail(email) { error ->
                if (error != null) cont(null, error.localizedDescription)
                else cont(AuthUser("", email, null, null), null)
            }
        }

    actual suspend fun signOut() {
        try { auth.signOut() } catch (_: Exception) {}
        GIDSignIn.sharedInstance().signOut()
    }

    actual suspend fun deleteAccount(): AuthResult =
        suspendFirebase { cont ->
            auth.currentUser()?.deleteWithCompletion { error ->
                if (error != null) cont(null, error.localizedDescription)
                else cont(AuthUser("", null, null, null), null)
            } ?: cont(null, "No current user")
        }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun FIRUser.toAuthUser() = AuthUser(
        uid             = uid(),
        email           = email(),
        displayName     = displayName(),
        photoUrl        = photoURL()?.absoluteString,
        isEmailVerified = isEmailVerified(),
        provider        = if (providerID().contains("google")) AuthProvider.Google else AuthProvider.Email,
    )

    private suspend fun suspendFirebase(
        block: ((AuthUser?, String?) -> Unit) -> Unit
    ): AuthResult = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        block { user, error ->
            if (user != null) cont.resume(AuthResult.Success(user)) {}
            else cont.resume(AuthResult.Failure(error ?: "Auth failed")) {}
        }
    }
}
