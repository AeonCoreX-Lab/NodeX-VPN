// androidApp/src/main/kotlin/com/nodex/vpn/android/BootReceiver.kt
package com.nodex.vpn.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the VPN automatically after device reboot
 * if the user had it connected before shutdown.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("nodex_prefs", Context.MODE_PRIVATE)
        val autoConnect = prefs.getBoolean("auto_connect", false)
        val wasConnected = prefs.getBoolean("was_connected", false)

        if (autoConnect && wasConnected) {
            val vpnIntent = Intent(context, NodeXVpnService::class.java).apply {
                action = NodeXVpnService.ACTION_START
            }
            context.startForegroundService(vpnIntent)
        }
    }
}
