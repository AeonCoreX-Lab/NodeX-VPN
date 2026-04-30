// shared/src/commonMain/kotlin/com/nodex/vpn/ui/tv/TvSettingsScreen.kt
package com.nodex.vpn.ui.tv

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.nodex.vpn.auth.*
import com.nodex.vpn.manager.VpnManager
import com.nodex.vpn.ui.theme.NodeXColors

@Composable
fun TvSettingsScreen(
    vpnManager:    VpnManager,
    authViewModel: AuthViewModel,
) {
    val authState by authViewModel.authState.collectAsState()
    val user = (authState as? AuthState.Authenticated)?.user

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(NodeXColors.Void)
            .padding(40.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        // Left: settings list
        Column(
            modifier = Modifier.width(560.dp).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Settings",
                style = MaterialTheme.typography.headlineLarge,
                color = NodeXColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 32.sp)
            Spacer(Modifier.height(8.dp))

            // Account section
            TvSettingsSection("Account")
            user?.let {
                TvSettingsInfoRow(
                    icon  = Icons.Default.Person,
                    title = it.displayName ?: "User",
                    value = it.email ?: "",
                )
                TvSettingsActionRow(
                    icon    = Icons.Default.Logout,
                    title   = "Sign Out",
                    color   = NodeXColors.RedAlert,
                    onClick = { authViewModel.signOut() },
                )
            }

            Spacer(Modifier.height(16.dp))

            // Privacy section
            TvSettingsSection("Privacy & Security")
            TvSettingsInfoRow(
                icon  = Icons.Default.Shield,
                title = "Protocol",
                value = "Tor Network (Onion Routing)",
            )
            TvSettingsInfoRow(
                icon  = Icons.Default.Lock,
                title = "Encryption",
                value = "AES-256-GCM",
            )
            TvSettingsInfoRow(
                icon  = Icons.Default.Visibility,
                title = "No-Logs Policy",
                value = "Enforced · Open Source",
            )

            Spacer(Modifier.height(16.dp))

            // About section
            TvSettingsSection("About")
            TvSettingsInfoRow(
                icon  = Icons.Default.Info,
                title = "Version",
                value = "0.1.0 (TV)",
            )
            TvSettingsInfoRow(
                icon  = Icons.Default.Code,
                title = "Source Code",
                value = "github.com/AeonCoreX/NodeX-VPN",
            )
        }

        // Right: TV-specific info panel
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TvSectionTitle("NodeX VPN on TV")
            TvCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvInfoPanelItem(
                        icon  = Icons.Default.Tv,
                        title = "TV Platform",
                        body  = "NodeX VPN runs the full Tor core on your TV. " +
                                "Traffic from this app is anonymised through the Tor network.",
                    )
                    HorizontalDivider(color = NodeXColors.NebulaDark)
                    TvInfoPanelItem(
                        icon  = Icons.Default.PhoneAndroid,
                        title = "Companion Device",
                        body  = "For system-wide VPN coverage on your network, use " +
                                "NodeX VPN on an iPhone, Android, or desktop and " +
                                "route your TV through it via your router.",
                    )
                    HorizontalDivider(color = NodeXColors.NebulaDark)
                    TvInfoPanelItem(
                        icon  = Icons.Default.RemoteControl,
                        title = "Remote Control",
                        body  = "Navigate using your D-pad or remote. " +
                                "Press OK/Select to confirm, Back to return.",
                    )
                }
            }
        }
    }
}

@Composable
private fun TvSettingsSection(title: String) {
    Text(
        title.uppercase(),
        style         = MaterialTheme.typography.labelLarge,
        color         = NodeXColors.TextMuted,
        fontWeight    = FontWeight.Bold,
        letterSpacing = 1.5.sp,
    )
}

@Composable
private fun TvSettingsInfoRow(
    icon:  ImageVector,
    title: String,
    value: String,
) {
    Surface(
        shape    = RoundedCornerShape(14.dp),
        color    = NodeXColors.DeepSpace,
        border   = BorderStroke(1.dp, NodeXColors.NebulaDark),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, null, tint = NodeXColors.CyanGlow, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    color = NodeXColors.TextPrimary, fontWeight = FontWeight.Medium)
            }
            Text(value, style = MaterialTheme.typography.bodyMedium,
                color = NodeXColors.TextSecondary)
        }
    }
}

@Composable
private fun TvSettingsActionRow(
    icon:    ImageVector,
    title:   String,
    color:   androidx.compose.ui.graphics.Color = NodeXColors.TextPrimary,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick  = onClick,
        shape    = RoundedCornerShape(14.dp),
        color    = if (isFocused) color.copy(alpha = 0.12f) else NodeXColors.DeepSpace,
        border   = BorderStroke(
            if (isFocused) 2.dp else 1.dp,
            if (isFocused) color else NodeXColors.NebulaDark,
        ),
        modifier = Modifier
            .fillMaxWidth()
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
            modifier          = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = color, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun TvInfoPanelItem(icon: ImageVector, title: String, body: String) {
    Row(
        verticalAlignment     = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = NodeXColors.CyanGlow,
            modifier = Modifier.size(28.dp).padding(top = 2.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = NodeXColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium,
                color = NodeXColors.TextSecondary)
        }
    }
}
