// shared/src/iosMain/kotlin/com/nodex/vpn/IOSFirstLaunchPrefs.kt
package com.nodex.vpn

import platform.Foundation.NSUserDefaults

object IOSFirstLaunchPrefs {
    private const val KEY = "nodex_first_launch_done"

    fun isFirstLaunch(): Boolean =
        !NSUserDefaults.standardUserDefaults.boolForKey(KEY)

    fun markLaunched() =
        NSUserDefaults.standardUserDefaults.setBool(true, forKey = KEY)
}
