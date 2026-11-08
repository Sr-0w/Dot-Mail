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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import app.dot.design.rememberReducedMotion

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
 * Alpha mask of the frosted edge: fully on down to [solidUntil] (fraction of the height: the bottom of the visible header),
 * then easing out to nothing over [fadeLength] (fraction). The same curve drives the blur *and* its neutral tint, so content
 * goes from sharp to progressively blurred with no darker strip ahead of the blur.
 */
fun edgeMaskStops(solidUntil: Float, fadeLength: Float): Array<Pair<Float, Float>> {
    val s = solidUntil.coerceIn(0f, 1f)
    val f = fadeLength.coerceAtLeast(0.01f)
    fun at(t: Float) = (s + f * t).coerceAtMost(1f)
    // Smoothstep-like ease out: no visible knee where the solid part ends.
    return arrayOf(0f to 1f, s to 1f, at(0.2f) to 0.9f, at(0.4f) to 0.66f, at(0.6f) to 0.38f, at(0.8f) to 0.14f, at(1f) to 0f)
        .distinctBy { it.first }.toTypedArray()
}

private fun maskBrush(stops: Array<Pair<Float, Float>>, color: androidx.compose.ui.graphics.Color, maxAlpha: Float = 1f): Brush =
    Brush.verticalGradient(*stops.map { (y, a) -> y to color.copy(alpha = a * maxAlpha) }.toTypedArray())

/**
 * The soft frosted edge at the top of a scrolling area. Where the platform can blur, the content under it is blurred
 * (uniform strength, shaped by [edgeMaskStops]) with a light neutral tint for contrast; titles and buttons are drawn on
 * top and stay crisp. Without a usable [state] (no RenderEffect, or WebView content that must not be captured) it is a
 * page-coloured gradient of the same shape. Not a glass control: see [glassSurface] for those.
 *
 * Place it as a **sibling above** the scrolling content, and mark only that content with `backdropSource(state)` (never an
 * ancestor of this overlay). Keep its [height] stable while scrolling (it is the blur texture size) and move
 * [solidUntil] instead: changing a mask is cheap, resizing the effect reallocates and flickers.
 */
@Composable
fun TopEdgeFade(
    state: Backdrop?,
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 32.dp,
    solidUntil: Float = 0.6f,
    fadeLength: Float = 1f - solidUntil,
    edgeScrim: Dp = 0.dp,
    edgeScrimSolid: Dp = 0.dp,
) {
    val reduced = rememberReducedMotion()
    val p = if (reduced) (if (progress > 0.001f) 1f else 0f) else progress.coerceIn(0f, 1f)
    if (p <= 0.001f) return
    val bg = Dot.colors.background
    val blur = state != null && state.blurEnabled && !reduced && androidx.compose.ui.platform.LocalView.current.isHardwareAccelerated
    val stops = edgeMaskStops(solidUntil, fadeLength)
    val box = modifier.fillMaxWidth().height(height).graphicsLayer { alpha = p }
    if (blur) {
        // Neutral page-colour veil only (no accent hue); light enough that the moving blurred content stays visible.
        val veil = androidx.compose.ui.graphics.SolidColor(bg.copy(alpha = if (Dot.colors.isDark) 0.34f else 0.42f))
        Box(box.backdropBlur(state!!, radius = 28.dp, background = bg, tint = veil, mask = maskBrush(stops, androidx.compose.ui.graphics.Color.White))
            .edgeScrim(edgeScrim, edgeScrimSolid, bg))
    } else {
        Box(box.background(maskBrush(stops, bg, 0.94f)))
    }
}

/**
 * Page colour over the top [height] of the band: fully opaque down to [solid] (the screen edge and status bar), then easing
 * out to clear at [height]. At the screen edge there is nothing beyond to blur, so content sliding out there changes the
 * blur abruptly; native-quality frosted bars (Grok's, for one) hide that half of the bar under an opaque page-colour
 * gradient and let the blur show only where content is genuinely passing underneath.
 */
private fun Modifier.edgeScrim(height: Dp, solid: Dp, color: androidx.compose.ui.graphics.Color): Modifier =
    if (height <= 0.dp) this else this.then(androidx.compose.ui.Modifier.drawWithContent {
        drawContent()
        val h = height.toPx().coerceAtMost(size.height)
        val s = (solid.toPx() / h).coerceIn(0f, 0.95f)
        drawRect(
            Brush.verticalGradient(
                0f to color, s to color,
                s + (1f - s) * 0.35f to color.copy(alpha = 0.72f),
                s + (1f - s) * 0.7f to color.copy(alpha = 0.30f),
                1f to color.copy(alpha = 0f), endY = h,
            ),
            size = androidx.compose.ui.geometry.Size(size.width, h),
        )
    })
