// shared/src/desktopMain/kotlin/com/nodex/vpn/platform/PlatformVpnBridge.desktop.kt
package com.nodex.vpn.platform

import com.nodex.vpn.manager.BootstrapStatus
import com.nodex.vpn.manager.RawVpnStats
import com.nodex.vpn.manager.RustVpnConfig
import com.sun.jna.*
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.delay
import java.io.File

// ── JNA interface to UniFFI-generated C API ───────────────────────────────────

interface NodeXNative : Library {
    fun nodex_vpn_start_nodex(
        socks_listen_addr:          String,
        dns_listen_addr:            String,
        use_bridges:                Byte,
        bridge_lines:               Array<String>?,
        bridge_lines_len:           Int,
        strict_exit_nodes:          Byte,
        preferred_exit_iso:         String?,
        circuit_build_timeout_secs: Int,
        state_dir:                  String,
        cache_dir:                  String,
        out_err:                    IntByReference,
    )
    fun nodex_vpn_stop_nodex(out_err: IntByReference)
    fun nodex_vpn_is_running(): Byte
    fun nodex_vpn_set_exit_node(iso_code: String, out_err: IntByReference)
    fun nodex_vpn_get_bootstrap_percent(): Byte
    fun nodex_vpn_get_bootstrap_phase(): String
    fun nodex_vpn_get_bootstrap_complete(): Byte
    fun nodex_vpn_get_bytes_sent(): Long
    fun nodex_vpn_get_bytes_received(): Long
    fun nodex_vpn_get_send_rate_bps(): Long
    fun nodex_vpn_get_recv_rate_bps(): Long
    fun nodex_vpn_get_active_circuits(): Int
    fun nodex_vpn_get_latency_ms(): Double
    fun nodex_vpn_get_current_exit_country(): String
    fun nodex_vpn_get_current_exit_ip(): String
    fun nodex_vpn_get_uptime_secs(): Long
}

object NativeLib {
    val instance: NodeXNative by lazy {
        val libName = when {
            System.getProperty("os.name").startsWith("Win") -> "nodex_vpn_core"
            System.getProperty("os.name").startsWith("Mac") -> "nodex_vpn_core"
            else                                            -> "nodex_vpn_core"
        }
        Native.load(libName, NodeXNative::class.java) as NodeXNative
    }
}

// ── Desktop actual implementation ─────────────────────────────────────────────

actual class PlatformVpnBridge actual constructor() {

    private val lib = NativeLib.instance
    private val os  = System.getProperty("os.name", "").lowercase()

    actual suspend fun prepare() {
        // Desktop: verify we have TUN permissions (handled by PrivilegeChecker before launch)
    }

    actual suspend fun startEngine(config: RustVpnConfig) {
        val err = IntByReference()
        lib.nodex_vpn_start_nodex(
            socks_listen_addr          = config.socksListenAddr,
            dns_listen_addr            = config.dnsListenAddr,
            use_bridges                = if (config.useBridges) 1 else 0,
            bridge_lines               = config.bridgeLines.toTypedArray().takeIf { it.isNotEmpty() },
            bridge_lines_len           = config.bridgeLines.size,
            strict_exit_nodes          = if (config.strictExitNodes) 1 else 0,
            preferred_exit_iso         = config.preferredExitIso,
            circuit_build_timeout_secs = config.circuitBuildTimeoutSecs.toInt(),
            state_dir                  = stateDirectory(),
            cache_dir                  = cacheDirectory(),
            out_err                    = err,
        )
        if (err.value != 0) throw RuntimeException("Rust startNodex error code ${err.value}")
    }

    actual suspend fun stopEngine() {
        val err = IntByReference()
        lib.nodex_vpn_stop_nodex(err)
    }

    actual suspend fun awaitBootstrap() {
        while (lib.nodex_vpn_get_bootstrap_complete().toInt() == 0) {
            delay(300)
        }
    }

    actual suspend fun setExitNode(isoCode: String) {
        val err = IntByReference()
        lib.nodex_vpn_set_exit_node(isoCode, err)
    }

    actual fun getRealTimeStats() = RawVpnStats(
        bytesSent          = lib.nodex_vpn_get_bytes_sent(),
        bytesReceived      = lib.nodex_vpn_get_bytes_received(),
        sendRateBps        = lib.nodex_vpn_get_send_rate_bps(),
        recvRateBps        = lib.nodex_vpn_get_recv_rate_bps(),
        activeCircuits     = lib.nodex_vpn_get_active_circuits(),
        latencyMs          = lib.nodex_vpn_get_latency_ms(),
        currentExitCountry = lib.nodex_vpn_get_current_exit_country(),
        currentExitIp      = lib.nodex_vpn_get_current_exit_ip(),
        uptimeSecs         = lib.nodex_vpn_get_uptime_secs(),
    )

    actual fun getBootstrapStatus() = BootstrapStatus(
        percent      = lib.nodex_vpn_get_bootstrap_percent().toUByte(),
        phase        = lib.nodex_vpn_get_bootstrap_phase(),
        isComplete   = lib.nodex_vpn_get_bootstrap_complete().toInt() == 1,
        errorMessage = null,
    )

    actual fun stateDirectory(): String {
        val base = when {
            os.contains("win") -> System.getenv("APPDATA") ?: System.getProperty("user.home")
            os.contains("mac") -> "${System.getProperty("user.home")}/Library/Application Support"
            else               -> "${System.getProperty("user.home")}/.local/share"
        }
        return File(base, "NodeXVPN/state").also { it.mkdirs() }.absolutePath
    }

    actual fun cacheDirectory(): String {
        val base = when {
            os.contains("win") -> System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
            os.contains("mac") -> "${System.getProperty("user.home")}/Library/Caches"
            else               -> "${System.getProperty("user.home")}/.cache"
        }
        return File(base, "NodeXVPN").also { it.mkdirs() }.absolutePath
    }
}
