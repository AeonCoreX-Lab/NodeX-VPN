// shared/src/commonMain/kotlin/com/nodex/vpn/ui/theme/Theme.kt
package com.nodex.vpn.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── NodeX Cyberpunk Palette ───────────────────────────────────────────────────

object NodeXColors {
    // Dark void backgrounds
    val Void           = Color(0xFF080B14)   // deepest background
    val DeepSpace      = Color(0xFF0D1117)   // card background
    val DarkMatter     = Color(0xFF141920)   // surface
    val NebulaDark     = Color(0xFF1A2233)   // elevated surface

    // Neon primaries
    val CyanGlow       = Color(0xFF00F5FF)   // primary accent (connect button)
    val CyanDim        = Color(0xFF0099AA)   // dimmed primary
    val PurpleNeon     = Color(0xFFBB00FF)   // secondary accent
    val PurpleDim      = Color(0xFF7700BB)
    val GreenPulse     = Color(0xFF39FF14)   // success / connected state
    val GreenDim       = Color(0xFF1A8A00)
    val AmberWarning   = Color(0xFFFFAA00)   // warning / medium load
    val RedAlert       = Color(0xFFFF2244)   // error / disconnected

    // Text
    val TextPrimary    = Color(0xFFE8F0FE)
    val TextSecondary  = Color(0xFF8899BB)
    val TextMuted      = Color(0xFF445566)

    // Graph
    val GraphSend      = CyanGlow
    val GraphRecv      = PurpleNeon

    // Glow (used as box shadow approximation)
    val GlowCyan       = Color(0x3300F5FF)
    val GlowPurple     = Color(0x33BB00FF)
    val GlowGreen      = Color(0x3339FF14)
}

// ── Color Scheme ─────────────────────────────────────────────────────────────

private val NodeXDarkColorScheme = darkColorScheme(
    primary             = NodeXColors.CyanGlow,
    onPrimary           = NodeXColors.Void,
    primaryContainer    = NodeXColors.NebulaDark,
    onPrimaryContainer  = NodeXColors.CyanGlow,

    secondary           = NodeXColors.PurpleNeon,
    onSecondary         = NodeXColors.Void,
    secondaryContainer  = Color(0xFF1A0033),
    onSecondaryContainer = NodeXColors.PurpleNeon,

    tertiary            = NodeXColors.GreenPulse,
    onTertiary          = NodeXColors.Void,

    background          = NodeXColors.Void,
    onBackground        = NodeXColors.TextPrimary,

    surface             = NodeXColors.DeepSpace,
    onSurface           = NodeXColors.TextPrimary,
    surfaceVariant      = NodeXColors.DarkMatter,
    onSurfaceVariant    = NodeXColors.TextSecondary,

    surfaceTint         = NodeXColors.CyanDim,
    inverseSurface      = NodeXColors.TextPrimary,
    inverseOnSurface    = NodeXColors.Void,

    error               = NodeXColors.RedAlert,
    onError             = Color.White,
    errorContainer      = Color(0xFF3A0011),
    onErrorContainer    = NodeXColors.RedAlert,

    outline             = Color(0xFF2A3A55),
    outlineVariant      = Color(0xFF1A2233),
)

// ── Typography ────────────────────────────────────────────────────────────────

// Using system monospaced for the "terminal/tech" feel
private val NodeXTypography = Typography(
    displayLarge = TextStyle(
        fontFamily   = FontFamily.Monospace,
        fontWeight   = FontWeight.W300,
        fontSize     = 57.sp,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontFamily   = FontFamily.Monospace,
        fontWeight   = FontWeight.W300,
        fontSize     = 45.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily   = FontFamily.SansSerif,
        fontWeight   = FontWeight.W600,
        fontSize     = 32.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily   = FontFamily.SansSerif,
        fontWeight   = FontWeight.W500,
        fontSize     = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily   = FontFamily.SansSerif,
        fontWeight   = FontWeight.W600,
        fontSize     = 22.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily   = FontFamily.Monospace,
        fontWeight   = FontWeight.W500,
        fontSize     = 16.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily   = FontFamily.SansSerif,
        fontWeight   = FontWeight.W400,
        fontSize     = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily   = FontFamily.SansSerif,
        fontWeight   = FontWeight.W400,
        fontSize     = 14.sp,
        letterSpacing = 0.25.sp,
    ),
    labelSmall = TextStyle(
        fontFamily   = FontFamily.Monospace,
        fontWeight   = FontWeight.W500,
        fontSize     = 11.sp,
        letterSpacing = 0.5.sp,
    ),
)

// ── Theme Composable ──────────────────────────────────────────────────────────

@Composable
fun NodeXTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NodeXDarkColorScheme,
        typography  = NodeXTypography,
        content     = content,
    )
}
