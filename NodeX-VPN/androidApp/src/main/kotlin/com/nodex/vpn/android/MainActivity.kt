package com.nodex.vpn.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.google.firebase.FirebaseApp
import com.nodex.vpn.auth.AuthRepository
import com.nodex.vpn.auth.AuthViewModel
import com.nodex.vpn.di.appModule
import com.nodex.vpn.manager.VpnManager
import com.nodex.vpn.platform.PlatformVpnBridge
import com.nodex.vpn.ui.NodeXApp
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

// ── Application class ─────────────────────────────────────────────────────────
class NodeXApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()

        // Firebase must be initialised before any Firebase service is used.
        // google-services.json is injected at build time via GitHub secret
        // (never committed to the repo).
        FirebaseApp.initializeApp(this)

        // Koin DI
        PlatformVpnBridge.applicationContext     = applicationContext
        AuthRepository.applicationContext        = applicationContext

        startKoin {
            androidContext(this@NodeXApplication)
            modules(appModule)
        }
    }
}

// ── Main Activity ─────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {

    private val vpnManager:   VpnManager    by inject()
    private val authViewModel: AuthViewModel by inject()

    // ── VPN permission launcher ────────────────────────────────────────────────
    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vpnManager.connect()
    }

    // ── Google Sign-In launcher ────────────────────────────────────────────────
    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            AuthRepository.googleSignInCallback?.invoke(account, null)
        } catch (e: ApiException) {
            AuthRepository.googleSignInCallback?.invoke(null, e)
        }
        AuthRepository.googleSignInCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make this activity available for Google Sign-In
        AuthRepository.currentActivity = this
        AuthRepository.googleLauncher  = { intent ->
            googleSignInLauncher.launch(intent)
        }

        val isFirstLaunch = FirstLaunchPrefs.isFirstLaunch(this)
        if (isFirstLaunch) FirstLaunchPrefs.markLaunched(this)

        setContent {
            NodeXApp(
                vpnManager    = vpnManager,
                authViewModel = authViewModel,
                isFirstLaunch = isFirstLaunch,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        AuthRepository.currentActivity = this
    }

    override fun onPause() {
        super.onPause()
        if (AuthRepository.currentActivity == this) {
            AuthRepository.currentActivity = null
        }
    }

    fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermLauncher.launch(intent)
        else vpnManager.connect()
    }
}

// ── First launch detection ────────────────────────────────────────────────────
object FirstLaunchPrefs {
    private const val PREFS = "nodex_prefs"
    private const val KEY   = "first_launch_done"

    fun isFirstLaunch(ctx: Context): Boolean =
        !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun markLaunched(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
}
