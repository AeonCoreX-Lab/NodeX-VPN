// shared/src/commonMain/kotlin/com/nodex/vpn/ui/screens/DashboardScreen.kt
package com.nodex.vpn.ui.screens

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.nodex.vpn.domain.*
import com.nodex.vpn.manager.VpnManager
import com.nodex.vpn.ui.responsive.*
import com.nodex.vpn.ui.theme.NodeXColors
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun DashboardScreen(
    vpnManager:   VpnManager,
    windowSize:   WindowSizeClass,
    onShowServers: () -> Unit,
) {
    val state   by vpnManager.vpnState.collectAsState()
    val stats   by vpnManager.stats.collectAsState()
    val history by vpnManager.trafficHistory.collectAsState()
    val selNode by vpnManager.selectedNode.collectAsState()

    if (windowSize.isExpanded) {
        // ── Desktop: 2-column grid layout ─────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NodeXColors.Void)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Header
            DashboardHeader(state, windowSize)

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment     = Alignment.Top,
            ) {
                // Left column - connect button + info
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    GlowingConnectButton(state = state, size = 200.dp,
                        onConnect = { vpnManager.connect() }, onDisconnect = { vpnManager.disconnect() })
                    if (state is VpnState.Connecting || state is VpnState.Bootstrapping)
                        BootstrapCard(state)
                    if (state.isConnected)
                        ConnectionInfoCard(stats)
                    ServerSelectorCard(node = selNode, onClick = onShowServers)
                }

                // Right column - stats + graph
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.isActive) {
                        TrafficGraphCard(history, windowSize)
                        StatsGrid(stats, windowSize)
                    } else {
                        IdleInfoCard(windowSize)
                    }
                    if (state is VpnState.Error)
                        ErrorBanner((state as VpnState.Error).message)
                }
            }
        }
    } else if (windowSize.isMedium) {
        // ── Tablet: single column, bigger connect button ───────────────────────
        Column(
            modifier            = Modifier
                .fillMaxSize()
                .background(NodeXColors.Void)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            DashboardHeader(state, windowSize)
            GlowingConnectButton(state = state, size = 200.dp,
                onConnect = { vpnManager.connect() }, onDisconnect = { vpnManager.disconnect() })
            if (state is VpnState.Connecting || state is VpnState.Bootstrapping) BootstrapCard(state)
            if (state.isConnected) ConnectionInfoCard(stats)
            ServerSelectorCard(node = selNode, onClick = onShowServers)
            if (state.isActive) {
                TrafficGraphCard(history, windowSize)
                StatsGrid(stats, windowSize)
            }
            if (state is VpnState.Error) ErrorBanner((state as VpnState.Error).message)
        }
    } else {
        // ── Phone: standard scroll layout ─────────────────────────────────────
        Column(
            modifier            = Modifier
                .fillMaxSize()
                .background(NodeXColors.Void)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            DashboardHeader(state, windowSize)
            GlowingConnectButton(state = state, size = 160.dp,
                onConnect = { vpnManager.connect() }, onDisconnect = { vpnManager.disconnect() })
            if (state is VpnState.Connecting || state is VpnState.Bootstrapping) BootstrapCard(state)
            if (state.isConnected) ConnectionInfoCard(stats)
            ServerSelectorCard(node = selNode, onClick = onShowServers)
            if (state.isActive) {
                TrafficGraphCard(history, windowSize)
                StatsGrid(stats, windowSize)
            }
            if (state is VpnState.Error) ErrorBanner((state as VpnState.Error).message)
        }
    }
}

@Composable
private fun DashboardHeader(state: VpnState, windowSize: WindowSizeClass) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                "Dashboard",
                style = if (windowSize.isExpanded) MaterialTheme.typography.headlineLarge
                        else MaterialTheme.typography.headlineMedium,
                color = NodeXColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Tor-powered · Serverless · Anonymous",
                style = MaterialTheme.typography.labelSmall,
                color = NodeXColors.TextSecondary,
            )
        }
        StatusPill(state)
    }
}

@Composable
private fun StatusPill(state: VpnState) {
    val inf = rememberInfiniteTransition(label = "pill")
    val alpha by inf.animateFloat(0.5f, 1f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    val (color, label) = when (state) {
        is VpnState.Connected   -> NodeXColors.GreenPulse to "Connected"
        is VpnState.Connecting,
        is VpnState.Bootstrapping -> NodeXColors.AmberWarning to "Connecting"
        is VpnState.Error       -> NodeXColors.RedAlert to "Error"
        else                    -> NodeXColors.TextMuted to "Idle"
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = if (state.isConnecting) alpha else 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(7.dp).background(color.copy(alpha = if (state.isConnecting) alpha else 1f), RoundedCornerShape(50)))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun GlowingConnectButton(
    state: VpnState, size: Dp,
    onConnect: () -> Unit, onDisconnect: () -> Unit,
) {
    val inf = rememberInfiniteTransition(label = "btn")
    val glowR  by inf.animateFloat(size.value * 0.3f, size.value * 0.5f,
        infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "gr")
    val ringS  by inf.animateFloat(1f, 1.12f,
        infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "rs")

    val (label, icon, btnColor, glowColor) = when (state) {
        is VpnState.Connected     -> Q("DISCONNECT", Icons.Default.PowerSettingsNew, NodeXColors.RedAlert,   NodeXColors.GlowPurple)
        is VpnState.Connecting,
        is VpnState.Bootstrapping -> Q("CONNECTING…", Icons.Default.Loop,            NodeXColors.AmberWarning, NodeXColors.GlowCyan)
        is VpnState.Disconnecting -> Q("STOPPING…",   Icons.Default.HourglassTop,   NodeXColors.TextSecondary, Color.Transparent)
        else                      -> Q("CONNECT",     Icons.Default.Shield,          NodeXColors.CyanGlow,   NodeXColors.GlowCyan)
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size + 40.dp)) {
        if (state.isActive) {
            Canvas(modifier = Modifier.size(size + 40.dp).scale(ringS)) {
                drawCircle(Brush.radialGradient(listOf(glowColor, Color.Transparent), radius = glowR, center = center), glowR)
            }
        }
        Button(
            onClick  = if (state.isConnected) onDisconnect else onConnect,
            enabled  = state !is VpnState.Connecting && state !is VpnState.Bootstrapping && state !is VpnState.Disconnecting,
            shape    = RoundedCornerShape(50),
            modifier = Modifier.size(size),
            colors   = ButtonDefaults.buttonColors(containerColor = NodeXColors.DeepSpace, contentColor = btnColor),
            border   = BorderStroke(2.dp, btnColor),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, label, modifier = Modifier.size(size.value.dp * 0.22f), tint = btnColor)
                Spacer(Modifier.height(6.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = btnColor)
            }
        }
    }
}

@Composable
private fun ConnectionInfoCard(stats: VpnStats) {
    NodeXCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            InfoChip("Exit IP", stats.currentExitIp, NodeXColors.CyanGlow)
            InfoChip("Country", stats.currentExitCountry, NodeXColors.TextPrimary)
            InfoChip("Uptime", stats.formattedUptime, NodeXColors.GreenPulse)
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextMuted)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BootstrapCard(state: VpnState) {
    val (progress, phase) = when (state) {
        is VpnState.Connecting    -> state.progress / 100f to state.phase
        is VpnState.Bootstrapping -> 0.05f to "Initialising Tor…"
        else -> 0f to ""
    }
    NodeXCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Bootstrapping Tor", style = MaterialTheme.typography.bodyMedium, color = NodeXColors.TextPrimary)
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = NodeXColors.CyanGlow)
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = NodeXColors.CyanGlow, trackColor = NodeXColors.DarkMatter)
            Text(phase, style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextMuted)
        }
    }
}

@Composable
private fun ServerSelectorCard(node: ServerNode?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape   = RoundedCornerShape(16.dp),
        color   = NodeXColors.DeepSpace,
        border  = BorderStroke(1.dp, NodeXColors.NebulaDark),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(node?.flagEmoji ?: "🌐", fontSize = 26.sp)
                Column {
                    Text(node?.countryName ?: "Auto Select", style = MaterialTheme.typography.titleSmall, color = NodeXColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(node?.city ?: "Fastest server", style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextSecondary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                node?.let { Text(it.latencyLabel, style = MaterialTheme.typography.labelSmall) }
                Icon(Icons.Default.ChevronRight, null, tint = NodeXColors.TextMuted)
            }
        }
    }
}

@Composable
private fun TrafficGraphCard(history: TrafficHistory, windowSize: WindowSizeClass) {
    NodeXCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Live Traffic", style = MaterialTheme.typography.titleSmall, color = NodeXColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LegendDot(NodeXColors.CyanGlow,   "↑ Upload")
                    LegendDot(NodeXColors.PurpleNeon, "↓ Download")
                }
            }
            val graphHeight = if (windowSize.isExpanded) 120.dp else 80.dp
            Canvas(modifier = Modifier.fillMaxWidth().height(graphHeight)) {
                drawTrafficGraph(history)
            }
        }
    }
}

@Composable
private fun StatsGrid(stats: VpnStats, windowSize: WindowSizeClass) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatChip(Modifier.weight(1f), "↑ Upload",   stats.sendRateLabel,        NodeXColors.CyanGlow)
        StatChip(Modifier.weight(1f), "↓ Download", stats.recvRateLabel,        NodeXColors.PurpleNeon)
        StatChip(Modifier.weight(1f), "Latency",    "${stats.latencyMs.toInt()} ms", NodeXColors.AmberWarning)
        StatChip(Modifier.weight(1f), "Circuits",   "${stats.activeCircuits}",   NodeXColors.GreenPulse)
    }
}

@Composable
private fun IdleInfoCard(windowSize: WindowSizeClass) {
    NodeXCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Info, null, tint = NodeXColors.TextMuted, modifier = Modifier.size(32.dp))
            Text("Connect to see live stats", style = MaterialTheme.typography.bodyMedium, color = NodeXColors.TextMuted)
            Text("Traffic graph will appear here", style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextMuted.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun StatChip(modifier: Modifier, label: String, value: String, color: Color) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = NodeXColors.DeepSpace, border = BorderStroke(1.dp, color.copy(alpha = 0.3f))) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextMuted)
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = NodeXColors.RedAlert.copy(alpha = 0.15f), border = BorderStroke(1.dp, NodeXColors.RedAlert)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.ErrorOutline, null, tint = NodeXColors.RedAlert)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = NodeXColors.RedAlert)
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        Text(label, style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextMuted)
    }
}

@Composable
private fun NodeXCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = NodeXColors.DeepSpace, border = BorderStroke(1.dp, NodeXColors.NebulaDark)) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

private fun DrawScope.drawTrafficGraph(history: TrafficHistory) {
    if (history.points.isEmpty()) return
    val w = size.width; val h = size.height
    val maxBps = history.maxBps.toFloat().coerceAtLeast(1f)
    val n = history.points.size
    val step = if (n > 1) w / (n - 1) else w
    fun xOf(i: Int) = i * step
    fun yOf(v: Long) = h - (v.toFloat() / maxBps) * h * 0.85f
    repeat(4) { i -> drawLine(NodeXColors.DarkMatter, Offset(0f, h / 4 * i), Offset(w, h / 4 * i), 0.5f) }
    if (n >= 2) {
        val sendP = Path().apply { history.points.forEachIndexed { i, p -> if (i == 0) moveTo(xOf(i), yOf(p.sendBps)) else lineTo(xOf(i), yOf(p.sendBps)) } }
        drawPath(sendP, NodeXColors.CyanGlow, style = Stroke(2f))
        val recvP = Path().apply { history.points.forEachIndexed { i, p -> if (i == 0) moveTo(xOf(i), yOf(p.recvBps)) else lineTo(xOf(i), yOf(p.recvBps)) } }
        drawPath(recvP, NodeXColors.PurpleNeon, style = Stroke(2f))
    }
}

private data class Q<A,B,C,D>(val a: A, val b: B, val c: C, val d: D)
private operator fun <A,B,C,D> Q<A,B,C,D>.component1() = a
private operator fun <A,B,C,D> Q<A,B,C,D>.component2() = b
private operator fun <A,B,C,D> Q<A,B,C,D>.component3() = c
private operator fun <A,B,C,D> Q<A,B,C,D>.component4() = d

private fun Modifier.clip(shape: androidx.compose.ui.graphics.Shape) = graphicsLayer { clip = true; this.shape = shape }
