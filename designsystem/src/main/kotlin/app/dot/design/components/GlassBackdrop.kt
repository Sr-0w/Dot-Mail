package app.dot.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * Real backdrop blur for the Soft Frosted Glass controls.
 *
 * A screen remembers one [rememberGlassBackdrop], marks the content that scrolls *under* its glass controls with
 * [glassBackdropSource] (a sibling of the controls, never their ancestor), and passes the same state to
 * [DotFloatingCapsule] / [DotFrostedPill]. Each control then blurs only the pixels behind its own clipped shape and
 * draws its translucent tint on top; icons and labels stay sharp because they are drawn after the effect.
 *
 * Android 12+ blurs with RenderEffect on the GPU. Older devices (or no state) keep the previous opaque frosted
 * gradient, which is the graceful fallback.
 */
@Composable
fun rememberGlassBackdrop(): HazeState = rememberHazeState()

fun Modifier.glassBackdropSource(state: HazeState?): Modifier = if (state == null) this else hazeSource(state)

/** Surface of a glass control: blurred backdrop + tint when [state] can blur, otherwise the opaque frosted gradient. */
@Composable
internal fun Modifier.glassSurface(state: HazeState?, pressed: Boolean): Modifier =
    if (state == null || !state.blurEnabled || !androidx.compose.ui.platform.LocalView.current.isHardwareAccelerated) {
        background(frostedSurfaceBrush(pressed), CircleShape)
    } else {
        hazeEffect(
            state,
            HazeStyle(
                backgroundColor = Dot.colors.background,
                tints = listOf(HazeTint(glassTintBrush(pressed))),
                blurRadius = 18.dp,
                noiseFactor = 0.04f,
            ),
        )
    }

/** Same hues as [frostedSurfaceBrush] but translucent enough for the blurred content to read through. */
@Composable
private fun glassTintBrush(pressed: Boolean): Brush = if (Dot.colors.isDark) {
    val a = if (pressed) 0.82f else 0.62f
    Brush.verticalGradient(listOf(Color(0xFF2A2A30).copy(alpha = a), Color(0xFF1C1C21).copy(alpha = a)))
} else {
    val a = if (pressed) 0.84f else 0.66f
    Brush.verticalGradient(listOf(Color(0xFFF5F5F8).copy(alpha = a), Color(0xFFE8E8EE).copy(alpha = a)))
}
