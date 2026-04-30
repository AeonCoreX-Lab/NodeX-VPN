// androidApp/src/main/kotlin/com/nodex/vpn/android/NodeXVpnService.kt
package com.nodex.vpn.android

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * Android VpnService – manages the TUN file descriptor and routes
 * all device traffic through the arti SOCKS5 proxy via tun2socks.
 *
 * Key flow:
 *   1. Build a VPN interface (Builder) with 10.66.0.1/24
 *   2. Protect the SOCKS5 socket from being looped back through the VPN
 *   3. Pass the TUN fd to the native tun2socks library (or use
 *      the Java-side relay loop for MVP)
 *   4. Run until ACTION_STOP received
 */
class NodeXVpnService : VpnService() {

    companion object {
        const val ACTION_START      = "com.nodex.vpn.START"
        const val ACTION_STOP       = "com.nodex.vpn.STOP"
        const val EXTRA_SOCKS_ADDR  = "socks_addr"
        const val EXTRA_EXIT_ISO    = "exit_iso"
        const val EXTRA_USE_BRIDGES = "use_bridges"
        const val EXTRA_STRICT_EXIT = "strict_exit"

        private const val NOTIFICATION_ID  = 1001
        private const val CHANNEL_ID       = "nodex_vpn_channel"
        private const val TAG              = "NodeXVpnService"

        // Native method: hand off TUN fd to Rust tun2socks loop
        @JvmStatic external fun nativeSetTunFd(fd: Int, socksAddr: String)
        @JvmStatic external fun nativeStopTun()
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val socksAddr = intent.getStringExtra(EXTRA_SOCKS_ADDR) ?: "127.0.0.1:9050"
                startVpnTunnel(socksAddr)
            }
            ACTION_STOP  -> stopVpnTunnel()
        }
        return START_STICKY
    }

    private fun startVpnTunnel(socksAddr: String) {
        Log.i(TAG, "Starting NodeX VPN tunnel")

        // ── Foreground notification ────────────────────────────────────────────
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        // ── Build TUN interface ────────────────────────────────────────────────
        val builder = Builder()
            .setSession("NodeX VPN")
            .addAddress("10.66.0.1", 24)
            .addRoute("0.0.0.0", 0)                  // Intercept all IPv4 traffic
            .addRoute("::", 0)                        // Intercept all IPv6 traffic
            .addDnsServer("127.0.0.1")               // Our DoT listener
            .setMtu(1500)
            .setBlocking(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Allow arti's SOCKS socket to bypass the VPN (prevent loop)
        // The socket is created in Rust; we protect it via the native protect() call
        // which is wired through nativeSetTunFd.

        tunInterface = builder.establish() ?: run {
            Log.e(TAG, "VPN interface not established")
            stopSelf()
            return
        }

        val fd = tunInterface!!.fd
        Log.i(TAG, "TUN fd = $fd, routing traffic via $socksAddr")

        // ── Hand TUN fd to Rust tun2socks ─────────────────────────────────────
        scope.launch {
            nativeSetTunFd(fd, socksAddr)
        }

        // ── Update notification ────────────────────────────────────────────────
        updateNotification("Connected · Tor Active")
    }

    private fun stopVpnTunnel() {
        Log.i(TAG, "Stopping NodeX VPN tunnel")
        nativeStopTun()
        scope.cancel()
        tunInterface?.close()
        tunInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by system")
        stopVpnTunnel()
    }

    override fun onDestroy() {
        stopVpnTunnel()
        super.onDestroy()
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NodeX VPN",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "NodeX VPN connection status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, NodeXVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("NodeX VPN")
            .setContentText(status)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Disconnect", stopIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }
}
