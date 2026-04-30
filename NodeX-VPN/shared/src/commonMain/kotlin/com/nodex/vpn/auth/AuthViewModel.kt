// shared/src/commonMain/kotlin/com/nodex/vpn/auth/AuthViewModel.kt
package com.nodex.vpn.auth

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AuthViewModel(
    private val repo:  AuthRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    // ── State flows ───────────────────────────────────────────────────────────
    val authState: StateFlow<AuthState> = repo.authState
        .stateIn(scope, SharingStarted.Eagerly, AuthState.Loading)

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    // ── Form actions ──────────────────────────────────────────────────────────
    fun onEmailChange(v: String)    { _uiState.update { it.copy(email = v, emailError = null, globalError = null) } }
    fun onPasswordChange(v: String) { _uiState.update { it.copy(password = v, passwordError = null, globalError = null) } }
    fun onConfirmChange(v: String)  { _uiState.update { it.copy(confirmPassword = v, confirmError = null) } }
    fun toggleMode()                { _uiState.update { it.copy(isSignUp = !it.isSignUp, globalError = null) } }
    fun togglePasswordVisible()     { _uiState.update { it.copy(passwordVisible = !it.passwordVisible) } }
    fun clearError()                { _uiState.update { it.copy(globalError = null) } }

    // ── Auth actions ──────────────────────────────────────────────────────────
    fun submitEmailAuth() {
        val s = _uiState.value
        if (!validate(s)) return

        _uiState.update { it.copy(isLoading = true, globalError = null) }
        scope.launch {
            val result = if (s.isSignUp)
                repo.signUpWithEmail(s.email.trim(), s.password)
            else
                repo.signInWithEmail(s.email.trim(), s.password)

            when (result) {
                is AuthResult.Success -> _uiState.update { it.copy(isLoading = false) }
                is AuthResult.Failure -> _uiState.update { it.copy(isLoading = false, globalError = result.message) }
            }
        }
    }

    fun signInWithGoogle() {
        _uiState.update { it.copy(isGoogleLoading = true, globalError = null) }
        scope.launch {
            when (val result = repo.signInWithGoogle()) {
                is AuthResult.Success -> _uiState.update { it.copy(isGoogleLoading = false) }
                is AuthResult.Failure -> _uiState.update { it.copy(isGoogleLoading = false, globalError = result.message) }
            }
        }
    }

    fun resetPassword() {
        val email = _uiState.value.email.trim()
        if (email.isBlank()) {
            _uiState.update { it.copy(emailError = "Enter your email first") }
            return
        }
        _uiState.update { it.copy(isLoading = true) }
        scope.launch {
            val result = repo.resetPassword(email)
            _uiState.update {
                it.copy(
                    isLoading  = false,
                    globalError = if (result is AuthResult.Failure) result.message else null,
                    resetSent  = result is AuthResult.Success,
                )
            }
        }
    }

    fun signOut() = scope.launch { repo.signOut() }

    // ── Validation ────────────────────────────────────────────────────────────
    private fun validate(s: AuthUiState): Boolean {
        var ok = true
        if (s.email.isBlank() || !s.email.contains("@")) {
            _uiState.update { it.copy(emailError = "Enter a valid email") }; ok = false
        }
        if (s.password.length < 6) {
            _uiState.update { it.copy(passwordError = "Password must be ≥ 6 characters") }; ok = false
        }
        if (s.isSignUp && s.password != s.confirmPassword) {
            _uiState.update { it.copy(confirmError = "Passwords do not match") }; ok = false
        }
        return ok
    }

    fun dispose() = scope.cancel()
}

data class AuthUiState(
    val email:           String  = "",
    val password:        String  = "",
    val confirmPassword: String  = "",
    val isSignUp:        Boolean = false,
    val isLoading:       Boolean = false,
    val isGoogleLoading: Boolean = false,
    val passwordVisible: Boolean = false,
    val emailError:      String? = null,
    val passwordError:   String? = null,
    val confirmError:    String? = null,
    val globalError:     String? = null,
    val resetSent:       Boolean = false,
)
