package app.dot.design.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import app.dot.design.DotMotion
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.rememberReducedMotion
import kotlinx.coroutines.launch

/**
 * Subtle Quiet UI enter transition for menus, dialogs, and sheets:
 * scales from `0.96 -> 1.0` while fading in (`0 -> 1`). Snaps immediately when reduced motion is on.
 */
@Composable
fun Modifier.dotSurfaceEnter(): Modifier {
    val reducedMotion = rememberReducedMotion()
    if (reducedMotion) return this
    val scale = remember { Animatable(0.96f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, DotMotion.spec(DotMotion.fast, DotMotion.decelerate)) }
        launch { alpha.animateTo(1f, DotMotion.spec(DotMotion.fast, DotMotion.decelerate)) }
    }
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        this.alpha = alpha.value
    }
}

/** Quiet dropdown: flat surface, hairline border, small radius. Items are 48dp tall (M3 default). */
@Composable
fun DotMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.dotSurfaceEnter(),
        containerColor = Dot.colors.surface,
        shape = DotShapes.medium,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(DotSpacing.hairline * 2, Dot.colors.border),
    ) { content() }
}

/** Menu entry. A non-default [color] (e.g. danger) tints the icon too. */
@Composable
fun DotMenuItem(text: String, icon: ImageVector?, modifier: Modifier = Modifier, color: Color = Dot.colors.textPrimary, onClick: () -> Unit) {
    val iconTint = if (color == Dot.colors.textPrimary) Dot.colors.textSecondary else color
    DropdownMenuItem(
        text = { Text(text, style = Dot.type.callout, color = color) },
        modifier = modifier,
        leadingIcon = icon?.let { { Icon(it, null, Modifier.size(20.dp), tint = iconTint) } },
        onClick = onClick,
    )
}
