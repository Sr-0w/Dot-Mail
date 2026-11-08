package app.dot.mail.engine.text

import java.io.ByteArrayOutputStream

/**
 * A parsed `mailto:` unsubscribe target. [address] may be a comma-separated
 * recipient list, as mailto allows; [subject] and [body] are percent-decoded,
 * null when absent (an empty value is also null).
 */
data class MailtoTarget(val address: String, val subject: String?, val body: String?)

/**
 * Usable unsubscribe options extracted from a message's List-Unsubscribe
 * (RFC 2369), List-Unsubscribe-Post (RFC 8058) and List-Id headers.
 *
 * - [oneClickHttps] is set ONLY when List-Unsubscribe-Post requests
 *   `List-Unsubscribe=One-Click` (case-insensitive, whitespace tolerant) AND an
 *   https URI is present — http never qualifies for one-click.
 * - [webPage] is the first https URI, falling back to the first http URI only
 *   when no https exists (never downgraded when both are offered); set only
 *   when NOT in one-click mode.
 * - [mailto] is the first usable `mailto:` target.
 * - [parse] returns null when nothing usable is found: no mailto target, no
 *   web/one-click URL and no list id.
 */
data class UnsubscribeOptions(
    val oneClickHttps: String?,
    val mailto: MailtoTarget?,
    val webPage: String?,
    val listId: String?,
)

/**
 * RFC 2369 / RFC 8058 List-Unsubscribe parsing, pure JVM.
 *
 * Forgiving on purpose: folded header values, multiple `<>` entries, comments,
 * junk between entries, and case differences are all handled. Hostile on
 * purpose: URIs longer than [MAX_URI_LENGTH] characters, entries with
 * non-ASCII bytes, and URL entries containing whitespace are rejected;
 * `mailto:` entries tolerate raw whitespace in subject/body (common in the
 * wild) but not in the address.
 */
object ListUnsubscribe {

    private const val MAX_URI_LENGTH = 2048

    /** The exact request body a client POSTs to [UnsubscribeOptions.oneClickHttps]. */
    fun oneClickRequestBody(): String = "List-Unsubscribe=One-Click"

    /**
     * Parses the unsubscribe-related headers. [headers] maps raw header names
     * (any casing) to their raw values in order; each value may still contain
     * line folding (CRLF/LF + space/tab).
     */
    fun parse(headers: Map<String, List<String>>): UnsubscribeOptions? {
        var mailto: MailtoTarget? = null
        var https: String? = null
        var http: String? = null
        for (entry in valuesFor(headers, "List-Unsubscribe").flatMap { cleanEntries(it) }) {
            when {
                entry.startsWith("mailto:", true) -> if (mailto == null) mailto = parseMailto(entry)
                entry.startsWith("https://", true) -> if (https == null && !entry.hasWhitespace()) https = entry
                entry.startsWith("http://", true) -> if (http == null && !entry.hasWhitespace()) http = entry
            }
        }
        val oneClick = valuesFor(headers, "List-Unsubscribe-Post").any { isOneClickRequest(it) }
        val listId = valuesFor(headers, "List-Id").mapNotNull { parseListId(it) }.firstOrNull()

        return UnsubscribeOptions(
            oneClickHttps = if (oneClick) https else null,
            mailto = mailto,
            webPage = if (!oneClick) https ?: http else null,
            listId = listId,
        ).takeIf { it.oneClickHttps != null || it.mailto != null || it.webPage != null || it.listId != null }
    }

    // ------------------------------------------------------------ headers

    private fun valuesFor(headers: Map<String, List<String>>, name: String): List<String> =
        headers.entries.flatMap { (key, values) ->
            if (key.equals(name, ignoreCase = true)) values else emptyList()
        }

    /** Unfolds a folded header value (CRLF/LF/CR followed by space or tab). */
    private fun unfold(value: String): String = value
        .replace(Regex("\r\n[ \t]"), "")
        .replace(Regex("\n[ \t]"), "")
        .replace(Regex("\r[ \t]"), "")

    /**
     * RFC 5322 comment removal: balanced `(...)` spans (nesting and backslash
     * escapes inside comments honoured) are deleted.
     */
    private fun stripComments(s: String): String {
        val out = StringBuilder(s.length)
        var depth = 0
        var i = 0
        while (i < s.length) {
            when {
                depth > 0 && s[i] == '\\' && i + 1 < s.length -> i += 2 // escaped char inside comment
                depth > 0 && s[i] == ')' -> { depth--; i++ }
                depth > 0 -> i++ // comment content: dropped
                s[i] == '(' -> { depth++; i++ }
                else -> { out.append(s[i]); i++ }
            }
        }
        return out.toString()
    }

    /**
     * The candidate entries of one List-Unsubscribe value: the content of each
     * `<...>` span when any angle brackets are present, otherwise the
     * comma-separated pieces. Comments are stripped first, `NONE` yields no
     * entries, and each candidate is trimmed and checked (length, ASCII-only).
     */
    private fun cleanEntries(raw: String): List<String> {
        val v = stripComments(unfold(raw)).trim()
        if (v.isEmpty() || v.equals("NONE", ignoreCase = true)) return emptyList()
        val candidates = if (v.contains('<')) {
            val out = mutableListOf<String>()
            var i = 0
            while (i < v.length) {
                val lt = v.indexOf('<', i)
                if (lt < 0) break
                val gt = v.indexOf('>', lt + 1)
                if (gt < 0) break
                out.add(v.substring(lt + 1, gt).trim())
                i = gt + 1
            }
            out
        } else {
            v.split(',')
        }
        return candidates.map { it.trim() }.filter { entry ->
            entry.isNotEmpty() && entry.length <= MAX_URI_LENGTH && entry.none { it.code > 0x7F }
        }
    }

    private fun String.hasWhitespace(): Boolean = any { it.isWhitespace() }

    /** `List-Unsubscribe=One-Click`, ignoring case and any whitespace placement. */
    private fun isOneClickRequest(raw: String): Boolean =
        unfold(raw).replace(Regex("\\s"), "").equals("List-Unsubscribe=One-Click", ignoreCase = true)

    /**
     * The List-Id value: the content of `<...>` when present (RFC 2369's
     * "listname <list@host>" form), otherwise the whole comment-stripped value.
     */
    private fun parseListId(raw: String): String? {
        val v = stripComments(unfold(raw)).trim()
        if (v.isEmpty()) return null
        val lt = v.indexOf('<')
        val gt = v.lastIndexOf('>')
        val id = if (lt in 0 until gt - 1) v.substring(lt + 1, gt) else v
        val cleaned = stripComments(id).trim()
        return cleaned.ifEmpty { null }?.takeIf { !it.contains('<') && !it.contains('>') }
    }

    // ------------------------------------------------------------- mailto

    private fun parseMailto(entry: String): MailtoTarget? {
        // "mailto:" was already matched case-insensitively by the caller
        val rest = entry.substring(7)
        val q = rest.indexOf('?')
        val address = (if (q >= 0) rest.substring(0, q) else rest).trim()
        if (address.isEmpty() || address.hasWhitespace()) return null
        var subject: String? = null
        var body: String? = null
        if (q >= 0) {
            for (param in rest.substring(q + 1).split('&')) {
                val eq = param.indexOf('=')
                if (eq <= 0) continue
                val name = param.substring(0, eq).trim().lowercase()
                val value = percentDecode(param.substring(eq + 1))
                when (name) {
                    "subject" -> if (subject == null) subject = value.ifEmpty { null }
                    "body" -> if (body == null) body = value.ifEmpty { null }
                }
            }
        }
        return MailtoTarget(address, subject, body)
    }

    /** Percent-decoding as bytes (UTF-8), leaving malformed sequences as-is. */
    private fun percentDecode(s: String): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
                out.write(hexVal(s[i + 1]) * 16 + hexVal(s[i + 2]))
                i += 3
            } else {
                out.write(s[i].code)
                i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f'

    private fun hexVal(c: Char): Int = when {
        c in '0'..'9' -> c - '0'
        c in 'A'..'F' -> c - 'A' + 10
        else -> c - 'a' + 10
    }
}
