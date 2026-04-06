// shared/src/commonMain/kotlin/com/nodex/vpn/ui/responsive/AdaptiveLayout.kt
package com.nodex.vpn.ui.responsive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import com.nodex.vpn.ui.theme.NodeXColors

/**
 * Adaptive content container.
 * - Compact  → single full-width column
 * - Medium   → two pane (list | detail) side by side
 * - Expanded → two pane with more breathing room + max-width center
 */
@Composable
fun AdaptiveContentPane(
    windowSize:  WindowSizeClass,
    listPane:    @Composable () -> Unit,
    detailPane:  (@Composable () -> Unit)? = null,
    modifier:    Modifier = Modifier,
) {
    when {
        // Phone: list only (detail navigated to separately)
        windowSize.isCompact -> {
            Box(modifier = modifier.fillMaxSize()) {
                listPane()
            }
        }

        // Tablet: side-by-side 40/60 split
        windowSize.isMedium -> {
            Row(modifier = modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(0.42f).fillMaxHeight()) {
                    listPane()
                }
                VerticalPaneDivider()
                Box(modifier = Modifier.weight(0.58f).fillMaxHeight()) {
                    detailPane?.invoke()
                }
            }
        }

        // Desktop: 35/65 split with max-width constraint
        else -> {
            Row(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(modifier = Modifier.weight(0.35f).fillMaxHeight()) {
                    listPane()
                }
                Box(modifier = Modifier.weight(0.65f).fillMaxHeight()) {
                    detailPane?.invoke()
                }
            }
        }
    }
}

/**
 * Centered scrollable content with max-width constraint for large screens.
 * Prevents content from stretching too wide on desktop.
 */
@Composable
fun CenteredContent(
    windowSize: WindowSizeClass,
    modifier:   Modifier = Modifier,
    content:    @Composable ColumnScope.() -> Unit,
) {
    val hPad = when {
        windowSize.isExpanded -> 48.dp
        windowSize.isMedium   -> 32.dp
        else                  -> 20.dp
    }
    val maxWidth = when {
        windowSize.isExpanded -> 860.dp
        windowSize.isMedium   -> 700.dp
        else                  -> Dp.Unspecified
    }

    Box(
        modifier          = modifier.fillMaxSize(),
        contentAlignment  = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .then(if (maxWidth != Dp.Unspecified) Modifier.widthIn(max = maxWidth) else Modifier.fillMaxWidth())
                .padding(horizontal = hPad),
            content = content,
        )
    }
}

/**
 * Desktop card panel — glass-morphism style surface for content panels.
 */
@Composable
fun DesktopPanel(
    modifier: Modifier = Modifier,
    content:  @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape    = RoundedCornerShape(20.dp),
        color    = NodeXColors.DeepSpace,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(24.dp), content = content)
    }
}

@Composable
private fun VerticalPaneDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(NodeXColors.NebulaDark)
    )
}
