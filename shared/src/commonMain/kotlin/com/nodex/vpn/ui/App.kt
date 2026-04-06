// shared/src/commonMain/kotlin/com/nodex/vpn/ui/App.kt
package com.nodex.vpn.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.nodex.vpn.auth.*
import com.nodex.vpn.manager.VpnManager
import com.nodex.vpn.ui.responsive.*
import com.nodex.vpn.ui.screens.*
import com.nodex.vpn.ui.theme.NodeXTheme

// ── Screens ───────────────────────────────────────────────────────────────────
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Shield",   Icons.Default.Shield)
    object Servers   : Screen("servers",   "Servers",  Icons.Default.Public)
    object Settings  : Screen("settings",  "Settings", Icons.Default.Settings)
    object Logs      : Screen("logs",      "Logs",     Icons.Default.Terminal)
}

// ── App route ─────────────────────────────────────────────────────────────────
private sealed interface AppRoute {
    object Splash     : AppRoute
    object Onboarding : AppRoute
    object Auth       : AppRoute
    object Main       : AppRoute
}

// ── Root ──────────────────────────────────────────────────────────────────────
@Composable
fun NodeXApp(
    vpnManager:    VpnManager,
    authViewModel: AuthViewModel,
    isFirstLaunch: Boolean = false,
) {
    NodeXTheme {
        val windowSize = rememberWindowSizeClass()
        val authState  by authViewModel.authState.collectAsState()
        var route by remember { mutableStateOf<AppRoute>(AppRoute.Splash) }

        CompositionLocalProvider(LocalWindowSizeClass provides windowSize) {
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    when (targetState) {
                        AppRoute.Main ->
                            (slideInHorizontally { it } + fadeIn(tween(400))).togetherWith(
                                slideOutHorizontally { -it } + fadeOut(tween(300))
                            )
                        AppRoute.Auth ->
                            fadeIn(tween(500)).togetherWith(fadeOut(tween(300)))
                        AppRoute.Onboarding ->
                            (slideInVertically { it } + fadeIn(tween(400))).togetherWith(fadeOut(tween(300)))
                        else ->
                            fadeIn(tween(600)).togetherWith(fadeOut(tween(400)))
                    }
                },
                label = "root_nav",
            ) { currentRoute ->
                when (currentRoute) {
                    AppRoute.Splash -> SplashScreen(
                        authState  = authState,
                        onFinished = { isAuthenticated ->
                            route = when {
                                isAuthenticated -> AppRoute.Main
                                isFirstLaunch   -> AppRoute.Onboarding
                                else            -> AppRoute.Auth
                            }
                        },
                    )
                    AppRoute.Onboarding -> OnboardingScreen(
                        windowSize = windowSize,
                        onFinish   = { route = AppRoute.Auth }
                    )
                    AppRoute.Auth -> AuthScreen(
                        viewModel  = authViewModel,
                        windowSize = windowSize,
                    )
                    AppRoute.Main -> MainScreen(
                        vpnManager    = vpnManager,
                        authViewModel = authViewModel,
                        windowSize    = windowSize,
                    )
                }
            }

            // Auto-route on auth change
            LaunchedEffect(authState) {
                when {
                    authState is AuthState.Authenticated && route == AppRoute.Auth -> route = AppRoute.Main
                    authState is AuthState.Unauthenticated && route == AppRoute.Main -> route = AppRoute.Auth
                }
            }
        }
    }
}

// ── Main screen ───────────────────────────────────────────────────────────────
@Composable
fun MainScreen(
    vpnManager:    VpnManager,
    authViewModel: AuthViewModel,
    windowSize:    WindowSizeClass,
) {
    var currentScreen  by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    var showServerList by remember { mutableStateOf(false) }

    // On desktop/tablet, server list is shown as side panel, not full navigation
    val useInlineServers = windowSize.isMedium || windowSize.isExpanded

    AdaptiveScaffold(
        windowSize    = windowSize,
        currentScreen = currentScreen,
        onNavigate    = { screen ->
            currentScreen  = screen
            showServerList = false
        },
        authViewModel = authViewModel,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {

            // Phone: full-screen navigation
            if (windowSize.isCompact) {
                AnimatedContent(
                    targetState = showServerList to currentScreen,
                    transitionSpec = {
                        (slideInHorizontally { it } + fadeIn(tween(220))).togetherWith(
                            slideOutHorizontally { -it } + fadeOut(tween(180))
                        )
                    },
                    label = "main_nav"
                ) { (serverList, screen) ->
                    if (serverList) {
                        ServerListScreen(
                            vpnManager = vpnManager,
                            windowSize = windowSize,
                            onBack     = { showServerList = false },
                        )
                    } else {
                        when (screen) {
                            Screen.Dashboard -> DashboardScreen(
                                vpnManager    = vpnManager,
                                windowSize    = windowSize,
                                onShowServers = { showServerList = true },
                            )
                            Screen.Servers   -> ServerListScreen(
                                vpnManager = vpnManager,
                                windowSize = windowSize,
                                onBack     = { currentScreen = Screen.Dashboard },
                            )
                            Screen.Settings  -> SettingsScreen(
                                vpnManager    = vpnManager,
                                authViewModel = authViewModel,
                                windowSize    = windowSize,
                            )
                            Screen.Logs      -> LogsScreen(vpnManager, windowSize)
                        }
                    }
                }
            } else {
                // Tablet / Desktop: dashboard + server list as two pane
                when (currentScreen) {
                    Screen.Dashboard -> AdaptiveContentPane(
                        windowSize = windowSize,
                        listPane   = {
                            DashboardScreen(
                                vpnManager    = vpnManager,
                                windowSize    = windowSize,
                                onShowServers = { showServerList = !showServerList },
                            )
                        },
                        detailPane = {
                            ServerListScreen(
                                vpnManager = vpnManager,
                                windowSize = windowSize,
                                onBack     = {},
                            )
                        },
                    )
                    Screen.Servers   -> ServerListScreen(
                        vpnManager = vpnManager,
                        windowSize = windowSize,
                        onBack     = { currentScreen = Screen.Dashboard },
                    )
                    Screen.Settings  -> SettingsScreen(
                        vpnManager    = vpnManager,
                        authViewModel = authViewModel,
                        windowSize    = windowSize,
                    )
                    Screen.Logs      -> LogsScreen(vpnManager, windowSize)
                }
            }
        }
    }
}
