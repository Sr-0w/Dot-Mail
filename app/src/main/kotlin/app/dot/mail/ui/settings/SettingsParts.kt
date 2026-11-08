package app.dot.mail.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import app.dot.mail.R
import app.dot.design.Dot
import app.dot.design.DotSpacing
import app.dot.design.components.DotMenu
import app.dot.design.components.DotMenuItem
import app.dot.design.components.DotSwitch
import app.dot.design.components.ListRow
import app.dot.mail.ui.account.Segmented

/** A switch row; the whole row is one toggle target ("Title, switch, on/off"). */
@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    ListRow(
        title = title, subtitle = subtitle, icon = icon,
        titleColor = if (enabled) Dot.colors.textPrimary else Dot.colors.textTertiary,
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        trailing = { DotSwitch(checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** A row whose subtitle is the current value; tapping opens a menu of options. */
@Composable
internal fun <T> ChoiceRow(
    title: String,
    icon: ImageVector?,
    value: T,
    options: List<Pair<T, String>>,
    enabled: Boolean = true,
    onChange: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListRow(
            title = title, subtitle = options.firstOrNull { it.first == value }?.second, icon = icon,
            titleColor = if (enabled) Dot.colors.textPrimary else Dot.colors.textTertiary,
            onClick = if (enabled) ({ open = true }) else null,
        )
        DotMenu(open, { open = false }) {
            options.forEach { (v, label) -> DotMenuItem(label, null) { open = false; onChange(v) } }
        }
    }
}

/** Title above a segmented control (2–3 short options). */
@Composable
internal fun <T> SegmentedSetting(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s)) {
        Text(title, style = Dot.type.bodyStrong, color = Dot.colors.textPrimary, modifier = Modifier.padding(bottom = DotSpacing.s))
        Segmented(options, selected, onSelect)
    }
}

/** Percent slider with discrete steps and a live preview line at the chosen size. */
@Composable
internal fun PercentSlider(title: String, value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    val c = Dot.colors
    val steps = (range.last - range.first) / step - 1
    val percentState = stringResource(R.string.settings_percent_state, value)
    Column(Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s)) {
        Text(stringResource(R.string.settings_reading_size_value, title, value), style = Dot.type.bodyStrong, color = c.textPrimary)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange((it / step).toInt() * step) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
            modifier = Modifier.semantics { stateDescription = percentState },
            colors = SliderDefaults.colors(
                thumbColor = c.inverse, activeTrackColor = c.inverse, inactiveTrackColor = c.surfaceRaised,
                activeTickColor = c.onInverse, inactiveTickColor = c.borderStrong,
            ),
        )
        val base = Dot.type.body
        Text(
            stringResource(R.string.settings_reading_size_preview),
            style = base.copy(fontSize = base.fontSize * (value / 100f), lineHeight = base.lineHeight * (value / 100f)),
            color = c.textSecondary,
        )
    }
}
