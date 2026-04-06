package com.nodex.vpn.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.nodex.vpn.auth.AuthViewModel
import com.nodex.vpn.di.appModule
import com.nodex.vpn.manager.VpnManager
import com.nodex.vpn.ui.NodeXApp
import org.koin.core.context.startKoin

fun main() = application {
    val koin          = startKoin { modules(appModule) }.koin
    val vpnManager    = koin.get<VpnManager>()
    val authViewModel = koin.get<AuthViewModel>()

    if (!PrivilegeChecker.hasTunPermission()) {
        PrivilegeChecker.requestPrivilegesAndRelaunch()
        return@application
    }

    // Default window: large enough to trigger Expanded (desktop sidebar) layout
    val windowState = rememberWindowState(
        size     = DpSize(1280.dp, 800.dp),   // > 1200dp → desktop sidebar nav
        position = WindowPosition.PlatformDefault,
    )

    Window(
        onCloseRequest = {
            vpnManager.disconnect()
            vpnManager.dispose()
            authViewModel.dispose()
            exitApplication()
        },
        title     = "NodeX VPN",
        state     = windowState,
        resizable = true,
        // Min size to keep UI usable
    ) {
        // Mark first launch
        val isFirst = DesktopFirstLaunchPrefs.isFirstLaunch().also {
            if (it) DesktopFirstLaunchPrefs.markLaunched()
        }
        NodeXApp(
            vpnManager    = vpnManager,
            authViewModel = authViewModel,
            isFirstLaunch = isFirst,
        )
    }
}
