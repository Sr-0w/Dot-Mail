package app.dot.mail.engine.text

import java.net.IDN

/** Host-level view of a link, as produced by [Links.describe]. */
data class LinkInfo(
    /** Host in Unicode form: punycode labels are decoded via [IDN.toUnicode]. */
    val displayHost: String,
    /**
     * True when the (decoded) host mixes Latin letters with Cyrillic or Greek
     * ones, or when a punycode label decodes to non-Latin characters. Labels that
     * cannot be decoded at all are treated as suspicious too.
     */
    val isIdnHomographRisk: Boolean,
    /** True when the URL uses https. */
    val isHttps: Boolean,
)

/**
 * Link hygiene for mail bodies. Pure JVM, no dependencies:
 *
 *  - [stripTracking] removes well-known tracking query parameters from http(s) URLs.
 *  - [describe] extracts the host, decodes punycode for display, and flags IDN
 *    homograph risks (Latin mixed with Cyrillic/Greek, or punycode decoding to
 *    non-Latin).
 *  - [textMismatch] detects link text that looks like a URL/domain whose
 *    registrable host differs from the href's host.
 */
object Links {

    // ------------------------------------------------------ stripTracking

    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "mc_cid", "mc_eid",
        "_hsenc", "_hsmi", "mkt_tok", "igshid", "yclid", "oly_anon_id", "oly_enc_id",
        "vero_id", "__s", "ref_src",
    )

    /**
     * Removes tracking query parameters (`utm_*` and a set of known ids) from an
     * http(s) URL, case-insensitively, preserving the order and encoding of the
     * remaining parameters and the fragment. Non-http(s) URLs and URLs without a
     * query are returned unchanged; when every parameter is removed the `?` goes
     * with it.
     */
    fun stripTracking(url: String): String {
        val lower = url.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return url

        val fragIdx = url.indexOf('#')
        val head = if (fragIdx >= 0) url.substring(0, fragIdx) else url
        val fragment = if (fragIdx >= 0) url.substring(fragIdx) else ""
        val qIdx = head.indexOf('?')
        if (qIdx < 0) return url

        val before = head.substring(0, qIdx)
        val kept = head.substring(qIdx + 1).split('&').filter { token ->
            token.isNotEmpty() && !isTrackingParam(token.substringBefore('='))
        }
        return when {
            kept.isEmpty() -> before + fragment
            else -> before + "?" + kept.joinToString("&") + fragment
        }
    }

    private fun isTrackingParam(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("utm_") || n in TRACKING_PARAMS
    }

    // ----------------------------------------------------------- describe

    /**
     * Describes the host of an http(s) URL: its Unicode display form, its
     * homograph risk, and whether it is https. Returns null for anything that is
     * not an http(s) URL with a host (other schemes, no authority, ...).
     */
    fun describe(url: String): LinkInfo? {
        val lower = url.lowercase()
        val isHttps = lower.startsWith("https://")
        if (!isHttps && !lower.startsWith("http://")) return null
        val host = hostOf(url) ?: return null
        val hadPunycode = host.split('.').any { it.startsWith("xn--", ignoreCase = true) }
        val decoded = runCatching { IDN.toUnicode(host) }.getOrNull()
        // JDK does not throw for every invalid punycode label (e.g. "xn--" comes
        // back unchanged) — treat any label still in punycode form after decoding
        // as undecodable and therefore suspicious.
        val undecodable = decoded == null ||
            decoded.split('.').any { it.startsWith("xn--", ignoreCase = true) }
        return LinkInfo(
            displayHost = decoded ?: host,
            isIdnHomographRisk = if (undecodable) true else hostRisky(decoded!!, hadPunycode),
            isHttps = isHttps,
        )
    }

    private fun hostRisky(host: String, hadPunycode: Boolean): Boolean {
        var mask = 0
        for (c in host) mask = mask or scriptMask(c)
        val latin = mask and LATIN != 0
        val cyrillicOrGreek = mask and (CYRILLIC or GREEK) != 0
        // Latin mixed with Cyrillic/Greek (e.g. "еxample.com" with a Cyrillic e)
        val mixed = latin && cyrillicOrGreek
        // ... or a punycode label that decodes to non-Latin, even without Latin
        return mixed || (hadPunycode && cyrillicOrGreek)
    }

    private const val LATIN = 1
    private const val CYRILLIC = 2
    private const val GREEK = 4

    /** Coarse script bucket for the ranges that matter for Latin homographs. */
    private fun scriptMask(c: Char): Int {
        val v = c.code
        return when {
            v in 0x41..0x5A || v in 0x61..0x7A || v in 0xC0..0x24F || v in 0x1E00..0x1EFF -> LATIN
            v in 0x400..0x52F || v in 0x2DE0..0x2DFF || v in 0xA640..0xA69F -> CYRILLIC
            v in 0x370..0x3FF || v in 0x1F00..0x1FFF -> GREEK
            else -> 0
        }
    }

    // -------------------------------------------------------- textMismatch

    /** 2-level public suffixes that push the registrable domain to 3 labels. */
    private val TWO_LEVEL_SUFFIXES = setOf(
        "co.uk", "org.uk", "me.uk", "net.uk", "gov.uk", "ac.uk",
        "com.au", "net.au", "org.au", "gov.au", "edu.au", "id.au",
        "co.nz", "org.nz", "co.za",
        "com.br", "com.mx", "com.ar", "com.co",
        "co.in", "com.cn", "com.hk", "com.sg", "com.tr", "com.my",
    )

    /**
     * True when [linkText] looks like a URL or bare domain and its registrable
     * host (last two labels, or last three for `co.uk`/`com.au`-style suffixes)
     * differs from the registrable host of [href]. Prose link text, and texts
     * without a registrable host, never mismatch.
     */
    fun textMismatch(linkText: String, href: String): Boolean {
        val textHost = visibleHostOf(linkText) ?: return false
        val hrefHost = hostOf(href) ?: return false
        val a = registrable(textHost) ?: return false
        val b = registrable(hrefHost) ?: return false
        return a != b
    }

    private fun registrable(host: String): String? {
        val labels = host.lowercase().trimEnd('.').split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return null
        val last2 = labels.takeLast(2).joinToString(".")
        return if (last2 in TWO_LEVEL_SUFFIXES && labels.size >= 3) labels.takeLast(3).joinToString(".")
        else last2
    }

    /**
     * The host of link text that looks like a URL or bare domain: any scheme is
     * dropped, then the authority up to the first `/`, `?` or `#` is taken,
     * userinfo, port and a trailing dot removed. Text containing whitespace is
     * prose, not a URL → null.
     */
    private fun visibleHostOf(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        var rest = t
        val scheme = rest.indexOf("://")
        if (scheme >= 0) rest = rest.substring(scheme + 3)
        else if (rest.startsWith("//")) rest = rest.substring(2)
        return authorityHostOf(rest)
    }

    /** Authority → host: cuts at the first `/`, `?`, `#`, drops userinfo and port. */
    private fun authorityHostOf(rest: String): String? {
        val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (cut >= 0) rest.substring(0, cut) else rest
        if (authority.isEmpty()) return null
        val at = authority.lastIndexOf('@')
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        if (hostPort.any { it == '[' || it == ']' }) return null // IPv6 literal: no hostname
        val colon = hostPort.indexOf(':')
        val host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
        return host.ifEmpty { null }
    }

    /** The host part of a URL: after the first `://`, up to the first `/`, `?` or `#`. */
    private fun hostOf(url: String): String? {
        val sep = url.indexOf("://")
        if (sep < 0) return null
        return authorityHostOf(url.substring(sep + 3))
    }
}
