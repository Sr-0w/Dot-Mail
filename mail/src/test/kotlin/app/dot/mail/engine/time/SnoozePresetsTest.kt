package app.dot.mail.engine.time

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Test

class SnoozePresetsTest {

    private val brussels = ZoneId.of("Europe/Brussels")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): ZonedDateTime =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, brussels)

    // 2026 calendar facts: Jun 10 = Wednesday, Jun 13 = Saturday, Jun 14 = Sunday,
    // Jun 15 = Monday; Mar 28 = Saturday (spring gap Mar 29 02:00-03:00);
    // Oct 24 = Monday (fall back Oct 25).

    @Test
    fun `basic weekday shape offers all four presets`() {
        val now = at(2026, 6, 10, 9, 17)
        val ids = presets(now).map { it.id }
        assertThat(ids).containsExactly(
            "later_today", "tomorrow", "this_weekend", "next_week",
        ).inOrder()
        val byId = presets(now).associateBy { it.id }
        assertThat(byId.getValue("later_today").at).isEqualTo(at(2026, 6, 10, 12, 30))
        assertThat(byId.getValue("tomorrow").at).isEqualTo(at(2026, 6, 11, 8))
        assertThat(byId.getValue("this_weekend").at).isEqualTo(at(2026, 6, 13, 8))
        assertThat(byId.getValue("next_week").at).isEqualTo(at(2026, 6, 15, 8))
    }

    @Test
    fun `later today rounds up to the next half hour and keeps exact boundaries`() {
        assertThat(presets(at(2026, 6, 10, 9, 17)).first().at).isEqualTo(at(2026, 6, 10, 12, 30))
        assertThat(presets(at(2026, 6, 10, 9, 0)).first().at).isEqualTo(at(2026, 6, 10, 12, 0))
        assertThat(presets(at(2026, 6, 10, 10, 45)).first().at).isEqualTo(at(2026, 6, 10, 14, 0))
    }

    @Test
    fun `later today is omitted after 21 and evening is not offered once passed`() {
        val now = at(2026, 6, 10, 18, 45)
        val ids = presets(now).map { it.id }
        assertThat(ids).doesNotContain("later_today")
        assertThat(ids).doesNotContain("this_evening")
        assertThat(ids).containsExactly("tomorrow", "this_weekend", "next_week").inOrder()
    }

    @Test
    fun `near evening switches to this evening`() {
        val now = at(2026, 6, 10, 14, 15) // +3h -> 17:30, half an hour before 18:00
        val today = presets(now).first()
        assertThat(today.id).isEqualTo("this_evening")
        assertThat(today.at).isEqualTo(at(2026, 6, 10, 18, 0))
        assertThat(presets(now).map { it.id }).doesNotContain("later_today")
    }

    @Test
    fun `later today is kept when more than an hour from evening`() {
        val now = at(2026, 6, 10, 13, 30) // +3h -> 16:30, 1h30 before 18:00
        val ids = presets(now).map { it.id }
        assertThat(ids.first()).isEqualTo("later_today")
        assertThat(ids).doesNotContain("this_evening")
    }

    @Test
    fun `a candidate exactly at 21 is still offered`() {
        val now = at(2026, 6, 10, 18, 0) // +3h -> 21:00, not after 21:00
        val first = presets(now).first()
        assertThat(first.id).isEqualTo("later_today")
        assertThat(first.at).isEqualTo(at(2026, 6, 10, 21, 0))
    }

    @Test
    fun `custom morning hour applies to the other presets`() {
        val p = presets(at(2026, 6, 10, 9, 0), morningHour = 7)
        assertThat(p.find { it.id == "tomorrow" }?.at).isEqualTo(at(2026, 6, 11, 7))
        assertThat(p.find { it.id == "this_weekend" }?.at).isEqualTo(at(2026, 6, 13, 7))
        assertThat(p.find { it.id == "next_week" }?.at).isEqualTo(at(2026, 6, 15, 7))
    }

    @Test
    fun `weekend is omitted on the weekend day and the day after`() {
        assertThat(presets(at(2026, 6, 13, 9, 0)).map { it.id }).doesNotContain("this_weekend")
        assertThat(presets(at(2026, 6, 14, 9, 0)).map { it.id }).doesNotContain("this_weekend")
        val friday = presets(at(2026, 6, 12, 9, 0))
        assertThat(friday.find { it.id == "this_weekend" }?.at).isEqualTo(at(2026, 6, 13, 8))
    }

    @Test
    fun `weekend day is configurable`() {
        val monday = presets(at(2026, 6, 15, 9, 0), weekendDay = DayOfWeek.SUNDAY)
        assertThat(monday.map { it.id }).doesNotContain("this_weekend") // day after Sunday
        val tuesday = presets(at(2026, 6, 16, 9, 0), weekendDay = DayOfWeek.SUNDAY)
        assertThat(tuesday.find { it.id == "this_weekend" }?.at).isEqualTo(at(2026, 6, 21, 8))
    }

    @Test
    fun `next week from a monday is seven days ahead`() {
        val p = presets(at(2026, 6, 8, 9, 0))
        assertThat(p.find { it.id == "next_week" }?.at).isEqualTo(at(2026, 6, 15, 8))
    }

    @Test
    fun `on a sunday next week is not a duplicate of tomorrow`() {
        // Regression (seen on device, Sun 8 Nov 2026): both showed "Tomorrow 08:00".
        val p = presets(at(2026, 6, 14, 18, 0)) // Sunday
        assertThat(p.find { it.id == "tomorrow" }?.at).isEqualTo(at(2026, 6, 15, 8))
        assertThat(p.find { it.id == "next_week" }?.at).isEqualTo(at(2026, 6, 22, 8))
    }

    @Test
    fun `custom week start`() {
        val p = presets(at(2026, 6, 10, 9, 0), weekStart = DayOfWeek.THURSDAY)
        // week of Jun 8 (Mon) starts Thu Jun 4; next start is Jun 11 = tomorrow, so the week after (no duplicate of "Tomorrow")
        assertThat(p.find { it.id == "next_week" }?.at).isEqualTo(at(2026, 6, 18, 8))
    }

    // ----------------------------------------------------------------- DST

    @Test
    fun `spring forward, tomorrow lands on the new offset`() {
        val now = at(2026, 3, 28, 8, 0) // Sat morning, still +01:00
        val tomorrow = presets(now).find { it.id == "tomorrow" }!!.at
        // Mar 29 02:00 jumps to 03:00: 08:00 local is now +02:00
        assertThat(tomorrow.toLocalDate().toString()).isEqualTo("2026-03-29")
        assertThat(tomorrow.toLocalTime().toString()).isEqualTo("08:00")
        assertThat(tomorrow.offset.toString()).isEqualTo("+02:00")
    }

    @Test
    fun `spring forward, a nonexistent morning time shifts forward`() {
        val now = ZonedDateTime.of(2026, 3, 28, 9, 0, 0, 0, brussels) // +01:00
        val tomorrow = presets(now, morningHour = 2).find { it.id == "tomorrow" }!!.at
        // 02:00 on Mar 29 does not exist: Java shifts it to 03:00+02:00
        assertThat(tomorrow).isEqualTo(ZonedDateTime.of(2026, 3, 29, 3, 0, 0, 0, brussels))
        assertThat(tomorrow.toLocalTime().toSecondOfDay()).isEqualTo(3 * 3600)
    }

    @Test
    fun `fall back, tomorrow at eight takes the winter offset`() {
        val now = at(2026, 10, 24, 8, 0) // Monday, still +02:00
        val tomorrow = presets(now).find { it.id == "tomorrow" }!!.at
        assertThat(tomorrow).isEqualTo(ZonedDateTime.of(2026, 10, 25, 8, 0, 0, 0, brussels))
        assertThat(tomorrow.offset.toString()).isEqualTo("+01:00")
    }

    // ------------------------------------------------------------ describe

    @Test
    fun `describe today`() {
        val now = at(2026, 6, 10, 9, 0)
        assertThat(describe(at(2026, 6, 10, 14, 30), now, Locale.ENGLISH)).isEqualTo("Today 14:30")
    }

    @Test
    fun `describe tomorrow`() {
        val now = at(2026, 6, 10, 9, 0)
        assertThat(describe(at(2026, 6, 11, 8, 0), now, Locale.ENGLISH)).isEqualTo("Tomorrow 08:00")
    }

    @Test
    fun `describe a day within the week by weekday name`() {
        val now = at(2026, 6, 10, 9, 0)
        assertThat(describe(at(2026, 6, 13, 8, 0), now, Locale.ENGLISH)).isEqualTo("Sat 08:00")
    }

    @Test
    fun `describe a day beyond the week by date`() {
        val now = at(2026, 6, 10, 9, 0)
        assertThat(describe(at(2026, 12, 12, 8, 0), now, Locale.ENGLISH)).isEqualTo("12 Dec 08:00")
    }

    @Test
    fun `describe uses 12 hour time when asked`() {
        val now = at(2026, 6, 10, 9, 0)
        assertThat(describe(at(2026, 6, 10, 14, 30), now, Locale.ENGLISH, use24h = false))
            .isEqualTo("Today 2:30 PM")
        assertThat(describe(at(2026, 6, 11, 8, 0), now, Locale.ENGLISH, use24h = false))
            .isEqualTo("Tomorrow 8:00 AM")
    }

    @Test
    fun `describe follows the locale for names`() {
        val now = at(2026, 6, 10, 9, 0)
        assertThat(describe(at(2026, 6, 13, 8, 0), now, Locale.FRENCH)).isEqualTo("sam. 08:00")
        assertThat(describe(at(2026, 12, 12, 8, 0), now, Locale.FRENCH)).isEqualTo("12 déc. 08:00")
    }

    @Test
    fun `describe switches from weekday to date at seven days`() {
        val now = at(2026, 6, 10, 9, 0)
        assertThat(describe(at(2026, 6, 17, 8, 0), now, Locale.ENGLISH)).isEqualTo("Wed 08:00")
        assertThat(describe(at(2026, 6, 18, 8, 0), now, Locale.ENGLISH)).isEqualTo("18 Jun 08:00")
    }
}
