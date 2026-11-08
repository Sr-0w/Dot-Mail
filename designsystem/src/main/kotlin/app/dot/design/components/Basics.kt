package app.dot.design.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dot.design.AvatarTints
import app.dot.design.Dot
import app.dot.design.DotMotion
import app.dot.design.DotShapes
import app.dot.design.DotSpacing

/** 0.5dp separator, inset by default to align with row text. */
@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(
        modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(DotSpacing.hairline)
            .background(Dot.colors.border),
    )
}

enum class ButtonStyle { Primary, Secondary, Ghost, Danger }

/**
 * The one button. Primary is an inverted solid (no brand colour); Secondary a hairline outline;
 * Ghost is text only. Rounded rectangles, never pills.
 */
@Composable
fun DotButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    compact: Boolean = false,
) {
    val c = Dot.colors
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val (bg, fg, outline) = when (style) {
        ButtonStyle.Primary -> Triple(c.inverse, c.onInverse, Color.Transparent)
        ButtonStyle.Secondary -> Triple(Color.Transparent, c.textPrimary, c.borderStrong)
        ButtonStyle.Ghost -> Triple(Color.Transparent, c.textPrimary, Color.Transparent)
        ButtonStyle.Danger -> Triple(Color.Transparent, c.danger, c.border)
    }
    val bgAnimated by animateColorAsState(
        when {
            !enabled && style == ButtonStyle.Primary -> c.surfaceRaised
            pressed && style == ButtonStyle.Primary -> bg.copy(alpha = 0.86f)
            pressed -> c.surfaceRaised
            else -> bg
        },
        tween(DotMotion.fast), label = "btn",
    )
    val fgFinal = if (enabled) fg else c.textTertiary
    Row(
        modifier
            .heightIn(min = if (compact) 36.dp else 48.dp)
            .clip(DotShapes.medium)
            .background(bgAnimated)
            .border(DotSpacing.hairline * 2, if (enabled) outline else c.border, DotShapes.medium)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                role = Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = if (compact) 14.dp else 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), color = fgFinal, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(DotSpacing.s))
        } else if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = fgFinal)
            Spacer(Modifier.width(DotSpacing.s))
        }
        Text(text, style = Dot.type.label, color = fgFinal, maxLines = 1)
    }
}

/** 44dp touch target, 22dp glyph, soft circular press feedback. */
@Composable
fun DotIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Dot.colors.textPrimary,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = Dot.colors.textPrimary),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(22.dp), tint = if (enabled) tint else Dot.colors.textTertiary)
    }
}

/** Initials avatar with a muted, stable tint derived from [seed] (usually the email address). */
@Composable
fun Avatar(name: String, seed: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val dark = Dot.colors.isDark
    val tint = AvatarTints[(seed.lowercase().hashCode() and 0x7fffffff) % AvatarTints.size].let { if (dark) it.first else it.second }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = if (dark) 0.22f else 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials(name),
            style = if (size >= 40.dp) Dot.type.label else Dot.type.caption,
            color = tint,
            maxLines = 1,
        )
    }
}

fun initials(name: String): String {
    val cleaned = name.substringBefore('@').replace(Regex("[^\\p{L}\\p{N} ._-]"), " ").trim()
    val parts = cleaned.split(' ', '.', '_', '-').filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "·"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts.last().take(1)).uppercase()
    }
}

/** Small accent dot: unread / new. */
@Composable
fun UnreadDot(modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(Dot.colors.accent))
}

/** Uppercase tracking header for list groups. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = Dot.type.overline,
        color = Dot.colors.textTertiary,
        modifier = modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
    )
}

/** Calm empty / error state: an outlined glyph, a title, one sentence, optional action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = DotSpacing.xxxl, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).border(DotSpacing.hairline * 2, Dot.colors.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(24.dp), tint = Dot.colors.textSecondary) }
        Spacer(Modifier.height(DotSpacing.xl))
        Text(title, style = Dot.type.title, color = Dot.colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(DotSpacing.s))
        Text(message, style = Dot.type.callout, color = Dot.colors.textSecondary, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(DotSpacing.xl))
            action()
        }
    }
}

/** Settings-style row: optional leading icon, title + optional subtitle, trailing slot. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    titleColor: Color = Dot.colors.textPrimary,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = DotSpacing.gutter, vertical = 14.dp),
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = Dot.colors.textSecondary)
            Spacer(Modifier.width(DotSpacing.l))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Dot.type.bodyStrong, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = Dot.type.caption, color = Dot.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(DotSpacing.m))
            trailing()
        }
    }
}
