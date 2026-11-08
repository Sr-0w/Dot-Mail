package app.dot.design.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize

/**
 * Dot's own backdrop blur (replaces Haze, whose downsampled, one-frame-late copy of the list shimmered while scrolling).
 *
 * The source records its content once per frame into a [GraphicsLayer] and draws it. Every blur surface draws *that same
 * layer* (a RenderNode reference, not a copy) through a GPU [BlurEffect], at full resolution. Because a RenderNode drawn
 * inside another display list always shows its latest recording, a scrolling list updates its sharp and blurred pixels in
 * the very same frame: nothing lags, nothing is resampled, so nothing can flicker. Offsets are read from layout coordinates
 * at draw time, so they are never stale either.
 *
 * Usage: `val backdrop = rememberBackdrop()`, `Modifier.backdropSource(backdrop)` on the scrolling content (a sibling of
 * the blur surfaces, never their ancestor), `Modifier.backdropBlur(backdrop, …)` on the surfaces. Android 12+ only;
 * [blurEnabled] is false below, where callers draw their opaque fallback.
 */
@Stable
class Backdrop internal constructor() {
    internal var source: BackdropSourceNode? = null
    internal val effects = mutableSetOf<BackdropBlurNode>()
    val blurEnabled: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    internal fun sourceMoved() = effects.forEach { it.invalidateDraw() }
}

@Composable
fun rememberBackdrop(): Backdrop = remember { Backdrop() }

/** Marks the content that glass and the frosted header blur. */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this then BackdropSourceElement(backdrop)

/**
 * Draws the backdrop behind this element: the source blurred by [radius], over an opaque [background] (so gaps between
 * glyphs never show the sharp content through), veiled by [tint], then shaped by the alpha [mask] (vertical gradient,
 * white = fully frosted) when given. Content of this element is drawn on top, sharp.
 */
fun Modifier.backdropBlur(backdrop: Backdrop, radius: Dp, background: Color, tint: Brush?, mask: Brush? = null): Modifier =
    this then BackdropBlurElement(backdrop, radius, background, tint, mask)

private data class BackdropSourceElement(val backdrop: Backdrop) : ModifierNodeElement<BackdropSourceNode>() {
    override fun create() = BackdropSourceNode(backdrop)
    override fun update(node: BackdropSourceNode) { node.update(backdrop) }
    override fun InspectorInfo.inspectableProperties() { name = "backdropSource" }
}

internal class BackdropSourceNode(private var backdrop: Backdrop) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    var layer: GraphicsLayer? = null
        private set
    var coordinates: LayoutCoordinates? = null
        private set
    private var lastPosition = Offset.Unspecified

    override fun onAttach() {
        layer = requireGraphicsContext().createGraphicsLayer()
        backdrop.source = this
        backdrop.sourceMoved()
    }

    override fun onDetach() {
        if (backdrop.source === this) backdrop.source = null
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
        coordinates = null
        backdrop.sourceMoved()
    }

    fun update(next: Backdrop) {
        if (next === backdrop) return
        if (backdrop.source === this) backdrop.source = null
        backdrop.sourceMoved()
        backdrop = next
        next.source = this
        next.sourceMoved()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        val p = coordinates.positionInRoot()
        if (p != lastPosition) { lastPosition = p; backdrop.sourceMoved() }
    }

    override fun ContentDrawScope.draw() {
        val l = layer ?: return drawContent()
        l.record(size.toIntSize()) { this@draw.drawContent() }
        drawLayer(l)
    }
}

private fun LayoutCoordinates.positionInRoot(): Offset = localToRoot(Offset.Zero)

private data class BackdropBlurElement(
    val backdrop: Backdrop, val radius: Dp, val background: Color, val tint: Brush?, val mask: Brush?,
) : ModifierNodeElement<BackdropBlurNode>() {
    override fun create() = BackdropBlurNode(backdrop, radius, background, tint, mask)
    override fun update(node: BackdropBlurNode) = node.update(backdrop, radius, background, tint, mask)
    override fun InspectorInfo.inspectableProperties() { name = "backdropBlur" }
}

internal class BackdropBlurNode(
    private var backdrop: Backdrop, private var radius: Dp, private var background: Color,
    private var tint: Brush?, private var mask: Brush?,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var blurLayer: GraphicsLayer? = null
    private var maskLayer: GraphicsLayer? = null
    private var lastPosition = Offset.Unspecified
    private var appliedRadiusPx = -1f

    override fun onAttach() { backdrop.effects += this }

    override fun onDetach() {
        backdrop.effects -= this
        val ctx = requireGraphicsContext()
        blurLayer?.let(ctx::releaseGraphicsLayer); blurLayer = null
        maskLayer?.let(ctx::releaseGraphicsLayer); maskLayer = null
        appliedRadiusPx = -1f
    }

    fun update(backdrop: Backdrop, radius: Dp, background: Color, tint: Brush?, mask: Brush?) {
        if (backdrop !== this.backdrop) { this.backdrop.effects -= this; backdrop.effects += this; this.backdrop = backdrop }
        this.radius = radius; this.background = background; this.tint = tint; this.mask = mask
        invalidateDraw()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInRoot()
        if (p != lastPosition) { lastPosition = p; invalidateDraw() }
    }

    override fun ContentDrawScope.draw() {
        val source = backdrop.source
        val sourceLayer = source?.layer
        val sourceCoords = source?.coordinates
        if (!backdrop.blurEnabled || sourceLayer == null || sourceCoords == null || !sourceCoords.isAttached || size.minDimension <= 0f) {
            drawContent(); return
        }
        // Where this surface sits inside the source, read now: never a position from a previous frame.
        val offset = sourceCoords.localPositionOf(requireLayoutCoordinates(), Offset.Zero)
        val ctx = requireGraphicsContext()
        val blur = blurLayer ?: ctx.createGraphicsLayer().also { blurLayer = it }
        val px = radius.toPx()
        if (px != appliedRadiusPx) { blur.renderEffect = BlurEffect(px, px, TileMode.Clamp); appliedRadiusPx = px }
        val box: IntSize = size.toIntSize()
        blur.record(box) {
            drawRect(background)
            translate(-offset.x, -offset.y) { drawLayer(sourceLayer) }
        }
        val m = mask
        if (m == null) {
            drawLayer(blur)
            tint?.let { drawRect(it) }
        } else {
            val masked = maskLayer ?: ctx.createGraphicsLayer().also { maskLayer = it; it.compositingStrategy = CompositingStrategy.Offscreen }
            masked.record(box) {
                drawLayer(blur)
                tint?.let { drawRect(it) }
                drawRect(m, blendMode = BlendMode.DstIn)
            }
            drawLayer(masked)
        }
        drawContent()
    }
}
