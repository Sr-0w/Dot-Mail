package app.dot.mail.ui.schedule

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import app.dot.design.components.ListRow
import app.dot.mail.R
import app.dot.mail.data.SendSchedule
import app.dot.mail.ui.snooze.DateTimeDialog
import app.dot.mail.ui.snooze.describeWake
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Times for "send at…": the usual presets plus a date-and-time picker, each shown with its exact local time and timezone.
 * [onPick] gets epoch milliseconds. Used by the composer's send-later action and by the outbox's Reschedule.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSheet(title: String, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val c = Dot.colors
    val context = LocalContext.current
    val use24h = remember { DateFormat.is24HourFormat(context) }
    val now = remember { ZonedDateTime.now() }
    var picking by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface, shape = DotShapes.sheet, dragHandle = null) {
        Text(title, style = Dot.type.title, color = c.textPrimary, modifier = Modifier.padding(DotSpacing.gutter))
        SendSchedule.presets(now).forEach { p ->
            ListRow(title = presetLabel(p.id), subtitle = scheduleLabel(context, p.at), icon = Icons.Outlined.Schedule, onClick = { onPick(p.at) })
        }
        ListRow(stringResource(R.string.schedule_pick_date_time), icon = Icons.Outlined.CalendarMonth, onClick = { picking = true })
        Text(
            stringResource(R.string.schedule_note), style = Dot.type.caption, color = c.textTertiary,
            modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
        )
        Spacer(Modifier.navigationBarsPadding().height(DotSpacing.l))
    }
    if (picking) DateTimeDialog(use24h, onDismiss = { picking = false }, onPick = onPick)
}

@Composable
private fun presetLabel(id: String): String = stringResource(
    when (id) {
        SendSchedule.EVENING -> R.string.compose_schedule_this_evening
        SendSchedule.TOMORROW -> R.string.compose_schedule_tomorrow_morning
        else -> R.string.compose_schedule_monday_morning
    },
)

/** "Tomorrow 08:00 · CET": the exact local time with the timezone it is in, in the user's language and clock style. */
fun scheduleLabel(context: android.content.Context, epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    describeWake(context, epochMillis) + " · " + zone.getDisplayName(TextStyle.SHORT, Locale.getDefault())
