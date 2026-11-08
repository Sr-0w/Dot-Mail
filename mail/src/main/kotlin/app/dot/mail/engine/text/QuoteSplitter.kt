package app.dot.mail.engine.text

/**
 * A reply message split into the part the user actually wrote ([visible]) and the
 * trailing quoted material ([quoted]).
 *
 * [quoted] is `null` when no quoted tail was detected — and when the *entire* input
 * is quoted, in which case [visible] is the whole text: the splitter never hides
 * everything.
 */
data class QuoteSplit(val visible: String, val quoted: String?)

/**
 * Detects the quoted tail of a plain-text reply.
 *
 * Recognised quote starts (the earliest one in the text wins):
 *  - lines beginning with `>` (Gmail/Thunderbird style, any nesting depth `>>`, `>>>`);
 *  - "wrote" headers: `On … wrote:`, `Le … a écrit :` (French), `Am … schrieb …:` (German);
 *  - the Outlook block `-----Original Message-----` (the whole block, including its
 *    `From:`/`Sent:` header lines, becomes the quoted tail).
 *
 * The PGP/Mutt signature delimiter `-- ` is *not* a quote marker: a signature stays
 * part of [visible].
 *
 * Line endings `\n`, `\r\n` and `\r` are all handled.
 */
object QuoteSplitter {

    fun split(text: String): QuoteSplit {
        val lines = splitLines(text)
        val quoteStart = lines.indexOfFirst { isQuoteStart(it) }
        if (quoteStart < 0) return QuoteSplit(text.trimEnd(), null)

        val visiblePart = lines.subList(0, quoteStart).joinToString("\n")
        if (visiblePart.isBlank()) {
            // Never hide everything: if the whole message is quoted (or only
            // whitespace precedes the quote), show the full text.
            return QuoteSplit(text, null)
        }
        val quotedPart = lines.subList(quoteStart, lines.size).joinToString("\n")
        return QuoteSplit(visiblePart.trimEnd(), quotedPart)
    }

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return text.split('\n').map { it.removeSuffix("\r") }
    }

    private fun isQuoteStart(line: String): Boolean {
        // `>` markers must start at column 0 (a `>` mid-line is just text).
        if (line.startsWith(">")) return true
        if (WROTE_HEADER.matches(line)) return true
        if (ORIGINAL_MESSAGE.matches(line)) return true
        return false
    }

    /**
     * `On <date>, <sender> wrote:`, `Le <date> … a écrit :` and `Am <date> … schrieb …:`
     * — case-insensitive, trailing colon optional.
     */
    private val WROTE_HEADER = Regex(
        "(?i)^(?:on\\s.*\\swrote|le\\s.*\\sécrit|am\\s.*\\bschrieb.*)\\s*:?\\s*$"
    )

    /** Outlook's `-----Original Message-----` separator line. */
    private val ORIGINAL_MESSAGE = Regex("(?i)^-----+\\s*Original Message\\s*-----+$")
}
