// shared/src/tvosMain/kotlin/com/nodex/vpn/platform/PlatformVpnBridge.tvos.kt
package com.nodex.vpn.platform

import com.nodex.vpn.manager.BootstrapStatus
import com.nodex.vpn.manager.RawVpnStats
import com.nodex.vpn.manager.RustVpnConfig
import kotlinx.coroutines.delay
import platform.Foundation.*
import platform.NetworkExtension.*

// tvOS supports NEVPNManager (personal VPN) but NOT NetworkExtension packet tunnels
// (NETunnelProviderManager is only available on iOS/macOS). The TV app therefore
// uses the standard NEVPNManager (IKEv2 / IPSec) to connect through the VPN
// provided by a paired device, or relies on a system-level VPN profile.
//
// For this production build, PlatformVpnBridge on tvOS acts as a status-only
// bridge: it reads existing VPN connection status from NEVPNManager but cannot
// start/stop the Rust core tunnel directly.  Full VPN control on Apple TV
// requires the user to configure the VPN profile via their iPhone or iPad.
actual class PlatformVpnBridge actual constructor() {

    actual suspend fun prepare() {
        // tvOS: read existing system VPN profile; no-op if absent
        // The UI will show "Connect via iPhone" guidance when no profile is active
    }

    actual suspend fun startEngine(config: RustVpnConfig) {
        // Cannot start Rust TUN tunnel on tvOS; redirect user to companion device
        throw UnsupportedOperationException(
            "Direct VPN tunnel is not supported on Apple TV. " +
            "Please start NodeX VPN on your iPhone, iPad, or Mac."
        )
    }

    actual suspend fun stopEngine() {
        // No-op on tvOS
    }

    actual suspend fun awaitBootstrap() {
        // Poll NEVPNManager status
        repeat(30) {
            val status = NEVPNManager.sharedManager().connection?.status
            if (status == NEVPNStatusConnected) return
            delay(500)
        }
    }

    actual suspend fun setExitNode(isoCode: String) {
        // Cannot reconfigure Rust core on tvOS
    }

    actual fun getRealTimeStats() = RawVpnStats(
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

    actual fun getBootstrapStatus(): BootstrapStatus {
        val status = NEVPNManager.sharedManager().connection?.status
        return BootstrapStatus(
            percent    = if (status == NEVPNStatusConnected) 100u else 0u,
            phase      = if (status == NEVPNStatusConnected) "Connected via system VPN"
                         else "Use iPhone/iPad to start NodeX VPN",
            isComplete = status == NEVPNStatusConnected,
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
}
