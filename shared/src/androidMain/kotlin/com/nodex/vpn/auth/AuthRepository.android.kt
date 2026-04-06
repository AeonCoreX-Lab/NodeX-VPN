package com.nodex.vpn.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await

actual class AuthRepository actual constructor() {

    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()

    companion object {
        lateinit var applicationContext: Context
        var currentActivity: Activity? = null
        // Launcher reference set from MainActivity
        var googleLauncher: ((Intent) -> Unit)? = null
        var googleSignInCallback: ((GoogleSignInAccount?, Exception?) -> Unit)? = null

        private val googleSignInClient: GoogleSignInClient by lazy {
            val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(applicationContext.getString(
                    com.nodex.vpn.android.R.string.default_web_client_id
                ))
                .requestEmail()
                .build()
            GoogleSignIn.getClient(applicationContext, options)
        }
    }

    actual val authState: Flow<AuthState> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            trySend(
                if (auth.currentUser != null) AuthState.Authenticated(auth.currentUser!!.toAuthUser())
                else AuthState.Unauthenticated
            )
        }
        firebaseAuth.addAuthStateListener(listener)
        awaitClose { firebaseAuth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    actual fun currentUser(): AuthUser? = firebaseAuth.currentUser?.toAuthUser()

    actual suspend fun signInWithEmail(email: String, password: String): AuthResult = try {
        val result = firebaseAuth.signInWithEmailAndPassword(email, password).await()
        AuthResult.Success(result.user!!.toAuthUser())
    } catch (e: FirebaseAuthException) {
        AuthResult.Failure(e.toReadable())
    } catch (e: Exception) {
        AuthResult.Failure(e.message ?: "Sign in failed")
    }

    actual suspend fun signUpWithEmail(email: String, password: String): AuthResult = try {
        val result = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
        result.user?.sendEmailVerification()?.await()
        AuthResult.Success(result.user!!.toAuthUser())
    } catch (e: FirebaseAuthException) {
        AuthResult.Failure(e.toReadable())
    } catch (e: Exception) {
        AuthResult.Failure(e.message ?: "Sign up failed")
    }

    actual suspend fun signInWithGoogle(): AuthResult = try {
        // Launch intent via activity; result comes back via googleSignInCallback
        val account = suspendCancellableCoroutine<GoogleSignInAccount> { cont ->
            googleSignInCallback = { acc, err ->
                if (acc != null) cont.resume(acc) {}
                else cont.resumeWithException(err ?: Exception("Google sign-in cancelled"))
                googleSignInCallback = null
            }
            googleLauncher?.invoke(googleSignInClient.signInIntent)
                ?: cont.resumeWithException(Exception("No launcher registered"))
        }
        val credential = GoogleAuthProvider.getCredential(account.idToken, null)
        val result     = firebaseAuth.signInWithCredential(credential).await()
        AuthResult.Success(result.user!!.toAuthUser())
    } catch (e: ApiException) {
        AuthResult.Failure("Google Sign-In failed (code ${e.statusCode})")
    } catch (e: Exception) {
        AuthResult.Failure(e.message ?: "Google Sign-In failed")
    }

    actual suspend fun resetPassword(email: String): AuthResult = try {
        firebaseAuth.sendPasswordResetEmail(email).await()
        AuthResult.Success(AuthUser("", email, null, null))
    } catch (e: Exception) {
        AuthResult.Failure(e.message ?: "Reset failed")
    }

    actual suspend fun signOut() {
        firebaseAuth.signOut()
        googleSignInClient.signOut().await()
    }

    actual suspend fun deleteAccount(): AuthResult = try {
        firebaseAuth.currentUser?.delete()?.await()
        AuthResult.Success(AuthUser("", null, null, null))
    } catch (e: Exception) {
        AuthResult.Failure(e.message ?: "Delete failed")
    }

    private fun FirebaseUser.toAuthUser() = AuthUser(
        uid             = uid,
        email           = email,
        displayName     = displayName,
        photoUrl        = photoUrl?.toString(),
        isEmailVerified = isEmailVerified,
        provider        = when (providerData.firstOrNull()?.providerId) {
            GoogleAuthProvider.PROVIDER_ID -> AuthProvider.Google
            else -> AuthProvider.Email
        },
    )

    private fun FirebaseAuthException.toReadable(): String = when (errorCode) {
        "ERROR_INVALID_EMAIL"          -> "Invalid email address"
        "ERROR_WRONG_PASSWORD"         -> "Incorrect password"
        "ERROR_USER_NOT_FOUND"         -> "No account with this email"
        "ERROR_EMAIL_ALREADY_IN_USE"   -> "Email already registered"
        "ERROR_WEAK_PASSWORD"          -> "Password too weak (min 6 chars)"
        "ERROR_TOO_MANY_REQUESTS"      -> "Too many attempts. Try later"
        "ERROR_NETWORK_REQUEST_FAILED" -> "Network error. Check connection"
        else                           -> message ?: "Authentication failed"
    }
}
