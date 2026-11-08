package app.dot.design.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import app.dot.design.DotColors
import app.dot.design.DotTheme
import app.dot.design.DotMotion
import app.dot.design.ThemeMode
import kotlin.math.PI
import kotlin.math.cos

/**
 * The "Quiet" loading language: a thin breathing arc, three quiet dots and a hairline
 * progress bar. All motion is animation-driven (no timers), so it stays smooth at high
 * refresh rates, and everything stands still when the system has animations turned off
 * (Settings.Global.ANIMATOR_DURATION_SCALE == 0).
 */

/** True when the user has turned system animations off. */
@Composable
internal fun DotReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Sentinel default meaning "use the theme's secondary text colour". */
private fun DotColors.loaderColor(explicit: Color): Color =
    if (explicit.isSpecified) explicit else textSecondary

// ---------------------------------------------------------------------------
// DotSpinner
// ---------------------------------------------------------------------------

/**
 * Quiet spinner: a thin 2 dp rounded arc whose sweep breathes between 20° and 280°
 * while the whole ring rotates, with a soft tail that fades to transparent behind the
 * head. With reduced motion it renders as a static ring with a gentle alpha pulse.
 */
@Composable
fun DotSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = Color.Unspecified,
) {
    val arc = Dot.colors.loaderColor(color)
    if (DotReducedMotion()) {
        DotSpinnerStatic(modifier, size, arc)
    } else {
        DotSpinnerAnimated(modifier, size, arc)
    }
}

@Composable
private fun DotSpinnerAnimated(modifier: Modifier, size: Dp, color: Color) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Restart),
        label = "spinner rotation",
    )
    val sweep by transition.animateFloat(
        initialValue = 20f,
        targetValue = 280f,
        animationSpec = infiniteRepeatable(tween(850, easing = DotMotion.standard), RepeatMode.Reverse),
        label = "spinner sweep",
    )
    Canvas(modifier = modifier.size(size)) {
        drawSpinnerArc(color, rotation, sweep, 1f)
    }
}

@Composable
private fun DotSpinnerStatic(modifier: Modifier, size: Dp, color: Color) {
    val transition = rememberInfiniteTransition(label = "spinner pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = DotMotion.standard), RepeatMode.Reverse),
        label = "spinner pulse",
    )
    Canvas(modifier = modifier.size(size)) {
        drawSpinnerArc(color, 0f, 270f, pulse)
    }
}

private fun DrawScope.drawSpinnerArc(color: Color, rotation: Float, sweep: Float, alpha: Float) {
    val stroke = 2.dp.toPx()
    withTransform({ rotate(rotation, center) }) {
        // The gradient runs transparent -> color across the sweep, so the arc has a
        // soft tail and a bright head in the direction of rotation.
        drawArc(
            brush = Brush.sweepGradient(
                0f to color.copy(alpha = 0f),
                (sweep / 360f).coerceIn(0.01f, 1f) to color,
                1f to color.copy(alpha = 0f),
                center = center,
            ),
            startAngle = 0f,
            sweepAngle = sweep,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            alpha = alpha,
        )
    }
}

// ---------------------------------------------------------------------------
// DotDotsLoader
// ---------------------------------------------------------------------------

/**
 * Three dots pulsing in turn — for inline status lines like "Sending…".
 * With reduced motion the dots are static, staggered in opacity.
 */
@Composable
fun DotDotsLoader(
    modifier: Modifier = Modifier,
    dotSize: Dp = 6.dp,
    spacing: Dp = 4.dp,
    color: Color = Color.Unspecified,
) {
    val dot = Dot.colors.loaderColor(color)
    if (DotReducedMotion()) {
        DotDotsLoaderStatic(modifier, dotSize, spacing, dot)
    } else {
        DotDotsLoaderAnimated(modifier, dotSize, spacing, dot)
    }
}

@Composable
private fun DotDotsLoaderAnimated(modifier: Modifier, dotSize: Dp, spacing: Dp, color: Color) {
    val transition = rememberInfiniteTransition(label = "dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
        label = "dots phase",
    )
    val totalWidth = dotSize * 3 + spacing * 2
    Canvas(modifier = modifier.size(width = totalWidth, height = dotSize)) {
        val d = dotSize.toPx()
        val gap = spacing.toPx()
        for (i in 0..2) {
            // A smooth bump, peaking one third of the cycle after the previous dot's.
            val p = (phase + i / 3f) % 1f
            val bump = (1f - cos((2f * p - 1f) * PI.toFloat())) / 2f
            val alpha = 0.25f + 0.75f * bump
            val scale = 0.65f + 0.35f * bump
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = d * scale / 2f,
                center = Offset(i * (d + gap) + d / 2f, size.height / 2f),
            )
        }
    }
}

@Composable
private fun DotDotsLoaderStatic(modifier: Modifier, dotSize: Dp, spacing: Dp, color: Color) {
    val totalWidth = dotSize * 3 + spacing * 2
    Canvas(modifier = modifier.size(width = totalWidth, height = dotSize)) {
        val d = dotSize.toPx()
        val gap = spacing.toPx()
        val alphas = listOf(0.3f, 0.55f, 0.8f)
        for (i in 0..2) {
            drawCircle(
                color = color.copy(alpha = alphas[i]),
                radius = d / 2f,
                center = Offset(i * (d + gap) + d / 2f, size.height / 2f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// DotLinearProgress
// ---------------------------------------------------------------------------

/**
 * 2 dp hairline progress. Indeterminate ([progress] == null) slides a short rounded
 * highlight across with easing; determinate fills to [progress] with an animated
 * settle. Accessibility: standard progress-bar semantics (percent announced when
 * determinate). With reduced motion the indeterminate highlight sits centred and
 * pulses gently; determinate changes apply without animation.
 */
@Composable
fun DotLinearProgress(
    progress: Float? = null,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val dot = Dot.colors
    val bar = dot.loaderColor(color)
    val reduced = DotReducedMotion()
    val semantics = if (progress == null) Modifier.progressSemantics() else Modifier.progressSemantics(progress)
    Box(
        modifier = modifier
            .height(2.dp)
            .then(semantics),
        contentAlignment = Alignment.Center,
    ) {
        if (progress == null) {
            DotProgressIndeterminate(Modifier.fillMaxSize(), bar, dot.border, reduced)
        } else {
            DotProgressDeterminate(Modifier.fillMaxSize(), bar, dot.border, progress, reduced)
        }
    }
}

@Composable
private fun DotProgressIndeterminate(modifier: Modifier, bar: Color, track: Color, reduced: Boolean) {
    val transition = rememberInfiniteTransition(label = "linear progress")
    val slide by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = DotMotion.standard), RepeatMode.Restart),
        label = "linear slide",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = DotMotion.standard), RepeatMode.Reverse),
        label = "linear pulse",
    )
    Canvas(modifier) {
        drawRect(color = track)
        val h = size.height
        val seg = (size.width * 0.35f).coerceAtLeast(24.dp.toPx())
        val x = if (reduced) (size.width - seg) / 2f else -seg + slide * (size.width + 2f * seg)
        drawRoundRect(
            color = bar.copy(alpha = if (reduced) pulse else 1f),
            topLeft = Offset(x, 0f),
            size = Size(seg, h),
            cornerRadius = CornerRadius(h / 2f, h / 2f),
        )
    }
}

@Composable
private fun DotProgressDeterminate(modifier: Modifier, bar: Color, track: Color, target: Float, reduced: Boolean) {
    val fill by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = if (reduced) tween(1) else tween(DotMotion.medium, easing = DotMotion.standard),
        label = "linear fill",
    )
    Canvas(modifier) {
        drawRect(color = track)
        drawRect(color = bar, size = Size(size.width * fill, size.height))
    }
}

// ---------------------------------------------------------------------------
// dotShimmer
// ---------------------------------------------------------------------------

/**
 * Subtle skeleton sheen: a soft diagonal band of [app.dot.design.DotColors.surfaceRaised]
 * drifting over the content (which is drawn underneath). Deliberately quiet — no colour
 * wash, no rainbow. Pass `enabled = false` when the row already carries a spinner.
 * With reduced motion the band stays put and its opacity breathes gently.
 */
@Composable
fun Modifier.dotShimmer(enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val sheen = Dot.colors.surfaceRaised
    val reduced = DotReducedMotion()
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer phase",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = DotMotion.standard), RepeatMode.Reverse),
        label = "shimmer pulse",
    )
    return drawWithContent {
        drawContent()
        val band = size.width * 0.45f
        val travel = size.width + band * 2f
        val x = if (reduced) travel * 0.5f else phase * travel - band
        // Diagonal band, brightest in the middle, fading to both ends.
        drawRect(
            Brush.linearGradient(
                colors = listOf(
                    sheen.copy(alpha = 0f),
                    sheen.copy(alpha = if (reduced) pulse else 0.55f),
                    sheen.copy(alpha = 0f),
                ),
                start = Offset(x, size.height),
                end = Offset(x + band, 0f),
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview("Spinner light", showBackground = true)
@Composable
private fun DotSpinnerLightPreview() {
    DotTheme(ThemeMode.LIGHT) {
        Box(Modifier.size(72.dp).background(Dot.colors.background), contentAlignment = Alignment.Center) {
            DotSpinner(Modifier.size(26.dp))
        }
    }
}

@Preview("Spinner dark", showBackground = true)
@Composable
private fun DotSpinnerDarkPreview() {
    DotTheme(ThemeMode.DARK) {
        Box(Modifier.size(72.dp).background(Dot.colors.background), contentAlignment = Alignment.Center) {
            DotSpinner(Modifier.size(26.dp))
        }
    }
}

@Preview("Dots light", showBackground = true)
@Composable
private fun DotDotsLightPreview() {
    DotTheme(ThemeMode.LIGHT) {
        Box(Modifier.size(width = 220.dp, height = 56.dp).background(Dot.colors.background), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DotDotsLoader()
                Box(Modifier.width(8.dp))
                Text("Sending…", style = Dot.type.callout, color = Dot.colors.textPrimary)
            }
        }
    }
}

@Preview("Dots dark", showBackground = true)
@Composable
private fun DotDotsDarkPreview() {
    DotTheme(ThemeMode.DARK) {
        Box(Modifier.size(width = 220.dp, height = 56.dp).background(Dot.colors.background), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DotDotsLoader()
                Box(Modifier.width(8.dp))
                Text("Sending…", style = Dot.type.callout, color = Dot.colors.textPrimary)
            }
        }
    }
}

@Preview("Linear progress light", showBackground = true)
@Composable
private fun DotLinearLightPreview() {
    DotTheme(ThemeMode.LIGHT) {
        Box(Modifier.size(240.dp, 64.dp).background(Dot.colors.background), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center) {
                DotLinearProgress(progress = 0.42f, modifier = Modifier.fillMaxWidth())
                Box(Modifier.height(10.dp))
                DotLinearProgress(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Preview("Linear progress dark", showBackground = true)
@Composable
private fun DotLinearDarkPreview() {
    DotTheme(ThemeMode.DARK) {
        Box(Modifier.size(240.dp, 64.dp).background(Dot.colors.background), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center) {
                DotLinearProgress(progress = 0.42f, modifier = Modifier.fillMaxWidth())
                Box(Modifier.height(10.dp))
                DotLinearProgress(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Preview("Shimmer light", showBackground = true)
@Composable
private fun DotShimmerLightPreview() {
    DotTheme(ThemeMode.LIGHT) {
        Box(Modifier.size(240.dp, 76.dp).background(Dot.colors.background).padding(12.dp), contentAlignment = Alignment.TopStart) {
            SkeletonRow(Modifier.dotShimmer())
        }
    }
}

@Preview("Shimmer dark", showBackground = true)
@Composable
private fun DotShimmerDarkPreview() {
    DotTheme(ThemeMode.DARK) {
        Box(Modifier.size(240.dp, 76.dp).background(Dot.colors.background).padding(12.dp), contentAlignment = Alignment.TopStart) {
            SkeletonRow(Modifier.dotShimmer())
        }
    }
}

@Composable
private fun SkeletonRow(modifier: Modifier = Modifier) {
    Row(modifier = modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(Dot.colors.surfaceRaised, CircleShape))
        Box(Modifier.width(16.dp))
        Column {
            Box(Modifier.size(width = 110.dp, height = 10.dp).background(Dot.colors.surfaceRaised, RoundedCornerShape(5.dp)))
            Box(Modifier.height(5.dp))
            Box(Modifier.size(width = 72.dp, height = 8.dp).background(Dot.colors.surface, RoundedCornerShape(4.dp)))
        }
    }
}
