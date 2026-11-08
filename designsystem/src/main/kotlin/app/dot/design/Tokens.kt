package app.dot.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
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
    textTertiary = Color(0xFF6C6C73),
    inverse = Color(0xFFEDEDEF),
    onInverse = Color(0xFF0D0D0E),
    accent = Color(0xFF7C9CFF),
    accentSoft = Color(0x297C9CFF),
    star = Color(0xFFE8B95A),
    danger = Color(0xFFF2555A),
    success = Color(0xFF4CC38A),
    scrim = Color(0x99000000),
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
    textTertiary = Color(0xFF8E8E96),
    inverse = Color(0xFF0D0D0E),
    onInverse = Color(0xFFFFFFFF),
    accent = Color(0xFF3D63E0),
    accentSoft = Color(0x1F3D63E0),
    star = Color(0xFFC98A12),
    danger = Color(0xFFD92D33),
    success = Color(0xFF1E9E63),
    scrim = Color(0x66000000),
)

/**
 * Muted tints for avatars/account markers. Desaturated so a list of senders never looks like confetti.
 * Each pair is (dark-theme tint, light-theme tint).
 */
internal val AvatarTints = listOf(
    Color(0xFF8E9AAF) to Color(0xFF5F6B82),
    Color(0xFFA3927E) to Color(0xFF7D6A55),
    Color(0xFF8AA396) to Color(0xFF55776A),
    Color(0xFFA08AA6) to Color(0xFF75607C),
    Color(0xFF9AA27F) to Color(0xFF6B7351),
    Color(0xFF7FA0A8) to Color(0xFF50737B),
    Color(0xFFA88A8A) to Color(0xFF7E5B5B),
    Color(0xFF8C8FB0) to Color(0xFF5E6187),
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
}
