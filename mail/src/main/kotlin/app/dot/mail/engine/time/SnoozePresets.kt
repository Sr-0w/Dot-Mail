package app.dot.mail.engine.time

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** One snooze suggestion. [at] is the moment the message should surface again. */
data class SnoozeOption(val id: String, val label: String, val at: ZonedDateTime)

/**
 * Built-in snooze presets, computed DST-safely from [now]'s own zone:
 *
 * - `later_today` — now + 3h rounded up to the next :00/:30 boundary (a time
 *   already on the boundary stays). Omitted when that lands after 21:00 or
 *   within an hour of the evening slot; when omitted, `this_evening` at
 *   [eveningHour] is offered instead, as long as it is still more than an
 *   hour away.
 * - `tomorrow` — [morningHour] on the next day.
 * - `this_weekend` — [morningHour] on the next [weekendDay] occurrence;
 *   omitted when today is that day or the day after it.
 * - `next_week` — [morningHour] on [weekStart] of the coming week
 *   (the week after the one containing today).
 *
 * Targets are built with [LocalDateTime.atZone], so a nonexistent local time
 * (spring-forward gap) shifts forward to the first valid time, and a
 * fall-back ambiguity resolves to the earlier offset — e.g. a 02:30 morning
 * on Europe/Brussels' 2026-03-29 becomes 03:30+02:00.
 */
fun presets(
    now: ZonedDateTime,
    morningHour: Int = 8,
    eveningHour: Int = 18,
    weekStart: DayOfWeek = DayOfWeek.MONDAY,
    weekendDay: DayOfWeek = DayOfWeek.SATURDAY,
): List<SnoozeOption> {
    val zone = now.zone
    val today = now.toLocalDate()
    val out = mutableListOf<SnoozeOption>()

    // 1. later_today / this_evening
    val raw = now.plusHours(3).withSecond(0).withNano(0)
    val candidate = when {
        raw.minute == 0 -> raw
        raw.minute <= 30 -> raw.withMinute(30)
        else -> raw.plusHours(1).withMinute(0)
    }
    val evening = atTime(zone, today, eveningHour)
    val tooLate = candidate.toLocalTime() > LocalTime.of(21, 0)
    val nearEvening = Duration.between(candidate, evening).abs() < Duration.ofHours(1)
    when {
        !tooLate && !nearEvening -> out += SnoozeOption("later_today", "Later today", candidate)
        Duration.between(now, evening) > Duration.ofHours(1) ->
            out += SnoozeOption("this_evening", "This evening", evening)
    }

    // 2. tomorrow
    out += SnoozeOption("tomorrow", "Tomorrow", atTime(zone, today.plusDays(1), morningHour))

    // 3. this_weekend
    val dayAfterWeekend = (weekendDay.value + 1).let { if (it > 7) 1 else it }
    if (now.dayOfWeek != weekendDay && now.dayOfWeek != DayOfWeek.of(dayAfterWeekend)) {
        out += SnoozeOption(
            "this_weekend",
            "This weekend",
            atTime(zone, today.with(TemporalAdjusters.next(weekendDay)), morningHour),
        )
    }

    // 4. next_week
    val thisWeekStart = today.with(TemporalAdjusters.previousOrSame(weekStart))
    out += SnoozeOption("next_week", "Next week", atTime(zone, thisWeekStart.plusWeeks(1), morningHour))

    return out
}

/** Wall-clock [hour]:00 on [date] in [zone] (gap times shift forward, ambiguous times take the earlier offset). */
private fun atTime(zone: ZoneId, date: LocalDate, hour: Int): ZonedDateTime =
    LocalDateTime.of(date, LocalTime.of(hour, 0)).atZone(zone)

/**
 * Human description of a snooze target relative to [now]: "Today 14:30",
 * "Tomorrow 08:00", "Sat 08:00" (within a week), "12 Dec 08:00" (beyond).
 * [use24h] picks 24-hour ("14:30") or 12-hour ("2:30 PM") time; locale
 * preference detection is out of scope. Day/month names follow [locale];
 * [todayLabel] / [tomorrowLabel] are supplied by the caller so the app can translate them.
 */
fun describe(
    at: ZonedDateTime, now: ZonedDateTime, locale: Locale, use24h: Boolean = true,
    todayLabel: String = "Today", tomorrowLabel: String = "Tomorrow",
): String {
    val days = ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
    val time = DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", locale).format(at.toLocalTime())
    val day = when {
        days == 0L -> todayLabel
        days == 1L -> tomorrowLabel
        days in 2L..7L -> DateTimeFormatter.ofPattern("EEE", locale).format(at)
        else -> DateTimeFormatter.ofPattern("d MMM", locale).format(at)
    }
    return "$day $time"
}
