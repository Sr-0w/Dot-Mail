package app.dot.design.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.HazeFeatureFlags
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * Dot's backdrop blur: one small API over Haze 2, so screens never depend on the blur engine directly.
 *
 * Effects read [HazeInput.Backdrop]: on Android 17+ the platform blurs the window behind the surface natively (no
 * captured copy, so nothing lags behind a scrolling list and nothing flickers); elsewhere Haze falls back to the
 * content marked with [backdropSource]. Call [enablePlatformBackdrop] once at startup, before any surface attaches.
 *
 * Usage: `val backdrop = rememberBackdrop()`, `Modifier.backdropSource(backdrop)` on the scrolling content (a sibling
 * of the blur surfaces, never their ancestor), `Modifier.backdropBlur(backdrop, …)` on the surfaces. Blur needs
 * Android 12+; [blurEnabled] is false below, where callers draw their opaque fallback.
 */
@Stable
class Backdrop internal constructor(internal val haze: HazeState) {
    val blurEnabled: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

/** Lets Android 17+ blur the window backdrop natively. Must run before the first blur surface attaches. */
fun enablePlatformBackdrop() { HazeFeatureFlags.isPlatformBackdropEnabled = true }

@Composable
fun rememberBackdrop(): Backdrop {
    val haze = rememberHazeState()
    return remember(haze) { Backdrop(haze) }
}

/** Marks the content that glass and the frosted header blur where the platform can't blur the window itself. */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier = hazeSource(backdrop.haze)

/**
 * Draws the backdrop behind this element: blurred by [radius] over an opaque [background], veiled by [tint], shaped by
 * the alpha [mask] (vertical gradient, white = fully frosted) when given. This element's own content stays sharp on top.
 */
@Composable
fun Modifier.backdropBlur(backdrop: Backdrop, radius: Dp, background: Color, tint: Brush?, mask: Brush? = null): Modifier {
    // The style block is replayed under snapshot observation; reading these State objects keeps one remembered style
    // (Haze compares styles by identity) that still follows new values without being rebuilt.
    val r = rememberUpdatedState(radius)
    val bg = rememberUpdatedState(background)
    val t = rememberUpdatedState(tint)
    val m = rememberUpdatedState(mask)
    val style = remember {
        HazeBlurStyle {
            blurRadius(r.value)
            backgroundColor(bg.value)
            noiseFactor(0f)
            colorEffects(t.value?.let { listOf(HazeColorEffect.tint(it)) } ?: emptyList())
            mask(m.value)
        }
    }
    val input = remember(backdrop) { HazeInput.Backdrop(backdrop.haze) }
    return hazeBlur(input = input, style = style)
}
