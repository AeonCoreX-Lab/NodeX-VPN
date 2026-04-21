// shared/src/tvosMain/kotlin/com/nodex/vpn/TVOSFirstLaunchPrefs.kt
package com.nodex.vpn

import platform.Foundation.NSUserDefaults

object TVOSFirstLaunchPrefs {
    private const val KEY = "tvos_first_launch_done"

    fun isFirstLaunch(): Boolean =
        !NSUserDefaults.standardUserDefaults.boolForKey(KEY)

    fun markLaunched() =
        NSUserDefaults.standardUserDefaults.setBool(true, KEY)
}
