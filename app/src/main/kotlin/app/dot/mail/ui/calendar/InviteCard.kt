package app.dot.mail.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.DotTheme
import app.dot.design.ThemeMode
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotPrimaryButton
import androidx.compose.ui.res.stringResource
import app.dot.mail.R
import app.dot.mail.engine.calendar.Ics
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Quiet invitation card for an iCalendar invite in the thread view.
 *
 * - Date chip (day + month), title, time range (java.time; [use24h] mirrors
 *   `android.text.format.DateFormat.is24HourFormat(context)`), all-day and
 *   multi-day handling, location, organizer and the recurrence summary.
 * - The "Accept / Maybe / Decline" row is only shown when the invite is a
 *   REQUEST and [myEmail] is one of its attendees; the already-selected
 *   response is drawn with [DotPrimaryButton], the others with a compact
 *   [ButtonStyle.Secondary] [DotButton].
 * - A CANCEL method renders the "Cancelled" state: strikethrough title,
 *   danger caption, no actions.
 *
 * Only `app.dot.design` tokens/components are used.
 */
@Composable
fun InviteCard(
    invite: Ics.CalendarInvite,
    myEmail: String?,
    onRespond: (Ics.PartStat) -> Unit,
    onAddToCalendar: () -> Unit,
    modifier: Modifier = Modifier,
    responding: Boolean = false,
    myResponse: Ics.PartStat? = null,
    use24h: Boolean = false,
) {
    val c = Dot.colors
    // The range wording is translated; its two dates are formatted per call.
    val rangeTemplate = stringResource(R.string.calendar_all_day_range, "\u0001", "\u0002")
    val cancelled = invite.method == Ics.Method.CANCEL
    val showResponse = invite.method == Ics.Method.REQUEST &&
        myEmail != null &&
        invite.attendees.any { it.email.equals(myEmail, ignoreCase = true) }

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s)
            .clip(DotShapes.large)
            .border(DotSpacing.hairline * 2, c.border, DotShapes.large)
            .background(c.surface)
            .padding(DotSpacing.m),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            DateChip(invite.start.date)
            Spacer(Modifier.width(DotSpacing.m))
            Column(Modifier.weight(1f)) {
                Text(
                    invite.summary.ifBlank { stringResource(R.string.calendar_no_subject) },
                    style = Dot.type.headline,
                    color = if (cancelled) c.textSecondary else c.textPrimary,
                    fontWeight = if (cancelled) FontWeight.Medium else FontWeight.SemiBold,
                    textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (cancelled) {
                    Row(
                        Modifier.padding(top = DotSpacing.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.EventBusy, null, Modifier.size(14.dp), tint = c.danger)
                        Spacer(Modifier.width(DotSpacing.xs))
                        Text(stringResource(R.string.calendar_cancelled), style = Dot.type.label, color = c.danger)
                    }
                }
                Spacer(Modifier.height(DotSpacing.xs))
                Text(
                    timeLine(invite.start, invite.end, use24h, stringResource(R.string.calendar_all_day)) { a, b -> rangeTemplate.replace("\u0001", a).replace("\u0002", b) },
                    style = Dot.type.callout,
                    color = c.textSecondary,
                )
                if (invite.location.isNotBlank()) {
                    MetaLine(Icons.Outlined.LocationOn, invite.location)
                }
                MetaLine(
                    Icons.Outlined.Person,
                    invite.organizer.name?.let { "${it} · ${invite.organizer.email}" } ?: invite.organizer.email,
                )
                invite.rruleParts?.let { parts ->
                    val resources = androidx.compose.ui.platform.LocalContext.current.resources
                    MetaLine(Icons.Outlined.Repeat, remember(parts, resources.configuration) { RRuleText.format(parts, resources) })
                }
            }
        }
        if (!cancelled) {
            Spacer(Modifier.height(DotSpacing.m))
            DotButton(
                stringResource(R.string.calendar_add_to_calendar),
                onAddToCalendar,
                style = ButtonStyle.Secondary,
                compact = true,
                enabled = !responding,
                modifier = Modifier.align(Alignment.Start),
            )
        }
        if (showResponse) {
            Spacer(Modifier.height(DotSpacing.s))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DotSpacing.s)) {
                ResponseButton(stringResource(R.string.calendar_accept), Ics.PartStat.ACCEPTED, myResponse, responding, onRespond, Modifier.weight(1f))
                ResponseButton(stringResource(R.string.calendar_maybe), Ics.PartStat.TENTATIVE, myResponse, responding, onRespond, Modifier.weight(1f))
                ResponseButton(stringResource(R.string.calendar_decline), Ics.PartStat.DECLINED, myResponse, responding, onRespond, Modifier.weight(1f))
            }
        }
    }
}

/** Small calendar chip: month abbreviation over the day number. */
@Composable
private fun DateChip(date: LocalDate) {
    val c = Dot.colors
    Column(
        Modifier
            .widthIn(min = 44.dp)
            .clip(DotShapes.medium)
            .background(c.surfaceRaised)
            .padding(vertical = DotSpacing.s, horizontal = DotSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(date.format(DateTimeFormatter.ofPattern("MMM")), style = Dot.type.caption, color = c.textTertiary)
        Text(date.format(DateTimeFormatter.ofPattern("d")), style = Dot.type.title, color = c.textPrimary)
    }
}

/** Icon + one-line detail (location, organizer, recurrence). */
@Composable
private fun MetaLine(icon: ImageVector, text: String) {
    val c = Dot.colors
    Row(
        Modifier.padding(top = DotSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = c.textTertiary)
        Spacer(Modifier.width(DotSpacing.xs))
        Text(text, style = Dot.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One-line time range: "All day", "All day · Jun 10 – Jun 12" (multi-day,
 * RFC-exclusive end adjusted), "09:00 – 09:30", and "09:00 – Jun 11, 10:30"
 * when the end falls on a later day.
 */
private fun timeLine(start: Ics.EventTime, end: Ics.EventTime?, use24h: Boolean, allDayLabel: String, allDayRange: (String, String) -> String): String {
    val timeFmt = DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a")
    val dateFmt = runCatching { DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "MMMd")) }.getOrElse { DateTimeFormatter.ofPattern("MMM d") }
    return when {
        start.allDay -> {
            val last = end?.date?.minusDays(1) ?: start.date
            if (last > start.date) allDayRange(start.date.format(dateFmt), last.format(dateFmt))
            else allDayLabel
        }
        else -> {
            val s = start.time?.format(timeFmt).orEmpty()
            val e = end?.time?.let { t ->
                if (end.date != start.date) "${end.date.format(dateFmt)}, ${t.format(timeFmt)}"
                else t.format(timeFmt)
            }
            when {
                s.isEmpty() && e != null -> e
                e == null -> s
                else -> "$s – $e"
            }
        }
    }
}

@Composable
private fun ResponseButton(
    label: String,
    stat: Ics.PartStat,
    myResponse: Ics.PartStat?,
    responding: Boolean,
    onRespond: (Ics.PartStat) -> Unit,
    modifier: Modifier,
) {
    if (myResponse == stat) {
        DotPrimaryButton(label, { onRespond(stat) }, modifier = modifier, compact = true, enabled = !responding)
    } else {
        DotButton(
            label,
            { onRespond(stat) },
            modifier = modifier,
            style = ButtonStyle.Secondary,
            compact = true,
            enabled = !responding,
        )
    }
}

// ------------------------------------------------------------------ previews

private fun previewRequest(
    cancelled: Boolean = false,
    allDay: Boolean = false,
): Ics.CalendarInvite {
    val zone = ZoneId.of("Europe/Brussels")
    val start = Ics.EventTime(
        date = LocalDate.of(2026, 6, 10),
        time = if (allDay) null else LocalTime.of(14, 0),
        zoneId = zone,
        vtimeZone = null,
        floating = false,
    )
    return Ics.CalendarInvite(
        method = if (cancelled) Ics.Method.CANCEL else Ics.Method.REQUEST,
        uid = "preview-1",
        sequence = 0,
        organizer = Ics.Organizer("Alice Doe", "alice@example.com"),
        attendees = listOf(
            Ics.Attendee("Bob", "bob@example.com", Ics.PartStat.NEEDS_ACTION, "REQ-PARTICIPANT", true),
        ),
        summary = if (allDay) "Team offsite" else "Design review",
        description = "",
        location = "Room 4",
        url = null,
        start = start,
        end = Ics.EventTime(
            date = if (allDay) LocalDate.of(2026, 6, 12) else LocalDate.of(2026, 6, 10),
            time = if (allDay) null else LocalTime.of(15, 30),
            zoneId = zone,
            vtimeZone = null,
            floating = false,
        ),
        duration = null,
        rrule = if (allDay) null else "FREQ=WEEKLY;COUNT=4",
        rruleSummary = if (allDay) null else "weekly, 4 times",
        rruleParts = if (allDay) null else Ics.RRuleSummary(Ics.RRuleSummary.Unit.WEEK, 1, null, null, 4, null, null, false),
        status = "CONFIRMED",
    )
}

@Preview("Invite card light", showBackground = true)
@Composable
private fun InviteCardLightPreview() {
    DotTheme(ThemeMode.LIGHT) {
        Box(Modifier.fillMaxWidth().background(Dot.colors.background).padding(vertical = DotSpacing.l)) {
            InviteCard(
                invite = previewRequest(),
                myEmail = "bob@example.com",
                onRespond = {},
                onAddToCalendar = {},
                use24h = true,
            )
        }
    }
}

@Preview("Invite card dark", showBackground = true)
@Composable
private fun InviteCardDarkPreview() {
    DotTheme(ThemeMode.DARK) {
        Box(Modifier.fillMaxWidth().background(Dot.colors.background).padding(vertical = DotSpacing.l)) {
            InviteCard(
                invite = previewRequest(allDay = true),
                myEmail = "bob@example.com",
                onRespond = {},
                onAddToCalendar = {},
                myResponse = Ics.PartStat.TENTATIVE,
            )
        }
    }
}

@Preview("Invite card cancelled", showBackground = true)
@Composable
private fun InviteCardCancelledPreview() {
    DotTheme(ThemeMode.LIGHT) {
        Box(Modifier.fillMaxWidth().background(Dot.colors.background).padding(vertical = DotSpacing.l)) {
            InviteCard(
                invite = previewRequest(cancelled = true),
                myEmail = "bob@example.com",
                onRespond = {},
                onAddToCalendar = {},
            )
        }
    }
}
