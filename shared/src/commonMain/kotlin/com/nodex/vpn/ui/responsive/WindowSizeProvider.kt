// shared/src/commonMain/kotlin/com/nodex/vpn/ui/responsive/WindowSizeProvider.kt
package com.nodex.vpn.ui.responsive

import androidx.compose.runtime.Composable

/**
 * expect — each platform provides the actual window size.
 * Android: LocalConfiguration
 * iOS: UIScreen
 * Desktop: LocalWindowInfo / androidx.compose.ui.window
 */
@Composable
expect fun rememberWindowSizeClass(): WindowSizeClass
