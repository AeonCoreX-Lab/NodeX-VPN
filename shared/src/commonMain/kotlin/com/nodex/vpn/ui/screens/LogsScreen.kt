// shared/src/commonMain/kotlin/com/nodex/vpn/ui/screens/LogsScreen.kt
package com.nodex.vpn.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.nodex.vpn.manager.VpnManager
import com.nodex.vpn.ui.responsive.*
import com.nodex.vpn.ui.theme.NodeXColors
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock

data class LogLine(val timestamp: String, val level: String, val message: String, val levelColor: Color)

@Composable
fun LogsScreen(vpnManager: VpnManager, windowSize: WindowSizeClass) {
    var logs   by remember { mutableStateOf(listOf<LogLine>()) }
    var paused by remember { mutableStateOf(false) }
    var filterLevel by remember { mutableStateOf("ALL") }
    val listState = rememberLazyListState()

    LaunchedEffect(paused) {
        if (!paused) {
            while (true) {
                logs = (logs + buildDemoLog()).takeLast(500)
                delay(2_000)
            }
        }
    }
    LaunchedEffect(logs.size) {
        if (!paused && logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    val displayed = remember(logs, filterLevel) {
        if (filterLevel == "ALL") logs else logs.filter { it.level.contains(filterLevel) }
    }

    val hPad = when {
        windowSize.isExpanded -> 32.dp
        windowSize.isMedium   -> 24.dp
        else                  -> 16.dp
    }

    Column(modifier = Modifier.fillMaxSize().background(NodeXColors.Void)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = hPad, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Engine Logs", style = if (windowSize.isExpanded) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge, color = NodeXColors.TextPrimary, fontWeight = FontWeight.Bold)
                Text("${displayed.size} entries", style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Level filter chips
                listOf("ALL", "INFO", "WARN", "ERROR").forEach { level ->
                    FilterChip(
                        selected = filterLevel == level,
                        onClick  = { filterLevel = level },
                        label    = { Text(level, style = MaterialTheme.typography.labelSmall) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NodeXColors.CyanGlow.copy(alpha = 0.2f),
                            selectedLabelColor = NodeXColors.CyanGlow,
                            containerColor = NodeXColors.DeepSpace,
                            labelColor = NodeXColors.TextMuted,
                        )
                    )
                }
                IconButton(onClick = { paused = !paused }) {
                    Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, "Pause",
                        tint = if (paused) NodeXColors.GreenPulse else NodeXColors.AmberWarning)
                }
                IconButton(onClick = { logs = emptyList() }) {
                    Icon(Icons.Default.DeleteSweep, "Clear", tint = NodeXColors.TextMuted)
                }
            }
        }

        // Terminal
        Surface(
            modifier = Modifier.fillMaxSize().padding(horizontal = hPad).padding(bottom = 16.dp),
            shape    = RoundedCornerShape(16.dp),
            color    = NodeXColors.DeepSpace,
            border   = BorderStroke(1.dp, NodeXColors.NebulaDark),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (displayed.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            Text("No logs yet. Connect to start.", style = MaterialTheme.typography.bodyMedium, color = NodeXColors.TextMuted)
                        }
                    }
                } else {
                    items(displayed) { line -> LogEntry(line, windowSize) }
                }
            }
        }
    }
}

@Composable
private fun LogEntry(line: LogLine, windowSize: WindowSizeClass) {
    val fontSize = if (windowSize.isExpanded) 13.sp else 11.sp
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(line.timestamp, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = fontSize), color = NodeXColors.TextMuted, modifier = Modifier.width(if (windowSize.isExpanded) 100.dp else 80.dp))
        Text(line.level, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = fontSize, fontWeight = FontWeight.Bold), color = line.levelColor, modifier = Modifier.width(56.dp))
        Text(line.message, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = fontSize), color = NodeXColors.TextSecondary, modifier = Modifier.weight(1f))
    }
}

private var demoCounter = 0
private fun buildDemoLog(): LogLine {
    val pool = listOf(
        "INFO"  to "Tor circuit built successfully",
        "INFO"  to "Exit relay: DE Frankfurt (load 31%)",
        "DEBUG" to "SOCKS5 CONNECT → api.ipify.org:443",
        "INFO"  to "Bootstrap: 100% – Connected",
        "DEBUG" to "Circuit extended: guard→middle→exit",
        "INFO"  to "DNS query resolved via Tor",
        "DEBUG" to "Bandwidth: ↑ 12.4 KB/s  ↓ 87.2 KB/s",
        "WARN"  to "Circuit timeout, rebuilding…",
        "INFO"  to "New circuit ID: 0x4A2F",
        "DEBUG" to "obfs4 bridge handshake OK",
    )
    val (level, msg) = pool[demoCounter++ % pool.size]
    val now = Clock.System.now().toString().take(19).replace("T", " ")
    val color = when (level) {
        "ERROR" -> NodeXColors.RedAlert
        "WARN"  -> NodeXColors.AmberWarning
        "DEBUG" -> NodeXColors.TextMuted
        else    -> NodeXColors.GreenPulse
    }
    return LogLine(now, "[$level]", msg, color)
}
