// desktopApp/src/desktopMain/kotlin/com/nodex/vpn/desktop/DesktopFirstLaunchPrefs.kt
package com.nodex.vpn.desktop

import java.io.File

object DesktopFirstLaunchPrefs {
    private val file = File(
        System.getProperty("user.home"),
        ".config/nodex-vpn/.first_launch_done"
    )

    fun isFirstLaunch(): Boolean = !file.exists()

    fun markLaunched() {
        file.parentFile?.mkdirs()
        file.createNewFile()
    }
}
