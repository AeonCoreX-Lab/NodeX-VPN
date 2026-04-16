// shared/src/commonMain/kotlin/com/nodex/vpn/ui/screens/SplashScreen.kt
package com.nodex.vpn.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.nodex.vpn.auth.AuthState
import com.nodex.vpn.ui.theme.NodeXColors

import kotlinx.coroutines.delay
import kotlin.math.*

@Composable
fun SplashScreen(
    authState:  AuthState,
    onFinished: (isAuthenticated: Boolean) -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "splash")

    // ── Pulsing rings (orbit animation — Tor relay nodes) ─────────────────────
    val ring1Scale by infiniteTransition.animateFloat(
        initialValue  = 0.6f, targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "ring1",
    )
    val ring2Scale by infiniteTransition.animateFloat(
        initialValue  = 0.7f, targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(2400, 300, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "ring2",
    )
    val ring3Scale by infiniteTransition.animateFloat(
        initialValue  = 0.5f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(3000, 600, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "ring3",
    )
    val ring1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.8f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Restart), label = "a1",
    )
    val ring2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2400, 300), RepeatMode.Restart), label = "a2",
    )
    val ring3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(3000, 600), RepeatMode.Restart), label = "a3",
    )

    // ── Orbit rotation ────────────────────────────────────────────────────────
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "orbit",
    )
    val orbitAngle2 by infiniteTransition.animateFloat(
        initialValue = 360f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing)), label = "orbit2",
    )

    // ── Logo entrance animation ───────────────────────────────────────────────
    var logoVisible by remember { mutableStateOf(false) }
    val logoAlpha by animateFloatAsState(
        targetValue = if (logoVisible) 1f else 0f,
        animationSpec = tween(900),
        label = "logo_alpha",
    )
    val logoScale by animateFloatAsState(
        targetValue   = if (logoVisible) 1f else 0.55f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 180f),
        label = "logo_scale",
    )

    // ── Logo subtle glow pulse ────────────────────────────────────────────────
    val logoPulse by infiniteTransition.animateFloat(
        initialValue  = 0.85f,
        targetValue   = 1.0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "logo_pulse",
    )

    // ── Text entrance animation ───────────────────────────────────────────────
    var textVisible by remember { mutableStateOf(false) }
    val textAlpha  by animateFloatAsState(
        targetValue   = if (textVisible) 1f else 0f,
        animationSpec = tween(600),
        label = "txt_alpha",
    )
    val textOffset by animateFloatAsState(
        targetValue   = if (textVisible) 0f else 28f,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "txt_offset",
    )

    // ── Launch sequence timing ────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        delay(120)
        logoVisible = true
        delay(450)
        textVisible = true
        // Minimum splash display time
        delay(1800)
        // Wait for auth state if still loading
        while (authState is AuthState.Loading) { delay(100) }
        delay(200)
        onFinished(authState is AuthState.Authenticated)
    }

    // ── Root container ────────────────────────────────────────────────────────
    Box(
        modifier         = Modifier
            .fillMaxSize()
            .background(NodeXColors.Void),
        contentAlignment = Alignment.Center,
    ) {

        // ── Background dot grid ───────────────────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawParticleGrid()
        }

        // ── Orbit rings + nodes ───────────────────────────────────────────────
        Canvas(modifier = Modifier.size(320.dp)) {
            val cx    = size.width / 2
            val cy    = size.height / 2
            val baseR = size.minDimension / 2 * 0.5f

            // Expanding pulse rings
            drawCircle(
                NodeXColors.CyanGlow.copy(alpha = ring1Alpha),
                baseR * ring1Scale,
                center = Offset(cx, cy),
                style  = Stroke(1.5f),
            )
            drawCircle(
                NodeXColors.CyanGlow.copy(alpha = ring2Alpha),
                baseR * ring2Scale,
                center = Offset(cx, cy),
                style  = Stroke(1f),
            )
            drawCircle(
                NodeXColors.PurpleNeon.copy(alpha = ring3Alpha),
                baseR * ring3Scale,
                center = Offset(cx, cy),
                style  = Stroke(0.8f),
            )

            // Orbiting Tor relay nodes
            val r1 = baseR * 0.9f
            val r2 = baseR * 1.15f
            drawOrbitNode(cx, cy, r1, orbitAngle,        NodeXColors.CyanGlow)
            drawOrbitNode(cx, cy, r1, orbitAngle + 120f, NodeXColors.CyanGlow.copy(alpha = 0.6f))
            drawOrbitNode(cx, cy, r1, orbitAngle + 240f, NodeXColors.CyanGlow.copy(alpha = 0.35f))
            drawOrbitNode(cx, cy, r2, orbitAngle2,        NodeXColors.PurpleNeon.copy(alpha = 0.7f))
            drawOrbitNode(cx, cy, r2, orbitAngle2 + 180f, NodeXColors.PurpleNeon.copy(alpha = 0.35f))
        }

        // ── Logo + App name + tagline ─────────────────────────────────────────
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier            = Modifier
                .alpha(logoAlpha)
                .scale(logoScale),
        ) {

            // ── REAL PNG LOGO ─────────────────────────────────────────────────
            // File: shared/src/commonMain/composeResources/drawable/ic_nodex_logo.png
            // Replace your 1024×1024 PNG there and sync Gradle.
            Box(
                contentAlignment = Alignment.Center,
                modifier         = Modifier.size(130.dp),
            ) {
                // Outer glow backdrop behind the logo
                Canvas(modifier = Modifier.size(160.dp)) {
                    drawCircle(
                        brush  = Brush.radialGradient(
                            colors  = listOf(
                                NodeXColors.CyanGlow.copy(alpha = 0.18f * logoPulse),
                                NodeXColors.PurpleNeon.copy(alpha = 0.10f * logoPulse),
                                Color.Transparent,
                            ),
                            radius = size.minDimension / 2,
                        ),
                        radius = size.minDimension / 2,
                    )
                }

                // Canvas-drawn NodeX logo (NX monogram)
                Canvas(modifier = Modifier.size(120.dp)) {
                    val s = size.minDimension
                    // Background circle
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(NodeXColors.CyanGlow.copy(0.3f), NodeXColors.PurpleNeon.copy(0.15f)),
                        ),
                        radius = s / 2,
                    )
                    // "NX" strokes drawn as lines
                    val stroke = Stroke(width = s * 0.07f, cap = StrokeCap.Round)
                    val p = s * 0.22f
                    // N: left-down-right diagonal-up
                    drawLine(NodeXColors.CyanGlow, Offset(p, p), Offset(p, s - p), s * 0.07f, StrokeCap.Round)
                    drawLine(NodeXColors.CyanGlow, Offset(p, p), Offset(s - p, s - p), s * 0.07f, StrokeCap.Round)
                    drawLine(NodeXColors.CyanGlow, Offset(s - p, p), Offset(s - p, s - p), s * 0.07f, StrokeCap.Round)
                }
            }

            Spacer(Modifier.height(24.dp))

            // App name
            Text(
                text          = "NODE X",
                style         = MaterialTheme.typography.displaySmall,
                fontWeight    = FontWeight.Bold,
                color         = NodeXColors.TextPrimary,
                letterSpacing = 8.sp,
                modifier      = Modifier
                    .alpha(textAlpha)
                    .offset(y = textOffset.dp),
            )

            Text(
                text          = "V  P  N",
                style         = MaterialTheme.typography.titleMedium,
                color         = NodeXColors.CyanGlow,
                letterSpacing = 6.sp,
                fontWeight    = FontWeight.SemiBold,
                modifier      = Modifier
                    .alpha(textAlpha)
                    .offset(y = textOffset.dp),
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text     = "Powered by Tor Network",
                style    = MaterialTheme.typography.labelSmall,
                color    = NodeXColors.TextMuted,
                modifier = Modifier.alpha(textAlpha * 0.7f),
            )
        }

        // ── Bottom scanning progress bar ──────────────────────────────────────
        val loadProgress by infiniteTransition.animateFloat(
            initialValue  = 0f,
            targetValue   = 1f,
            animationSpec = infiniteRepeatable(
                animation  = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "load",
        )

        Box(
            modifier         = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .alpha(textAlpha),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LinearProgressIndicator(
                    progress      = { loadProgress },
                    modifier      = Modifier
                        .width(140.dp)
                        .height(2.dp)
                        .graphicsLayer {
                            clip  = true
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(1.dp)
                        },
                    color      = NodeXColors.CyanGlow,
                    trackColor = NodeXColors.DarkMatter,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text  = "Initialising secure connection…",
                    style = MaterialTheme.typography.labelSmall,
                    color = NodeXColors.TextMuted.copy(alpha = 0.5f),
                )
            }
        }
    }
}

// ── Canvas helpers ────────────────────────────────────────────────────────────

private fun DrawScope.drawParticleGrid() {
    val rows = 22; val cols = 13
    val cw   = size.width  / cols
    val ch   = size.height / rows
    for (r in 0..rows) {
        for (c in 0..cols) {
            drawCircle(
                color  = NodeXColors.CyanGlow.copy(alpha = 0.035f),
                radius = 1.5f,
                center = Offset(c * cw, r * ch),
            )
        }
    }
}

private fun DrawScope.drawOrbitNode(
    cx:       Float,
    cy:       Float,
    radius:   Float,
    angleDeg: Float,
    color:    Color,
) {
    val rad = angleDeg.toDouble() * PI / 180.0
    val x   = cx + radius * cos(rad).toFloat()
    val y   = cy + radius * sin(rad).toFloat()
    drawCircle(color, 5f, Offset(x, y))
    drawCircle(color.copy(alpha = 0.25f), 11f, Offset(x, y), style = Stroke(1f))
}
