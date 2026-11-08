package app.dot.design.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import app.dot.design.DotMotion
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.rememberReducedMotion

/**
 * Minimal top bar: at least 56dp, background-coloured, a hairline appears only once content scrolls
 * under it ([elevated]). No tinted surfaces, no shadow.
 */
@Composable
fun DotTopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    elevated: Boolean = false,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null,
) {
    val reducedMotion = rememberReducedMotion()
    val lineColor by animateColorAsState(
        if (elevated) Dot.colors.border else Color.Transparent,
        animationSpec = DotMotion.fastSpec(reducedMotion),
        label = "bar-line",
    )
    Column(modifier.background(Dot.colors.background).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            // Min height, not fixed: title + subtitle must not clip at large font scales.
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = DotSpacing.xs, vertical = DotSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigation != null) navigation() else Spacer(Modifier.width(DotSpacing.l))
            Box(Modifier.weight(1f).padding(horizontal = DotSpacing.xs)) {
                if (titleContent != null) {
                    titleContent()
                } else if (title != null) {
                    Column {
                        Text(title, style = Dot.type.title, color = Dot.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) {
                            Text(subtitle, style = Dot.type.caption, color = Dot.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            actions()
        }
        Box(Modifier.fillMaxWidth().height(DotSpacing.hairline).background(lineColor))
    }
}

@Composable
fun BackButton(onClick: () -> Unit) {
    DotIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onClick)
}

/** Inverted snackbar: one line of text and an optional single action ("Undo"). */
@Composable
fun DotSnackbar(data: SnackbarData) {
    val c = Dot.colors
    Row(
        Modifier
            .padding(horizontal = DotSpacing.l, vertical = DotSpacing.s)
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(DotShapes.medium)
            .background(c.inverse)
            .padding(start = DotSpacing.l, end = DotSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            data.visuals.message,
            style = Dot.type.callout,
            color = c.onInverse,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
        )
        data.visuals.actionLabel?.let { label ->
            Box(
                Modifier
                    .clip(DotShapes.small)
                    .clickable(role = Role.Button) { data.performAction() }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = DotSpacing.m),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Dot.type.label, color = c.onInverse)
            }
        }
    }
}
