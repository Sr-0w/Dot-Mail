package app.dot.mail.ui.thread

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.layout
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MAX_ZOOM = 3f

/** The reader's scroll container, so a pinch can keep the point under the fingers in place vertically too. */
internal val LocalZoomScroll = staticCompositionLocalOf<ScrollableState?> { null }

/**
 * Two-finger zoom for a message body (1×–3×). The zoomed content really takes more room — the layout height grows with
 * the scale — so the surrounding list keeps scrolling to the end of the message; one finger pans sideways while zoomed.
 * Single taps are never consumed, so links in the WebView keep working. Pinching back below ~1.05× snaps to 1×.
 */
internal fun Modifier.pinchToZoom(): Modifier = composed {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(0f) }
    val scroll = LocalZoomScroll.current
    fun clampX(x: Float) = x.coerceIn(-(width * scale - width).coerceAtLeast(0f), 0f)

    this
        .clipToBounds()
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        val zoom = event.calculateZoom()
                        val centroid = event.calculateCentroid(useCurrent = true)
                        val pan = event.calculatePan()
                        val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                        // Keep the point under the fingers where it is. Horizontally we move the content; vertically the
                        // message grows downwards from its top, so the list scrolls by however much that point moved.
                        val contentX = (centroid.x - offsetX) / scale
                        val grownAbove = centroid.y * (newScale / scale - 1f)
                        scale = newScale
                        offsetX = clampX(centroid.x - contentX * newScale + pan.x)
                        scroll?.dispatchRawDelta(grownAbove - pan.y)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    } else if (scale > 1f && pressed.size == 1) {
                        val pan = event.calculatePan()
                        if (abs(pan.x) > abs(pan.y)) {
                            offsetX = clampX(offsetX + pan.x)
                            pressed.first().consume()
                        }
                    }
                } while (event.changes.any { it.pressed })
                if (scale < 1.05f) { scale = 1f; offsetX = 0f }
            }
        }
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            width = placeable.width.toFloat()
            layout(placeable.width, (placeable.height * scale).roundToInt()) {
                // Unzoomed (the normal case) the content is placed directly: no extra graphics layer around a WebView, which
                // is as tall as the whole message and draws through a GPU functor that does not like being wrapped.
                if (scale == 1f && offsetX == 0f) placeable.place(0, 0)
                else placeable.placeWithLayer(0, 0) {
                    scaleX = scale; scaleY = scale
                    translationX = offsetX
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
}
