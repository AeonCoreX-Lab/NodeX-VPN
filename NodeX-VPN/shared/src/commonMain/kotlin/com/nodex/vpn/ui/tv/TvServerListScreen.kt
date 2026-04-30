// shared/src/commonMain/kotlin/com/nodex/vpn/ui/tv/TvServerListScreen.kt
package com.nodex.vpn.ui.tv

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.nodex.vpn.domain.*
import com.nodex.vpn.manager.VpnManager
import com.nodex.vpn.ui.responsive.WindowSizeClass
import com.nodex.vpn.ui.theme.NodeXColors

@Composable
fun TvServerListScreen(
    vpnManager: VpnManager,
    windowSize: WindowSizeClass,
) {
    val nodes        by vpnManager.nodes.collectAsState()
    val selectedNode by vpnManager.selectedNode.collectAsState()
    var query        by remember { mutableStateOf("") }
    var sortBy       by remember { mutableStateOf(TvSortBy.Latency) }

    val filtered = remember(nodes, query, sortBy) {
        nodes.filter { n ->
            query.isEmpty() || n.countryName.contains(query, true) ||
            n.countryCode.contains(query, true) || n.city.contains(query, true)
        }.let { list ->
            when (sortBy) {
                TvSortBy.Latency  -> list.sortedBy { it.latencyMs }
                TvSortBy.Load     -> list.sortedBy { it.loadPercent }
                TvSortBy.Country  -> list.sortedBy { it.countryName }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NodeXColors.Void)
            .padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Header
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Server Selection",
                    style = MaterialTheme.typography.headlineLarge,
                    color = NodeXColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 32.sp)
                Text("${filtered.size} servers available",
                    style = MaterialTheme.typography.bodyLarge, color = NodeXColors.TextMuted)
            }
            // Sort chips
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvSortBy.entries.forEach { sort ->
                    TvFilterChip(
                        label    = sort.label,
                        selected = sortBy == sort,
                        onClick  = { sortBy = sort },
                    )
                }
            }
        }

        // Server grid — 2 columns for TV
        LazyVerticalGrid(
            columns               = GridCells.Fixed(2),
            contentPadding        = PaddingValues(0.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement   = Arrangement.spacedBy(12.dp),
            modifier              = Modifier.weight(1f),
        ) {
            items(filtered, key = { it.id }) { node ->
                TvServerRow(
                    node       = node,
                    isSelected = node.id == selectedNode?.id,
                    onClick    = { vpnManager.selectNode(node) },
                )
            }
        }
    }
}

// ── Single server row card ─────────────────────────────────────────────────────
@Composable
private fun TvServerRow(
    node:       ServerNode,
    isSelected: Boolean,
    onClick:    () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    val borderColor = when {
        isFocused  -> NodeXColors.CyanGlow
        isSelected -> NodeXColors.CyanGlow.copy(alpha = 0.5f)
        else       -> NodeXColors.NebulaDark
    }
    val bgColor = when {
        isSelected -> NodeXColors.CyanGlow.copy(alpha = 0.08f)
        isFocused  -> NodeXColors.NebulaDark.copy(alpha = 0.5f)
        else       -> NodeXColors.DeepSpace
    }

    Surface(
        onClick  = onClick,
        shape    = RoundedCornerShape(16.dp),
        color    = bgColor,
        border   = BorderStroke(if (isFocused) 2.5.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown &&
                    (ev.key == Key.DirectionCenter || ev.key == Key.Enter)) {
                    onClick(); true
                } else false
            },
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(node.flagEmoji, fontSize = 28.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    node.countryName,
                    style      = MaterialTheme.typography.titleMedium,
                    color      = if (isSelected || isFocused) NodeXColors.CyanGlow else NodeXColors.TextPrimary,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(node.city, style = MaterialTheme.typography.bodySmall,
                    color = NodeXColors.TextMuted)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(node.latencyLabel, style = MaterialTheme.typography.bodySmall,
                    color = NodeXColors.TextSecondary)
                TvLoadBar(node.loadPercent, node.loadColor)
            }
            if (isSelected) {
                Icon(Icons.Default.CheckCircle, null, tint = NodeXColors.CyanGlow,
                    modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun TvLoadBar(loadPercent: Float, loadColor: LoadColor) {
    val color = when (loadColor) {
        LoadColor.Low    -> NodeXColors.GreenPulse
        LoadColor.Medium -> NodeXColors.AmberWarning
        else             -> NodeXColors.RedAlert
    }
    LinearProgressIndicator(
        progress   = { loadPercent / 100f },
        modifier   = Modifier.width(56.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
        color      = color,
        trackColor = NodeXColors.DarkMatter,
    )
}

@Composable
private fun TvFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick  = onClick,
        shape    = RoundedCornerShape(50),
        color    = when {
            selected  -> NodeXColors.CyanGlow.copy(alpha = 0.2f)
            isFocused -> NodeXColors.NebulaDark
            else      -> NodeXColors.DeepSpace
        },
        border   = BorderStroke(
            if (isFocused) 2.dp else 1.dp,
            if (selected || isFocused) NodeXColors.CyanGlow else NodeXColors.NebulaDark,
        ),
        modifier = Modifier
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            style    = MaterialTheme.typography.titleSmall,
            color    = if (selected || isFocused) NodeXColors.CyanGlow else NodeXColors.TextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private enum class TvSortBy(val label: String) {
    Latency("By Speed"), Load("By Load"), Country("By Country")
}
private fun Modifier.clip(shape: androidx.compose.ui.graphics.Shape) =
    graphicsLayer { clip = true; this.shape = shape }
