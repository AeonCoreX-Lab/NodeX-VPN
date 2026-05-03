// shared/src/commonMain/kotlin/com/nodex/vpn/domain/Models.kt
package com.nodex.vpn.domain

import kotlinx.serialization.Serializable
import kotlin.time.Duration

// ── VPN connection state machine ──────────────────────────────────────────────

sealed interface VpnState {
    data object Idle        : VpnState
    data object Bootstrapping : VpnState
    data class  Connecting(val progress: Int, val phase: String) : VpnState
    data object Connected   : VpnState
    data object Disconnecting : VpnState
    data class  Error(val message: String) : VpnState
}

val VpnState.isConnected   get() = this is VpnState.Connected
val VpnState.isConnecting  get() = this is VpnState.Connecting || this is VpnState.Bootstrapping
val VpnState.isActive      get() = isConnected || isConnecting

// ── Server / Exit-node ────────────────────────────────────────────────────────

@Serializable
data class ServerNode(
    val id:             String,
    val countryCode:    String,   // ISO 3166-1 alpha-2, e.g. "DE"
    val countryName:    String,
    val city:           String,
    val latencyMs:      Double,
    val loadPercent:    Int,
    val isBridge:       Boolean,
    val supportsObfs4:  Boolean,
) {
    val flagEmoji: String get() {
        // Convert ISO country code to regional indicator symbols (KMP-safe surrogate pair encoding)
        val base = 0x1F1E6 - 'A'.code
        return countryCode.uppercase()
            .filter { it.isLetter() }
            .take(2)
            .map { ch ->
                val cp = base + ch.code
                // Regional indicator symbols are in supplementary plane — encode as surrogate pair
                val hi = ((cp - 0x10000) shr 10) + 0xD800
                val lo = ((cp - 0x10000) and 0x3FF) + 0xDC00
                "${hi.toChar()}${lo.toChar()}"
            }
            .joinToString("")
    }
    val latencyLabel: String get() = when {
        latencyMs < 100 -> "⚡ ${latencyMs.toInt()} ms"
        latencyMs < 300 -> "🟡 ${latencyMs.toInt()} ms"
        else            -> "🔴 ${latencyMs.toInt()} ms"
    }
    val loadColor: LoadColor get() = when {
        loadPercent < 50 -> LoadColor.Low
        loadPercent < 80 -> LoadColor.Medium
        else             -> LoadColor.High
    }
}

enum class LoadColor { Low, Medium, High }

// ── Live statistics ───────────────────────────────────────────────────────────

data class VpnStats(
    val bytesSent:          Long   = 0L,
    val bytesReceived:      Long   = 0L,
    val sendRateBps:        Long   = 0L,
    val recvRateBps:        Long   = 0L,
    val activeCircuits:     Int    = 0,
    val latencyMs:          Double = 0.0,
    val currentExitCountry: String = "—",
    val currentExitIp:      String = "0.0.0.0",
    val uptimeSeconds:      Long   = 0L,
) {
    val formattedUptime: String get() {
        val h = uptimeSeconds / 3600
        val m = (uptimeSeconds % 3600) / 60
        val s = uptimeSeconds % 60
        return "${h.toString().padStart(2,'0')}:${m.toString().padStart(2,'0')}:${s.toString().padStart(2,'0')}"
    }
    val sendRateLabel: String  get() = formatRate(sendRateBps)
    val recvRateLabel: String  get() = formatRate(recvRateBps)
    val totalDataLabel: String get() = formatBytes(bytesSent + bytesReceived)

    private fun Double.fmt1(): String {
        val v = kotlin.math.round(this * 10) / 10.0
        val i = v.toLong(); val d = kotlin.math.round((v - i) * 10).toLong()
        return "$i.$d"
    }
    private fun Double.fmt2(): String {
        val v = kotlin.math.round(this * 100) / 100.0
        val i = v.toLong(); val d = kotlin.math.round((v - i) * 100).toLong()
        return "$i.${d.toString().padStart(2,'0')}"
    }
    private fun formatRate(bps: Long): String = when {
        bps > 1_000_000 -> "${(bps / 1_000_000.0).fmt1()} MB/s"
        bps > 1_000     -> "${(bps / 1_000.0).fmt1()} KB/s"
        else            -> "$bps B/s"
    }
    private fun formatBytes(b: Long): String = when {
        b > 1_073_741_824 -> "${(b / 1_073_741_824.0).fmt2()} GB"
        b > 1_048_576     -> "${(b / 1_048_576.0).fmt1()} MB"
        b > 1_024         -> "${(b / 1_024.0).fmt1()} KB"
        else              -> "$b B"
    }
}

// ── Graph data ────────────────────────────────────────────────────────────────

data class TrafficPoint(val timestamp: Long, val sendBps: Long, val recvBps: Long)

data class TrafficHistory(
    val points: List<TrafficPoint> = emptyList(),
    val maxBps: Long = 1L,
) {
    fun add(point: TrafficPoint, maxHistory: Int = 60): TrafficHistory {
        val updated = (points + point).takeLast(maxHistory)
        return copy(points = updated, maxBps = updated.maxOfOrNull { maxOf(it.sendBps, it.recvBps) }?.coerceAtLeast(1L) ?: 1L)
    }
}

// ── VPN Configuration ─────────────────────────────────────────────────────────

@Serializable
data class NodeXConfig(
    val selectedNodeId:          String?      = null,
    val useBridges:              Boolean      = false,
    val bridgeLines:             List<String> = emptyList(),
    val strictExitNodes:         Boolean      = true,
    // Priority 1: Safety
    val killSwitch:              Boolean      = true,
    val autoReconnect:           Boolean      = true,
    // Priority 2: UX
    val dnsOverTor:              Boolean      = true,
    val httpsWarn:               Boolean      = true,
    val backgroundBootstrap:     Boolean      = true,
    val autoConnect:             Boolean      = false,
    val circuitTimeout:          Int          = 30,
    // Priority 3: Power users
    val onionAccess:             Boolean      = true,
    // Advanced: Split Tunneling
    val splitTunnelMode:         String       = "Disabled",
    val splitTunnelBypassApps:   List<String> = emptyList(),
    val splitTunnelBypassDomains:List<String> = emptyList(),
    // Advanced: Privacy
    val ipv6Protection:          Boolean      = true,
    val webRtcProtection:        Boolean      = true,
    val macRandomization:        Boolean      = false,
    // Advanced: Performance
    val speedTestEnabled:        Boolean      = true,
    val bandwidthUploadMbps:     Float        = 0f,   // 0 = unlimited
    val bandwidthDownloadMbps:   Float        = 0f,
    // Advanced: Auth
    val socks5Username:          String?      = null,
    val socks5Password:          String?      = null,
)
