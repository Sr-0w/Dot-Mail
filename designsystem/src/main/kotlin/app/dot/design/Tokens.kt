package app.dot.design

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * Quiet design tokens. The palette is deliberately almost monochrome: hierarchy comes from
 * type weight, contrast and spacing, not colour. A single accent marks "new" (unread) and focus.
 */

@Immutable
data class DotColors(
    val isDark: Boolean,
    /** App background. */
    val background: Color,
    /** Grouped surfaces: sheets, drawer, inputs. */
    val surface: Color,
    /** Pressed/selected rows, raised chips. */
    val surfaceRaised: Color,
    /** Hairlines and outlines. */
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** Inverted primary action (white-on-black in dark, black-on-white in light). */
    val inverse: Color,
    val onInverse: Color,
    /** The one accent: unread markers, links, focus. */
    val accent: Color,
    val accentSoft: Color,
    /** Theme-adjusted electric-blue fill for primary actions with white labels. */
    val accentFill: Color,
    val star: Color,
    val danger: Color,
    val success: Color,
    val scrim: Color,
)

val DarkColors = DotColors(
    isDark = true,
    background = Color(0xFF0D0D0E),
    surface = Color(0xFF161618),
    surfaceRaised = Color(0xFF202023),
    border = Color(0xFF26262A),
    borderStrong = Color(0xFF38383D),
    textPrimary = Color(0xFFEDEDEF),
    textSecondary = Color(0xFFA0A0A7),
    // 5.1:1 on background, 4.75:1 on surface (WCAG AA); still clearly below textSecondary.
    textTertiary = Color(0xFF82828B),
    inverse = Color(0xFFEDEDEF),
    onInverse = Color(0xFF0D0D0E),
    accent = Color(0xFF70A7FF),
    accentSoft = Color(0x2970A7FF),
    accentFill = Color(0xFF245FE6),
    star = Color(0xFFE8B95A),
    danger = Color(0xFFF2555A),
    success = Color(0xFF4CC38A),
    scrim = Color(0x99000000),
)

/**
 * Pure-black theme for OLED panels: the app background is exactly #000000 (pixels off), and only the surfaces that must
 * read as separate (cards, inputs, sheets, raised chips, hairlines) are lifted a few steps so they stay distinguishable.
 * Text and accent keep the Dark theme's AA contrast, which only improves on true black.
 */
val OledColors = DarkColors.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF111113),
    surfaceRaised = Color(0xFF1B1B1E),
    border = Color(0xFF242428),
    borderStrong = Color(0xFF3A3A40),
    onInverse = Color(0xFF000000),
    scrim = Color(0xB3000000),
)

val LightColors = DotColors(
    isDark = false,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF6F6F7),
    surfaceRaised = Color(0xFFEDEDEF),
    border = Color(0xFFE8E8EB),
    borderStrong = Color(0xFFD4D4D9),
    textPrimary = Color(0xFF0D0D0E),
    textSecondary = Color(0xFF5E5E66),
    // 5.36:1 on background, 4.96:1 on surface (WCAG AA).
    textTertiary = Color(0xFF6A6A72),
    inverse = Color(0xFF0D0D0E),
    onInverse = Color(0xFFFFFFFF),
    accent = Color(0xFF0057D9),
    accentSoft = Color(0x1F0057D9),
    accentFill = Color(0xFF0057D9),
    star = Color(0xFFC98A12),
    danger = Color(0xFFD92D33),
    success = Color(0xFF1E9E63),
    scrim = Color(0x66000000),
)

/** One avatar hue: solid fill + initials colour, for the dark and the light theme. */
internal class AvatarTone(val darkFill: Color, val darkText: Color, val lightFill: Color, val lightText: Color)

/**
 * Twelve hues spread around the wheel, deliberately mid-saturation: deep tinted fills with pale initials
 * on dark, pale tinted fills with deep initials on light. Initials contrast is >= 5:1 in every cell
 * (computed with the WCAG formula), and no fill is bright enough to compete with content.
 */
internal val AvatarTones = listOf(
    AvatarTone(Color(0xFF5F322B), Color(0xFFEAADA4), Color(0xFFF8DCD8), Color(0xFF70261A)), // red
    AvatarTone(Color(0xFF5F432B), Color(0xFFEAC5A4), Color(0xFFF8E7D8), Color(0xFF70421A)), // orange
    AvatarTone(Color(0xFF5F512B), Color(0xFFEAD7A4), Color(0xFFF8F0D8), Color(0xFF70591A)), // amber
    AvatarTone(Color(0xFF555F2B), Color(0xFFDCEAA4), Color(0xFFF2F8D8), Color(0xFF5E701A)), // lime
    AvatarTone(Color(0xFF2B5F33), Color(0xFFA4EAB0), Color(0xFFD8F8DD), Color(0xFF1A7028)), // green
    AvatarTone(Color(0xFF2B5F4E), Color(0xFFA4EAD2), Color(0xFFD8F8ED), Color(0xFF1A7053)), // teal
    AvatarTone(Color(0xFF2B5B5F), Color(0xFFA4E4EA), Color(0xFFD8F5F8), Color(0xFF1A6870)), // cyan
    AvatarTone(Color(0xFF2B475F), Color(0xFFA4C9EA), Color(0xFFD8E9F8), Color(0xFF1A4870)), // blue
    AvatarTone(Color(0xFF2B325F), Color(0xFFA4ADEA), Color(0xFFD8DCF8), Color(0xFF1A2670)), // indigo
    AvatarTone(Color(0xFF3E2B5F), Color(0xFFBEA4EA), Color(0xFFE4D8F8), Color(0xFF391A70)), // violet
    AvatarTone(Color(0xFF582B5F), Color(0xFFE0A4EA), Color(0xFFF4D8F8), Color(0xFF641A70)), // magenta
    AvatarTone(Color(0xFF5F2B43), Color(0xFFEAA4C5), Color(0xFFF8D8E7), Color(0xFF701A42)), // rose
)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private val Trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Inter,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.em,
    lineHeightStyle = Trim,
)

@Immutable
data class DotTypography(
    /** Screen titles and the subject of an open conversation. */
    val display: TextStyle = style(26, 32, FontWeight.SemiBold, -0.022),
    val title: TextStyle = style(19, 25, FontWeight.SemiBold, -0.014),
    /** Row primary line (sender). */
    val headline: TextStyle = style(15, 21, FontWeight.SemiBold, -0.006),
    /** Long-form reading text. */
    val body: TextStyle = style(15, 23, FontWeight.Normal, -0.004),
    val bodyStrong: TextStyle = style(15, 23, FontWeight.Medium, -0.004),
    /** Row secondary lines, form text. */
    val callout: TextStyle = style(14, 20, FontWeight.Normal, -0.003),
    val label: TextStyle = style(13, 18, FontWeight.Medium),
    val caption: TextStyle = style(12, 16, FontWeight.Normal, 0.002),
    /** Small caps-like section headers ("ACCOUNTS"). */
    val overline: TextStyle = style(11, 14, FontWeight.Medium, 0.06),
)

object DotShapes {
    val small = RoundedCornerShape(8.dp)
    val medium = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(18.dp)
    val sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
}

object DotSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
    val xxxl = 40.dp
    /** Horizontal page gutter. */
    val gutter = 20.dp
    val hairline = 0.5.dp
}

object DotMotion {
    /** Standard: quick start, gentle settle. Most transitions. */
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Entering elements. */
    val decelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    val accelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    const val fast = 150
    const val medium = 250
    const val slow = 380

    /** Returns [snap] when [reducedMotion] is true, otherwise a [tween] with [durationMillis] and [easing]. */
    fun <T> spec(
        durationMillis: Int = medium,
        easing: Easing = standard,
        reducedMotion: Boolean = false,
    ): FiniteAnimationSpec<T> = if (reducedMotion) snap() else tween(durationMillis = durationMillis, easing = easing)

    fun <T> fastSpec(reducedMotion: Boolean = false, easing: Easing = standard): FiniteAnimationSpec<T> =
        spec(durationMillis = fast, easing = easing, reducedMotion = reducedMotion)

    fun <T> mediumSpec(reducedMotion: Boolean = false, easing: Easing = standard): FiniteAnimationSpec<T> =
        spec(durationMillis = medium, easing = easing, reducedMotion = reducedMotion)

    fun <T> slowSpec(reducedMotion: Boolean = false, easing: Easing = standard): FiniteAnimationSpec<T> =
        spec(durationMillis = slow, easing = easing, reducedMotion = reducedMotion)

    /** Composable convenience that reads [rememberReducedMotion] and returns [snap] when reduced motion is enabled. */
    @Composable
    fun <T> rememberSpec(
        durationMillis: Int = medium,
        easing: Easing = standard,
    ): FiniteAnimationSpec<T> {
        val reduced = rememberReducedMotion()
        return remember(reduced, durationMillis, easing) { spec(durationMillis, easing, reduced) }
    }
}

/**
 * True when the user has disabled system animations (`Settings.Global.ANIMATOR_DURATION_SCALE == 0f`).
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
