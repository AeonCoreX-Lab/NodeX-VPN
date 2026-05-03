// shared/src/commonMain/kotlin/com/nodex/vpn/manager/VpnManager.kt
package com.nodex.vpn.manager

import com.nodex.vpn.domain.*
import com.nodex.vpn.platform.PlatformVpnBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock

/**
 * Central VPN orchestrator.
 *
 * Responsibilities:
 *  - Delegates platform-level tunnel control to [PlatformVpnBridge]
 *    (Android VpnService / iOS NetworkExtension / Desktop Rust TUN)
 *  - Polls the Rust core for stats and bootstrap progress
 *  - Exposes reactive [StateFlow]s consumed by the Compose UI
 *  - Manages the selected server and connection lifecycle
 */
class VpnManager(
    private val platform: PlatformVpnBridge,
    private val scope:    CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    // ── Public state ──────────────────────────────────────────────────────────

    private val _vpnState    = MutableStateFlow<VpnState>(VpnState.Idle)
    private val _stats       = MutableStateFlow(VpnStats())
    private val _history     = MutableStateFlow(TrafficHistory())
    private val _nodes       = MutableStateFlow(defaultNodes())
    private val _selectedNode = MutableStateFlow<ServerNode?>(_nodes.value.firstOrNull())
    private val _config      = MutableStateFlow(NodeXConfig())

    val vpnState:     StateFlow<VpnState>      = _vpnState.asStateFlow()
    val stats:        StateFlow<VpnStats>       = _stats.asStateFlow()
    val trafficHistory: StateFlow<TrafficHistory> = _history.asStateFlow()
    val nodes:        StateFlow<List<ServerNode>> = _nodes.asStateFlow()
    val selectedNode: StateFlow<ServerNode?>    = _selectedNode.asStateFlow()
    val config:       StateFlow<NodeXConfig>    = _config.asStateFlow()

    // Derived
    val isConnected: StateFlow<Boolean> = vpnState
        .map { it.isConnected }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var statsJob:     Job? = null
    private var bootstrapJob: Job? = null

    // ── Connection control ────────────────────────────────────────────────────

    fun connect() {
        if (_vpnState.value.isActive) return
        val node = _selectedNode.value ?: return

        scope.launch {
            _vpnState.emit(VpnState.Bootstrapping)
            try {
                // 1. Ask the platform to prepare the VPN tunnel
                platform.prepare()

                // 2. Build VpnConfig for Rust core
                val cfg = _config.value
                val rustCfg = buildRustConfig(cfg, node)

                // 3. Start polling bootstrap progress
                bootstrapJob = launchBootstrapPoller()

                // 4. Start the engine
                platform.startEngine(rustCfg)

                // 5. Wait until bootstrap is complete (or error)
                platform.awaitBootstrap()

                _vpnState.emit(VpnState.Connected)
                bootstrapJob?.cancel()

                // 6. Start stats polling
                statsJob = launchStatsPoller()

            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _vpnState.emit(VpnState.Error(e.message ?: "Unknown error"))
                statsJob?.cancel()
                bootstrapJob?.cancel()
            }
        }
    }

    fun disconnect() {
        if (!_vpnState.value.isActive) return
        scope.launch {
            _vpnState.emit(VpnState.Disconnecting)
            try {
                statsJob?.cancelAndJoin()
                platform.stopEngine()
                _vpnState.emit(VpnState.Idle)
                _stats.emit(VpnStats())
            } catch (e: Exception) {
                _vpnState.emit(VpnState.Error(e.message ?: "Stop failed"))
            }
        }
    }

    fun selectNode(node: ServerNode) {
        _selectedNode.value = node
        // If connected, rebuild circuit to new exit
        if (_vpnState.value.isConnected) {
            scope.launch { platform.setExitNode(node.countryCode) }
        }
    }

    fun updateConfig(transform: (NodeXConfig) -> NodeXConfig) {
        _config.value = transform(_config.value)
    }

    // ── Pollers ───────────────────────────────────────────────────────────────

    private fun launchStatsPoller(): Job = scope.launch {
        while (isActive) {
            try {
                val raw = platform.getRealTimeStats()
                val s = VpnStats(
                    bytesSent          = raw.bytesSent,
                    bytesReceived      = raw.bytesReceived,
                    sendRateBps        = raw.sendRateBps,
                    recvRateBps        = raw.recvRateBps,
                    activeCircuits     = raw.activeCircuits,
                    latencyMs          = raw.latencyMs,
                    currentExitCountry = raw.currentExitCountry,
                    currentExitIp      = raw.currentExitIp,
                    uptimeSeconds      = raw.uptimeSecs,
                )
                _stats.emit(s)
                _history.value = _history.value.add(
                    TrafficPoint(
                        timestamp = Clock.System.now().epochSeconds,
                        sendBps   = s.sendRateBps,
                        recvBps   = s.recvRateBps,
                    )
                )
            } catch (_: Exception) {}
            delay(1_000)
        }
    }

    private fun launchBootstrapPoller(): Job = scope.launch {
        while (isActive) {
            try {
                val bs = platform.getBootstrapStatus()
                _vpnState.emit(VpnState.Connecting(bs.percent.toInt(), bs.phase))
                if (bs.isComplete) break
                bs.errorMessage?.let { throw Exception(it) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _vpnState.emit(VpnState.Error(e.message ?: "")); break }
            delay(300)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildRustConfig(cfg: NodeXConfig, node: ServerNode) = RustVpnConfig(
        socksListenAddr          = "127.0.0.1:9050",
        dnsListenAddr            = "127.0.0.1:5353",
        useBridges               = cfg.useBridges,
        bridgeLines              = cfg.bridgeLines,
        strictExitNodes          = cfg.strictExitNodes,
        preferredExitIso         = node.countryCode,
        circuitBuildTimeoutSecs  = cfg.circuitTimeout.toUInt(),
        stateDir                 = platform.stateDirectory(),
        cacheDir                 = platform.cacheDirectory(),
        // Priority 1: Safety
        killSwitch               = cfg.killSwitch,
        autoReconnect            = cfg.autoReconnect,
        // Priority 2: UX
        httpsWarn                = cfg.httpsWarn,
        backgroundBootstrap      = cfg.backgroundBootstrap,
        // Priority 3: Power users
        onionAccess              = cfg.onionAccess,
    )

    fun dispose() {
        statsJob?.cancel()
        bootstrapJob?.cancel()
        scope.cancel()
    }
}

// ── Data class matching Rust VpnConfig (passed through PlatformVpnBridge) ────

data class RustVpnConfig(
    val socksListenAddr:         String,
    val dnsListenAddr:           String,
    val useBridges:              Boolean,
    val bridgeLines:             List<String>,
    val strictExitNodes:         Boolean,
    val preferredExitIso:        String?,
    val circuitBuildTimeoutSecs: UInt,
    val stateDir:                String,
    val cacheDir:                String,
    // Priority 1: Safety
    val killSwitch:              Boolean = true,
    val autoReconnect:           Boolean = true,
    // Priority 2: UX
    val httpsWarn:               Boolean = true,
    val backgroundBootstrap:     Boolean = true,
    // Priority 3: Power users
    val onionAccess:             Boolean = true,
)

// ── Data class mirroring Rust BootstrapStatus ─────────────────────────────────

data class BootstrapStatus(
    val percent:      UByte,
    val phase:        String,
    val isComplete:   Boolean,
    val errorMessage: String?,
)

// ── Data class mirroring Rust VpnStats ───────────────────────────────────────

data class RawVpnStats(
    val bytesSent:          Long,
    val bytesReceived:      Long,
    val sendRateBps:        Long,
    val recvRateBps:        Long,
    val activeCircuits:     Int,
    val latencyMs:          Double,
    val currentExitCountry: String,
    val currentExitIp:      String,
    val uptimeSecs:         Long,
)

// ── Hardcoded node list (augmented at runtime via Tor consensus) ──────────────

private fun defaultNodes(): List<ServerNode> = listOf(
    ServerNode("us-1",  "US", "United States", "New York",     45.0,  42, false, false),
    ServerNode("de-1",  "DE", "Germany",        "Frankfurt",   22.0,  31, false, false),
    ServerNode("nl-1",  "NL", "Netherlands",    "Amsterdam",   18.0,  28, false, false),
    ServerNode("jp-1",  "JP", "Japan",          "Tokyo",       120.0, 55, false, false),
    ServerNode("gb-1",  "GB", "United Kingdom", "London",      35.0,  38, false, false),
    ServerNode("sg-1",  "SG", "Singapore",      "Singapore",   98.0,  47, false, false),
    ServerNode("ca-1",  "CA", "Canada",         "Toronto",     52.0,  33, false, false),
    ServerNode("fr-1",  "FR", "France",         "Paris",       28.0,  25, false, false),
    ServerNode("au-1",  "AU", "Australia",      "Sydney",      210.0, 60, false, false),
    ServerNode("ch-1",  "CH", "Switzerland",    "Zurich",      25.0,  22, false, false),
    ServerNode("br-1",  "BR", "Brazil",         "São Paulo",   180.0, 70, true,  true),
    ServerNode("in-1",  "IN", "India",          "Mumbai",      140.0, 65, true,  true),
)
