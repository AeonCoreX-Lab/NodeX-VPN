// shared/src/iosMain/kotlin/com/nodex/vpn/platform/PlatformVpnBridge.ios.kt
package com.nodex.vpn.platform

import com.nodex.vpn.manager.BootstrapStatus
import com.nodex.vpn.manager.RawVpnStats
import com.nodex.vpn.manager.RustVpnConfig
import kotlinx.coroutines.delay
import platform.Foundation.*
import platform.NetworkExtension.*

/**
 * iOS actual implementation of PlatformVpnBridge.
 *
 * Uses Apple's NetworkExtension framework (NEVPNManager / NETunnelProviderManager)
 * to drive the PacketTunnelProvider extension which lives in a separate process.
 *
 * IPC between the main app and the tunnel extension is done via
 * NETunnelProviderSession messages (sendProviderMessage).
 */
actual class PlatformVpnBridge actual constructor() {

    // ── NEVPNManager singleton ─────────────────────────────────────────────
    private var manager: NETunnelProviderManager? = null

    actual suspend fun prepare() {
        // Load existing VPN configuration or create a new one
        val loaded = suspendLoad()
        if (loaded.isEmpty()) {
            val m = NETunnelProviderManager()
            m.localizedDescription = "NodeX VPN"
            val proto = NETunnelProviderProtocol()
            proto.providerBundleIdentifier = "com.nodex.vpn.ios.tunnel"
            proto.serverAddress = "Tor Network"
            m.protocolConfiguration = proto
            m.enabled = true
            suspendSave(m)
            manager = m
        } else {
            manager = loaded.firstOrNull() as? NETunnelProviderManager
        }
    }

    actual suspend fun startEngine(config: RustVpnConfig) {
        val m = manager ?: throw IllegalStateException("VPN manager not prepared")

        // Pass config to the Tunnel Extension via providerConfiguration
        val proto = m.protocolConfiguration as? NETunnelProviderProtocol
        proto?.providerConfiguration = mapOf(
            "socksAddr"    to config.socksListenAddr,
            "dnsAddr"      to config.dnsListenAddr,
            "useBridges"   to config.useBridges,
            "bridges"      to config.bridgeLines.joinToString("\n"),
            "strictExit"   to config.strictExitNodes,
            "exitIso"      to (config.preferredExitIso ?: ""),
            "timeout"      to config.circuitBuildTimeoutSecs.toInt(),
            "stateDir"     to stateDirectory(),
            "cacheDir"     to cacheDirectory(),
        ) as Map<Any?, *>

        suspendSave(m)

        // Start the tunnel
        val session = m.connection as? NETunnelProviderSession
            ?: throw IllegalStateException("No tunnel session")
        try {
            session.startTunnelWithOptions(null, andReturnError = null)
        } catch (e: Exception) {
            throw RuntimeException("Failed to start tunnel: ${e.message}")
        }
    }

    actual suspend fun stopEngine() {
        val session = manager?.connection as? NETunnelProviderSession
        session?.stopTunnel()
        delay(500)
    }

    actual suspend fun awaitBootstrap() {
        // Poll tunnel status until connected
        repeat(200) {
            val status = manager?.connection?.status
            when (status) {
                NEVPNStatus.NEVPNStatusConnected -> return
                NEVPNStatus.NEVPNStatusDisconnected -> throw RuntimeException("Tunnel disconnected")
                NEVPNStatus.NEVPNStatusInvalid -> throw RuntimeException("Tunnel invalid config")
                else -> delay(300)
            }
        }
        throw RuntimeException("Bootstrap timeout")
    }

    actual suspend fun setExitNode(isoCode: String) {
        val session = manager?.connection as? NETunnelProviderSession ?: return
        val msg = "SET_EXIT:$isoCode".encodeToByteArray()
        session.sendProviderMessage(msg.toNSData(), responseHandler = null, error = null)
    }

    actual fun getRealTimeStats(): RawVpnStats {
        // Query the tunnel extension via IPC message "GET_STATS"
        // For now return zeroed stats; real impl uses sendProviderMessage + semaphore
        return RawVpnStats(
            bytesSent          = 0L,
            bytesReceived      = 0L,
            sendRateBps        = 0L,
            recvRateBps        = 0L,
            activeCircuits     = 0,
            latencyMs          = 0.0,
            currentExitCountry = "—",
            currentExitIp      = "0.0.0.0",
            uptimeSecs         = 0L,
        )
    }

    actual fun getBootstrapStatus(): BootstrapStatus {
        val status = manager?.connection?.status
        return BootstrapStatus(
            percent      = if (status == NEVPNStatus.NEVPNStatusConnected) 100u else 50u,
            phase        = statusToPhase(status),
            isComplete   = status == NEVPNStatus.NEVPNStatusConnected,
            errorMessage = null,
        )
    }

    actual fun stateDirectory(): String {
        val appSupport = NSFileManager.defaultManager
            .URLsForDirectory(NSApplicationSupportDirectory, inDomains = NSUserDomainMask)
            .firstOrNull() as? NSURL
        return appSupport?.path?.plus("/NodeXVPN/state") ?: NSTemporaryDirectory() + "nodex/state"
    }

    actual fun cacheDirectory(): String {
        val caches = NSFileManager.defaultManager
            .URLsForDirectory(NSCachesDirectory, inDomains = NSUserDomainMask)
            .firstOrNull() as? NSURL
        return caches?.path?.plus("/NodeXVPN") ?: NSTemporaryDirectory() + "nodex/cache"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun statusToPhase(status: NEVPNStatus?): String = when (status) {
        NEVPNStatus.NEVPNStatusConnecting    -> "Connecting to Tor…"
        NEVPNStatus.NEVPNStatusConnected     -> "Connected"
        NEVPNStatus.NEVPNStatusDisconnecting -> "Disconnecting…"
        NEVPNStatus.NEVPNStatusReasserting   -> "Rebuilding circuit…"
        else                                 -> "Idle"
    }

    private suspend fun suspendLoad(): List<Any?> {
        var result: List<Any?> = emptyList()
        val sema = kotlinx.coroutines.channels.Channel<Unit>(1)
        NETunnelProviderManager.loadAllFromPreferencesWithCompletionHandler { managers, _ ->
            result = (managers as? List<Any?>) ?: emptyList()
            sema.trySend(Unit)
        }
        sema.receive()
        return result
    }

    private suspend fun suspendSave(m: NETunnelProviderManager) {
        val sema = kotlinx.coroutines.channels.Channel<Unit>(1)
        m.saveToPreferencesWithCompletionHandler { _ -> sema.trySend(Unit) }
        sema.receive()
    }
}

// Helper extension
private fun ByteArray.toNSData(): NSData =
    NSData.dataWithBytes(this.toUByteArray().toCValues(), this.size.toULong())
