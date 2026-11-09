package app.dot.design.components

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dot.design.AvatarTones
import app.dot.design.Dot
import app.dot.design.DotMotion
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.rememberReducedMotion

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

/** Solid fill of primary actions: Accent Blue with >= 4.7:1 contrast against a white label in both themes. */
@Composable
internal fun primaryFill(): Color = Dot.colors.accentFill

/**
 * The one button. Primary is a solid Accent Blue fill (priority, never glass); Secondary a hairline outline;
 * Ghost is text only; Danger a red outline. Rounded rectangles, never pills. Floating actions use [DotFrostedPill].
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
    val reduceMotion = rememberReducedMotion()
    val (bg, fg, outline) = when (style) {
        ButtonStyle.Primary -> Triple(primaryFill(), Color.White, Color.Transparent)
        ButtonStyle.Secondary -> Triple(Color.Transparent, c.textPrimary, c.borderStrong)
        ButtonStyle.Ghost -> Triple(Color.Transparent, c.textPrimary, Color.Transparent)
        ButtonStyle.Danger -> Triple(Color.Transparent, c.danger, c.border)
    }
    val bgAnimated by animateColorAsState(
        when {
            !enabled && style == ButtonStyle.Primary -> bg.copy(alpha = 0.35f)
            pressed && style == ButtonStyle.Primary -> bg.copy(alpha = 0.86f)
            pressed -> c.surfaceRaised
            else -> bg
        },
        DotMotion.fastSpec(reduceMotion), label = "btn",
    )
    val fgFinal = if (enabled) fg else if (style == ButtonStyle.Primary) Color.White.copy(alpha = 0.7f) else c.textTertiary
    Row(
        modifier
            // Clickable outermost so the 48dp touch target of compact buttons is fully tappable.
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                role = Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .minimumInteractiveComponentSize()
            .heightIn(min = if (compact) 36.dp else 48.dp)
            .clip(DotShapes.pill)
            .background(bgAnimated)
            .border(DotSpacing.hairline * 2, if (enabled || outline == Color.Transparent) outline else c.border, DotShapes.pill)
            .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            DotSpinner(size = 16.dp, color = fgFinal)
            Spacer(Modifier.width(DotSpacing.s))
        } else if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = fgFinal)
            Spacer(Modifier.width(DotSpacing.s))
        }
        Text(text, style = Dot.type.label, color = fgFinal, maxLines = 1)
    }
}

/**
 * Primary / confirmatory action button (Send, Save, Confirm): solid Accent Blue fill with
 * high-contrast foreground, rounded geometry ([DotShapes.pill]), and >= 48dp touch target.
 */
@Composable
fun DotPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    compact: Boolean = false,
    contentDescription: String? = null,
) {
    val c = Dot.colors
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = rememberReducedMotion()
    // Solid Accent Blue fill with >= 4.7:1 WCAG AA contrast against white foreground in both themes.
    val accentFill = c.accentFill
    val bgAnimated by animateColorAsState(
        when {
            !enabled -> accentFill.copy(alpha = 0.35f)
            pressed -> accentFill.copy(alpha = 0.86f)
            else -> accentFill
        },
        DotMotion.fastSpec(reduceMotion), label = "primary-btn",
    )
    val fgFinal = if (enabled) Color.White else Color.White.copy(alpha = 0.7f)
    Row(
        modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                role = Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .minimumInteractiveComponentSize()
            .heightIn(min = if (compact) 36.dp else 48.dp)
            .clip(DotShapes.pill)
            .background(bgAnimated)
            .padding(horizontal = if (compact) 14.dp else 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            DotSpinner(size = 16.dp, color = fgFinal)
            Spacer(Modifier.width(DotSpacing.s))
        } else if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = fgFinal)
            Spacer(Modifier.width(DotSpacing.s))
        }
        Text(text, style = Dot.type.label, color = fgFinal, maxLines = 1)
    }
}

/**
 * Icon-only variant of [DotPrimaryButton] (e.g., top-bar Send action): solid Accent Blue fill,
 * rounded geometry ([DotShapes.pill]), and >= 48dp interactive touch target.
 */
@Composable
fun DotPrimaryButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    compact: Boolean = true,
) {
    val c = Dot.colors
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = rememberReducedMotion()
    val accentFill = c.accentFill
    val bgAnimated by animateColorAsState(
        when {
            !enabled -> accentFill.copy(alpha = 0.35f)
            pressed -> accentFill.copy(alpha = 0.86f)
            else -> accentFill
        },
        DotMotion.fastSpec(reduceMotion), label = "primary-icon-btn",
    )
    val fgFinal = if (enabled) Color.White else Color.White.copy(alpha = 0.7f)
    val visualSize = if (compact) 40.dp else 48.dp
    Box(
        modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                role = Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .minimumInteractiveComponentSize()
            .size(visualSize)
            .clip(DotShapes.pill)
            .background(bgAnimated),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            DotSpinner(size = 16.dp, color = fgFinal)
        } else {
            Icon(icon, contentDescription, Modifier.size(19.dp), tint = fgFinal)
        }
    }
}

/**
 * Soft Frosted Pill for floating actions (Compose / New email):
 * compact fully-rounded translucent graphite surface, subtle top highlight, delicate low-contrast
 * hairline border, minimal elevation, no glow, light + dark variants, and >= 48dp touch target.
 */
@Composable
fun DotFrostedPill(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    expanded: Boolean = true,
    enabled: Boolean = true,
    contentDescription: String? = label,
    /** Content scrolling underneath, for a real backdrop blur ([rememberGlassBackdrop]); null = opaque frosted. */
    backdrop: app.dot.design.components.Backdrop? = null,
) {
    val c = Dot.colors
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = rememberReducedMotion()

    val borderBrush = frostedBorderBrush()

    val fg = if (!enabled) c.textTertiary else c.textPrimary
    val showLabel = expanded && !label.isNullOrEmpty()
    val enterTransition = if (reduceMotion) {
        EnterTransition.None
    } else {
        expandHorizontally(tween(200, easing = FastOutSlowInEasing)) + fadeIn(tween(200, easing = FastOutSlowInEasing))
    }
    val exitTransition = if (reduceMotion) {
        ExitTransition.None
    } else {
        shrinkHorizontally(tween(200, easing = FastOutSlowInEasing)) + fadeOut(tween(150, easing = FastOutSlowInEasing))
    }

    Row(
        modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .shadow(
                elevation = 3.dp,
                shape = DotShapes.circle,
                ambientColor = Color(0x26000000),
                spotColor = Color(0x33000000),
            )
            .clip(DotShapes.circle)
            .glassSurface(backdrop, pressed)
            .border(DotSpacing.hairline * 2, borderBrush, DotShapes.circle)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true, color = c.textPrimary),
                enabled = enabled,
                role = Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            }
            // Icon only (collapsed): no side padding, so the 48dp minimum makes it a true circle; with its label: a pill.
            .padding(horizontal = if (showLabel) 16.dp else 0.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(19.dp), tint = fg)
        if (!label.isNullOrEmpty()) {
            AnimatedVisibility(
                visible = expanded,
                enter = enterTransition,
                exit = exitTransition,
            ) {
                Text(
                    text = label,
                    style = Dot.type.label,
                    color = fg,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp, end = 2.dp),
                )
            }
        }
    }
}

/** 48dp touch target, 22dp glyph, soft circular press feedback. */
@Composable
fun DotIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Dot.colors.textPrimary,
    enabled: Boolean = true,
) {
    val header=LocalHeaderGlass.current
    if(header!=null && contentDescription!=null) {
        DotFloatingCapsule(modifier=modifier,backdrop=header.backdrop) {
            DotCapsuleButton(icon,contentDescription,onClick,enabled=enabled,tint=tint)
        }
        return
    }
    Box(
        modifier
            .size(48.dp)
            .clip(DotShapes.circle)
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

/** When false, avatars use one neutral grey instead of a per-sender hue (Settings → Appearance). */
val LocalAvatarColors = androidx.compose.runtime.compositionLocalOf { true }

/** Stable hue index for an address, shared by every avatar surface. */
fun avatarHue(seed: String): Int = (seed.trim().lowercase().hashCode() and 0x7fffffff) % AvatarTones.size

/** Initials avatar with a stable tinted hue derived from [seed] (usually the email address). */
@Composable
fun Avatar(name: String, seed: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val c = Dot.colors
    val tone = AvatarTones[avatarHue(seed)]
    val colorful = LocalAvatarColors.current
    val fill = if (!colorful) c.surfaceRaised else if (c.isDark) tone.darkFill else tone.lightFill
    val text = if (!colorful) c.textSecondary else if (c.isDark) tone.darkText else tone.lightText
    Box(
        modifier.size(size).clip(DotShapes.circle).background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials(name),
            style = if (size >= 40.dp) Dot.type.label else Dot.type.caption,
            color = text,
            maxLines = 1,
        )
    }
}

fun initials(name: String): String {
    // "Alice Smith <a@b.co>" → use the display part; a bare address → its local part.
    val display = name.substringBefore('<').trim().ifEmpty { name.trim().trim('<', '>') }
    val cleaned = (if ('@' in display) display.substringBefore('@') else display).replace(Regex("[^\\p{L}\\p{N} ._-]"), " ").trim()
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
    Box(modifier.size(size).clip(DotShapes.circle).background(Dot.colors.accent))
}

/** Uppercase tracking header for list groups. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = Dot.type.overline,
        color = Dot.colors.textTertiary,
        modifier = modifier.semantics { heading() }.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
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
            Modifier.size(56.dp).clip(DotShapes.circle).border(DotSpacing.hairline * 2, Dot.colors.border, DotShapes.circle),
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
    subtitleColor: Color = Dot.colors.textSecondary,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = DotSpacing.gutter, vertical = 14.dp),
    trailing: (@Composable () -> Unit)? = null,
    subtitleMaxLines: Int = 2,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
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
                Text(subtitle, style = Dot.type.caption, color = subtitleColor, maxLines = subtitleMaxLines, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(DotSpacing.m))
            trailing()
        }
    }
}

/** Translucent graphite (dark) / pearl (light) gradient shared by every floating surface: compose pill, snackbars. */
@Composable
internal fun frostedSurfaceBrush(pressed: Boolean): Brush = if (Dot.colors.isDark) {
    val top = if (pressed) Color(0xEB323232) else Color(0xE02A2A2A)
    val bottom = if (pressed) Color(0xEB242424) else Color(0xE01D1D1D)
    Brush.verticalGradient(listOf(top, bottom))
} else {
    val top = if (pressed) Color(0xEDEBEBEB) else Color(0xEBF6F6F6)
    val bottom = if (pressed) Color(0xEDE0E0E0) else Color(0xEBEAEAEA)
    Brush.verticalGradient(listOf(top, bottom))
}

@Composable
internal fun frostedBorderBrush(): Brush = if (Dot.colors.isDark) {
    Brush.verticalGradient(listOf(Color(0xFF444444), Color(0xFF2C2C2C)))
} else {
    Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFD4D4D4)))
}
