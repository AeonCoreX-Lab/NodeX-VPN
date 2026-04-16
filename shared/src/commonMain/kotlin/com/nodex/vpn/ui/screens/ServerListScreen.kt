// shared/src/commonMain/kotlin/com/nodex/vpn/ui/screens/ServerListScreen.kt
package com.nodex.vpn.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.nodex.vpn.domain.*
import com.nodex.vpn.manager.VpnManager
import com.nodex.vpn.ui.responsive.*
import com.nodex.vpn.ui.theme.NodeXColors

@Composable
fun ServerListScreen(
    vpnManager: VpnManager,
    windowSize: WindowSizeClass,
    onBack:     () -> Unit,
) {
    val nodes        by vpnManager.nodes.collectAsState()
    val selectedNode by vpnManager.selectedNode.collectAsState()
    var query        by remember { mutableStateOf("") }
    var sortBy       by remember { mutableStateOf(SortBy.Latency) }

    val filtered = remember(nodes, query, sortBy) {
        nodes.filter { n ->
            query.isEmpty() || n.countryName.contains(query, true) ||
            n.countryCode.contains(query, true) || n.city.contains(query, true)
        }.let { list ->
            when (sortBy) {
                SortBy.Latency  -> list.sortedBy { it.latencyMs }
                SortBy.Load     -> list.sortedBy { it.loadPercent }
                SortBy.Country  -> list.sortedBy { it.countryName }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(NodeXColors.Void)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = if (windowSize.isExpanded) 24.dp else 16.dp,
                vertical = 14.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (windowSize.isCompact) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = NodeXColors.TextPrimary)
                }
            }
            Text(
                if (windowSize.isExpanded) "Server Selection" else "Select Server",
                style     = if (windowSize.isExpanded) MaterialTheme.typography.headlineMedium
                            else MaterialTheme.typography.headlineSmall,
                color     = NodeXColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                modifier  = Modifier.weight(1f),
            )
            Text("${filtered.size} servers", style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextMuted)
        }

        // Search + sort
        val hPad = if (windowSize.isExpanded) 24.dp else 16.dp
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = hPad),
            placeholder = { Text("Search…", color = NodeXColors.TextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = NodeXColors.TextSecondary) },
            trailingIcon = if (query.isNotEmpty()) {{ IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, null, tint = NodeXColors.TextSecondary) } }} else null,
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NodeXColors.CyanGlow, unfocusedBorderColor = NodeXColors.NebulaDark,
                focusedTextColor = NodeXColors.TextPrimary, unfocusedTextColor = NodeXColors.TextPrimary,
                cursorColor = NodeXColors.CyanGlow, focusedContainerColor = NodeXColors.DeepSpace,
                unfocusedContainerColor = NodeXColors.DeepSpace,
            )
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.padding(horizontal = hPad), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SortBy.entries.forEach { sort ->
                FilterChip(
                    selected = sortBy == sort, onClick = { sortBy = sort },
                    label = { Text(sort.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NodeXColors.CyanGlow.copy(alpha = 0.2f),
                        selectedLabelColor = NodeXColors.CyanGlow,
                        containerColor = NodeXColors.DeepSpace,
                        labelColor = NodeXColors.TextSecondary,
                    )
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // Grid (2 cols on tablet/desktop, 1 col on phone)
        val columns = when {
            windowSize.isExpanded -> 2
            windowSize.isMedium   -> 2
            else                  -> 1
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(horizontal = hPad, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered, key = { it.id }) { node ->
                ServerRow(
                    node       = node,
                    isSelected = node.id == selectedNode?.id,
                    compact    = windowSize.isCompact,
                    onClick    = {
                        vpnManager.selectNode(node)
                        if (windowSize.isCompact) onBack()
                    }
                )
            }
        }
    }
}

@Composable
private fun ServerRow(node: ServerNode, isSelected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val borderColor = if (isSelected) NodeXColors.CyanGlow else NodeXColors.NebulaDark
    val bgColor     = if (isSelected) NodeXColors.CyanGlow.copy(alpha = 0.08f) else NodeXColors.DeepSpace

    Surface(
        shape = RoundedCornerShape(14.dp), color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(node.flagEmoji, fontSize = if (compact) 22.sp else 26.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    node.countryName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) NodeXColors.CyanGlow else NodeXColors.TextPrimary,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(node.city, style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextMuted)
            }
            if (node.isBridge) {
                Surface(shape = RoundedCornerShape(6.dp), color = NodeXColors.PurpleNeon.copy(alpha = 0.15f)) {
                    Text("BRIDGE", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = NodeXColors.PurpleNeon)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(node.latencyLabel, style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextSecondary)
                LinearProgressIndicator(
                    progress = { node.loadPercent / 100f },
                    modifier = Modifier.width(48.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = when (node.loadColor) { LoadColor.Low -> NodeXColors.GreenPulse; LoadColor.Medium -> NodeXColors.AmberWarning; else -> NodeXColors.RedAlert },
                    trackColor = NodeXColors.DarkMatter,
                )
            }
            if (isSelected) Icon(Icons.Default.CheckCircle, null, tint = NodeXColors.CyanGlow, modifier = Modifier.size(18.dp))
        }
    }
}

private enum class SortBy(val label: String) { Latency("By Speed"), Load("By Load"), Country("By Country") }
