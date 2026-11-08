package app.dot.design.components

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import app.dot.design.DotSpacing

/**
 * One soft frosted capsule holding several actions (reader: Archive | Delete | Mark unread | More, or
 * Reply | Reply all | Forward). Same surface, border and low elevation as [DotFrostedPill]; the actions share it
 * instead of each floating in its own bubble. Content is [DotCapsuleButton]s separated by [DotCapsuleDivider]s.
 * Height 48dp, so every action is a 48dp touch target.
 */
@Composable
fun DotFloatingCapsule(
    modifier: Modifier = Modifier,
    /** Content scrolling underneath, for a real backdrop blur ([rememberGlassBackdrop]); null = opaque frosted. */
    backdrop: app.dot.design.components.Backdrop? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .height(48.dp)
            .shadow(3.dp, CircleShape, ambientColor = Color(0x26000000), spotColor = Color(0x33000000))
            .clip(CircleShape)
            .glassSurface(backdrop, pressed = false)
            .border(DotSpacing.hairline * 2, frostedBorderBrush(), CircleShape)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A 48dp icon action inside a [DotFloatingCapsule] (or alone in one, for a Back bubble). */
@Composable
fun DotCapsuleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Dot.colors.textPrimary,
) {
    Box(
        modifier
            .size(48.dp)
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
        Icon(icon, contentDescription, Modifier.size(21.dp), tint = if (enabled) tint else Dot.colors.textTertiary)
    }
}

/** Hairline between two actions of a capsule. */
@Composable
fun DotCapsuleDivider() {
    Box(Modifier.width(DotSpacing.hairline * 2).height(20.dp).background(Dot.colors.border))
}
