package app.dot.mail.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class SendScheduleTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, paris)

    @Test fun `a time less than a minute ahead is not a schedule`(): Unit {
        val now = 1_000_000L
        assertThat(SendSchedule.validate(now - 1, now)).isEqualTo(SendSchedule.Validity.IN_THE_PAST)
        assertThat(SendSchedule.validate(now + SendSchedule.MIN_LEAD_MS - 1, now)).isEqualTo(SendSchedule.Validity.IN_THE_PAST)
        assertThat(SendSchedule.validate(now + SendSchedule.MIN_LEAD_MS, now)).isEqualTo(SendSchedule.Validity.OK)
        assertThat(SendSchedule.isScheduled(now + 5_000, now)).isFalse() // the undo window
        assertThat(SendSchedule.isScheduled(now + 3_600_000, now)).isTrue()
    }

    @Test fun `presets offer this evening before 17 and drop it afterwards`(): Unit {
        val morning = SendSchedule.presets(at(2026, 11, 10, 9)).map { it.id } // a Tuesday
        assertThat(morning).containsExactly(SendSchedule.EVENING, SendSchedule.TOMORROW, SendSchedule.MONDAY).inOrder()
        val late = SendSchedule.presets(at(2026, 11, 10, 17, 30)).map { it.id }
        assertThat(late).containsExactly(SendSchedule.TOMORROW, SendSchedule.MONDAY).inOrder()
    }

    @Test fun `preset times are 18 00 and 08 00 local, Monday is the next one`(): Unit {
        val now = at(2026, 11, 10, 9) // Tuesday
        val p = SendSchedule.presets(now).associate { it.id to java.time.Instant.ofEpochMilli(it.at).atZone(paris) }
        assertThat(p.getValue(SendSchedule.EVENING).toLocalDateTime()).isEqualTo(at(2026, 11, 10, 18).toLocalDateTime())
        assertThat(p.getValue(SendSchedule.TOMORROW).toLocalDateTime()).isEqualTo(at(2026, 11, 11, 8).toLocalDateTime())
        assertThat(p.getValue(SendSchedule.MONDAY).toLocalDateTime()).isEqualTo(at(2026, 11, 16, 8).toLocalDateTime())
    }

    @Test fun `on a Sunday tomorrow is Monday and the two presets never duplicate`(): Unit {
        val ids = SendSchedule.presets(at(2026, 11, 15, 12)).map { it.id } // Sunday
        assertThat(ids).containsExactly(SendSchedule.EVENING, SendSchedule.TOMORROW).inOrder()
        // On a Monday "next Monday" is a week away, still distinct from tomorrow.
        assertThat(SendSchedule.presets(at(2026, 11, 16, 12)).map { it.id }).contains(SendSchedule.MONDAY)
    }

    @Test fun `every preset is in the future when it is offered`(): Unit {
        for (hour in 0..23) SendSchedule.presets(at(2026, 11, 10, hour, 59)).forEach { assertThat(it.at).isGreaterThan(at(2026, 11, 10, hour, 59).toInstant().toEpochMilli()) }
    }

    @Test fun `a time in the spring-forward gap moves forward, an autumn overlap takes the earlier offset`(): Unit {
        // 2026-03-29 02:30 does not exist in Paris (clocks jump from 02:00 to 03:00 CET→CEST).
        val gap = SendSchedule.at(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), paris)
        assertThat(java.time.Instant.ofEpochMilli(gap).atZone(paris).toLocalTime()).isEqualTo(LocalTime.of(3, 30))
        // 2026-10-25 02:30 happens twice; the first (summer time, +02:00) wins.
        val overlap = SendSchedule.at(LocalDate.of(2026, 10, 25), LocalTime.of(2, 30), paris)
        assertThat(java.time.Instant.ofEpochMilli(overlap).atZone(paris).offset.totalSeconds).isEqualTo(2 * 3600)
    }

    @Test fun `the same wall clock time is a different instant in another timezone`(): Unit {
        val date = LocalDate.of(2026, 11, 10)
        val paris8 = SendSchedule.at(date, LocalTime.of(8, 0), paris)
        val tokyo8 = SendSchedule.at(date, LocalTime.of(8, 0), ZoneId.of("Asia/Tokyo"))
        assertThat(paris8 - tokyo8).isEqualTo(8 * 3_600_000L) // Tokyo is 8 hours ahead of Paris in November
    }
}
