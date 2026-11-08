package app.dot.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import app.dot.design.rememberReducedMotion
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * How far content has scrolled under the top edge, 0 (at rest) … 1 (fully under). Interpolated, never on/off.
 * [offsetPx] is the scroll distance from the top; the effect is complete after [thresholdPx].
 */
fun edgeProgress(offsetPx: Float, thresholdPx: Float): Float =
    if (thresholdPx <= 0f) (if (offsetPx > 0f) 1f else 0f) else (offsetPx / thresholdPx).coerceIn(0f, 1f)

/** Scroll progress of a lazy list: 0 while its first item is still fully visible, rising over [threshold] of travel. */
@Composable
fun rememberTopEdgeProgress(state: LazyListState, threshold: Dp = 48.dp): Float {
    val px = with(LocalDensity.current) { threshold.toPx() }
    val value by remember(state, px) {
        derivedStateOf { if (state.firstVisibleItemIndex > 0) 1f else edgeProgress(state.firstVisibleItemScrollOffset.toFloat(), px) }
    }
    return value
}

/** Scroll progress of a plain scrolling column. */
@Composable
fun rememberTopEdgeProgress(state: ScrollState, threshold: Dp = 48.dp): Float {
    val px = with(LocalDensity.current) { threshold.toPx() }
    val value by remember(state, px) { derivedStateOf { edgeProgress(state.value.toFloat(), px) } }
    return value
}

/**
 * The soft frosted edge at the top of a scrolling area: as content moves under it, it fades to the page colour and (where
 * the platform can blur) blurs progressively, strongest at the very top. Titles and buttons are not part of it, so they
 * stay crisp. Not a glass control: see [glassSurface] for those.
 *
 * Place it as a **sibling above** the scrolling content, and mark only that content with `hazeSource(state)` (never an
 * ancestor of this overlay). Without a usable [state] (no RenderEffect, e.g. before Android 12, or WebView content that
 * cannot be captured) it is the plain gradient. With reduced motion the blur is dropped and the gradient is steady.
 */
@Composable
fun TopEdgeFade(
    state: HazeState?,
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 32.dp,
) {
    val reduced = rememberReducedMotion()
    val p = if (reduced) (if (progress > 0.001f) 1f else 0f) else progress.coerceIn(0f, 1f)
    if (p <= 0.001f) return
    val bg = Dot.colors.background
    val blur = state != null && state.blurEnabled && !reduced
    val effect = if (blur) Modifier.hazeEffect(
        state!!,
        HazeStyle(backgroundColor = bg, tints = listOf(HazeTint(bg.copy(alpha = 0.35f))), blurRadius = 14.dp, noiseFactor = 0f),
    ) { progressive = HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f) } else Modifier
    Box(
        modifier.fillMaxWidth().height(height).graphicsLayer {
            alpha = p
            compositingStrategy = CompositingStrategy.Offscreen
        }.drawWithContent {
            drawContent()
            // Fade the complete blur+tint, not only its background. No hard seam at the bottom.
            drawRect(Brush.verticalGradient(0f to androidx.compose.ui.graphics.Color.White,
                0.35f to androidx.compose.ui.graphics.Color.White,
                0.72f to androidx.compose.ui.graphics.Color.White.copy(alpha=0.45f),
                1f to androidx.compose.ui.graphics.Color.Transparent),blendMode=BlendMode.DstIn)
        }.then(effect)
            .background(Brush.verticalGradient(listOf(bg.copy(alpha = if (blur) 0.72f else 0.94f), bg.copy(alpha = 0f)))),
    )
}
