package com.nodex.vpn.androidtv

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.nodex.vpn.auth.AuthRepository
import com.nodex.vpn.auth.AuthViewModel
import com.nodex.vpn.di.appModule
import com.nodex.vpn.manager.VpnManager
import com.nodex.vpn.platform.PlatformVpnBridge
import com.nodex.vpn.ui.tv.TvNodeXApp
import org.koin.android.ext.android.inject

class TvMainActivity : ComponentActivity() {

    private val vpnManager:    VpnManager    by inject()
    private val authViewModel: AuthViewModel by inject()

    // VPN permission launcher
    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vpnManager.connect()
    }

    // Google Sign-In launcher (TV uses the same OAuth flow via browser/device auth)
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

        AuthRepository.currentActivity = this
        AuthRepository.googleLauncher  = { intent ->
            googleSignInLauncher.launch(intent)
        }

        val isFirstLaunch = TvFirstLaunchPrefs.isFirstLaunch(this)
        if (isFirstLaunch) TvFirstLaunchPrefs.markLaunched(this)

        setContent {
            TvNodeXApp(
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
        if (AuthRepository.currentActivity == this) AuthRepository.currentActivity = null
    }

    fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermLauncher.launch(intent)
        else vpnManager.connect()
    }
}

// First launch prefs for TV
object TvFirstLaunchPrefs {
    private const val PREFS = "nodex_tv_prefs"
    private const val KEY   = "first_launch_done"

    fun isFirstLaunch(ctx: android.content.Context): Boolean =
        !ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun markLaunched(ctx: android.content.Context) =
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, true).apply()
}
