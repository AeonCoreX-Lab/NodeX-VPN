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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.nodex.vpn.auth.AuthState
import com.nodex.vpn.shared.generated.resources.Res
import com.nodex.vpn.shared.generated.resources.ic_nodex_logo
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import kotlin.math.*
import com.nodex.vpn.ui.theme.NodeXColors

@Composable
fun SplashScreen(
    authState:  AuthState,
    onFinished: (isAuthenticated: Boolean) -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "splash")

    // ── Pulsing rings (orbit animation) ──────────────────────────────────────
    val ring1Scale by infiniteTransition.animateFloat(0.6f, 1.2f,
        infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "r1")
    val ring2Scale by infiniteTransition.animateFloat(0.7f, 1.4f,
        infiniteRepeatable(tween(2400, 300, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "r2")
    val ring3Scale by infiniteTransition.animateFloat(0.5f, 1.6f,
        infiniteRepeatable(tween(3000, 600, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "r3")
    val ring1Alpha by infiniteTransition.animateFloat(0.8f, 0f,
        infiniteRepeatable(tween(2000), RepeatMode.Restart), label = "a1")
    val ring2Alpha by infiniteTransition.animateFloat(0.6f, 0f,
        infiniteRepeatable(tween(2400, 300), RepeatMode.Restart), label = "a2")
    val ring3Alpha by infiniteTransition.animateFloat(0.4f, 0f,
        infiniteRepeatable(tween(3000, 600), RepeatMode.Restart), label = "a3")
    val orbitAngle  by infiniteTransition.animateFloat(0f, 360f,
        infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "o1")
    val orbitAngle2 by infiniteTransition.animateFloat(360f, 0f,
        infiniteRepeatable(tween(12000, easing = LinearEasing)), label = "o2")

    // ── Logo/text entrance ────────────────────────────────────────────────────
    var logoVisible by remember { mutableStateOf(false) }
    var textVisible by remember { mutableStateOf(false) }

    val logoAlpha by animateFloatAsState(if (logoVisible) 1f else 0f, tween(900), label = "la")
    val logoScale by animateFloatAsState(
        if (logoVisible) 1f else 0.55f, spring(0.55f, 180f), label = "ls")
    val textAlpha  by animateFloatAsState(if (textVisible) 1f else 0f, tween(600), label = "ta")
    val textOffset by animateFloatAsState(if (textVisible) 0f else 28f,
        tween(600, easing = FastOutSlowInEasing), label = "to")
    val logoPulse by infiniteTransition.animateFloat(0.85f, 1.0f,
        infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "lp")

    // ── FIX: two separate LaunchedEffects ─────────────────────────────────────
    // Previously: LaunchedEffect(Unit) captured authState by value at first
    // composition. The while(authState is Loading) loop never saw state updates
    // because `authState` is a val parameter — it doesn't change inside the effect.
    //
    // Fix: separate the animation timing (LaunchedEffect(Unit)) from auth-state
    // routing (LaunchedEffect(splashDone, authState)). splashDone is a MutableState
    // that the animation effect sets after the minimum display time. The routing
    // effect is keyed on BOTH splashDone AND authState so it re-runs whenever
    // either changes, and it always reads the current value of authState.

    var splashDone by remember { mutableStateOf(false) }

    // Animation + minimum display time
    LaunchedEffect(Unit) {
        delay(120);  logoVisible = true
        delay(450);  textVisible = true
        delay(1800); splashDone  = true
    }

    // Route to next screen — re-triggers whenever authState OR splashDone changes
    LaunchedEffect(splashDone, authState) {
        if (splashDone && authState !is AuthState.Loading) {
            onFinished(authState is AuthState.Authenticated)
        }
    }

    // ── Root container ────────────────────────────────────────────────────────
    Box(
        modifier         = Modifier.fillMaxSize().background(NodeXColors.Void),
        contentAlignment = Alignment.Center,
    ) {
        // Dot grid
        Canvas(modifier = Modifier.fillMaxSize()) { drawParticleGrid() }

        // Orbit rings
        Canvas(modifier = Modifier.size(320.dp)) {
            val cx = size.width / 2; val cy = size.height / 2
            val baseR = size.minDimension / 2 * 0.5f
            drawCircle(NodeXColors.CyanGlow.copy(ring1Alpha),   baseR * ring1Scale,
                Offset(cx,cy), style = Stroke(1.5f))
            drawCircle(NodeXColors.CyanGlow.copy(ring2Alpha),   baseR * ring2Scale,
                Offset(cx,cy), style = Stroke(1f))
            drawCircle(NodeXColors.PurpleNeon.copy(ring3Alpha), baseR * ring3Scale,
                Offset(cx,cy), style = Stroke(0.8f))
            val r1 = baseR * 0.9f; val r2 = baseR * 1.15f
            drawOrbitNode(cx, cy, r1, orbitAngle,        NodeXColors.CyanGlow)
            drawOrbitNode(cx, cy, r1, orbitAngle + 120f, NodeXColors.CyanGlow.copy(0.6f))
            drawOrbitNode(cx, cy, r1, orbitAngle + 240f, NodeXColors.CyanGlow.copy(0.35f))
            drawOrbitNode(cx, cy, r2, orbitAngle2,        NodeXColors.PurpleNeon.copy(0.7f))
            drawOrbitNode(cx, cy, r2, orbitAngle2 + 180f, NodeXColors.PurpleNeon.copy(0.35f))
        }

        // Logo + text
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.alpha(logoAlpha).scale(logoScale),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(130.dp)) {
                // Outer glow backdrop
                Canvas(modifier = Modifier.size(160.dp)) {
                    drawCircle(
                        brush = Brush.radialGradient(listOf(
                            NodeXColors.CyanGlow.copy(0.18f * logoPulse),
                            NodeXColors.PurpleNeon.copy(0.10f * logoPulse),
                            Color.Transparent,
                        ), radius = size.minDimension / 2),
                        radius = size.minDimension / 2,
                    )
                }
                // Real logo PNG via Compose Resources
                // ic_nodex_logo.png is at:
                //   shared/src/commonMain/composeResources/drawable/ic_nodex_logo.png
                Image(
                    painter            = painterResource(Res.drawable.ic_nodex_logo),
                    contentDescription = "NodeX VPN Logo",
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier.size(110.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text          = "NODE X",
                style         = MaterialTheme.typography.displaySmall,
                fontWeight    = FontWeight.Bold,
                color         = NodeXColors.TextPrimary,
                letterSpacing = 8.sp,
                modifier      = Modifier.alpha(textAlpha).offset(y = textOffset.dp),
            )
            Text(
                text          = "V  P  N",
                style         = MaterialTheme.typography.titleMedium,
                color         = NodeXColors.CyanGlow,
                letterSpacing = 6.sp,
                fontWeight    = FontWeight.SemiBold,
                modifier      = Modifier.alpha(textAlpha).offset(y = textOffset.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text     = "Powered by Tor Network",
                style    = MaterialTheme.typography.labelSmall,
                color    = NodeXColors.TextMuted,
                modifier = Modifier.alpha(textAlpha * 0.7f),
            )
        }

        // Bottom progress bar
        val loadProgress by infiniteTransition.animateFloat(0f, 1f,
            infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "lp2")
        Box(
            modifier         = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = 64.dp).alpha(textAlpha),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LinearProgressIndicator(
                    progress  = { loadProgress },
                    modifier  = Modifier.width(140.dp).height(2.dp)
                        .graphicsLayer { clip = true; shape = androidx.compose.foundation.shape.RoundedCornerShape(1.dp) },
                    color     = NodeXColors.CyanGlow,
                    trackColor = NodeXColors.DarkMatter,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text  = "Initialising secure connection…",
                    style = MaterialTheme.typography.labelSmall,
                    color = NodeXColors.TextMuted.copy(0.5f),
                )
            }
        }
    }
}

private fun DrawScope.drawParticleGrid() {
    val rows = 22; val cols = 13
    val cw   = size.width  / cols
    val ch   = size.height / rows
    for (r in 0..rows) for (c in 0..cols)
        drawCircle(NodeXColors.CyanGlow.copy(0.035f), 1.5f, Offset(c * cw, r * ch))
}

private fun DrawScope.drawOrbitNode(cx: Float, cy: Float, radius: Float, angleDeg: Float, color: Color) {
    val rad = angleDeg.toDouble() * PI / 180.0
    val x   = cx + radius * cos(rad).toFloat()
    val y   = cy + radius * sin(rad).toFloat()
    drawCircle(color, 5f, Offset(x, y))
    drawCircle(color.copy(0.25f), 11f, Offset(x, y), style = Stroke(1f))
}
