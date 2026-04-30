// shared/src/commonMain/kotlin/com/nodex/vpn/ui/tv/TvApp.kt
package com.nodex.vpn.ui.tv

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import com.nodex.vpn.ui.Screen
import com.nodex.vpn.ui.responsive.*
import com.nodex.vpn.ui.screens.*
import com.nodex.vpn.ui.theme.*

// ── TV Screen navigation items ────────────────────────────────────────────────
private val tvNavItems = listOf(
    Screen.Dashboard,
    Screen.Servers,
    Screen.Settings,
    Screen.Logs,
)

// ── TV root composable ────────────────────────────────────────────────────────
@Composable
fun TvNodeXApp(
    vpnManager:    VpnManager,
    authViewModel: AuthViewModel,
    isFirstLaunch: Boolean = false,
) {
    NodeXTheme {
        // TV is always landscape/expanded
        val windowSize = WindowSizeClass(
            widthClass  = WindowWidthClass.Expanded,
            heightClass = WindowHeightClass.Expanded,
            widthDp     = 1920.dp,
            heightDp    = 1080.dp,
            isTV        = true,
        )
        val authState by authViewModel.authState.collectAsState()
        var showAuth  by remember { mutableStateOf(false) }

        CompositionLocalProvider(LocalWindowSizeClass provides windowSize) {
            // Auth gate — TV auth is email-only (no Google Sign-In touch flow)
            when {
                authState is AuthState.Loading -> {
                    TvSplashScreen()
                }
                authState is AuthState.Unauthenticated && (isFirstLaunch || showAuth) -> {
                    TvAuthScreen(
                        authViewModel = authViewModel,
                        onAuthSuccess = { showAuth = false },
                    )
                }
                else -> {
                    TvMainScreen(
                        vpnManager    = vpnManager,
                        authViewModel = authViewModel,
                        windowSize    = windowSize,
                    )
                }
            }

            LaunchedEffect(authState) {
                if (authState is AuthState.Unauthenticated) showAuth = true
            }
        }
    }
}

// ── TV Main Screen — side navigation + content ────────────────────────────────
@Composable
fun TvMainScreen(
    vpnManager:    VpnManager,
    authViewModel: AuthViewModel,
    windowSize:    WindowSizeClass,
) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    val firstItemFocus = remember { FocusRequester() }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(NodeXColors.Void)
    ) {
        // ── Left: TV navigation sidebar ───────────────────────────────────────
        TvNavSidebar(
            current       = currentScreen,
            onSelect      = { currentScreen = it },
            authViewModel = authViewModel,
            firstItemFocus = firstItemFocus,
        )

        // ── Right: Content area ───────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    (slideInHorizontally { it / 3 } + fadeIn(tween(200))).togetherWith(
                        slideOutHorizontally { -it / 3 } + fadeOut(tween(150))
                    )
                },
                label = "tv_nav",
            ) { screen ->
                when (screen) {
                    Screen.Dashboard -> TvDashboardScreen(
                        vpnManager = vpnManager,
                        windowSize = windowSize,
                        onShowServers = { currentScreen = Screen.Servers },
                    )
                    Screen.Servers   -> TvServerListScreen(
                        vpnManager = vpnManager,
                        windowSize = windowSize,
                    )
                    Screen.Settings  -> TvSettingsScreen(
                        vpnManager    = vpnManager,
                        authViewModel = authViewModel,
                    )
                    Screen.Logs      -> LogsScreen(vpnManager, windowSize)
                }
            }
        }
    }

    // Auto-focus first nav item when composition starts
    LaunchedEffect(Unit) {
        runCatching { firstItemFocus.requestFocus() }
    }
}

// ── TV Navigation Sidebar ─────────────────────────────────────────────────────
@Composable
private fun TvNavSidebar(
    current:        Screen,
    onSelect:       (Screen) -> Unit,
    authViewModel:  AuthViewModel,
    firstItemFocus: FocusRequester,
) {
    val authState by authViewModel.authState.collectAsState()
    val user = (authState as? AuthState.Authenticated)?.user

    Surface(
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight(),
        color = NodeXColors.DeepSpace,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 40.dp, horizontal = 24.dp),
        ) {
            // Logo
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier              = Modifier.padding(bottom = 8.dp),
            ) {
                Box(
                    modifier         = Modifier
                        .size(52.dp)
                        .background(NodeXColors.CyanGlow.copy(alpha = 0.15f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Shield, null, tint = NodeXColors.CyanGlow, modifier = Modifier.size(30.dp))
                }
                Column {
                    Text("NodeX", style = MaterialTheme.typography.headlineSmall,
                        color = NodeXColors.TextPrimary, fontWeight = FontWeight.Bold)
                    Text("VPN", style = MaterialTheme.typography.labelMedium,
                        color = NodeXColors.CyanGlow, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                }
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider(color = NodeXColors.NebulaDark)
            Spacer(Modifier.height(32.dp))

            // Nav items
            tvNavItems.forEachIndexed { index, screen ->
                TvNavItem(
                    screen      = screen,
                    selected    = current == screen,
                    onClick     = { onSelect(screen) },
                    focusRequester = if (index == 0) firstItemFocus else remember { FocusRequester() },
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = NodeXColors.NebulaDark)
            Spacer(Modifier.height(24.dp))

            // User info
            user?.let {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier         = Modifier
                            .size(44.dp)
                            .background(NodeXColors.CyanGlow.copy(alpha = 0.2f), RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            it.displayName?.firstOrNull()?.uppercaseChar()?.toString()
                                ?: it.email?.firstOrNull()?.uppercaseChar()?.toString() ?: "N",
                            style      = MaterialTheme.typography.titleMedium,
                            color      = NodeXColors.CyanGlow,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(it.displayName ?: "User", style = MaterialTheme.typography.bodyMedium,
                            color = NodeXColors.TextPrimary, fontWeight = FontWeight.Medium,
                            maxLines = 1)
                        Text(it.email ?: "", style = MaterialTheme.typography.labelSmall,
                            color = NodeXColors.TextMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ── TV Nav Item (D-pad focusable) ─────────────────────────────────────────────
@Composable
fun TvNavItem(
    screen:        Screen,
    selected:      Boolean,
    onClick:       () -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    var isFocused by remember { mutableStateOf(false) }

    val bgColor     = when {
        selected  -> NodeXColors.CyanGlow.copy(alpha = 0.18f)
        isFocused -> NodeXColors.NebulaDark.copy(alpha = 0.8f)
        else      -> androidx.compose.ui.graphics.Color.Transparent
    }
    val borderColor = when {
        isFocused -> NodeXColors.CyanGlow
        selected  -> NodeXColors.CyanGlow.copy(alpha = 0.5f)
        else      -> androidx.compose.ui.graphics.Color.Transparent
    }
    val textColor   = when {
        isFocused || selected -> NodeXColors.CyanGlow
        else                  -> NodeXColors.TextSecondary
    }

    Surface(
        onClick     = onClick,
        shape       = RoundedCornerShape(14.dp),
        color       = bgColor,
        border      = BorderStroke(if (isFocused) 2.dp else 1.dp, borderColor),
        modifier    = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                    onClick(); true
                } else false
            },
    ) {
        Row(
            modifier          = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                screen.icon, null,
                tint     = textColor,
                modifier = Modifier.size(26.dp),
            )
            Text(
                screen.label,
                style      = MaterialTheme.typography.titleMedium,
                color      = textColor,
                fontWeight = if (selected || isFocused) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (selected) {
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(NodeXColors.CyanGlow, RoundedCornerShape(50))
                )
            }
        }
    }
}

// ── TV Splash ─────────────────────────────────────────────────────────────────
@Composable
private fun TvSplashScreen() {
    Box(
        modifier         = Modifier.fillMaxSize().background(NodeXColors.Void),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Shield, null,
                tint = NodeXColors.CyanGlow, modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(24.dp))
            Text("NodeX VPN", style = MaterialTheme.typography.displaySmall,
                color = NodeXColors.TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(color = NodeXColors.CyanGlow, modifier = Modifier.size(32.dp))
        }
    }
}

// ── TV Auth Screen (email only — no Google touch flow on TV) ──────────────────
@Composable
private fun TvAuthScreen(
    authViewModel: AuthViewModel,
    onAuthSuccess: () -> Unit,
) {
    val authState by authViewModel.authState.collectAsState()

    LaunchedEffect(authState) {
        if (authState is AuthState.Authenticated) onAuthSuccess()
    }

    Box(
        modifier         = Modifier.fillMaxSize().background(NodeXColors.Void),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier            = Modifier.width(480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Icon(Icons.Default.Shield, null,
                tint = NodeXColors.CyanGlow, modifier = Modifier.size(60.dp))
            Text("Sign in to NodeX VPN",
                style = MaterialTheme.typography.headlineMedium,
                color = NodeXColors.TextPrimary, fontWeight = FontWeight.Bold)
            Text("Use your phone or computer to complete sign-in,\nthen return here.",
                style = MaterialTheme.typography.bodyLarge,
                color = NodeXColors.TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)

            Spacer(Modifier.height(8.dp))

            // On TV, direct users to use companion device for auth
            Surface(
                shape  = RoundedCornerShape(16.dp),
                color  = NodeXColors.DeepSpace,
                border = BorderStroke(1.dp, NodeXColors.NebulaDark),
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            ) {
                Column(
                    modifier            = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.PhoneAndroid, null,
                        tint = NodeXColors.CyanGlow, modifier = Modifier.size(40.dp))
                    Text("Open NodeX VPN on your iPhone or Android phone",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NodeXColors.TextPrimary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text("Sign in there, then this screen will update automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NodeXColors.TextMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }

            if (authState is AuthState.Loading) {
                CircularProgressIndicator(color = NodeXColors.CyanGlow)
            }
        }
    }
}
