package app.dot.mail.engine.calendar

import java.io.File
import org.junit.Test

/** Temporary debug probe — deletes itself after the task. */
class DebugProbeTest {
    @Test
    fun debugDumps() {
        val sb = StringBuilder()
        fun s(x: Any?) { sb.appendLine(x) }

        val text = requireNotNull(javaClass.getResourceAsStream("/ics/outlook-request.ics")).bufferedReader().use { it.readText() }
        // replicate unfold+contentline for the interesting lines
        val unfolded = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .let { lines ->
                val out = mutableListOf<String>()
                for (l in lines) {
                    if (l.startsWith(" ") || l.startsWith("\t")) out[out.size - 1] = out[out.size - 1] + l.substring(1) else out.add(l)
                }
                out
            }
        for (l in unfolded) {
            if (l.startsWith("TZID:") || l.startsWith("DTSTART;TZID") || l.startsWith("DTEND;TZID")) {
                s("RAW: $l")
                val pl = Ics.debugParseContentLine(l)
                s("  name=${pl?.name} params=${pl?.params} value=${pl?.value}")
            }
        }
        val inv = Ics.parse(text).single()
        s("method=${inv.method}")
        s("start: zoneId=${inv.start.zoneId} vtz=${inv.start.vtimeZone} floating=${inv.start.floating} zoneName=${inv.start.zoneName}")
        s("startInstant=${inv.start.toInstant()}")
        s("attendees=${inv.attendees}")
        s("organizer=${inv.organizer}")
        s("summary=${inv.summary}")
        File("build/reports/debug-ics.txt").writeText(sb.toString())
    }
}
