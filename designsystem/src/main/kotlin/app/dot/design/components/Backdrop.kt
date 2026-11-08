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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import io.github.nadeemiqbal.liquidglass.LiquidGlassQuality
import io.github.nadeemiqbal.liquidglass.LiquidGlassState
import io.github.nadeemiqbal.liquidglass.liquidGlass
import io.github.nadeemiqbal.liquidglass.liquidGlassSource
import io.github.nadeemiqbal.liquidglass.rememberLiquidGlassState

/**
 * Dot's backdrop blur, rendered by liquid-glass (Nadeem Iqbal, Apache-2.0): [backdropSource] is `liquidGlassSource`,
 * [backdropBlur] is `liquidGlass`. Screens never depend on the engine directly.
 *
 * `liquidGlass` draws the recorded source at the surface's own origin. Surfaces that don't share the source's corner
 * are drawn in the source's coordinates instead (shifted by the offset between them), with a shape that puts the
 * outline back on the surface; their content is shifted back so it stays where it is laid out.
 */
@Stable
class Backdrop internal constructor(internal val glass: LiquidGlassState) {
    val blurEnabled: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && glass.backdrop != null

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

/** The surface's own rectangle, expressed in the source's coordinates. */
private class ShiftedRect(private val shift: () -> Offset) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(-shift(), size))
}

/**
 * Draws the backdrop behind this element with liquid-glass: blurred by [radius] over an opaque [background], veiled
 * by [tint], shaped by the alpha [mask] (vertical gradient, white = fully frosted) when given. This element's own
 * content stays sharp on top.
 */
fun Modifier.backdropBlur(backdrop: Backdrop, radius: Dp, background: Color, tint: Brush?, mask: Brush? = null): Modifier =
    composed {
        val here = remember { mutableStateOf(Offset.Zero) }
        val shift = { here.value - backdrop.sourceInRoot }
        val shape = remember { ShiftedRect(shift) }
        val solidTint = (tint as? SolidColor)?.value ?: Color.Transparent
        this
            .onPlaced { here.value = it.positionInRoot() }
            .then(if (mask != null) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen } else Modifier)
            .then(if (mask != null) Modifier.drawWithContent { drawContent(); drawRect(mask, blendMode = BlendMode.DstIn) } else Modifier)
            .drawBehind { drawRect(background) }
            // Into the source's coordinates for liquidGlass…
            .drawWithContent { val d = shift(); translate(-d.x, -d.y) { this@drawWithContent.drawContent() } }
            .liquidGlass(
                state = backdrop.glass,
                shape = shape,
                blurRadius = radius,
                saturation = 1f,
                tint = solidTint,
                borderHighlight = SolidColor(Color.Transparent),
                grain = 0.04f,
            )
            // …and back for the surface's own content.
            .drawWithContent { val d = shift(); translate(d.x, d.y) { this@drawWithContent.drawContent() } }
            .then(if (tint != null && tint !is SolidColor) Modifier.drawBehind { drawRect(tint) } else Modifier)
    }
