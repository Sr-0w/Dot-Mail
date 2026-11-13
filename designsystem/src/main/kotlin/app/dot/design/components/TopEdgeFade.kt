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
    return arrayOf(
        0f to 1f, s to 1f, at(0.1f) to 0.97f, at(0.25f) to 0.85f, at(0.4f) to 0.66f, at(0.55f) to 0.46f,
        at(0.7f) to 0.27f, at(0.85f) to 0.11f, at(1f) to 0f,
    )
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
) {
    val reduced = rememberReducedMotion()
    val p = if (reduced) (if (progress > 0.001f) 1f else 0f) else progress.coerceIn(0f, 1f)
    if (p <= 0.001f) return
    val bg = Dot.colors.background
    val blur = state != null && state.blurEnabled && !reduced && androidx.compose.ui.platform.LocalView.current.isHardwareAccelerated
    val stops = edgeMaskStops(solidUntil, fadeLength)
    val box = modifier.fillMaxWidth().height(height).graphicsLayer { alpha = p }
    if (blur) {
        // Pure blur (FR-54, #115): no veil, no tint, no opaque scrim. Only the gradient mask shapes it, so content goes from
        // sharp to progressively blurred without any darkening.
        Box(box.backdropBlur(state!!, background = bg, tint = null, mask = maskBrush(stops, androidx.compose.ui.graphics.Color.White)))
    } else {
        Box(box.background(maskBrush(stops, bg, 0.94f)))
    }
}
