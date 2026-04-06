// shared/src/commonMain/kotlin/com/nodex/vpn/ui/responsive/WindowSizeClass.kt
package com.nodex.vpn.ui.responsive

import androidx.compose.runtime.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ── Breakpoints ───────────────────────────────────────────────────────────────
//  Compact  : < 600dp   → Phone portrait
//  Medium   : 600–1200dp → Tablet / Phone landscape
//  Expanded : > 1200dp  → Desktop / Large tablet

enum class WindowWidthClass { Compact, Medium, Expanded }
enum class WindowHeightClass { Compact, Medium, Expanded }

data class WindowSizeClass(
    val widthClass:  WindowWidthClass,
    val heightClass: WindowHeightClass,
    val widthDp:     Dp,
    val heightDp:    Dp,
) {
    val isCompact:  Boolean get() = widthClass == WindowWidthClass.Compact
    val isMedium:   Boolean get() = widthClass == WindowWidthClass.Medium
    val isExpanded: Boolean get() = widthClass == WindowWidthClass.Expanded

    /** Phone: single-column layout */
    val isPhone:    Boolean get() = isCompact
    /** Tablet: two-pane layout */
    val isTablet:   Boolean get() = isMedium
    /** Desktop: full sidebar + multi-pane */
    val isDesktop:  Boolean get() = isExpanded

    /** Nav type appropriate for window size */
    val navType: NavType get() = when (widthClass) {
        WindowWidthClass.Compact  -> NavType.BottomBar
        WindowWidthClass.Medium   -> NavType.Rail
        WindowWidthClass.Expanded -> NavType.Sidebar
    }

    /** Content max width for centered layouts on large screens */
    val contentMaxWidth: Dp get() = when (widthClass) {
        WindowWidthClass.Compact  -> Dp.Unspecified
        WindowWidthClass.Medium   -> 840.dp
        WindowWidthClass.Expanded -> 1400.dp
    }

    /** Sidebar width on expanded screens */
    val sidebarWidth: Dp get() = 260.dp

    /** Rail width on medium screens */
    val railWidth: Dp get() = 80.dp
}

enum class NavType { BottomBar, Rail, Sidebar }

fun windowSizeClassOf(widthDp: Dp, heightDp: Dp): WindowSizeClass {
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
    return WindowSizeClass(wClass, hClass, widthDp, heightDp)
}

// ── Composition local ─────────────────────────────────────────────────────────
val LocalWindowSizeClass = staticCompositionLocalOf {
    WindowSizeClass(
        WindowWidthClass.Compact,
        WindowHeightClass.Medium,
        400.dp, 800.dp,
    )
}
