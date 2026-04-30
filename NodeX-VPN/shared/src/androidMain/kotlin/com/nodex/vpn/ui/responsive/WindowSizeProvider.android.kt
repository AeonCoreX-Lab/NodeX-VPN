// shared/src/androidMain/kotlin/com/nodex/vpn/ui/responsive/WindowSizeProvider.android.kt
package com.nodex.vpn.ui.responsive

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

@Composable
actual fun rememberWindowSizeClass(): WindowSizeClass {
    val config = LocalConfiguration.current
    return windowSizeClassOf(
        widthDp  = config.screenWidthDp.dp,
        heightDp = config.screenHeightDp.dp,
    )
}
