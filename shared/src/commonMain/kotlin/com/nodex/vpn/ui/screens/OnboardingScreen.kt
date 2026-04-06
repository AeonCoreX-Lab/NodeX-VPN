// shared/src/commonMain/kotlin/com/nodex/vpn/ui/screens/OnboardingScreen.kt
package com.nodex.vpn.ui.screens
import com.nodex.vpn.ui.responsive.*
import androidx.compose.ui.unit.dp

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.nodex.vpn.ui.theme.NodeXColors
import kotlinx.coroutines.launch
import kotlin.math.*

private data class OnboardPage(
    val title:    String,
    val subtitle: String,
    val body:     String,
    val accent:   Color,
    val illustrationId: Int,  // 0 = shield, 1 = network, 2 = speed
)

private val pages = listOf(
    OnboardPage(
        title    = "100% Anonymous",
        subtitle = "Zero-log serverless VPN",
        body     = "Your traffic is routed through the Tor network via multiple encrypted relays. No servers we own. No logs. No identity.",
        accent   = NodeXColors.CyanGlow,
        illustrationId = 0,
    ),
    OnboardPage(
        title    = "Bypass Any Block",
        subtitle = "obfs4 bridge technology",
        body     = "Deep packet inspection can't detect you. Our Tor bridges disguise your traffic as normal HTTPS, bypassing even government-level firewalls.",
        accent   = NodeXColors.PurpleNeon,
        illustrationId = 1,
    ),
    OnboardPage(
        title    = "Blazing Fast",
        subtitle = "Rust-powered core engine",
        body     = "The VPN engine is written in Rust for maximum speed and memory safety. Choose from 18 exit countries. Connect in seconds.",
        accent   = NodeXColors.GreenPulse,
        illustrationId = 2,
    ),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(windowSize: WindowSizeClass = WindowSizeClass(WindowWidthClass.Compact, WindowHeightClass.Medium, 400.dp, 800.dp), onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope      = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NodeXColors.Void)
    ) {
        // ── Background gradient that shifts with page ──────────────────────
        val bgColor = lerp(
            pages[0].accent.copy(alpha = 0.06f),
            pages.getOrNull(pagerState.currentPage + 1)?.accent?.copy(alpha = 0.06f)
                ?: pages.last().accent.copy(alpha = 0.06f),
            pagerState.currentPageOffsetFraction.coerceIn(0f, 1f),
        )
        Box(modifier = Modifier.fillMaxSize().background(bgColor))

        Column(modifier = Modifier.fillMaxSize()) {

            // ── Skip button ────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.TopEnd) {
                if (pagerState.currentPage < pages.size - 1) {
                    TextButton(onClick = onFinish) {
                        Text("Skip", color = NodeXColors.TextMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // ── Pages ──────────────────────────────────────────────────────
            HorizontalPager(
                state    = pagerState,
                modifier = Modifier.weight(1f),
            ) { pageIndex ->
                OnboardPageContent(pages[pageIndex])
            }

            // ── Dots + Button ──────────────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 40.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                // Page dots
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(pages.size) { index ->
                        val isActive = index == pagerState.currentPage
                        val width by animateDpAsState(if (isActive) 28.dp else 8.dp, spring(), label = "dot")
                        val color  = if (isActive) pages[pagerState.currentPage].accent else NodeXColors.TextMuted.copy(alpha = 0.4f)
                        Box(
                            modifier = Modifier
                                .height(8.dp)
                                .width(width)
                                .clip(RoundedCornerShape(4.dp))
                                .background(color)
                        )
                    }
                }

                // Action button
                val isLast = pagerState.currentPage == pages.size - 1
                val accent  = pages[pagerState.currentPage].accent

                Button(
                    onClick = {
                        if (isLast) onFinish()
                        else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape    = RoundedCornerShape(16.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = accent),
                ) {
                    Text(
                        text       = if (isLast) "Get Started" else "Next",
                        fontWeight = FontWeight.Bold,
                        fontSize   = 16.sp,
                        color      = NodeXColors.Void,
                    )
                    if (!isLast) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.ArrowForward, null, tint = NodeXColors.Void, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardPageContent(page: OnboardPage) {
    Column(
        modifier            = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // ── Illustration ───────────────────────────────────────────────────
        Box(
            modifier         = Modifier.size(240.dp),
            contentAlignment = Alignment.Center,
        ) {
            OnboardIllustration(page)
        }

        Spacer(Modifier.height(36.dp))

        // ── Text ───────────────────────────────────────────────────────────
        Text(
            text      = page.title,
            style     = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color     = NodeXColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text      = page.subtitle,
            style     = MaterialTheme.typography.titleSmall,
            color     = page.accent,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text      = page.body,
            style     = MaterialTheme.typography.bodyMedium,
            color     = NodeXColors.TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
        )
    }
}

@Composable
private fun OnboardIllustration(page: OnboardPage) {
    val inf = rememberInfiniteTransition(label = "ill_${page.illustrationId}")
    val rot by inf.animateFloat(
        0f, 360f, infiniteRepeatable(tween(12000, easing = LinearEasing)), label = "rot"
    )
    val pulse by inf.animateFloat(
        0.9f, 1.1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2; val cy = size.height / 2
        val r  = size.minDimension / 2

        when (page.illustrationId) {
            // ── Page 0: Animated shield ──────────────────────────────────
            0 -> {
                // Glow backdrop
                drawCircle(page.accent.copy(alpha = 0.08f), r * pulse)
                drawCircle(page.accent.copy(alpha = 0.04f), r * 1.2f * pulse)

                // Three orbit rings
                drawCircle(page.accent.copy(alpha = 0.2f), r * 0.75f, style = Stroke(1f))
                drawCircle(NodeXColors.PurpleNeon.copy(alpha = 0.15f), r * 0.55f, style = Stroke(1f))

                // Orbit dots
                for (i in 0..2) {
                    val a  = Math.toRadians((rot + i * 120.0))
                    val ox = cx + r * 0.75f * cos(a).toFloat()
                    val oy = cy + r * 0.75f * sin(a).toFloat()
                    drawCircle(page.accent, 6f, Offset(ox, oy))
                    drawLine(page.accent.copy(alpha = 0.3f), Offset(cx, cy), Offset(ox, oy), 1f)
                }

                // Shield
                val sw = r * 0.5f; val sh = r * 0.6f
                val shield = Path().apply {
                    moveTo(cx, cy - sh * 0.9f)
                    lineTo(cx + sw, cy - sh * 0.5f)
                    lineTo(cx + sw, cy + sh * 0.1f)
                    cubicTo(cx + sw, cy + sh * 0.5f, cx + sw * 0.4f, cy + sh * 0.8f, cx, cy + sh * 0.9f)
                    cubicTo(cx - sw * 0.4f, cy + sh * 0.8f, cx - sw, cy + sh * 0.5f, cx - sw, cy + sh * 0.1f)
                    lineTo(cx - sw, cy - sh * 0.5f)
                    close()
                }
                drawPath(shield, Brush.verticalGradient(listOf(page.accent, NodeXColors.PurpleNeon), startY = cy - sh, endY = cy + sh))
                drawPath(shield, page.accent, style = Stroke(2f))
                // Checkmark inside
                val chk = Path().apply {
                    moveTo(cx - sw * 0.3f, cy)
                    lineTo(cx - sw * 0.05f, cy + sh * 0.2f)
                    lineTo(cx + sw * 0.35f, cy - sh * 0.2f)
                }
                drawPath(chk, Color.White.copy(alpha = 0.95f), style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            // ── Page 1: Tor network nodes ─────────────────────────────────
            1 -> {
                val nodes = listOf(
                    Offset(cx, cy),                           // center (guard)
                    Offset(cx - r * 0.55f, cy - r * 0.3f),   // middle relay 1
                    Offset(cx + r * 0.55f, cy - r * 0.3f),   // middle relay 2
                    Offset(cx, cy - r * 0.7f),               // exit
                    Offset(cx - r * 0.6f, cy + r * 0.4f),
                    Offset(cx + r * 0.6f, cy + r * 0.4f),
                )
                // Connections
                val connections = listOf(0 to 1, 0 to 2, 1 to 3, 2 to 3, 0 to 4, 0 to 5)
                for ((a, b) in connections) {
                    val progress = ((rot / 360f) * 100 % 100) / 100f
                    val p = nodes[a] + (nodes[b] - nodes[a]) * progress
                    drawLine(page.accent.copy(alpha = 0.25f), nodes[a], nodes[b], 1.5f)
                    drawCircle(page.accent, 3f, p)  // animated packet
                }
                // Nodes
                nodes.forEachIndexed { i, pos ->
                    val nodeColor = if (i == 0) page.accent else if (i == 3) NodeXColors.GreenPulse else NodeXColors.PurpleNeon
                    drawCircle(NodeXColors.DeepSpace, r * 0.12f, pos)
                    drawCircle(nodeColor, r * 0.12f, pos, style = Stroke(2f))
                    drawCircle(nodeColor.copy(alpha = 0.2f), r * 0.18f, pos)
                }
            }

            // ── Page 2: Speed gauge ────────────────────────────────────────
            else -> {
                // Speedometer arc
                val sweepAngle = 200f
                val startAngle = 180f - (sweepAngle - 180f) / 2
                val speed      = (sin(Math.toRadians(rot.toDouble())) * 0.4 + 0.6).toFloat()

                drawArc(NodeXColors.DarkMatter, startAngle, sweepAngle, false,
                    topLeft = Offset(cx - r * 0.7f, cy - r * 0.7f), size = Size(r * 1.4f, r * 1.4f), style = Stroke(r * 0.12f, cap = StrokeCap.Round))
                drawArc(Brush.sweepGradient(listOf(NodeXColors.CyanGlow, NodeXColors.GreenPulse), Offset(cx, cy)),
                    startAngle, sweepAngle * speed, false,
                    topLeft = Offset(cx - r * 0.7f, cy - r * 0.7f), size = Size(r * 1.4f, r * 1.4f), style = Stroke(r * 0.12f, cap = StrokeCap.Round))

                // Needle
                val needleAngle = Math.toRadians((startAngle + sweepAngle * speed).toDouble())
                val nx = cx + r * 0.5f * cos(needleAngle).toFloat()
                val ny = cy + r * 0.5f * sin(needleAngle).toFloat()
                drawLine(page.accent, Offset(cx, cy), Offset(nx, ny), 3f, cap = StrokeCap.Round)
                drawCircle(page.accent, 8f, Offset(cx, cy))

                // Speed ticks
                repeat(11) { i ->
                    val a   = Math.toRadians((startAngle + sweepAngle / 10 * i).toDouble())
                    val ir  = r * 0.58f; val or_ = r * 0.65f
                    drawLine(
                        if (i <= (speed * 10).toInt()) page.accent else NodeXColors.TextMuted.copy(0.3f),
                        Offset(cx + ir * cos(a).toFloat(), cy + ir * sin(a).toFloat()),
                        Offset(cx + or_ * cos(a).toFloat(), cy + or_ * sin(a).toFloat()),
                        2f,
                    )
                }
            }
        }
    }
}

private operator fun Offset.times(f: Float) = Offset(x * f, y * f)
private operator fun Offset.plus(o: Offset)  = Offset(x + o.x, y + o.y)
private operator fun Offset.minus(o: Offset) = Offset(x - o.x, y - o.y)
