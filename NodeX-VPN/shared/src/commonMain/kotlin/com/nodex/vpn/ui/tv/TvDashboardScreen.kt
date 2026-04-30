// shared/src/commonMain/kotlin/com/nodex/vpn/ui/tv/TvDashboardScreen.kt
package com.nodex.vpn.ui.tv

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.nodex.vpn.domain.*
import com.nodex.vpn.manager.VpnManager
import com.nodex.vpn.ui.responsive.WindowSizeClass
import com.nodex.vpn.ui.theme.NodeXColors
import kotlin.math.min

@Composable
fun TvDashboardScreen(
    vpnManager:    VpnManager,
    windowSize:    WindowSizeClass,
    onShowServers: () -> Unit,
) {
    val state   by vpnManager.vpnState.collectAsState()
    val stats   by vpnManager.stats.collectAsState()
    val history by vpnManager.trafficHistory.collectAsState()
    val selNode by vpnManager.selectedNode.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(NodeXColors.Void)
            .padding(40.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        // ── Left column: connect + node selector ──────────────────────────────
        Column(
            modifier            = Modifier.width(420.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            TvStatusHeader(state)
            TvConnectButton(
                state       = state,
                onConnect   = { vpnManager.connect() },
                onDisconnect = { vpnManager.disconnect() },
            )
            if (state is VpnState.Connecting || state is VpnState.Bootstrapping) {
                TvBootstrapCard(state)
            }
            if (state.isConnected) {
                TvConnectionInfoCard(stats)
            }
            TvServerCard(node = selNode, onFocus = {}, onClick = onShowServers)
        }

        // ── Right column: stats + traffic graph ───────────────────────────────
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            TvSectionTitle("Live Statistics")
            if (state.isActive) {
                TvStatsRow(stats)
                Spacer(Modifier.height(8.dp))
                TvTrafficGraph(history)
            } else {
                TvIdleCard()
            }
            if (state is VpnState.Error) {
                TvErrorCard((state as VpnState.Error).message)
            }
        }
    }
}

// ── Status header ─────────────────────────────────────────────────────────────
@Composable
private fun TvStatusHeader(state: VpnState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "Dashboard",
            style      = MaterialTheme.typography.headlineLarge,
            color      = NodeXColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize   = 32.sp,
        )
        val (color, label) = when (state) {
            is VpnState.Connected                           -> NodeXColors.GreenPulse  to "Connected"
            is VpnState.Connecting, is VpnState.Bootstrapping -> NodeXColors.AmberWarning to "Connecting…"
            is VpnState.Error                               -> NodeXColors.RedAlert    to "Error"
            else                                            -> NodeXColors.TextMuted   to "Idle"
        }
        Surface(
            shape  = RoundedCornerShape(50),
            color  = color.copy(alpha = 0.15f),
            border = BorderStroke(1.5.dp, color.copy(alpha = 0.5f)),
        ) {
            Row(
                modifier              = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
                Text(label, style = MaterialTheme.typography.titleSmall,
                    color = color, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ── Big TV connect button ─────────────────────────────────────────────────────
@Composable
private fun TvConnectButton(
    state:       VpnState,
    onConnect:   () -> Unit,
    onDisconnect: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusReq  = remember { FocusRequester() }

    val inf = rememberInfiniteTransition(label = "btn")
    val glowAlpha by inf.animateFloat(0.3f, 0.8f,
        infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "ga")
    val scale by animateFloatAsState(if (isFocused) 1.04f else 1f, label = "sc")

    val (label, icon, btnColor) = when (state) {
        is VpnState.Connected     -> Triple("DISCONNECT", Icons.Default.PowerSettingsNew, NodeXColors.RedAlert)
        is VpnState.Connecting,
        is VpnState.Bootstrapping -> Triple("CONNECTING…", Icons.Default.Loop, NodeXColors.AmberWarning)
        else                      -> Triple("CONNECT",   Icons.Default.Shield,             NodeXColors.CyanGlow)
    }

    val glowColor = if (state.isConnected) NodeXColors.GlowPurple else NodeXColors.GlowCyan
    val enabled   = state !is VpnState.Connecting && state !is VpnState.Bootstrapping &&
                    state !is VpnState.Disconnecting

    Box(
        contentAlignment = Alignment.Center,
        modifier         = Modifier.size(240.dp),
    ) {
        if (state.isActive || isFocused) {
            Canvas(Modifier.size(280.dp).scale(scale)) {
                drawCircle(
                    Brush.radialGradient(listOf(glowColor.copy(alpha = glowAlpha), Color.Transparent),
                        radius = 140f, center = Offset(size.width / 2, size.height / 2)),
                    radius = 140f,
                )
            }
        }
        Surface(
            onClick  = { if (enabled) { if (state.isConnected) onDisconnect() else onConnect() } },
            enabled  = enabled,
            shape    = RoundedCornerShape(50),
            color    = NodeXColors.DeepSpace,
            border   = BorderStroke(if (isFocused) 3.dp else 2.dp,
                if (isFocused) NodeXColors.CyanGlow else btnColor),
            modifier = Modifier
                .size(220.dp)
                .scale(scale)
                .focusRequester(focusReq)
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .onKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown &&
                        (ev.key == Key.DirectionCenter || ev.key == Key.Enter)) {
                        if (enabled) { if (state.isConnected) onDisconnect() else onConnect() }
                        true
                    } else false
                },
        ) {
            Column(
                modifier            = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, label, modifier = Modifier.size(52.dp), tint = btnColor)
                Spacer(Modifier.height(12.dp))
                Text(label, style = MaterialTheme.typography.titleLarge,
                    color = btnColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focusReq.requestFocus() } }
}

// ── Bootstrap progress ────────────────────────────────────────────────────────
@Composable
private fun TvBootstrapCard(state: VpnState) {
    val (progress, phase) = when (state) {
        is VpnState.Connecting    -> state.progress / 100f to state.phase
        is VpnState.Bootstrapping -> 0.05f to "Initialising Tor…"
        else                      -> 0f to ""
    }
    TvCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("Bootstrapping Tor", style = MaterialTheme.typography.titleMedium,
                    color = NodeXColors.TextPrimary)
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium,
                    color = NodeXColors.CyanGlow, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(
                progress     = { progress },
                modifier     = Modifier.fillMaxWidth().height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color        = NodeXColors.CyanGlow,
                trackColor   = NodeXColors.DarkMatter,
            )
            Text(phase, style = MaterialTheme.typography.bodyMedium,
                color = NodeXColors.TextMuted)
        }
    }
}

// ── Connection info ────────────────────────────────────────────────────────────
@Composable
private fun TvConnectionInfoCard(stats: VpnStats) {
    TvCard {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
            TvInfoChip("Exit IP",   stats.currentExitIp,      NodeXColors.CyanGlow)
            TvInfoChip("Country",   stats.currentExitCountry, NodeXColors.TextPrimary)
            TvInfoChip("Uptime",    stats.formattedUptime,    NodeXColors.GreenPulse)
        }
    }
}

@Composable
private fun TvInfoChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = NodeXColors.TextMuted)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color,
            fontWeight = FontWeight.SemiBold)
    }
}

// ── Server selector card ───────────────────────────────────────────────────────
@Composable
private fun TvServerCard(node: ServerNode?, onFocus: () -> Unit, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick  = onClick,
        shape    = RoundedCornerShape(16.dp),
        color    = NodeXColors.DeepSpace,
        border   = BorderStroke(if (isFocused) 2.dp else 1.dp,
            if (isFocused) NodeXColors.CyanGlow else NodeXColors.NebulaDark),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused; if (it.isFocused) onFocus() }
            .focusable(),
    ) {
        Row(
            modifier          = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(node?.flagEmoji ?: "🌐", fontSize = 32.sp)
                Column {
                    Text(node?.countryName ?: "Auto Select",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (isFocused) NodeXColors.CyanGlow else NodeXColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold)
                    Text(node?.city ?: "Fastest server",
                        style = MaterialTheme.typography.bodyMedium, color = NodeXColors.TextSecondary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                node?.let { Text(it.latencyLabel, style = MaterialTheme.typography.bodyMedium) }
                Icon(Icons.Default.ChevronRight, null, tint = NodeXColors.TextMuted,
                    modifier = Modifier.size(28.dp))
            }
        }
    }
}

// ── Stats row ─────────────────────────────────────────────────────────────────
@Composable
private fun TvStatsRow(stats: VpnStats) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TvStatCard(Modifier.weight(1f), "↑ Upload",   stats.sendRateLabel,             NodeXColors.CyanGlow)
        TvStatCard(Modifier.weight(1f), "↓ Download", stats.recvRateLabel,             NodeXColors.PurpleNeon)
        TvStatCard(Modifier.weight(1f), "Latency",    "${stats.latencyMs.toInt()} ms", NodeXColors.AmberWarning)
        TvStatCard(Modifier.weight(1f), "Circuits",   "${stats.activeCircuits}",        NodeXColors.GreenPulse)
    }
}

@Composable
private fun TvStatCard(modifier: Modifier, label: String, value: String, color: Color) {
    Surface(
        modifier = modifier,
        shape    = RoundedCornerShape(16.dp),
        color    = NodeXColors.DeepSpace,
        border   = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier            = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall,
                color = color, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = NodeXColors.TextMuted)
        }
    }
}

// ── Traffic graph ─────────────────────────────────────────────────────────────
@Composable
private fun TvTrafficGraph(history: TrafficHistory) {
    TvCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Live Traffic", style = MaterialTheme.typography.titleLarge,
                    color = NodeXColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TvLegendDot(NodeXColors.CyanGlow,   "↑ Upload")
                    TvLegendDot(NodeXColors.PurpleNeon, "↓ Download")
                }
            }
            Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                if (history.points.isEmpty()) return@Canvas
                val w = size.width; val h = size.height
                val maxBps = history.maxBps.toFloat().coerceAtLeast(1f)
                val n = history.points.size
                val step = if (n > 1) w / (n - 1) else w
                repeat(4) { i ->
                    drawLine(NodeXColors.DarkMatter, Offset(0f, h / 4 * i),
                        Offset(w, h / 4 * i), 0.8f)
                }
                if (n >= 2) {
                    val sendPath = androidx.compose.ui.graphics.Path().apply {
                        history.points.forEachIndexed { i, p ->
                            val x = i * step
                            val y = h - (p.sendBps.toFloat() / maxBps) * h * 0.85f
                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }
                    drawPath(sendPath, NodeXColors.CyanGlow, style = Stroke(3f))
                    val recvPath = androidx.compose.ui.graphics.Path().apply {
                        history.points.forEachIndexed { i, p ->
                            val x = i * step
                            val y = h - (p.recvBps.toFloat() / maxBps) * h * 0.85f
                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }
                    drawPath(recvPath, NodeXColors.PurpleNeon, style = Stroke(3f))
                }
            }
        }
    }
}

@Composable
private fun TvLegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = NodeXColors.TextMuted)
    }
}

@Composable
private fun TvIdleCard() {
    TvCard {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Info, null, tint = NodeXColors.TextMuted, modifier = Modifier.size(40.dp))
            Text("Press CONNECT to start the VPN",
                style = MaterialTheme.typography.titleMedium, color = NodeXColors.TextMuted)
            Text("Traffic graph and statistics will appear here",
                style = MaterialTheme.typography.bodyMedium,
                color = NodeXColors.TextMuted.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun TvErrorCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        color    = NodeXColors.RedAlert.copy(alpha = 0.12f),
        border   = BorderStroke(1.dp, NodeXColors.RedAlert),
    ) {
        Row(
            modifier          = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = NodeXColors.RedAlert,
                modifier = Modifier.size(28.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = NodeXColors.RedAlert)
        }
    }
}

@Composable
fun TvSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge,
        color = NodeXColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
}

@Composable
fun TvCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        color    = NodeXColors.DeepSpace,
        border   = BorderStroke(1.dp, NodeXColors.NebulaDark),
    ) {
        Column(modifier = Modifier.padding(20.dp), content = content)
    }
}
