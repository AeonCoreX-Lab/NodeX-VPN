package com.nodex.vpn

import androidx.compose.ui.window.ComposeUIViewController
import com.nodex.vpn.auth.AuthViewModel
import com.nodex.vpn.di.appModule
import com.nodex.vpn.manager.VpnManager
import com.nodex.vpn.ui.NodeXApp
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatformTools
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    runCatching { startKoin { modules(appModule) } }

    val koin          = KoinPlatformTools.defaultContext().get()
    val vpnManager    = koin.get<VpnManager>()
    val authViewModel = koin.get<AuthViewModel>()
    val isFirstLaunch = IOSFirstLaunchPrefs.isFirstLaunch().also {
        if (it) IOSFirstLaunchPrefs.markLaunched()
    }

    return ComposeUIViewController {
        NodeXApp(
            vpnManager    = vpnManager,
            authViewModel = authViewModel,
            isFirstLaunch = isFirstLaunch,
        )
    }
}
