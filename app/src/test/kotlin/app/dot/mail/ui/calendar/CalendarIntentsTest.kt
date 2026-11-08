package app.dot.mail.ui.calendar

import app.dot.mail.engine.calendar.Ics
import app.dot.mail.engine.calendar.VTimezoneRules
import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

class CalendarIntentsTest {

    private val brussels = ZoneId.of("Europe/Brussels")

    private fun timedInvite(
        summary: String = "Team lunch",
        description: String = "",
        location: String = "Cafe",
        startZone: ZoneId = brussels,
        start: LocalDate = LocalDate.of(2026, 6, 10),
        startTime: LocalTime = LocalTime.of(9, 0),
        end: LocalDate? = null,
        endTime: LocalTime? = null,
        attendees: List<Ics.Attendee> = emptyList(),
    ): Ics.CalendarInvite = Ics.CalendarInvite(
        method = Ics.Method.REQUEST,
        uid = "test-1",
        sequence = 0,
        organizer = Ics.Organizer("Alice", "alice@example.com"),
        attendees = attendees,
        summary = summary,
        description = description,
        location = location,
        url = null,
        start = Ics.EventTime(start, startTime, startZone, null, false),
        end = end?.let { Ics.EventTime(it, endTime ?: LocalTime.of(10, 0), startZone, null, false) },
        duration = null,
        rrule = null,
        rruleSummary = null,
        status = null,
    )

    @Test
    fun `title uses the summary`() {
        val extras = insertEventExtras(timedInvite(summary = "Board 2026"))
        assertThat(extras["title"]).isEqualTo("Board 2026")
    }

    @Test
    fun `blank summary falls back to a placeholder title`() {
        assertThat(insertEventExtras(timedInvite(summary = "  "))["title"]).isEqualTo("Calendar event")
    }

    @Test
    fun `location is set when present and omitted when blank`() {
        assertThat(insertEventExtras(timedInvite(location = "Cafe 2"))["eventLocation"]).isEqualTo("Cafe 2")
        assertThat(insertEventExtras(timedInvite(location = ""))["eventLocation"]).isNull()
    }

    @Test
    fun `description is trimmed to 2000 chars`() {
        val long = "x".repeat(2500)
        val extras = insertEventExtras(timedInvite(description = long))
        val description = extras["description"] as String
        assertThat(description).hasLength(2000)
        assertThat(description).isEqualTo("x".repeat(2000))
    }

    @Test
    fun `timed begin and end are instants in the event zone`() {
        // 2026-06-10 09:00 Europe/Brussels (+02:00) = 07:00 UTC
        val extras = insertEventExtras(timedInvite(end = LocalDate.of(2026, 6, 10), endTime = LocalTime.of(10, 30)))
        assertThat(extras["beginTime"]).isEqualTo(Instant.parse("2026-06-10T07:00:00Z").toEpochMilli())
        assertThat(extras["endTime"]).isEqualTo(Instant.parse("2026-06-10T08:30:00Z").toEpochMilli())
        assertThat(extras["allDay"]).isEqualTo(false)
    }

    @Test
    fun `missing end omits the end time key`() {
        assertThat(insertEventExtras(timedInvite())["endTime"]).isNull()
    }

    @Test
    fun `all day events use utc midnight and the all-day flag`() {
        val invite = Ics.CalendarInvite(
            method = Ics.Method.PUBLISH,
            uid = "test-2",
            sequence = 0,
            organizer = Ics.Organizer(null, "a@example.com"),
            attendees = emptyList(),
            summary = "Offsite",
            description = "",
            location = "",
            url = null,
            start = Ics.EventTime(LocalDate.of(2026, 12, 5), null, null, null),
            end = Ics.EventTime(LocalDate.of(2026, 12, 8), null, null, null),
            duration = null,
            rrule = null,
            rruleSummary = null,
            status = null,
        )
        val extras = insertEventExtras(invite)
        assertThat(extras["allDay"]).isEqualTo(true)
        // RFC all-day DTEND is exclusive: 2026-12-08 as stored in the calendar
        assertThat(extras["beginTime"]).isEqualTo(Instant.parse("2026-12-05T00:00:00Z").toEpochMilli())
        assertThat(extras["endTime"]).isEqualTo(Instant.parse("2026-12-08T00:00:00Z").toEpochMilli())
    }

    @Test
    fun `attendee emails are a comma separated list`() {
        val invite = timedInvite(attendees = listOf(
            Ics.Attendee("Bob", "bob@example.com", Ics.PartStat.NEEDS_ACTION, null, null),
            Ics.Attendee(null, "carol@example.com", Ics.PartStat.NEEDS_ACTION, null, null),
        ))
        assertThat(insertEventExtras(invite)["android.intent.extra.EMAIL"]).isEqualTo("bob@example.com,carol@example.com")
    }

    @Test
    fun `blank attendee addresses are dropped and the key omitted when none remain`() {
        val one = timedInvite(attendees = listOf(
            Ics.Attendee(null, "  ", Ics.PartStat.NEEDS_ACTION, null, null),
        ))
        assertThat(insertEventExtras(one)["android.intent.extra.EMAIL"]).isNull()
        val two = timedInvite(attendees = listOf(
            Ics.Attendee(null, "  ", Ics.PartStat.NEEDS_ACTION, null, null),
            Ics.Attendee(null, "z@example.com", Ics.PartStat.NEEDS_ACTION, null, null),
        ))
        assertThat(insertEventExtras(two)["android.intent.extra.EMAIL"]).isEqualTo("z@example.com")
    }

    @Test
    fun `floating times are interpreted as utc per the ics parser`() {
        val invite = timedInvite(startZone = ZoneOffset.UTC).let {
            // rebuild with the floating flag: EventTime(., time, UTC, null, floating=true)
            it.copy(start = Ics.EventTime(
                date = LocalDate.of(2026, 6, 10),
                time = LocalTime.of(9, 0),
                zoneId = ZoneOffset.UTC,
                vtimeZone = null,
                floating = true,
            ))
        }
        assertThat(insertEventExtras(invite)["beginTime"]).isEqualTo(Instant.parse("2026-06-10T09:00:00Z").toEpochMilli())
    }

    @Test
    fun `zulu events map to their instant`() {
        val invite = timedInvite(startZone = ZoneOffset.UTC, startTime = LocalTime.of(14, 0))
        assertThat(insertEventExtras(invite)["beginTime"]).isEqualTo(Instant.parse("2026-06-10T14:00:00Z").toEpochMilli())
    }

    @Test
    fun `vtimezone events use the rules offset`() {
        val rules = VTimezoneRules(
            tzid = "Fixed+01:00",
            standardOffset = ZoneOffset.ofHours(1),
            savingsOffset = null,
            startMonth = 3, startNth = 2, startDayOfWeek = DayOfWeek.SUNDAY, startTime = LocalTime.of(2, 0),
            endMonth = 11, endNth = 1, endDayOfWeek = DayOfWeek.SUNDAY, endTime = LocalTime.of(2, 0),
        )
        val invite = timedInvite().copy(start = Ics.EventTime(
            date = LocalDate.of(2026, 6, 10),
            time = LocalTime.of(9, 0),
            zoneId = null,
            vtimeZone = rules,
            floating = false,
        ))
        // 09:00 at +01:00 = 08:00 UTC
        assertThat(insertEventExtras(invite)["beginTime"]).isEqualTo(Instant.parse("2026-06-10T08:00:00Z").toEpochMilli())
    }
}
