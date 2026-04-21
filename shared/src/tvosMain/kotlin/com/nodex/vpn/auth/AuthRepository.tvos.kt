// shared/src/tvosMain/kotlin/com/nodex/vpn/auth/AuthRepository.tvos.kt
package com.nodex.vpn.auth

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.*

// tvOS shares the same Firebase auth as iOS via the appleMain source set.
// FirebaseAuth and GoogleSignIn are declared in shared/build.gradle.kts cocoapods{}.
import cocoapods.FirebaseAuth.FIRAuth
import cocoapods.FirebaseAuth.FIRAuthStateDidChangeListenerHandle
import cocoapods.FirebaseAuth.FIRGoogleAuthProvider
import cocoapods.FirebaseAuth.FIRUser
import cocoapods.GoogleSignIn.GIDSignIn
import platform.UIKit.UIApplication

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
        awaitClose { handle?.let { auth.removeAuthStateDidChangeListener(it) } }
    }.distinctUntilChanged()

    actual fun currentUser(): AuthUser? = auth.currentUser()?.toAuthUser()

    actual suspend fun signInWithEmail(email: String, password: String): AuthResult =
        suspendCancellableCoroutine { cont ->
            auth.signInWithEmail(email, password = password) { result, error ->
                if (error != null) cont.resume(AuthResult.Failure(error.localizedDescription ?: "Sign-in failed")) {}
                else cont.resume(AuthResult.Success(result?.user()?.toAuthUser()
                    ?: return@signInWithEmail cont.resume(AuthResult.Failure("No user")) {})) {}
            }
        }

    actual suspend fun signUpWithEmail(email: String, password: String): AuthResult =
        suspendCancellableCoroutine { cont ->
            auth.createUserWithEmail(email, password = password) { result, error ->
                if (error != null) cont.resume(AuthResult.Failure(error.localizedDescription ?: "Sign-up failed")) {}
                else {
                    result?.user()?.sendEmailVerificationWithCompletion(null)
                    cont.resume(AuthResult.Success(result?.user()?.toAuthUser()
                        ?: return@createUserWithEmail cont.resume(AuthResult.Failure("No user")) {})) {}
                }
            }
        }

    // On tvOS, Google Sign-In redirects through a browser — same UIKit flow as iOS.
    actual suspend fun signInWithGoogle(): AuthResult =
        suspendCancellableCoroutine { cont ->
            val rootVC = UIApplication.sharedApplication.keyWindow?.rootViewController
                ?: return@suspendCancellableCoroutine cont.resume(
                    AuthResult.Failure("No root view controller")) {}
            GIDSignIn.sharedInstance().signInWithPresentingViewController(rootVC) { result, error ->
                if (error != null) {
                    cont.resume(AuthResult.Failure(error.localizedDescription ?: "Google sign-in failed")) {}
                    return@signInWithPresentingViewController
                }
                val idToken     = result?.user?.idToken?.tokenString
                    ?: return@signInWithPresentingViewController cont.resume(
                        AuthResult.Failure("Missing Google ID token")) {}
                val accessToken = result.user?.accessToken?.tokenString ?: ""
                val credential  = FIRGoogleAuthProvider.credentialWithIDToken(idToken, accessToken = accessToken)
                auth.signInWithCredential(credential) { authResult, err ->
                    if (err != null) cont.resume(AuthResult.Failure(err.localizedDescription ?: "Credential failed")) {}
                    else cont.resume(AuthResult.Success(authResult?.user()?.toAuthUser()
                        ?: return@signInWithCredential cont.resume(AuthResult.Failure("No user")) {})) {}
                }
            }
        }

    actual suspend fun resetPassword(email: String): AuthResult =
        suspendCancellableCoroutine { cont ->
            auth.sendPasswordResetWithEmail(email) { error ->
                if (error != null) cont.resume(AuthResult.Failure(error.localizedDescription ?: "Reset failed")) {}
                else cont.resume(AuthResult.Success(AuthUser("", email, null, null))) {}
            }
        }

    actual suspend fun signOut() {
        try { auth.signOut() } catch (_: Exception) {}
        GIDSignIn.sharedInstance().signOut()
    }

    actual suspend fun deleteAccount(): AuthResult =
        suspendCancellableCoroutine { cont ->
            auth.currentUser()?.deleteWithCompletion { error ->
                if (error != null) cont.resume(AuthResult.Failure(error.localizedDescription ?: "Delete failed")) {}
                else cont.resume(AuthResult.Success(AuthUser("", null, null, null))) {}
            } ?: cont.resume(AuthResult.Failure("No current user")) {}
        }

    private fun FIRUser.toAuthUser() = AuthUser(
        uid             = uid(),
        email           = email(),
        displayName     = displayName(),
        photoUrl        = photoURL()?.absoluteString,
        isEmailVerified = isEmailVerified(),
        provider        = if (providerID().contains("google")) AuthProvider.Google else AuthProvider.Email,
    )
}
