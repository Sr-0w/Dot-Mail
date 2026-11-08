package app.dot.mail.ui.snooze

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.ListRow
import app.dot.mail.R
import app.dot.mail.engine.time.describe
import app.dot.mail.engine.time.presets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

/**
 * "Snooze until…": a few sensible presets (from the engine's SnoozePresets) and "Pick date & time". [onPick] gets the
 * wake-up time in epoch milliseconds. Pickers are Material 3, coloured with the Dot tokens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnoozeSheet(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val c = Dot.colors
    val context = LocalContext.current
    val use24h = remember { DateFormat.is24HourFormat(context) }
    val now = remember { ZonedDateTime.now() }
    val options = remember { presets(now) }
    var picking by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface, shape = DotShapes.sheet, dragHandle = null) {
        Text(stringResource(R.string.snooze_title), style = Dot.type.title, color = c.textPrimary, modifier = Modifier.padding(DotSpacing.gutter))
        options.forEach { o ->
            ListRow(
                title = presetLabel(o.id, o.label), subtitle = describeWake(context, o.at.toInstant().toEpochMilli(), now), icon = Icons.Outlined.Schedule,
                onClick = { onPick(o.at.toInstant().toEpochMilli()) },
            )
        }
        ListRow(stringResource(R.string.snooze_pick_date_time), icon = Icons.Outlined.CalendarMonth, onClick = { picking = true })
        Spacer(Modifier.navigationBarsPadding().height(DotSpacing.l))
    }
    if (picking) DateTimeDialog(use24h, onDismiss = { picking = false }, onPick = onPick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeDialog(use24h: Boolean, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val c = Dot.colors
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    var step by remember { mutableStateOf(0) } // 0 = date, 1 = time
    var error by remember { mutableStateOf<String?>(null) }
    val futureError = stringResource(R.string.snooze_error_future)
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            // Past days make no sense; the picker's dates are UTC midnights.
            override fun isSelectableDate(utcTimeMillis: Long) = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() >= today
        },
    )
    val timeState = rememberTimePickerState(initialHour = 8, initialMinute = 0, is24Hour = use24h)
    val pickerColors = DatePickerDefaults.colors(
        containerColor = c.surface, titleContentColor = c.textSecondary, headlineContentColor = c.textPrimary,
        weekdayContentColor = c.textTertiary, subheadContentColor = c.textSecondary, navigationContentColor = c.textPrimary,
        yearContentColor = c.textPrimary, currentYearContentColor = c.accent,
        selectedYearContentColor = c.onInverse, selectedYearContainerColor = c.inverse,
        dayContentColor = c.textPrimary, selectedDayContentColor = c.onInverse, selectedDayContainerColor = c.inverse,
        todayContentColor = c.accent, todayDateBorderColor = c.accent, disabledDayContentColor = c.textTertiary,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = DotShapes.large,
        text = {
            Column {
                if (step == 0) {
                    DatePicker(state = dateState, colors = pickerColors, title = null, showModeToggle = false)
                } else {
                    TimePicker(
                        state = timeState,
                        colors = TimePickerDefaults.colors(
                            clockDialColor = c.surfaceRaised, clockDialSelectedContentColor = c.onInverse, clockDialUnselectedContentColor = c.textPrimary,
                            selectorColor = c.inverse, containerColor = c.surface,
                            periodSelectorBorderColor = c.border, periodSelectorSelectedContainerColor = c.surfaceRaised, periodSelectorUnselectedContainerColor = c.surface,
                            periodSelectorSelectedContentColor = c.textPrimary, periodSelectorUnselectedContentColor = c.textSecondary,
                            timeSelectorSelectedContainerColor = c.surfaceRaised, timeSelectorUnselectedContainerColor = c.surface,
                            timeSelectorSelectedContentColor = c.textPrimary, timeSelectorUnselectedContentColor = c.textSecondary,
                        ),
                    )
                }
                error?.let { Text(it, style = Dot.type.caption, color = c.danger, modifier = Modifier.padding(top = DotSpacing.s)) }
            }
        },
        confirmButton = {
            DotButton(stringResource(if (step == 0) R.string.snooze_next else R.string.snooze_confirm), compact = true, onClick = {
                if (step == 0) {
                    if (dateState.selectedDateMillis != null) step = 1
                } else {
                    val day = Instant.ofEpochMilli(dateState.selectedDateMillis ?: return@DotButton).atZone(ZoneOffset.UTC).toLocalDate()
                    val at = LocalDateTime.of(day, LocalTime.of(timeState.hour, timeState.minute)).atZone(zone)
                    if (at.toInstant().toEpochMilli() <= System.currentTimeMillis()) error = futureError else onPick(at.toInstant().toEpochMilli())
                }
            })
        },
        dismissButton = {
            DotButton(stringResource(if (step == 0) R.string.snooze_cancel else R.string.snooze_back), { if (step == 0) onDismiss() else { step = 0; error = null } }, style = ButtonStyle.Ghost, compact = true)
        },
    )
}

/** The engine's English preset label, replaced by the translated one for the known presets. */
@Composable
private fun presetLabel(id: String, fallback: String): String = when (id) {
    "later_today" -> stringResource(R.string.snooze_later_today)
    "this_evening" -> stringResource(R.string.snooze_this_evening_label)
    "tomorrow" -> stringResource(R.string.snooze_tomorrow)
    "this_weekend" -> stringResource(R.string.snooze_this_weekend)
    "next_week" -> stringResource(R.string.snooze_next_week)
    else -> fallback
}

/** "Today 14:30", "Tomorrow 08:00", "Sat 08:00", "12 Dec 08:00" in the user's language and clock style. */
fun describeWake(context: android.content.Context, epochMillis: Long, nowMillis: ZonedDateTime = ZonedDateTime.now()): String = describe(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()), nowMillis, Locale.getDefault(), DateFormat.is24HourFormat(context),
    todayLabel = context.getString(R.string.snooze_today), tomorrowLabel = context.getString(R.string.snooze_tomorrow),
)
