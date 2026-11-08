package app.dot.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing

/**
 * Quiet filter chip: hairline outline, inverse fill when on. The 48dp touch target is larger than the drawn pill.
 * Use as a switch (each chip toggles independently); for a single choice pass [radio] = true.
 */
@Composable
fun DotFilterChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, radio: Boolean = false) {
    val c = Dot.colors
    Box(
        modifier.defaultMinSize(minHeight = 48.dp).toggleable(value = selected, role = if (radio) Role.RadioButton else Role.Checkbox, onValueChange = { onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = Dot.type.label, color = if (selected) c.onInverse else c.textSecondary,
            modifier = Modifier.clip(DotShapes.small).background(if (selected) c.inverse else Color.Transparent)
                .border(1.dp, if (selected) c.inverse else c.border, DotShapes.small).padding(horizontal = DotSpacing.m, vertical = 7.dp),
        )
    }
}
