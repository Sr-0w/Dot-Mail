package app.dot.mail.ui.calendar

import android.content.res.Resources
import app.dot.mail.R
import app.dot.mail.engine.calendar.Ics.RRuleSummary
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Turns the engine's language-neutral [RRuleSummary] into text in the app language, with locale-aware dates and weekdays. */
object RRuleText {
    fun format(r: RRuleSummary, resources: Resources, locale: Locale = Locale.getDefault()): String {
        val parts = mutableListOf<String>()
        parts += if (r.interval > 1) {
            val plural = when (r.unit) {
                RRuleSummary.Unit.DAY -> R.plurals.rrule_every_days
                RRuleSummary.Unit.WEEK -> R.plurals.rrule_every_weeks
                RRuleSummary.Unit.MONTH -> R.plurals.rrule_every_months
                RRuleSummary.Unit.YEAR -> R.plurals.rrule_every_years
            }
            resources.getQuantityString(plural, r.interval, r.interval)
        } else resources.getString(
            when (r.unit) {
                RRuleSummary.Unit.DAY -> R.string.rrule_daily
                RRuleSummary.Unit.WEEK -> R.string.rrule_weekly
                RRuleSummary.Unit.MONTH -> R.string.rrule_monthly
                RRuleSummary.Unit.YEAR -> R.string.rrule_yearly
            },
        )
        r.byDay?.let { day ->
            val name = day.getDisplayName(TextStyle.FULL, locale)
            val res = when (r.ordinal) {
                null -> R.string.rrule_on_every_weekday
                -1 -> R.string.rrule_on_last_weekday
                1 -> R.string.rrule_on_first_weekday
                2 -> R.string.rrule_on_second_weekday
                3 -> R.string.rrule_on_third_weekday
                4 -> R.string.rrule_on_fourth_weekday
                5 -> R.string.rrule_on_fifth_weekday
                else -> R.string.rrule_on_every_weekday
            }
            parts += resources.getString(res, name)
        }
        r.untilDate?.let { d -> parts += resources.getString(R.string.rrule_until, untilText(d, r.untilTime, r.untilUtc, locale)) }
        r.count?.let { parts += resources.getQuantityString(R.plurals.rrule_times, it, it) }
        return parts.joinToString(", ")
    }

    internal fun untilText(date: LocalDate, time: LocalTime?, utc: Boolean, locale: Locale): String {
        val d = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
        if (time == null) return d
        val t = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time)
        return "$d $t" + if (utc) " UTC" else ""
    }
}
