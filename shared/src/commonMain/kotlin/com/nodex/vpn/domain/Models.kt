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
        // Convert ISO country code to regional indicator symbols
        val base = 0x1F1E6 - 'A'.code
        return countryCode.uppercase()
            .filter { it.isLetter() }
            .take(2)
            .map { String(Character.toChars(base + it.code)) }
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
        return "%02d:%02d:%02d".format(h, m, s)
    }
    val sendRateLabel: String  get() = formatRate(sendRateBps)
    val recvRateLabel: String  get() = formatRate(recvRateBps)
    val totalDataLabel: String get() = formatBytes(bytesSent + bytesReceived)

    private fun formatRate(bps: Long): String = when {
        bps > 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000.0)
        bps > 1_000     -> "%.1f KB/s".format(bps / 1_000.0)
        else            -> "$bps B/s"
    }
    private fun formatBytes(b: Long): String = when {
        b > 1_073_741_824 -> "%.2f GB".format(b / 1_073_741_824.0)
        b > 1_048_576     -> "%.1f MB".format(b / 1_048_576.0)
        b > 1_024         -> "%.1f KB".format(b / 1_024.0)
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
    val selectedNodeId:    String?  = null,
    val useBridges:        Boolean  = false,
    val bridgeLines:       List<String> = emptyList(),
    val strictExitNodes:   Boolean  = true,
    val killSwitch:        Boolean  = true,
    val dnsOverTor:        Boolean  = true,
    val autoConnect:       Boolean  = false,
    val circuitTimeout:    Int      = 30,
)
