// shared/src/commonMain/kotlin/com/nodex/vpn/ui/responsive/AdaptiveNav.kt
package com.nodex.vpn.ui.responsive

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.nodex.vpn.auth.AuthState
import com.nodex.vpn.auth.AuthViewModel
import com.nodex.vpn.ui.Screen
import com.nodex.vpn.ui.theme.NodeXColors

// ── Adaptive scaffold wrapping all nav types ──────────────────────────────────

@Composable
fun AdaptiveScaffold(
    windowSize:    WindowSizeClass,
    currentScreen: Screen,
    onNavigate:    (Screen) -> Unit,
    authViewModel: AuthViewModel,
    content:       @Composable (PaddingValues) -> Unit,
) {
    val authState by authViewModel.authState.collectAsState()
    val user = (authState as? AuthState.Authenticated)?.user

    when (windowSize.navType) {

        // ── Phone: bottom navigation bar ──────────────────────────────────────
        NavType.BottomBar -> {
            Scaffold(
                containerColor = NodeXColors.Void,
                bottomBar = {
                    NodeXBottomBar(current = currentScreen, onSelect = onNavigate)
                },
                content = content,
            )
        }

        // ── Tablet: navigation rail on left ───────────────────────────────────
        NavType.Rail -> {
            Row(modifier = Modifier.fillMaxSize().background(NodeXColors.Void)) {
                NodeXNavRail(
                    current    = currentScreen,
                    onSelect   = onNavigate,
                    userInitial = user?.displayName?.firstOrNull()?.uppercaseChar()?.toString()
                        ?: user?.email?.firstOrNull()?.uppercaseChar()?.toString() ?: "N",
                )
                Box(modifier = Modifier.weight(1f)) {
                    content(PaddingValues(0.dp))
                }
            }
        }

        // ── Desktop: permanent sidebar ────────────────────────────────────────
        NavType.Sidebar -> {
            Row(modifier = Modifier.fillMaxSize().background(NodeXColors.Void)) {
                NodeXSidebar(
                    current   = currentScreen,
                    onSelect  = onNavigate,
                    user      = user?.displayName ?: user?.email ?: "NodeX User",
                    userEmail = user?.email ?: "",
                    onSignOut = { authViewModel.signOut() },
                    width     = windowSize.sidebarWidth,
                )
                Box(modifier = Modifier.weight(1f)) {
                    content(PaddingValues(0.dp))
                }
            }
        }
    }
}

// ── Nav items ─────────────────────────────────────────────────────────────────

private val navItems = listOf(
    Screen.Dashboard,
    Screen.Servers,
    Screen.Settings,
    Screen.Logs,
)

// ── Bottom Bar ────────────────────────────────────────────────────────────────

@Composable
fun NodeXBottomBar(current: Screen, onSelect: (Screen) -> Unit) {
    NavigationBar(
        containerColor = NodeXColors.DeepSpace,
        tonalElevation = 0.dp,
    ) {
        navItems.forEach { screen ->
            NavigationBarItem(
                selected  = current == screen,
                onClick   = { onSelect(screen) },
                icon      = { Icon(screen.icon, screen.label, modifier = Modifier.size(22.dp)) },
                label     = { Text(screen.label, style = MaterialTheme.typography.labelSmall) },
                colors    = NavigationBarItemDefaults.colors(
                    selectedIconColor   = NodeXColors.CyanGlow,
                    selectedTextColor   = NodeXColors.CyanGlow,
                    unselectedIconColor = NodeXColors.TextMuted,
                    unselectedTextColor = NodeXColors.TextMuted,
                    indicatorColor      = NodeXColors.CyanGlow.copy(alpha = 0.15f),
                )
            )
        }
    }
}

// ── Navigation Rail (Tablet) ──────────────────────────────────────────────────

@Composable
fun NodeXNavRail(
    current:     Screen,
    onSelect:    (Screen) -> Unit,
    userInitial: String,
) {
    NavigationRail(
        modifier       = Modifier.fillMaxHeight(),
        containerColor = NodeXColors.DeepSpace,
        header = {
            Spacer(Modifier.height(12.dp))
            // Mini logo
            Box(
                modifier         = Modifier
                    .size(44.dp)
                    .background(NodeXColors.CyanGlow.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Shield, null, tint = NodeXColors.CyanGlow, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(8.dp))
        },
    ) {
        Spacer(Modifier.weight(1f))
        navItems.forEach { screen ->
            NavigationRailItem(
                selected = current == screen,
                onClick  = { onSelect(screen) },
                icon     = { Icon(screen.icon, screen.label, modifier = Modifier.size(22.dp)) },
                label    = { Text(screen.label, style = MaterialTheme.typography.labelSmall) },
                colors   = NavigationRailItemDefaults.colors(
                    selectedIconColor   = NodeXColors.CyanGlow,
                    selectedTextColor   = NodeXColors.CyanGlow,
                    unselectedIconColor = NodeXColors.TextMuted,
                    unselectedTextColor = NodeXColors.TextMuted,
                    indicatorColor      = NodeXColors.CyanGlow.copy(alpha = 0.15f),
                )
            )
        }
        Spacer(Modifier.weight(1f))
        // User avatar at bottom
        Box(
            modifier         = Modifier
                .padding(bottom = 16.dp)
                .size(36.dp)
                .background(NodeXColors.NebulaDark, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(userInitial, style = MaterialTheme.typography.labelSmall, color = NodeXColors.CyanGlow, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Sidebar (Desktop) ─────────────────────────────────────────────────────────

@Composable
fun NodeXSidebar(
    current:   Screen,
    onSelect:  (Screen) -> Unit,
    user:      String,
    userEmail: String,
    onSignOut: () -> Unit,
    width:     Dp,
) {
    Surface(
        modifier = Modifier.width(width).fillMaxHeight(),
        color    = NodeXColors.DeepSpace,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 24.dp, horizontal = 16.dp),
        ) {
            // ── Logo ──────────────────────────────────────────────────────────
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Box(
                    modifier         = Modifier
                        .size(38.dp)
                        .background(NodeXColors.CyanGlow.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Shield, null, tint = NodeXColors.CyanGlow, modifier = Modifier.size(22.dp))
                }
                Column {
                    Text("NodeX", style = MaterialTheme.typography.titleMedium, color = NodeXColors.TextPrimary, fontWeight = FontWeight.Bold)
                    Text("VPN", style = MaterialTheme.typography.labelSmall, color = NodeXColors.CyanGlow, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
            }

            Spacer(Modifier.height(32.dp))

            // ── Status chip ───────────────────────────────────────────────────
            Surface(
                shape  = RoundedCornerShape(10.dp),
                color  = NodeXColors.GreenPulse.copy(alpha = 0.1f),
                border = BorderStroke(1.dp, NodeXColors.GreenPulse.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier          = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.size(8.dp).background(NodeXColors.GreenPulse, CircleShape))
                    Text("Tor Network Ready", style = MaterialTheme.typography.labelSmall, color = NodeXColors.GreenPulse)
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Nav items ─────────────────────────────────────────────────────
            Text(
                "NAVIGATION",
                style    = MaterialTheme.typography.labelSmall,
                color    = NodeXColors.TextMuted,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(4.dp))

            navItems.forEach { screen ->
                SidebarNavItem(
                    screen   = screen,
                    selected = current == screen,
                    onClick  = { onSelect(screen) },
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Separator ─────────────────────────────────────────────────────
            HorizontalDivider(color = NodeXColors.NebulaDark)
            Spacer(Modifier.height(16.dp))

            // ── User profile ──────────────────────────────────────────────────
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier              = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier         = Modifier
                        .size(36.dp)
                        .background(NodeXColors.CyanGlow.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        user.firstOrNull()?.uppercaseChar()?.toString() ?: "N",
                        style      = MaterialTheme.typography.labelSmall,
                        color      = NodeXColors.CyanGlow,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(user.take(20), style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextPrimary, fontWeight = FontWeight.Medium)
                    Text(userEmail.take(22), style = MaterialTheme.typography.labelSmall, color = NodeXColors.TextMuted)
                }
                IconButton(onClick = onSignOut, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Logout, "Sign Out", tint = NodeXColors.TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SidebarNavItem(screen: Screen, selected: Boolean, onClick: () -> Unit) {
    val bgColor    = if (selected) NodeXColors.CyanGlow.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent
    val textColor  = if (selected) NodeXColors.CyanGlow else NodeXColors.TextSecondary
    val iconColor  = if (selected) NodeXColors.CyanGlow else NodeXColors.TextMuted
    val fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
    val borderColor = if (selected) NodeXColors.CyanGlow.copy(alpha = 0.3f) else androidx.compose.ui.graphics.Color.Transparent

    Surface(
        onClick = onClick,
        shape   = RoundedCornerShape(12.dp),
        color   = bgColor,
        border  = if (selected) BorderStroke(1.dp, borderColor) else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(screen.icon, null, tint = iconColor, modifier = Modifier.size(18.dp))
            Text(screen.label, style = MaterialTheme.typography.bodyMedium, color = textColor, fontWeight = fontWeight)
            if (selected) {
                Spacer(Modifier.weight(1f))
                Box(modifier = Modifier.size(6.dp).background(NodeXColors.CyanGlow, CircleShape))
            }
        }
    }
}

private val CircleShape = RoundedCornerShape(50)
