package app.dot.design.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import io.github.nadeemiqbal.liquidglass.LiquidGlassQuality
import io.github.nadeemiqbal.liquidglass.LiquidGlassState
import io.github.nadeemiqbal.liquidglass.liquidGlassSource
import io.github.nadeemiqbal.liquidglass.rememberLiquidGlassState

/**
 * Dot's backdrop blur: one small API over liquid-glass (Nadeem Iqbal, Apache-2.0), so screens never depend on the
 * blur engine directly.
 *
 * [backdropSource] records the scrolling content into liquid-glass's full-resolution GraphicsLayer
 * ([LiquidGlassQuality.Full], no downsampling). [backdropBlur] draws that layer behind a surface. liquid-glass's own
 * `liquidGlass()` draws the layer at the surface's origin, which only works when surface and source share a corner, so
 * we draw it ourselves: shifted by where the source sits relative to the surface, blurred (edges read as transparent
 * over the opaque background, never smeared), tinted and masked.
 *
 * Usage: `val backdrop = rememberBackdrop()`, `Modifier.backdropSource(backdrop)` on the scrolling content (a sibling
 * of the blur surfaces, never their ancestor), `Modifier.backdropBlur(backdrop, …)` on the surfaces. Blur needs
 * Android 12+; [blurEnabled] is false below, where callers draw their opaque fallback.
 */
@Stable
class Backdrop internal constructor(internal val glass: LiquidGlassState) {
    val blurEnabled: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && glass.backdrop != null

    /** Where the recorded source sits in the root; surfaces offset the layer by it. */
    internal var sourceInRoot by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val glass = rememberLiquidGlassState(LiquidGlassQuality.Full)
    return remember(glass) { Backdrop(glass) }
}

/** Marks the content that glass and the frosted header blur. */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier =
    onPlaced { backdrop.sourceInRoot = it.positionInRoot() }.liquidGlassSource(backdrop.glass)

/**
 * Draws the backdrop behind this element: blurred by [radius] over an opaque [background], veiled by [tint], shaped by
 * the alpha [mask] (vertical gradient, white = fully frosted) when given. This element's own content stays sharp on top.
 */
fun Modifier.backdropBlur(backdrop: Backdrop, radius: Dp, background: Color, tint: Brush?, mask: Brush? = null): Modifier =
    composed {
        val here = remember { mutableStateOf(Offset.Zero) }
        onPlaced { here.value = it.positionInRoot() }.drawWithCache {
            val radiusPx = radius.toPx()
            val blur = if (radiusPx > 0f) BlurEffect(radiusPx, radiusPx, TileMode.Decal) else null
            val bounds = Rect(Offset.Zero, size)
            val maskPaint = Paint()
            // Our own layer carries the blur: the shared source layer has one renderEffect, and surfaces with
            // different radii would overwrite each other's within a frame.
            val own = obtainGraphicsLayer().apply { renderEffect = blur }
            onDrawWithContent {
                val layer = backdrop.glass.backdrop
                drawIntoCanvas { if (mask != null) it.saveLayer(bounds, maskPaint) }
                drawRect(background)
                if (layer != null && layer.size.width > 0 && backdrop.blurEnabled) {
                    val d = backdrop.sourceInRoot - here.value
                    own.record { translate(d.x, d.y) { drawLayer(layer) } }
                    clipRect { drawLayer(own) }
                }
                if (tint != null) drawRect(tint)
                if (mask != null) {
                    drawRect(mask, blendMode = BlendMode.DstIn)
                    drawIntoCanvas { it.restore() }
                }
                drawContent()
            }
        }
    }
