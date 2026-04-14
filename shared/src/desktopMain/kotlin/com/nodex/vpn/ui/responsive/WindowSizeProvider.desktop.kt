// shared/src/desktopMain/kotlin/com/nodex/vpn/ui/responsive/WindowSizeProvider.desktop.kt
package com.nodex.vpn.ui.responsive

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun rememberWindowSizeClass(): WindowSizeClass {
    val windowInfo = LocalWindowInfo.current
    val density    = LocalDensity.current
    return with(density) {
        windowSizeClassOf(
            widthDp  = windowInfo.containerSize.width.toDp(),
            heightDp = windowInfo.containerSize.height.toDp(),
        )
    }
}
