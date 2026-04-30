// shared/src/tvosMain/kotlin/com/nodex/vpn/ui/responsive/WindowSizeProvider.tvos.kt
package com.nodex.vpn.ui.responsive

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// tvOS always renders at 1920×1080 (1080p) or 3840×2160 (4K).
// Both map to Expanded width class. isTV = true triggers the TvNodeXApp
// navigation model instead of the regular sidebar/bottombar nav.
@Composable
actual fun rememberWindowSizeClass(): WindowSizeClass = windowSizeClassOf(
    widthDp  = 1920.dp,
    heightDp = 1080.dp,
    isTV     = true,
)
