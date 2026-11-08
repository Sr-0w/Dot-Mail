package app.dot.mail.engine.calendar

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

class IcsTest {

    private fun ics(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/ics/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private fun one(name: String): Ics.CalendarInvite =
        Ics.parse(ics(name)).single()

    private val now = Instant.parse("2026-06-01T08:00:00Z")

    // -------------------------------------------------------------- parsing

    @Test
    fun `outlook request parses with vtimezone folding and escapes`() {
        val inv = one("outlook-request.ics")
        assertThat(inv.method).isEqualTo(Ics.Method.REQUEST)
        assertThat(inv.uid).isEqualTo("040024BF-0000-0000-FACB-CONTOSO0001")
        assertThat(inv.sequence).isEqualTo(1)
        assertThat(inv.organizer).isEqualTo(Ics.Organizer("John Smith", "john.smith@contoso.com"))

        assertThat(inv.attendees).hasSize(2)
        val alice = inv.attendees[0]
        assertThat(alice.name).isEqualTo("Alice Jones")
        assertThat(alice.email).isEqualTo("alice@example.com")
        assertThat(alice.partStat).isEqualTo(Ics.PartStat.NEEDS_ACTION)
        assertThat(alice.role).isEqualTo("REQ-PARTICIPANT")
        assertThat(alice.rsvp).isTrue()
        val bob = inv.attendees[1]
        assertThat(bob.name).isEqualTo("Bob Lee")
        assertThat(bob.partStat).isEqualTo(Ics.PartStat.ACCEPTED)
        assertThat(bob.role).isEqualTo("NON-PARTICIPANT")
        assertThat(bob.rsvp).isNull()

        assertThat(inv.summary).isEqualTo("Q3 Planning, R&D: \"Big\" things")
        assertThat(inv.location).isEqualTo("Conference Room A, Building 2")
        assertThat(inv.description).isEqualTo("Agenda:\n1. Roadmap, goals\n2. Staffing")
        assertThat(inv.rrule).isNull()
        assertThat(inv.status).isNull()

        // VTIMEZONE resolution: June -> EDT (-04:00)
        assertThat(inv.start.zoneName).isEqualTo("Eastern Standard Time")
        assertThat(inv.start.toInstant()).isEqualTo(Instant.parse("2026-06-10T13:00:00Z"))
        assertThat(inv.end?.toInstant()).isEqualTo(Instant.parse("2026-06-10T14:00:00Z"))
    }

    @Test
    fun `outlook vtimezone is winter in january`() {
        // Same zone rules, winter date: EST (-05:00)
        val text = ics("outlook-request.ics")
            .replace("20260610T090000", "20260110T090000")
            .replace("20260610T100000", "20260110T100000")
        val inv = Ics.parse(text).single()
        assertThat(inv.start.toInstant()).isEqualTo(Instant.parse("2026-01-10T14:00:00Z"))
        assertThat(inv.end?.toInstant()).isEqualTo(Instant.parse("2026-01-10T15:00:00Z"))
    }

    @Test
    fun `google request parses with iana tzid`() {
        val inv = one("google-request.ics")
        assertThat(inv.method).isEqualTo(Ics.Method.REQUEST)
        assertThat(inv.uid).isEqualTo("abc123def456@google.com")
        assertThat(inv.sequence).isEqualTo(0)
        assertThat(inv.organizer).isEqualTo(Ics.Organizer(null, "carol@gmail.com"))
        assertThat(inv.attendees.single().partStat).isEqualTo(Ics.PartStat.NEEDS_ACTION)
        assertThat(inv.summary).isEqualTo("Weekly sync")
        assertThat(inv.url).isEqualTo("https://calendar.google.com/calendar/event?eid=abc")
        assertThat(inv.status).isEqualTo("CONFIRMED")

        assertThat(inv.start.zoneId).isEqualTo(ZoneId.of("America/New_York"))
        assertThat(inv.start.toInstant()).isEqualTo(Instant.parse("2026-01-15T14:00:00Z")) // EST, -05:00
        assertThat(inv.rrule).isEqualTo("FREQ=WEEKLY;COUNT=4")
        assertThat(inv.rruleSummary).isEqualTo("weekly, 4 times")
    }

    @Test
    fun `missing method defaults to publish`() {
        val inv = one("allday-publish.ics")
        assertThat(inv.method).isEqualTo(Ics.Method.PUBLISH)
        assertThat(inv.start.allDay).isTrue()
        assertThat(inv.start.date).isEqualTo(LocalDate.of(2026, 12, 5))
        assertThat(inv.end?.date).isEqualTo(LocalDate.of(2026, 12, 8))
        assertThat(inv.start.toInstant()).isNull()
        assertThat(inv.sequence).isEqualTo(0)
        assertThat(inv.rruleSummary).isEqualTo("yearly, until 31 Dec 2028")
    }

    @Test
    fun `cancel method and status`() {
        val inv = one("cancel.ics")
        assertThat(inv.method).isEqualTo(Ics.Method.CANCEL)
        assertThat(inv.status).isEqualTo("CANCELLED")
        assertThat(inv.sequence).isEqualTo(1)
        assertThat(inv.summary).isEqualTo("Office holiday party")
    }

    @Test
    fun `lf endings x properties and duration`() {
        val inv = one("quirks-lf.ics")
        assertThat(inv.method).isEqualTo(Ics.Method.PUBLISH)
        assertThat(inv.uid).isEqualTo("quirk-1")
        assertThat(inv.start.toInstant()).isEqualTo(Instant.parse("2026-03-01T15:00:00Z"))
        assertThat(inv.duration).isEqualTo("PT90M")
        assertThat(inv.end?.toInstant()).isEqualTo(Instant.parse("2026-03-01T16:30:00Z"))
        assertThat(inv.attendees.single().name).isEqualTo("Zoe Kim")
    }

    @Test
    fun `two events in one calendar`() {
        val list = Ics.parse(ics("two-events-southern.ics"))
        assertThat(list).hasSize(2)
        assertThat(list.map { it.uid }).containsExactly("event-1", "event-2").inOrder()
    }

    @Test
    fun `southern hemisphere vtimezone crosses the year boundary`() {
        val (nz, chicago) = Ics.parse(ics("two-events-southern.ics"))
        // NZDT (September-April) is active in January: +13:00
        assertThat(nz.start.toInstant()).isEqualTo(Instant.parse("2026-01-14T20:00:00Z"))
        assertThat(nz.end?.toInstant()).isEqualTo(Instant.parse("2026-01-14T21:00:00Z"))
        assertThat(nz.description).isEqualTo("Café and cake") // QUOTED-PRINTABLE value
        assertThat(nz.rrule).isNull()

        // May: back to NZST +12:00 (second year of the same zone)
        val shifted = ics("two-events-southern.ics").replace("20260115T090000", "20260501T090000")
        val may = Ics.parse(shifted).first()
        // 09:00 NZST (+12:00) lands the previous day in UTC
        assertThat(may.start.toInstant()).isEqualTo(Instant.parse("2026-04-30T21:00:00Z"))

        // IANA zone, no VTIMEZONE
        assertThat(chicago.start.zoneName).isEqualTo("America/Chicago")
        assertThat(chicago.rrule).isEqualTo("FREQ=MONTHLY;BYDAY=2TU;BYMONTHDAY=15")
        assertThat(chicago.rruleSummary).isNull() // BYMONTHDAY: not a "simple" case
    }

    @Test
    fun `fixed offset vtimezone without daylight`() {
        val text = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VTIMEZONE
            TZID:GMT+05:30
            BEGIN:STANDARD
            DTSTART:19700101T000000
            TZOFFSETFROM:+0530
            TZOFFSETTO:+0530
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:fixed-1
            DTSTART;TZID="GMT+05:30":20260301T120000
            SUMMARY:Fixed offset
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val inv = Ics.parse(text).single()
        assertThat(inv.start.zoneName).isEqualTo("GMT+05:30")
        assertThat(inv.start.toInstant()).isEqualTo(Instant.parse("2026-03-01T06:30:00Z"))
    }

    @Test
    fun `unresolvable tzid falls back to utc without crashing`() {
        val text = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:odd-1
            DTSTART;TZID=Mars/Olympus:20260301T120000
            SUMMARY:Odd zone
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val inv = Ics.parse(text).single()
        assertThat(inv.start.zoneId).isEqualTo(ZoneOffset.UTC)
        assertThat(inv.start.toInstant()).isEqualTo(Instant.parse("2026-03-01T12:00:00Z"))
    }

    @Test
    fun `event without dtstart is skipped`() {
        val text = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:incomplete
            SUMMARY:No start time
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        assertThat(Ics.parse(text)).isEmpty()
    }

    @Test
    fun `blank calendar yields no events`() {
        assertThat(Ics.parse("")).isEmpty()
        assertThat(Ics.parse("BEGIN:VCALENDAR\r\nEND:VCALENDAR")).isEmpty()
    }

    @Test
    fun `all day duration adds days`() {
        val text = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:camp
            DTSTART;VALUE=DATE:20260501
            DURATION:P2D
            SUMMARY:Camp
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val inv = Ics.parse(text).single()
        assertThat(inv.end?.date).isEqualTo(LocalDate.of(2026, 5, 3))
        assertThat(inv.end?.allDay).isTrue()
    }

    @Test
    fun `floating time is flagged and treated as utc`() {
        val text = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:float
            DTSTART:20260301T090000
            SUMMARY:Float
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val inv = Ics.parse(text).single()
        assertThat(inv.start.floating).isTrue()
        assertThat(inv.start.toInstant()).isEqualTo(Instant.parse("2026-03-01T09:00:00Z"))
    }

    // ---------------------------------------------------------- rrule summary

    @Test
    fun `rrule summary interval until datetime`() {
        assertThat(Ics.summarizeForTest("FREQ=DAILY;INTERVAL=3;UNTIL=20260301T000000Z"))
            .isEqualTo("every 3 days, until 1 Mar 2026 00:00 UTC")
    }

    @Test
    fun `rrule summary byday variants`() {
        assertThat(Ics.summarizeForTest("FREQ=MONTHLY;BYDAY=2TU")).isEqualTo("monthly, on 2nd Tuesday")
        assertThat(Ics.summarizeForTest("FREQ=MONTHLY;BYDAY=-1FR")).isEqualTo("monthly, on last Friday")
        assertThat(Ics.summarizeForTest("FREQ=WEEKLY;BYDAY=MO,WE,FR")).isEqualTo("weekly, on Mondays")
    }

    @Test
    fun `rrule summary is null for unsupported parts`() {
        assertThat(Ics.summarizeForTest("FREQ=WEEKLY;BYWEEKNO=1")).isNull()
        assertThat(Ics.summarizeForTest("BYSETPOS=1;FREQ=MONTHLY;BYMONTH=6")).isNull()
        assertThat(Ics.summarizeForTest("FREQ=HOURLY")).isNull()
    }

    // ----------------------------------------------------------------- reply

    @Test
    fun `reply builds a valid method reply calendar`() {
        val inv = one("google-request.ics")
        val out = Ics.reply(inv, "alice@example.com", Ics.PartStat.ACCEPTED, now)

        assertThat(out).contains("METHOD:REPLY")
        assertThat(out).contains("VERSION:2.0")
        assertThat(out).contains("UID:abc123def456@google.com")
        assertThat(out).contains("SEQUENCE:0")
        assertThat(out).contains("DTSTAMP:20260601T080000Z")
        assertThat(out).contains("ORGANIZER:mailto:carol@gmail.com")
        assertThat(out).contains("ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED:mailto:alice@example.com")
        assertThat(out).contains("STATUS:CONFIRMED")
        assertThat(out).contains("SUMMARY:Weekly sync")
        assertThat(out).contains("DTSTART;TZID=America/New_York:20260115T090000")
        assertThat(out).contains("DTEND;TZID=America/New_York:20260115T093000")
        assertThat(out).contains("RRULE:FREQ=WEEKLY;COUNT=4")
        assertThat(out).endsWith("END:VCALENDAR")
        assertThat(out).contains("\r\n")
        // RFC 5545 line length: 75 octets or less per physical line
        out.split("\r\n").forEach { line ->
            assertThat(line.toByteArray(Charsets.UTF_8).size).isAtMost(75)
        }
    }

    @Test
    fun `reply round trips through the parser`() {
        val inv = one("google-request.ics")
        val out = Ics.reply(inv, "alice@example.com", Ics.PartStat.DECLINED, now)
        val parsed = Ics.parse(out).single()
        assertThat(parsed.method).isEqualTo(Ics.Method.REPLY)
        assertThat(parsed.uid).isEqualTo(inv.uid)
        assertThat(parsed.sequence).isEqualTo(inv.sequence)
        assertThat(parsed.start.toInstant()).isEqualTo(inv.start.toInstant())
        assertThat(parsed.end?.toInstant()).isEqualTo(inv.end?.toInstant())
        val me = parsed.attendees.single()
        assertThat(me.email).isEqualTo("alice@example.com")
        assertThat(me.partStat).isEqualTo(Ics.PartStat.DECLINED)
    }

    @Test
    fun `reply carries known attendee name as cn`() {
        val inv = one("outlook-request.ics")
        val out = Ics.reply(inv, "alice@example.com", Ics.PartStat.ACCEPTED, now)
        // The full logical ATTENDEE line exceeds 74 octets and is folded on the wire;
        // unfold before checking it.
        val unfolded = out.replace("\r\n ", "")
        assertThat(unfolded).contains("ATTENDEE;CN=Alice Jones;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED:mailto:alice@example.com")
        // VTIMEZONE zone id is re-serialised by its original name
        assertThat(out).contains("DTSTART;TZID=Eastern Standard Time:20260610T090000")
        out.split("\r\n").forEach { line ->
            assertThat(line.toByteArray(Charsets.UTF_8).size).isAtMost(75)
        }
    }

    @Test
    fun `reply for all day event keeps value date`() {
        val inv = one("allday-publish.ics")
        val out = Ics.reply(inv, "alice@example.com", Ics.PartStat.TENTATIVE, now)
        assertThat(out).contains("DTSTART;VALUE=DATE:20261205")
        assertThat(out).contains("DTEND;VALUE=DATE:20261208")
        assertThat(out).contains("PARTSTAT=TENTATIVE")
    }

    @Test
    fun `reply escapes and folds long values and survives reparsing`() {
        val longSummary = "A very long meeting title, with: colons, commas; semicolons " +
            "and more words to force a fold across several physical lines of the calendar output."
        val inv = Ics.CalendarInvite(
            method = Ics.Method.REQUEST,
            uid = "synthetic-1",
            sequence = 2,
            organizer = Ics.Organizer("Comma, Person", "org@ex.com"),
            attendees = emptyList(),
            summary = longSummary,
            description = "line one\nline two",
            location = "",
            url = null,
            start = Ics.EventTime(LocalDate.of(2026, 7, 1), java.time.LocalTime.of(10, 0), ZoneOffset.UTC, null),
            end = null,
            duration = null,
            rrule = null,
            rruleSummary = null,
            status = null,
        )
        val out = Ics.reply(inv, "me@ex.com", Ics.PartStat.NEEDS_ACTION, now)
        out.split("\r\n").forEach { line ->
            assertThat(line.toByteArray(Charsets.UTF_8).size).isAtMost(75)
        }
        assertThat(out).contains("ORGANIZER;CN=\"Comma, Person\":mailto:org@ex.com")
        val parsed = Ics.parse(out).single()
        assertThat(parsed.summary).isEqualTo(longSummary)
        assertThat(parsed.description).isEqualTo("line one\nline two")
        assertThat(parsed.organizer.name).isEqualTo("Comma, Person")
    }
}
