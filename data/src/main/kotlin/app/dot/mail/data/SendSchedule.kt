package app.dot.mail.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Pure rules for "Send later": which times are offered, what is a valid time, how a picked date and time become an instant.
 * Kept free of Android so the composer, the outbox and the tests share one definition.
 */
object SendSchedule {
    /** A scheduled time must be at least this far ahead; closer than that is just "send now" (the Undo window covers it). */
    const val MIN_LEAD_MS = 60_000L

    enum class Validity { OK, IN_THE_PAST }

    fun validate(at: Long, now: Long): Validity = if (at < now + MIN_LEAD_MS) Validity.IN_THE_PAST else Validity.OK

    /** True when an outbox item's due time is a real schedule, not just the short Undo-send grace period. */
    fun isScheduled(sendAt: Long, now: Long): Boolean = sendAt > now + MIN_LEAD_MS

    class Preset(val id: String, val at: Long)

    /**
     * This evening (18:00, offered until 17:00), tomorrow morning (08:00) and the next Monday morning (08:00) — Monday is
     * left out when tomorrow already is Monday so the two never duplicate.
     */
    fun presets(now: ZonedDateTime): List<Preset> {
        val zone = now.zone
        val out = mutableListOf<Preset>()
        if (now.hour < 17) out += Preset(EVENING, at(now.toLocalDate(), LocalTime.of(18, 0), zone))
        val tomorrow = now.toLocalDate().plusDays(1)
        out += Preset(TOMORROW, at(tomorrow, LocalTime.of(8, 0), zone))
        val monday = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        if (monday != tomorrow) out += Preset(MONDAY, at(monday, LocalTime.of(8, 0), zone))
        return out
    }

    /**
     * A wall-clock date and time in [zone] as an instant. A time that does not exist (spring-forward gap) moves forward by the
     * gap, an ambiguous one (autumn overlap) takes the earlier offset — `java.time`'s documented resolution, pinned by tests.
     */
    fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Long = date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    const val EVENING = "this_evening"
    const val TOMORROW = "tomorrow_morning"
    const val MONDAY = "monday_morning"
}
