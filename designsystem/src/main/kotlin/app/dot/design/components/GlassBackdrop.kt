package app.dot.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.dot.design.Dot

/**
 * Real backdrop blur for the Soft Frosted Glass controls.
 *
 * A screen remembers one [rememberGlassBackdrop], marks the content that scrolls *under* its glass controls with
 * [glassBackdropSource] (a sibling of the controls, never their ancestor), and passes the same state to
 * [DotFloatingCapsule] / [DotFrostedPill]. Each control then blurs only the pixels behind its own clipped shape and
 * draws its translucent tint on top; icons and labels stay sharp because they are drawn after the effect.
 *
 * Android 12+ blurs with RenderEffect on the GPU (see [Backdrop]). Older devices (or no state) keep the previous opaque frosted
 * gradient, which is the graceful fallback.
 */
@Composable
fun rememberGlassBackdrop(): Backdrop = rememberBackdrop()

fun Modifier.glassBackdropSource(state: Backdrop?): Modifier = if (state == null) this else backdropSource(state)

/** Surface of a glass control: blurred backdrop + tint when [state] can blur, otherwise the opaque frosted gradient. */
@Composable
internal fun Modifier.glassSurface(state: Backdrop?, pressed: Boolean): Modifier =
    if (LocalHeaderGlass.current != null) {
        // On the frosted header the band behind is already blurred: a light neutral veil reads as glass; a second live
        // blur per button would only cost frames, and the opaque fallback looked like a solid disc.
        background(headerGlassFill(pressed), CircleShape)
    } else if (state == null || !state.blurEnabled || !androidx.compose.ui.platform.LocalView.current.isHardwareAccelerated) {
        background(frostedSurfaceBrush(pressed), CircleShape)
    } else {
        backdropBlur(state, background = Dot.colors.background, tint = glassTintBrush(pressed))
    }

/**
 * Neutral veil over the blurred backdrop: graphite in dark themes, white in light, no hue. Light enough that the moving
 * blurred content is what gives the glass its character, dark/light enough to keep icons legible on any mail.
 */
@Composable
private fun glassTintBrush(pressed: Boolean): Brush = if (Dot.colors.isDark) {
    val a = if (pressed) 0.50f else 0.34f
    Brush.verticalGradient(listOf(Color(0xFF1E1E1E).copy(alpha = a), Color(0xFF121212).copy(alpha = a)))
} else {
    val a = if (pressed) 0.62f else 0.46f
    Brush.verticalGradient(listOf(Color.White.copy(alpha = a), Color(0xFFF2F2F2).copy(alpha = a)))
}

/** Glass on the frosted header (no blur of its own: the header band underneath is the blur). */
@Composable
internal fun headerGlassFill(pressed: Boolean): Color =
    if (Dot.colors.isDark) Color.White.copy(alpha = if (pressed) 0.16f else 0.09f) else Color.Black.copy(alpha = if (pressed) 0.10f else 0.05f)
