// shared/src/commonMain/kotlin/com/nodex/vpn/ui/screens/SettingsScreen.kt
package com.nodex.vpn.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nodex.vpn.auth.*
import com.nodex.vpn.manager.VpnManager
import com.nodex.vpn.ui.responsive.*
import com.nodex.vpn.ui.theme.NodeXColors

@Composable
fun SettingsScreen(vpnManager: VpnManager, authViewModel: AuthViewModel, windowSize: WindowSizeClass) {
    val config    by vpnManager.config.collectAsState()
    val authState by authViewModel.authState.collectAsState()
    var showSignOutDialog by remember { mutableStateOf(false) }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            containerColor   = NodeXColors.DeepSpace,
            title  = { Text("Sign Out", color = NodeXColors.TextPrimary) },
            text   = { Text("Are you sure you want to sign out?", color = NodeXColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { authViewModel.signOut(); showSignOutDialog = false }) {
                    Text("Sign Out", color = NodeXColors.RedAlert, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel", color = NodeXColors.TextMuted) } },
        )
    }

    val hPad = when {
        windowSize.isExpanded -> 40.dp
        windowSize.isMedium   -> 28.dp
        else                  -> 20.dp
    }

    // Desktop: two-column settings layout
    if (windowSize.isExpanded) {
        Column(
            modifier = Modifier.fillMaxSize().background(NodeXColors.Void).verticalScroll(rememberScrollState()).padding(horizontal = hPad, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineLarge, color = NodeXColors.TextPrimary, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AccountSection(authState, showSignOut = { showSignOutDialog = true })
                    SecuritySection(config, vpnManager)
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    BridgesSection(config, vpnManager)
                    ConnectionSection(config, vpnManager)
                    AboutSection()
                }
            }
        }
    } else {
        // Phone / Tablet: single column
        Column(
            modifier = Modifier.fillMaxSize().background(NodeXColors.Void).verticalScroll(rememberScrollState()).padding(horizontal = hPad, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = NodeXColors.TextPrimary, fontWeight = FontWeight.Bold)
            AccountSection(authState, showSignOut = { showSignOutDialog = true })
            SecuritySection(config, vpnManager)
            BridgesSection(config, vpnManager)
            ConnectionSection(config, vpnManager)
            AboutSection()
        }
    }
}

@Composable
private fun AccountSection(authState: AuthState, showSignOut: () -> Unit) {
    SettingsSection("Account") {
        val authUser = (authState as? AuthState.Authenticated)?.user
        if (authUser != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.size(42.dp).background(NodeXColors.CyanGlow.copy(alpha = 0.2f), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    Text((authUser.displayName?.firstOrNull() ?: authUser.email?.firstOrNull() ?: 'N').uppercaseChar().toString(), style = MaterialTheme.typography.titleMedium, color = NodeXColors.CyanGlow, fontWeight = FontWeight.Bold)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(authUser.displayName ?: "NodeX User", style = MaterialTheme.typography.bodyMedium, color = NodeXColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(authUser.email ?: "—", style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextSecondary)
                }
                Surface(shape = RoundedCornerShape(8.dp), color = NodeXColors.GreenPulse.copy(alpha = 0.15f)) {
                    Text("PRO", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = NodeXColors.GreenPulse, fontWeight = FontWeight.Bold)
                }
            }
            SettingsDivider()
            SettingsAction(Icons.Default.Logout, "Sign Out", NodeXColors.RedAlert, showSignOut)
        }
    }
}

@Composable
private fun SecuritySection(config: com.nodex.vpn.domain.NodeXConfig, vpnManager: VpnManager) {
    SettingsSection("Security") {
        SettingsToggle(Icons.Default.Security, "Kill Switch", "Block all traffic if VPN drops", config.killSwitch) { vpnManager.updateConfig { it.copy(killSwitch = !it.killSwitch) } }
        SettingsDivider()
        SettingsToggle(Icons.Default.Dns, "DNS over Tor", "Prevents DNS leaks completely", config.dnsOverTor) { vpnManager.updateConfig { it.copy(dnsOverTor = !it.dnsOverTor) } }
        SettingsDivider()
        SettingsToggle(Icons.Default.Lock, "Strict Exit Nodes", "Only selected country exit relays", config.strictExitNodes) { vpnManager.updateConfig { it.copy(strictExitNodes = !it.strictExitNodes) } }
    }
}

@Composable
private fun BridgesSection(config: com.nodex.vpn.domain.NodeXConfig, vpnManager: VpnManager) {
    SettingsSection("Tor Bridges (obfs4)") {
        SettingsToggle(Icons.Default.VpnLock, "Use Bridges", "Bypass ISP throttling & DPI", config.useBridges) { vpnManager.updateConfig { it.copy(useBridges = !it.useBridges) } }
        if (config.useBridges) {
            SettingsDivider()
            BridgesEditor(config.bridgeLines) { lines -> vpnManager.updateConfig { it.copy(bridgeLines = lines) } }
        }
    }
}

@Composable
private fun ConnectionSection(config: com.nodex.vpn.domain.NodeXConfig, vpnManager: VpnManager) {
    SettingsSection("Connection") {
        SettingsToggle(Icons.Default.Autorenew, "Auto-Connect", "Connect automatically on launch", config.autoConnect) { vpnManager.updateConfig { it.copy(autoConnect = !it.autoConnect) } }
        SettingsDivider()
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Circuit Timeout", style = MaterialTheme.typography.bodyMedium, color = NodeXColors.TextPrimary)
                Text("${config.circuitTimeout}s", style = MaterialTheme.typography.labelSmall, color = NodeXColors.CyanGlow)
            }
            Slider(value = config.circuitTimeout.toFloat(), onValueChange = { vpnManager.updateConfig { c -> c.copy(circuitTimeout = it.toInt()) } }, valueRange = 10f..120f, steps = 10,
                colors = SliderDefaults.colors(thumbColor = NodeXColors.CyanGlow, activeTrackColor = NodeXColors.CyanGlow, inactiveTrackColor = NodeXColors.DarkMatter))
        }
    }
}

@Composable
private fun AboutSection() {
    SettingsSection("About") {
        SettingsInfo(Icons.Default.Info,       "Version",      "0.3.0-alpha")
        SettingsDivider()
        SettingsInfo(Icons.Default.Shield,     "Engine",       "Arti (Rust Tor Client)")
        SettingsDivider()
        SettingsInfo(Icons.Default.Public,     "Architecture", "Serverless · Tor Network")
        SettingsDivider()
        SettingsInfo(Icons.Default.PrivacyTip, "IP Hiding",    "99% via strict ExitNodes")
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelSmall, color = NodeXColors.CyanGlow, modifier = Modifier.padding(bottom = 8.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = NodeXColors.DeepSpace, border = BorderStroke(1.dp, NodeXColors.NebulaDark)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp), content = content)
        }
    }
}

@Composable
private fun SettingsToggle(icon: ImageVector, title: String, sub: String, checked: Boolean, onToggle: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = NodeXColors.CyanGlow, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = NodeXColors.TextPrimary)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextMuted)
        }
        Switch(checked = checked, onCheckedChange = { onToggle() }, colors = SwitchDefaults.colors(checkedThumbColor = NodeXColors.Void, checkedTrackColor = NodeXColors.CyanGlow, uncheckedThumbColor = NodeXColors.TextMuted, uncheckedTrackColor = NodeXColors.DarkMatter))
    }
}

@Composable
private fun SettingsAction(icon: ImageVector, title: String, color: Color, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, null, tint = color.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SettingsInfo(icon: ImageVector, label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = NodeXColors.TextMuted, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = NodeXColors.TextPrimary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextSecondary)
    }
}

@Composable private fun SettingsDivider() = HorizontalDivider(color = NodeXColors.NebulaDark, thickness = 0.5.dp)

@Composable
private fun BridgesEditor(lines: List<String>, onChange: (List<String>) -> Unit) {
    var text by remember(lines) { mutableStateOf(lines.joinToString("\n")) }
    Column(modifier = Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Bridge Lines (one per line)", style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextMuted)
        OutlinedTextField(value = text, onValueChange = { text = it; onChange(it.lines().filter(String::isNotBlank)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            placeholder = { Text("obfs4 1.2.3.4:1234 FINGERPRINT cert=… iat-mode=0", color = NodeXColors.TextMuted, style = MaterialTheme.typography.labelSmall) },
            textStyle = MaterialTheme.typography.labelSmall.copy(color = NodeXColors.TextPrimary),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NodeXColors.CyanGlow, unfocusedBorderColor = NodeXColors.NebulaDark, focusedContainerColor = NodeXColors.DarkMatter, unfocusedContainerColor = NodeXColors.DarkMatter))
    }
}
