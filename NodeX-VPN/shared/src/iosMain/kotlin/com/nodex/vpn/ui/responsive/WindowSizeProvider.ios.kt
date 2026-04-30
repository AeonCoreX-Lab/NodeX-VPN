// shared/src/iosMain/kotlin/com/nodex/vpn/ui/responsive/WindowSizeProvider.ios.kt
package com.nodex.vpn.ui.responsive

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.UIKit.UIScreen

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberWindowSizeClass(): WindowSizeClass {
    val bounds = UIScreen.mainScreen.bounds
    // UIKit bounds are in points (same as dp on iOS)
    return windowSizeClassOf(
        widthDp  = bounds.useContents { size.width }.dp,
        heightDp = bounds.useContents { size.height }.dp,
    )
}
