// shared/src/commonMain/kotlin/com/nodex/vpn/ui/responsive/WindowSizeClass.kt
package com.nodex.vpn.ui.responsive

import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WindowWidthClass  { Compact, Medium, Expanded }
enum class WindowHeightClass { Compact, Medium, Expanded }

data class WindowSizeClass(
    val widthClass:  WindowWidthClass,
    val heightClass: WindowHeightClass,
    val widthDp:     Dp,
    val heightDp:    Dp,
    /** True when running on Android TV or Apple TV (tvOS). Changes nav model and focus behaviour. */
    val isTV: Boolean = false,
) {
    val isCompact:  Boolean get() = widthClass == WindowWidthClass.Compact
    val isMedium:   Boolean get() = widthClass == WindowWidthClass.Medium
    val isExpanded: Boolean get() = widthClass == WindowWidthClass.Expanded

    val isPhone:   Boolean get() = isCompact
    val isTablet:  Boolean get() = isMedium
    val isDesktop: Boolean get() = isExpanded && !isTV

    val navType: NavType get() = when {
        isTV              -> NavType.TvSidebar
        isExpanded        -> NavType.Sidebar
        isMedium          -> NavType.Rail
        else              -> NavType.BottomBar
    }

    val contentMaxWidth: Dp get() = when (widthClass) {
        WindowWidthClass.Compact  -> Dp.Unspecified
        WindowWidthClass.Medium   -> 840.dp
        WindowWidthClass.Expanded -> 1400.dp
    }

    val sidebarWidth: Dp get() = if (isTV) 300.dp else 260.dp
    val railWidth:    Dp get() = 80.dp
}

enum class NavType { BottomBar, Rail, Sidebar, TvSidebar }

fun windowSizeClassOf(widthDp: Dp, heightDp: Dp, isTV: Boolean = false): WindowSizeClass {
    val wClass = when {
        widthDp < 600.dp  -> WindowWidthClass.Compact
        widthDp < 1200.dp -> WindowWidthClass.Medium
        else              -> WindowWidthClass.Expanded
    }
    val hClass = when {
        heightDp < 480.dp -> WindowHeightClass.Compact
        heightDp < 900.dp -> WindowHeightClass.Medium
        else              -> WindowHeightClass.Expanded
    }
    return WindowSizeClass(wClass, hClass, widthDp, heightDp, isTV)
}

val LocalWindowSizeClass = staticCompositionLocalOf {
    WindowSizeClass(WindowWidthClass.Compact, WindowHeightClass.Medium, 400.dp, 800.dp)
}
