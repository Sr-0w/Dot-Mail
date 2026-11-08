package app.dot.mail.engine.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Minimal RFC 5545 (iCalendar) parsing for mail invitations: the VEVENT fields an
 * email client needs, the METHOD, and enough VTIMEZONE support to resolve non-IANA
 * zone ids. Deliberately forgiving: CRLF or LF line endings, folded long lines,
 * X- properties and unknown fields are ignored, not fatal.
 *
 * What is *not* attempted: VALARM, ATTACH, recurrence sets (RRULE is kept raw, with
 * a human summary only for the simple FREQ/INTERVAL/COUNT/UNTIL/BYDAY cases), or
 * exotic duration formats.
 */
object Ics {

    /** iCalendar METHOD; a calendar without METHOD is a PUBLISH. */
    enum class Method { REQUEST, REPLY, CANCEL, PUBLISH }

    /** ATTENDEE PARTSTAT; unknown values default to NEEDS_ACTION (the RFC default). */
    enum class PartStat { NEEDS_ACTION, ACCEPTED, DECLINED, TENTATIVE }

    data class Organizer(val name: String?, val email: String)

    data class Attendee(
        val name: String?,
        val email: String,
        val partStat: PartStat,
        val role: String?,
        val rsvp: Boolean?,
    )

    /**
     * One date-time value.
     *
     * [time] == null means an all-day (VALUE=DATE) value: [date] is the day and the
     * zone is irrelevant. For date-times, [zoneId] is UTC for `Z` values, the IANA
     * zone (or a VTIMEZONE-derived fixed offset) for `TZID` values, and [vtimeZone]
     * carries the yearly rules when the TZID only existed as a VTIMEZONE block.
     * [floating] marks local date-times without TZID/Z: they are interpreted as UTC
     * for [toInstant]; the UI may re-render them in the viewer's zone.
     */
    data class EventTime(
        val date: LocalDate,
        val time: LocalTime?,
        val zoneId: ZoneId?,
        val vtimeZone: VTimezoneRules?,
        val floating: Boolean = false,
    ) {
        val allDay: Boolean get() = time == null
        val zoneName: String get() = vtimeZone?.tzid ?: zoneId?.id.orEmpty()

        /** Null for all-day events. */
        fun toInstant(): Instant? {
            val t = time ?: return null
            return when {
                vtimeZone != null -> vtimeZone.toInstant(date, t)
                floating -> LocalDateTime.of(date, t).toInstant(ZoneOffset.UTC)
                else -> ZonedDateTime.of(date, t, zoneId ?: ZoneOffset.UTC).toInstant()
            }
        }
    }

    data class CalendarInvite(
        val method: Method,
        val uid: String,
        val sequence: Int,
        val organizer: Organizer,
        val attendees: List<Attendee>,
        val summary: String,
        val description: String,
        val location: String,
        val url: String?,
        val start: EventTime,
        /** DTEND when present, else start + DURATION; null when neither exists. All-day DTEND is inclusive per RFC 5545. */
        val end: EventTime?,
        /** Raw DURATION value, when that is how the event ends. */
        val duration: String?,
        /** Raw RRULE value, when present. */
        val rrule: String?,
        /** Human summary for simple FREQ/INTERVAL/COUNT/UNTIL/BYDAY rules; null otherwise. */
        val rruleSummary: String?,
        /** CONFIRMED / TENTATIVE / CANCELLED, when present. */
        val status: String?,
    )

    /** Parses a text/calendar document; one [CalendarInvite] per usable VEVENT. */
    fun parse(text: String): List<CalendarInvite> {
        val lines = unfold(text)
        val method = captureMethod(lines)
        val vtimezones = linkedMapOf<String, VTimezoneRules>()
        val invites = mutableListOf<CalendarInvite>()
        val scope = ArrayDeque<String>()
        val vtzStack = ArrayDeque<VtzBuilder>()
        val event = EventBuilder()
        var inEvent = false

        for (line in lines) {
            val pl = parseContentLine(line) ?: continue
            when (pl.name) {
                "BEGIN" -> {
                    val comp = pl.value.trim().uppercase()
                    when (comp) {
                        "VEVENT" -> { event.reset(); inEvent = true }
                        "VTIMEZONE" -> vtzStack.addLast(VtzBuilder())
                        "STANDARD", "DAYLIGHT" -> vtzStack.lastOrNull()?.beginBlock(comp)
                    }
                    scope.addLast(comp)
                }
                "END" -> {
                    val comp = pl.value.trim().uppercase()
                    when (comp) {
                        "VEVENT" -> {
                            if (inEvent) event.toInvite(method, vtimezones)?.let { invites.add(it) }
                            inEvent = false
                        }
                        "VTIMEZONE" -> vtzStack.removeLastOrNull()?.let { b ->
                            b.tzid?.let { id -> vtimezones.putIfAbsent(id, b.build()) }
                        }
                    }
                    scope.removeLastOrNull()
                }
                else -> {
                    when {
                        inEvent -> event.add(pl)
                        vtzStack.isNotEmpty() -> {
                            val sub = scope.lastOrNull()
                            vtzStack.last().add(pl, sub)
                        }
                        else -> Unit // top-level VERSION/PRODID/CALSCALE and X-: ignored
                    }
                }
            }
        }
        return invites
    }

    private fun captureMethod(lines: List<String>): Method {
        for (line in lines) {
            val pl = parseContentLine(line) ?: continue
            if (pl.name == "METHOD" && pl.value.trim().isNotEmpty()) {
                val m = pl.value.trim().uppercase()
                return Method.entries.firstOrNull { it.name == m } ?: Method.PUBLISH
            }
        }
        return Method.PUBLISH
    }

    // ------------------------------------------------------------- VTIMEZONE

    private class VtzBuilder {
        var tzid: String? = null
        val blocks = linkedMapOf<String, MutableMap<String, String>>()

        fun beginBlock(block: String) {
            blocks.getOrPut(block) { mutableMapOf() }
        }

        fun add(pl: ParsedLine, sub: String?) {
            when (pl.name) {
                "TZID" -> tzid = pl.value
                else -> sub?.let { blocks[it]?.set(pl.name, pl.value) }
            }
        }

        fun build(): VTimezoneRules {
            val std = blocks["STANDARD"].orEmpty()
            val daylight = blocks["DAYLIGHT"].orEmpty()
            val standard = parseOffset(std["TZOFFSETTO"]) ?: ZoneOffset.UTC
            val savings = parseOffset(daylight["TZOFFSETTO"])
            val start = ruleFor(daylight) // DST starts via the DAYLIGHT block
            val end = ruleFor(std)        // ... and ends via the STANDARD block
            return VTimezoneRules(
                tzid = tzid ?: "unknown",
                standardOffset = standard,
                savingsOffset = savings,
                startMonth = start?.month ?: 3,
                startNth = start?.nth ?: 2,
                startDayOfWeek = start?.dow ?: DayOfWeek.SUNDAY,
                startTime = start?.time ?: LocalTime.of(2, 0),
                endMonth = end?.month ?: 11,
                endNth = end?.nth ?: 1,
                endDayOfWeek = end?.dow ?: DayOfWeek.SUNDAY,
                endTime = end?.time ?: LocalTime.of(2, 0),
            )
        }

        private data class Rule(val month: Int, val nth: Int, val dow: DayOfWeek, val time: LocalTime)

        private fun ruleFor(block: Map<String, String>): Rule? {
            if (parseOffset(block["TZOFFSETTO"]) == null) return null
            val dtStart = block["DTSTART"] ?: return null
            val anchor = parseDate(dtStart) ?: return null
            val time = parseTimePart(dtStart) ?: LocalTime.MIDNIGHT
            val rrule = block["RRULE"].orEmpty()
            val byday = rrule.split(';').firstOrNull { it.startsWith("BYDAY=", ignoreCase = true) }
                ?.substringAfter('=')?.split(',')?.firstOrNull()
                ?.let { parseByday(it, anchor) }
            val nth = byday?.first ?: (anchor.dayOfMonth + 6) / 7
            val dow = byday?.second ?: anchor.dayOfWeek
            val byMonth = rrule.split(';').firstOrNull { it.startsWith("BYMONTH=", ignoreCase = true) }
                ?.substringAfter('=')?.toIntOrNull()?.coerceIn(1, 12)
            return Rule(byMonth ?: anchor.monthValue, nth, dow, time)
        }
    }

    // --------------------------------------------------------------- events

    private class EventBuilder {
        private val properties = linkedMapOf<String, MutableList<ParsedLine>>()

        fun reset() = properties.clear()

        fun add(pl: ParsedLine) {
            properties.getOrPut(pl.name) { mutableListOf() }.add(pl)
        }

        fun toInvite(method: Method, vtimezones: Map<String, VTimezoneRules>): CalendarInvite? {
            fun first(name: String): ParsedLine? = properties[name]?.firstOrNull()
            val startProp = first("DTSTART") ?: return null
            val start = parseEventTime(startProp.value, startProp.params, vtimezones) ?: return null

            val endProp = first("DTEND")
            val duration = first("DURATION")?.value
            val end = when {
                endProp != null -> parseEventTime(endProp.value, endProp.params, vtimezones)
                duration != null -> addDuration(start, duration)
                else -> null
            }

            val organizer = first("ORGANIZER")?.let {
                Organizer(it.params["CN"]?.unquote(), mailto(it.value))
            } ?: Organizer(null, "")

            val attendees = properties["ATTENDEE"].orEmpty().map { pl ->
                val partstat = pl.params["PARTSTAT"]?.uppercase()?.let { v ->
                    PartStat.entries.firstOrNull { it.name == v }
                } ?: PartStat.NEEDS_ACTION
                Attendee(
                    name = pl.params["CN"]?.unquote(),
                    email = mailto(pl.value),
                    partStat = partstat,
                    role = pl.params["ROLE"]?.uppercase(),
                    rsvp = pl.params["RSVP"]?.uppercase()?.let { it == "TRUE" },
                )
            }

            return CalendarInvite(
                method = method,
                uid = first("UID")?.value ?: "",
                sequence = first("SEQUENCE")?.value?.toIntOrNull() ?: 0,
                organizer = organizer,
                attendees = attendees,
                summary = first("SUMMARY")?.let { decodeText(it.value, it.params) } ?: "",
                description = first("DESCRIPTION")?.let { decodeText(it.value, it.params) } ?: "",
                location = first("LOCATION")?.let { decodeText(it.value, it.params) } ?: "",
                url = first("URL")?.value,
                start = start,
                end = end,
                duration = duration,
                rrule = first("RRULE")?.value,
                rruleSummary = first("RRULE")?.value?.let { summarizeRrule(it) },
                status = first("STATUS")?.value?.uppercase(),
            )
        }
    }

    // ------------------------------------------------------------- parsing

    private data class ParsedLine(val name: String, val params: Map<String, String>, val value: String)

    /** Splits raw text into content lines: normalises line endings and unfolds continuations. */
    private fun unfold(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = mutableListOf<String>()
        for (line in normalized.split('\n')) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (out.isNotEmpty()) out[out.size - 1] = out[out.size - 1] + line.substring(1)
            } else {
                out.add(line)
            }
        }
        return out.filter { it.isNotEmpty() }
    }

    private fun parseContentLine(line: String): ParsedLine? {
        // The value separator is the first ':' outside quotes; ';' only separates
        // parameters within the head (name;PARAM=...:value).
        var i = 0
        var inQuotes = false
        var sep = -1
        while (i < line.length) {
            when (val c = line[i]) {
                '"' -> inQuotes = !inQuotes
                ':' -> if (!inQuotes) { sep = i; break }
            }
            i++
        }
        if (sep <= 0) return null // no value separator: not a content line we understand
        val head = line.substring(0, sep)
        val value = line.substring(sep + 1)
        val semi = head.indexOf(';')
        val name = (if (semi >= 0) head.substring(0, semi) else head).trim().uppercase()
        val params = if (semi >= 0) parseParams(head.substring(semi + 1)) else emptyMap()
        return ParsedLine(name, params, value)
    }

    private fun parseParams(raw: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var inQuotes = false
        val current = StringBuilder()
        fun flush() {
            val token = current.toString().trim()
            current.clear()
            if (token.isEmpty()) return
            val eq = token.indexOf('=')
            if (eq <= 0) return
            out[token.substring(0, eq).trim().uppercase()] = token.substring(eq + 1).trim()
        }
        for (c in raw) {
            when {
                c == '"' -> { inQuotes = !inQuotes; current.append(c) }
                c == ';' && !inQuotes -> flush()
                else -> current.append(c)
            }
        }
        flush()
        return out
    }

    private fun String.unquote(): String =
        if (length >= 2 && startsWith('"') && endsWith('"')) substring(1, length - 1).replace("\\\"", "\"") else this

    /** mailto: values (and bare addresses, with trailing params cut off). */
    private fun mailto(value: String): String {
        var v = value.trim()
        if (v.startsWith("mailto:", ignoreCase = true)) v = v.substring(7)
        return v.substringBefore(';').trim()
    }

    /**
     * Text-value decoding. Handles both the VALUE=QUOTED-PRINTABLE/BASE64 parameter form
     * and the legacy "QUOTED-PRINTABLE:"/"BASE64:" value prefixes, then applies RFC 5545
     * backslash unescaping to ordinary text.
     */
    private fun decodeText(value: String, params: Map<String, String>): String {
        val form = params["VALUE"]?.uppercase()
        val v = value.trim()
        return when (form) {
            "QUOTED-PRINTABLE" -> qpDecode(v.replace("=\r", "").replace("=\n", ""))
            "BASE64" -> runCatching { String(java.util.Base64.getMimeDecoder().decode(v)) }.getOrDefault(unescape(v))
            else -> when {
                v.startsWith("QUOTED-PRINTABLE:", ignoreCase = true) ->
                    qpDecode(v.substring(17).replace("=\r", "").replace("=\n", ""))
                v.startsWith("BASE64:", ignoreCase = true) ->
                    runCatching { String(java.util.Base64.getMimeDecoder().decode(v.substring(7))) }.getOrDefault(unescape(v))
                else -> unescape(v)
            }
        }
    }

    /** QUOTED-PRINTABLE decodes to bytes, not characters (multi-byte UTF-8 sequences). */
    private fun qpDecode(body: String): String {
        val bytes = ArrayList<Byte>(body.length)
        var i = 0
        while (i < body.length) {
            if (body[i] == '=' && i + 2 < body.length) {
                val code = body.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    bytes.add(code.toByte()); i += 3; continue
                }
            }
            bytes.add(body[i].code.toByte())
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /**
     * RFC 5545 backslash unescaping. `\n`/`\N`/`\r` are newlines; any other escaped
     * character (the spec lists `,` and `;`; Outlook in the wild also escapes `:`
     * and friends) unescapes to that literal character.
     */
    private fun unescape(v: String): String {
        val out = StringBuilder(v.length)
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                when (val n = v[i + 1]) {
                    'n', 'N', 'r' -> out.append('\n')
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------ date-times

    private val DATE_RE = Regex("(\\d{4})(\\d{2})(\\d{2})")
    private val TIME_RE = Regex("T(\\d{2})(\\d{2})(\\d{2})?")
    private val OFFSET_RE = Regex("([+-])(\\d{2}):?(\\d{2})")

    private fun parseDate(value: String): LocalDate? = DATE_RE.find(value)?.let {
        runCatching { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
            .getOrNull()
    }

    private fun parseTimePart(value: String): LocalTime? = TIME_RE.find(value)?.let {
        val s = it.groupValues[3].ifEmpty { "00" }
        runCatching { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), s.toInt()) }.getOrNull()
    }

    private fun parseOffset(value: String?): ZoneOffset? = value?.let { v ->
        OFFSET_RE.find(v.trim())?.let {
            val sign = if (it.groupValues[1] == "-") -1 else 1
            runCatching {
                ZoneOffset.ofHoursMinutesSeconds(sign * it.groupValues[2].toInt(), sign * it.groupValues[3].toInt(), 0)
            }.getOrNull()
        }
    }

    private fun parseByday(token: String, anchor: LocalDate): Pair<Int, DayOfWeek>? {
        val m = Regex("^([+-]?\\d+)?([A-Z]{2,3})$").find(token.trim().uppercase()) ?: return null
        val dow = dayOfWeek(m.groupValues[2]) ?: return null
        val ord = m.groupValues[1]
        return when {
            ord.isEmpty() -> ((anchor.dayOfMonth + 6) / 7) to dow
            else -> {
                val n = ord.toInt()
                if (n in 1..5) n to dow
                else {
                    val k = -n // e.g. -1 = last, -2 = second-to-last
                    val last = lastOfMonthDow(anchor.year, anchor.month, dow)
                    val target = last.minusWeeks((k - 1).toLong())
                    ((target.dayOfMonth + 6) / 7) to dow
                }
            }
        }
    }

    private fun lastOfMonthDow(year: Int, month: Month, dow: DayOfWeek): LocalDate {
        var d = LocalDate.of(year, month, 1).with(TemporalAdjusters.lastDayOfMonth())
        while (d.dayOfWeek != dow) d = d.minusDays(1)
        return d
    }

    private fun dayOfWeek(code: String): DayOfWeek? = when (code.uppercase()) {
        "SU", "SUN" -> DayOfWeek.SUNDAY
        "MO", "MON" -> DayOfWeek.MONDAY
        "TU", "TUE" -> DayOfWeek.TUESDAY
        "WE", "WED" -> DayOfWeek.WEDNESDAY
        "TH", "THU" -> DayOfWeek.THURSDAY
        "FR", "FRI" -> DayOfWeek.FRIDAY
        "SA", "SAT" -> DayOfWeek.SATURDAY
        else -> null
    }

    private fun parseEventTime(
        value: String,
        params: Map<String, String>,
        vtimezones: Map<String, VTimezoneRules>,
    ): EventTime? {
        val v = value.trim()
        if (params["VALUE"]?.uppercase() == "DATE" || v.length == 8) {
            return parseDate(v)?.let { EventTime(it, null, null, null) }
        }
        val date = parseDate(v) ?: return null
        val time = parseTimePart(v) ?: LocalTime.MIDNIGHT
        val zulu = v.endsWith("Z")
        val tzid = params["TZID"]?.unquote()
        return when {
            zulu -> EventTime(date, time, ZoneOffset.UTC, null)
            tzid != null -> {
                vtimezones[tzid]?.let { EventTime(date, time, null, it) }
                    ?: EventTime(date, time, runCatching { ZoneId.of(tzid) }.getOrNull() ?: ZoneOffset.UTC, null)
            }
            else -> EventTime(date, time, ZoneOffset.UTC, null, floating = true)
        }
    }

    private fun addDuration(start: EventTime, raw: String): EventTime? {
        val d = parseDuration(raw) ?: return null
        val totalDays = d.wholeDays + d.weeks.toLong() * 7
        val totalSeconds = totalDays * 86_400 + d.seconds
        if (start.allDay) {
            return EventTime(start.date.plusDays(totalDays), null, null, null)
        }
        return when {
            start.floating -> {
                val wall = LocalDateTime.of(start.date, start.time!!).plusSeconds(totalSeconds)
                EventTime(wall.toLocalDate(), wall.toLocalTime(), ZoneOffset.UTC, null, floating = true)
            }
            start.vtimeZone != null -> {
                val base = start.toInstant() ?: return null
                val newInstant = base.plusSeconds(totalSeconds)
                val wall = newInstant.atOffset(start.vtimeZone!!.offsetAt(newInstant)).toLocalDateTime()
                EventTime(wall.toLocalDate(), wall.toLocalTime(), null, start.vtimeZone)
            }
            else -> {
                val base = start.toInstant() ?: return null
                val zone = start.zoneId ?: ZoneOffset.UTC
                val wall = base.plusSeconds(totalSeconds).atZone(zone)
                EventTime(wall.toLocalDate(), wall.toLocalTime(), zone, null)
            }
        }
    }

    private data class DurationParts(val weeks: Int, val wholeDays: Long, val seconds: Long)

    private fun parseDuration(raw: String): DurationParts? {
        val m = Regex("^P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?")
            .find(raw.trim()) ?: return null
        if (m.value.isEmpty() || m.value == "P") return null
        val w = m.groupValues[1].toIntOrNull() ?: 0
        val d = m.groupValues[2].toLongOrNull() ?: 0L
        val h = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val mi = m.groupValues[4].toDoubleOrNull() ?: 0.0
        val s = m.groupValues[5].toDoubleOrNull() ?: 0.0
        return DurationParts(w, d, (h * 3600 + mi * 60 + s).toLong())
    }

    // ------------------------------------------------------------ rrule text

    private val RRULE_SIMPLE_KEYS = setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY")
    private val DOW_NAMES = mapOf(
        DayOfWeek.MONDAY to "Monday", DayOfWeek.TUESDAY to "Tuesday", DayOfWeek.WEDNESDAY to "Wednesday",
        DayOfWeek.THURSDAY to "Thursday", DayOfWeek.FRIDAY to "Friday", DayOfWeek.SATURDAY to "Saturday",
        DayOfWeek.SUNDAY to "Sunday",
    )

    /** Test hook around [summarizeRrule]. */
    internal fun summarizeForTest(raw: String): String? = summarizeRrule(raw)

    /** Human summary for the simple cases; null when the rule uses anything else. */
    private fun summarizeRrule(raw: String): String? {
        val params = LinkedHashMap<String, String>()
        for (p in raw.split(';').filter { it.contains('=') }) {
            val eq = p.indexOf('=')
            val k = p.substring(0, eq).trim().uppercase()
            if (k !in RRULE_SIMPLE_KEYS) return null
            params[k] = p.substring(eq + 1).trim()
        }
        val unit = when (params["FREQ"]?.uppercase()) {
            "DAILY" -> "day"; "WEEKLY" -> "week"; "MONTHLY" -> "month"; "YEARLY" -> "year"
            else -> return null
        }
        val interval = params["INTERVAL"]?.toIntOrNull() ?: 1
        val count = params["COUNT"]?.toIntOrNull()
        val until = params["UNTIL"]
        val byday = params["BYDAY"]?.split(',')?.firstOrNull()?.let { bydaySummary(it) }

        val sb = StringBuilder()
        sb.append(if (interval > 1) "every $interval ${unit}s" else "${unit}ly")
        byday?.let { sb.append(", on ").append(it) }
        until?.let { u ->
            val text = when {
                u.length == 8 -> parseDate(u)?.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
                u.length in 13..16 -> {
                    val d = parseDate(u) ?: return null
                    val t = parseTimePart(u)
                    if (t == null) d.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
                    else d.format(DateTimeFormatter.ofPattern("d MMM yyyy")) + " " +
                        t.format(DateTimeFormatter.ofPattern("HH:mm")) +
                        (if (u.endsWith("Z")) " UTC" else "")
                }
                else -> return null
            } ?: return null
            sb.append(", until ").append(text)
        }
        if (count != null) sb.append(", ").append(count).append(" times")
        return sb.toString()
    }

    private fun bydaySummary(token: String): String? {
        val m = Regex("^([+-]?\\d+)?([A-Z]{2,3})$").find(token.trim().uppercase()) ?: return null
        val dow = dayOfWeek(m.groupValues[2]) ?: return null
        val name = DOW_NAMES.getValue(dow)
        val ord = m.groupValues[1]
        return when {
            ord.isEmpty() -> "${name}s" // "Mondays"
            ord == "-1" -> "last $name"
            else -> "${ord}${ordinalSuffix(ord.toInt())} $name"
        }
    }

    private fun ordinalSuffix(n: Int): String {
        if (n in 11..13) return "th"
        return when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    }

    // ---------------------------------------------------------------- reply

    private val DTSTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HHmmss")

    /**
     * Builds the METHOD:REPLY calendar for an attendee response (RFC 5545): UID,
     * SEQUENCE and DTSTAMP are mandatory, plus the ORGANIZER and the responding
     * ATTENDEE with its PARTSTAT. Logical lines are folded at 75 octets and joined
     * with CRLF.
     */
    fun reply(invite: CalendarInvite, attendeeEmail: String, partstat: PartStat, now: Instant): String {
        val known = invite.attendees.firstOrNull { it.email.equals(attendeeEmail, ignoreCase = true) }
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "METHOD:REPLY",
            "PRODID:-//Dot Mail//Dot Mail 0.1//EN",
            "BEGIN:VEVENT",
            "UID:" + escapeText(invite.uid),
            "SEQUENCE:" + invite.sequence,
            "DTSTAMP:" + now.atZone(ZoneOffset.UTC).format(DTSTAMP_FORMAT),
            "ORGANIZER" + (invite.organizer.name?.let { ";CN=" + escapeParam(it) }.orEmpty()) +
                ":mailto:" + invite.organizer.email,
            "ATTENDEE" + (known?.name?.let { ";CN=" + escapeParam(it) }.orEmpty()) +
                ";ROLE=REQ-PARTICIPANT;PARTSTAT=" + partstat.name + ":mailto:" + attendeeEmail,
        )
        invite.status?.let { lines += "STATUS:" + it }
        if (invite.summary.isNotBlank()) lines += "SUMMARY:" + escapeText(invite.summary)
        if (invite.description.isNotBlank()) lines += "DESCRIPTION:" + escapeText(invite.description)
        if (invite.location.isNotBlank()) lines += "LOCATION:" + escapeText(invite.location)
        lines += serializeTime("DTSTART", invite.start)
        invite.end?.let { lines += serializeTime("DTEND", it) }
        invite.duration?.let { lines += "DURATION:" + it }
        invite.rrule?.let { lines += "RRULE:" + it }
        lines += "END:VEVENT"
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") { fold(it) }
    }

    private fun serializeTime(name: String, t: EventTime): String {
        val base = t.date.format(DateTimeFormatter.BASIC_ISO_DATE) +
            (t.time?.let { "T" + TIME_FORMAT.format(it) } ?: "")
        return when {
            t.allDay -> "$name;VALUE=DATE:$base"
            t.vtimeZone != null -> "$name;TZID=${t.vtimeZone!!.tzid}:$base"
            t.floating -> "$name:$base"
            t.zoneId == ZoneOffset.UTC -> "$name:${base}Z"
            else -> "$name;TZID=${t.zoneId!!.id}:$base"
        }
    }

    /** RFC 5545 text escaping. */
    private fun escapeText(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
        .replace("\r", "\\n")
        .replace(",", "\\,")
        .replace(";", "\\;")

    /** RFC 5545 parameter escaping (for CN etc.). */
    private fun escapeParam(s: String): String =
        if (s.any { it == ',' || it == ';' || it == '"' || it == ':' }) {
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else s

    /** Folds a logical line into physical lines of at most 74 octets + CRLF (continuation: CRLF + space). */
    private fun fold(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= 74) return line
        val out = StringBuilder(line.length + 16)
        val current = StringBuilder()
        var currentBytes = 0
        for (c in line) {
            val b = c.toString().toByteArray(Charsets.UTF_8).size
            if (currentBytes + b > 74) {
                out.append(current).append("\r\n ")
                current.clear()
                currentBytes = 0
            }
            current.append(c)
            currentBytes += b
        }
        out.append(current)
        return out.toString()
    }
}

/**
 * Rules materialised from a VTIMEZONE component. Fixed-offset zones collapse to
 * [standardOffset] alone ([savingsOffset] == null). DST zones use yearly rules:
 * DST is active from "the [startNth] [startDayOfWeek] of [startMonth] at [startTime]"
 * until the end rule. "Last X" BYDAY rules are converted to an absolute Nth via the
 * VTIMEZONE's anchor dates — good enough for invitations, not for tzdb archaeology.
 */
data class VTimezoneRules(
    val tzid: String,
    val standardOffset: ZoneOffset,
    val savingsOffset: ZoneOffset?,
    val startMonth: Int,
    val startNth: Int,
    val startDayOfWeek: DayOfWeek,
    val startTime: LocalTime,
    val endMonth: Int,
    val endNth: Int,
    val endDayOfWeek: DayOfWeek,
    val endTime: LocalTime,
) {
    /** Wall-clock -> instant. In a transition gap the pre-transition offset is used. */
    fun toInstant(date: LocalDate, time: LocalTime): Instant {
        val dt = LocalDateTime.of(date, time)
        return dt.toInstant(offsetAt(dt))
    }

    /** The offset in effect at [dt] (wall-clock). */
    fun offsetAt(dt: LocalDateTime): ZoneOffset {
        val savings = savingsOffset ?: return standardOffset
        val active = listOf(dt.year - 1, dt.year, dt.year + 1).any { y ->
            startWall(y) <= dt && dt < endWall(y)
        }
        return if (active) savings else standardOffset
    }

    /**
     * The offset in effect for a UTC instant (for shifting duration-math results back
     * to wall time). The UTC-based wall guess is within the offset band of the truth,
     * which is enough to land on the right side of any DST transition.
     */
    fun offsetAt(instant: Instant): ZoneOffset {
        val guess = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
        return offsetAt(guess)
    }

    private fun startWall(year: Int): LocalDateTime =
        nthDow(year, startMonth, startNth.coerceIn(1, 5), startDayOfWeek).atTime(startTime)

    private fun endWall(startYear: Int): LocalDateTime {
        val endYear = if (endMonth < startMonth) startYear + 1 else startYear
        return nthDow(endYear, endMonth, endNth.coerceIn(1, 5), endDayOfWeek).atTime(endTime)
    }
}

internal fun nthDow(year: Int, month: Int, nth: Int, dow: DayOfWeek): LocalDate {
    var d = LocalDate.of(year, month, 1)
    var count = 0
    while (count < nth) {
        if (d.dayOfWeek == dow) count++
        d = d.plusDays(1)
    }
    return d.minusDays(1)
}
