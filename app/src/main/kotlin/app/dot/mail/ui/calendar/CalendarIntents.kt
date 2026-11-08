package app.dot.mail.ui.calendar

import android.content.Intent
import android.provider.CalendarContract
import app.dot.mail.engine.calendar.Ics
import java.time.ZoneOffset

// The standard CalendarContract.Events extra keys. This SDK's CalendarContract
// strips the String constants (only the content URIs survive), so the stable
// AOSP values are used as literals — they are the wire contract the system
// calendar reads.
private const val EXTRA_TITLE = "title"
private const val EXTRA_EVENT_LOCATION = "eventLocation"
private const val EXTRA_DESCRIPTION = "description"
private const val EXTRA_BEGIN_TIME = "beginTime"
private const val EXTRA_END_TIME = "endTime"
private const val EXTRA_ALL_DAY = "allDay"

private const val MAX_DESCRIPTION_CHARS = 2000

/**
 * All of the calendar event extras [insertEventIntent] sets, as plain data.
 * Deliberately separated from the Intent so the mapping is testable on the
 * JVM (`Intent` and the content URI are framework objects; every key used
 * here is a plain string and the values are computed from pure `java.time`).
 *
 * All-day events follow the CalendarProvider convention: UTC midnight of the
 * day, exclusive end (an RFC 5545 all-day `DTEND` is already exclusive, so no
 * adjustment is needed).
 */
fun insertEventExtras(invite: Ics.CalendarInvite, untitled: String = "Calendar event"): Map<String, Any> {
    val out = LinkedHashMap<String, Any>()
    out[EXTRA_TITLE] = invite.summary.ifBlank { untitled }
    if (invite.location.isNotBlank()) {
        out[EXTRA_EVENT_LOCATION] = invite.location
    }
    if (invite.description.isNotBlank()) {
        out[EXTRA_DESCRIPTION] = invite.description.take(MAX_DESCRIPTION_CHARS)
    }
    out[EXTRA_ALL_DAY] = invite.start.allDay
    out[EXTRA_BEGIN_TIME] = eventMillis(invite.start)
    invite.end?.let { out[EXTRA_END_TIME] = eventMillis(it) }
    val emails = invite.attendees.map { it.email }.filter { it.isNotBlank() }
    if (emails.isNotEmpty()) {
        out[Intent.EXTRA_EMAIL] = emails.joinToString(",")
    }
    return out
}

/**
 * An [Intent.ACTION_INSERT] against the system calendar for [invite].
 * No permission is required: the calendar app shows its normal account
 * picker / confirm UI when the intent is fired.
 */
fun insertEventIntent(invite: Ics.CalendarInvite, untitled: String = "Calendar event"): Intent {
    val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
    insertEventExtras(invite, untitled).forEach { (key, value) ->
        // This SDK has no putExtra(String, Object) overload: dispatch by type.
        when (value) {
            is String -> intent.putExtra(key, value)
            is Boolean -> intent.putExtra(key, value)
            is Long -> intent.putExtra(key, value)
            else -> error("unexpected extra type for '$key': ${value::class}")
        }
    }
    return intent
}

private fun eventMillis(t: Ics.EventTime): Long =
    if (t.allDay) t.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    else t.toInstant()?.toEpochMilli() ?: t.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
