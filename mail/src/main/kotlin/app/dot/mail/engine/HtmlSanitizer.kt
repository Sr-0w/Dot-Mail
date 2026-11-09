package app.dot.mail.engine

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import java.util.Locale

/** Defense in depth. The renderer must also disable JS and intercept ALL network requests. */
object HtmlSanitizer {
    /**
     * @property layoutWidth widest fixed pixel width the email's layout asks for (tables, cells, images,
     *   CSS `width`/`min-width`), or 0 when it has none. The reader uses it to scale fixed-width designs
     *   to fit the screen instead of clipping them.
     * @property responsive the email ships `@media (max-width…)` rules, i.e. it adapts to narrow screens
     *   by itself and should be laid out at the device width.
     */
    data class Result(
        val html: String,
        val hasRemoteImages: Boolean,
        val blockedRemoteResources: Int,
        val layoutWidth: Int = 0,
        val responsive: Boolean = false,
        /** Tracking pixels removed even though remote images were allowed. */
        val trackersBlocked: Int = 0,
        /** href → visible text for links whose text looks like a different site than where they go. */
        val suspiciousLinks: Map<String, String> = emptyMap(),
    )

    private val tags = arrayOf("a", "abbr", "b", "blockquote", "br", "caption", "center", "code", "col", "colgroup", "dd", "del", "div", "dl", "dt", "em", "font", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "img", "li", "ol", "p", "pre", "s", "small", "span", "strong", "sub", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "u", "ul")

    // Deliberately value-restrict CSS: no functions (except plain rgb()/rgba() colours), escapes, comments,
    // custom properties or URLs, and nothing that positions content outside the message flow.
    private val properties = setOf(
        "color", "background", "background-color", "font", "font-family", "font-size", "font-weight", "font-style", "font-variant",
        "text-align", "text-decoration", "text-transform", "text-indent", "line-height", "letter-spacing", "word-spacing",
        "white-space", "word-break", "word-wrap", "overflow-wrap", "vertical-align",
        "border", "border-color", "border-width", "border-style", "border-collapse", "border-spacing", "border-radius",
        "border-top", "border-right", "border-bottom", "border-left",
        "padding", "padding-top", "padding-bottom", "padding-left", "padding-right",
        "margin", "margin-top", "margin-bottom", "margin-left", "margin-right",
        "width", "height", "max-width", "min-width", "max-height", "min-height",
        "display", "float", "clear", "table-layout", "box-sizing", "direction", "list-style-type",
        "background-size", "background-position", "background-repeat",
    )
    /**
     * `overflow` and `text-overflow` with keyword values: newsletters pair `white-space:nowrap` with `overflow:hidden;
     * text-overflow:ellipsis` on product titles. Without them the titles spill over their neighbours and widen the
     * layout. No scroll containers, no custom strings.
     */
    private val clippingProperties = mapOf(
        "overflow" to setOf("hidden", "visible", "clip"),
        "overflow-x" to setOf("hidden", "visible", "clip"),
        "overflow-y" to setOf("hidden", "visible", "clip"),
        "text-overflow" to setOf("ellipsis", "clip"),
    )
    private val colourFunction = Regex("(?i)rgba?\\(\\s*[0-9.]+%?\\s*[, ]\\s*[0-9.]+%?\\s*[, ]\\s*[0-9.]+%?\\s*([,/]\\s*[0-9.]+%?\\s*)?\\)")
    private val safeValue = Regex("[a-zA-Z0-9#.,%\\s'\"+/-]+")
    private val negativeNumber = Regex("(?<![a-zA-Z0-9_-])-\\s*\\.?[0-9]")
    private val remoteCssPattern = Regex("(?i)url\\s*\\(|image-set\\s*\\(|@import|@font-face|\\\\")

    private fun hasBalancedQuotes(s: String): Boolean {
        var activeQuote = '\u0000'
        for (ch in s) {
            if (ch == '"' || ch == '\'') {
                when (activeQuote) {
                    '\u0000' -> activeQuote = ch
                    ch -> activeQuote = '\u0000'
                    else -> return false // nested opposite quote
                }
            }
        }
        return activeQuote == '\u0000'
    }

    private fun hasBalancedParens(s: String): Boolean {
        var depth = 0
        var prev = '\u0000'
        for (ch in s) {
            if (ch == '(') {
                depth++
            } else if (ch == ')') {
                if (prev == '(') return false // empty ()
                depth--
                if (depth < 0) return false
            }
            if (!ch.isWhitespace()) prev = ch
        }
        return depth == 0
    }

    /**
     * Strips CSS block comments with string awareness.
     * Returns `null` if comment delimiters appear inside quoted strings or a stray close-comment appears outside a comment.
     * Truncates cleanly if an unclosed block comment reaches EOF.
     */
    private fun stripCssComments(css: String): String? {
        val sb = StringBuilder(css.length)
        var i = 0
        var quote = '\u0000'
        while (i < css.length) {
            val c = css[i]
            val next = if (i + 1 < css.length) css[i + 1] else '\u0000'
            if (quote != '\u0000') {
                if (c == '/' && next == '*') return null
                if (c == '*' && next == '/') return null
                if (c == quote) quote = '\u0000'
                sb.append(c)
                i++
            } else if (c == '"' || c == '\'') {
                quote = c
                sb.append(c)
                i++
            } else if (c == '/' && next == '*') {
                i += 2
                val close = css.indexOf("*/", i)
                if (close < 0) break // unclosed comment truncates remaining stylesheet
                // Reject if the comment body itself tries to smuggle quotes that pair with outside quotes
                sb.append(' ')
                i = close + 2
            } else if (c == '*' && next == '/') {
                return null
            } else {
                sb.append(c)
                i++
            }
        }
        return if (quote == '\u0000') sb.toString() else null
    }

    /** One `property:value` declaration, or null when it isn't on the allow-list. */
    private fun declaration(raw: String): String? {
        if ("/*" in raw || "*/" in raw) return null
        val pair = raw.split(':', limit = 2)
        if (pair.size != 2) return null
        val key = pair[0].trim().lowercase(Locale.ROOT)
        var value = pair[1].trim()
        val important = value.endsWith("!important", ignoreCase = true)
        if (important) value = value.dropLast("!important".length).trim()
        if (key in clippingProperties) {
            // Clipping only ever hides overflow inside the element's own box; keyword values only.
            return if (value.lowercase(Locale.ROOT) in clippingProperties.getValue(key)) "$key:${value.lowercase(Locale.ROOT)}" + if (important) " !important" else "" else null
        }
        if (key !in properties || value.isEmpty() || value.length > MAX_VALUE_CHARS) return null
        if ('"' in value || '\'' in value) {
            if (key != "font-family" && key != "font") return null
            if (!hasBalancedQuotes(value)) return null
        }
        if (key != "letter-spacing" && key != "word-spacing" && negativeNumber.containsMatchIn(value)) return null
        if (!colourFunction.replace(value, "c").matches(safeValue)) return null
        return "$key:$value" + if (important) " !important" else ""
    }

    private fun declarations(block: String): String =
        block.split(';').take(MAX_DECLARATIONS_PER_RULE).mapNotNull(::declaration).joinToString(";")

    // Allow only element, .class, #id, *, descendant, child (>), adjacent (+), and comma selectors.
    // No attribute selectors ([...]), pseudo-classes (:has, :nth-child), quotes, or parentheses.
    private val safeSelector = Regex("[a-zA-Z0-9\\s.#,_*>+-]+")
    private val safeMedia = Regex("(?i)^@media(?:\\s+[a-z0-9]+|\\s*\\()[a-z0-9\\s(),:.-]*$")

    /**
     * Rebuilds a stylesheet from scratch, keeping only plain style rules (allow-listed declarations) and
     * `@media` blocks around them. Everything else — `@import`, `@font-face`, `@keyframes`, escapes, comments,
     * markup-looking content — is dropped. Returns an empty string when nothing survives.
     */
    internal fun sanitizeStylesheet(css: String): String {
        if (css.isEmpty() || '\\' in css || '<' in css || "-->" in css || '\u0000' in css) return ""
        val bounded = if (css.length > MAX_STYLE_TAG_CHARS) css.substring(0, MAX_STYLE_TAG_CHARS) else css
        val text = stripCssComments(bounded) ?: return ""
        val out = StringBuilder()
        var ruleCount = 0
        fun rules(from: Int, to: Int, depth: Int) {
            var i = from
            while (i < to && ruleCount < MAX_CSS_RULES && out.length < MAX_TOTAL_CSS_CHARS) {
                val brace = text.indexOf('{', i).takeIf { it in i until to } ?: return
                val semi = text.indexOf(';', i).takeIf { it in i until brace }
                if (semi != null && text.substring(i, semi).trim().startsWith("@")) { i = semi + 1; continue } // @import/@charset…
                val prelude = text.substring(i, brace).trim()
                // Matching close brace for this block.
                var level = 1
                var j = brace + 1
                while (j < to && level > 0) { if (text[j] == '{') level++ else if (text[j] == '}') level--; j++ }
                if (level != 0) return
                val inner = brace + 1 until j - 1
                when {
                    prelude.startsWith("@media", true) && depth == 0 && prelude.length <= MAX_SELECTOR_CHARS &&
                        prelude.matches(safeMedia) && hasBalancedParens(prelude) -> {
                        val mark = out.length
                        out.append(prelude.replace(Regex("\\s+"), " ")).append('{')
                        val before = out.length
                        rules(inner.first, inner.last + 1, depth + 1)
                        if (out.length == before) out.setLength(mark) else out.append('}')
                    }
                    prelude.startsWith("@") -> Unit
                    prelude.isNotEmpty() && prelude.length <= MAX_SELECTOR_CHARS && prelude.matches(safeSelector) -> {
                        val body = declarations(text.substring(inner.first, inner.last + 1))
                        if (body.isNotEmpty()) {
                            val ruleStr = prelude.replace(Regex("\\s+"), " ") + "{" + body + "}"
                            if (out.length + ruleStr.length <= MAX_TOTAL_CSS_CHARS) {
                                out.append(ruleStr)
                                ruleCount++
                            }
                        }
                    }
                }
                i = j
            }
        }
        rules(0, text.length, 0)
        return out.toString()
    }

    /**
     * [blockTrackers]: email-open tracking pixels are removed even when [allowRemoteImages] is on (the default). Turned off
     * they are treated like any other remote image: blocked unless remote images are allowed.
     */
    fun sanitize(input: String, allowRemoteImages: Boolean = false, cidUris: Map<String, String> = emptyMap(), blockTrackers: Boolean = true): Result {
        val doc = Jsoup.parse(input)
        doc.outputSettings().prettyPrint(false)
        var remote = false
        var blocked = 0
        var trackers = 0
        val backgrounds = ArrayList<String>()
        val sheets = StringBuilder()
        for (el in doc.allElements) {
            val style = el.attr("style")
            // Cell/table backgrounds (the legacy `background=` attribute or an inline background url) are pictures
            // like any <img>: same remote-image gate, same tracker filter. A marker class lets us re-attach a clean
            // `background-image` after the cleaner has run; only plain http(s) URLs without quotes/escapes qualify.
            val bgUrl = if (el.tagName() in backgroundTags) backgroundUrl(el.attr("background"), style) else null
            if (bgUrl != null) {
                remote = true
                when {
                    blockTrackers && isTrackerUrl(bgUrl) -> trackers++
                    allowRemoteImages -> { el.addClass("$BG_MARKER${backgrounds.size}"); backgrounds += httpsOnly(bgUrl) }
                    else -> blocked++
                }
            } else if (el.hasAttr("background")) { remote = true; blocked++ }
            if (el.hasAttr("srcset")) { remote = true; blocked++ }
            if (bgUrl == null && style.isNotEmpty() && containsRemoteCss(style)) { remote = true; blocked++ }
            if (el.tagName() == "style") {
                val cssData = el.data()
                if (containsRemoteCss(cssData)) { remote = true; blocked++ }
                if (sheets.length < MAX_TOTAL_CSS_CHARS) {
                    val sanitizedCss = sanitizeStylesheet(cssData)
                    if (sheets.length + sanitizedCss.length <= MAX_TOTAL_CSS_CHARS) {
                        sheets.append(sanitizedCss)
                    }
                }
            }
            if (el.tagName() == "img") {
                val src = el.attr("src").trim()
                if (src.startsWith("cid:", true)) {
                    val uri = cidUris[src.substring(4).trim('<', '>')]
                    // Only app-supplied content URIs can survive; raw file paths / data / network cannot.
                    if (uri != null && uri.startsWith("content://")) el.attr("src", uri) else el.removeAttr("src")
                } else if (src.isNotEmpty()) {
                    remote = true
                    if (blockTrackers && isTrackingPixel(el, src)) { el.remove(); trackers++; continue }
                    if (!allowRemoteImages || !Regex("(?i)^https?://[^\\s]+$").matches(src)) { el.removeAttr("src"); blocked++ }
                    else el.attr("src", httpsOnly(src))
                }
            }
            el.removeAttr("srcset").removeAttr("background")
            if (style.isNotEmpty()) el.attr("style", declarations(style))
        }
        val safe = Safelist().addTags(*tags)
            .addAttributes(":all", "style", "title", "dir", "lang", "class", "id", "align")
            .addAttributes("a", "href").addProtocols("a", "href", "https", "http", "mailto")
            .addAttributes("img", "src", "alt", "width", "height", "border").addProtocols("img", "src", "content")
            .addAttributes("td", "colspan", "rowspan", "valign", "width", "height", "bgcolor")
            .addAttributes("th", "colspan", "rowspan", "valign", "width", "height", "bgcolor")
            .addAttributes("tr", "valign", "bgcolor")
            .addAttributes("table", "width", "height", "cellpadding", "cellspacing", "border", "bgcolor")
            .addAttributes("col", "width", "span").addAttributes("colgroup", "width", "span")
            .addAttributes("font", "color", "face", "size")
        if (allowRemoteImages) safe.addProtocols("img", "src", "https", "http")
        val cleaned = Cleaner(safe).clean(doc)
        cleaned.outputSettings().prettyPrint(false)
        cleaned.select("a").attr("rel", "noreferrer noopener")
        val suspicious = LinkedHashMap<String, String>()
        cleaned.select("a[href]").forEach { a ->
            val href = a.attr("href"); val text = a.text().trim()
            if (text.isNotEmpty() && suspicious.size < 200 && app.dot.mail.engine.text.Links.textMismatch(text, href)) suspicious.putIfAbsent(href, text.take(120))
        }
        backgrounds.forEachIndexed { i, url ->
            cleaned.select(".$BG_MARKER$i").forEach { el ->
                el.removeClass("$BG_MARKER$i")
                val own = el.attr("style").trim().trimEnd(';')
                el.attr("style", (if (own.isEmpty()) "" else "$own;") + "background-image:url(\"$url\")")
            }
        }
        cleaned.select("[class]").forEach { el -> el.classNames().filter { it.startsWith(BG_MARKER) }.forEach(el::removeClass) }
        // Only colour values survive in bgcolor; ids and classes can't collide with anything we inject.
        cleaned.select("[bgcolor]").forEach { if (!it.attr("bgcolor").matches(Regex("#?[a-zA-Z0-9]{1,20}"))) it.removeAttr("bgcolor") }
        val responsive = Regex("(?i)@media[^{]*max-width").containsMatchIn(sheets)
        val layout = layoutWidth(cleaned)
        if (responsive) makeFixedWidthsFluid(cleaned)
        val bodyColour = doc.body().attr("bgcolor").takeIf { it.matches(Regex("#?[a-zA-Z0-9]{1,20}")) }
        val stylesheet = sheets.toString()
        val body = cleaned.body().html()
        val html = buildString {
            if (stylesheet.isNotEmpty()) append("<style>").append(stylesheet).append("</style>")
            if (bodyColour != null) append("<div style=\"background-color:").append(bodyColour).append("\">").append(body).append("</div>") else append(body)
        }
        return Result(
            html = html,
            hasRemoteImages = remote,
            blockedRemoteResources = blocked,
            layoutWidth = layout,
            responsive = responsive,
            trackersBlocked = trackers,
            suspiciousLinks = suspicious,
        )
    }

    private val pxWidth = Regex("(?i)(?:^|;)\\s*(?:min-)?width\\s*:\\s*([0-9]{2,4})(?:\\.[0-9]+)?\\s*(?:px)?\\s*(?:!important)?\\s*(?:;|$)")

    /** Widest fixed width in CSS pixels the layout requests (capped), 0 if it is fluid. */
    private fun layoutWidth(doc: Document): Int {
        var max = 0
        for (el in doc.body().allElements) {
            when (el.tagName()) {
                "table", "td", "th", "img", "col", "colgroup" -> el.attr("width").trim().removeSuffix("px").toIntOrNull()?.let { max = maxOf(max, it) }
            }
            pxWidth.findAll(el.attr("style")).forEach { m -> m.groupValues[1].toIntOrNull()?.let { max = maxOf(max, it) } }
        }
        return max.coerceAtMost(MAX_LAYOUT_WIDTH)
    }

    private fun containsRemoteCss(css: String): Boolean {
        if (remoteCssPattern.containsMatchIn(css)) return true
        val stripped = stripCssComments(css) ?: css.replace(Regex("(?s)/\\*.*?\\*/"), "")
        val collapsed = css.replace(Regex("(?s)/\\*.*?\\*/"), "")
        return remoteCssPattern.containsMatchIn(stripped) || remoteCssPattern.containsMatchIn(collapsed)
    }

    private const val MAX_LAYOUT_WIDTH = 1280
    /** Fixed widths at or above this (CSS px) can't fit a phone; narrower ones (icons, columns) are left alone. */
    private const val FLUID_FROM = 480
    private val fixedWidthDecl = Regex("(?i)(?<![a-z-])width\\s*:\\s*([0-9]{3,4})(?:\\.[0-9]+)?\\s*px\\s*(!important)?")
    private val fixedMinWidthDecl = Regex("(?i)(?<![a-z-])min-width\\s*:\\s*([0-9]{3,4})(?:\\.[0-9]+)?\\s*px\\s*(!important)?")

    /**
     * For mails that ship their own responsive rules, wide fixed widths that no @media rule overrides would still push
     * the layout past a phone screen (e.g. a footer `<table style="width:640px">`). Like Gmail/Samsung Email, turn them
     * into "fluid up to that width": `width:100%;max-width:640px`, large `min-width` to 0, and the same for `width=` attrs.
     */
    internal fun makeFixedWidthsFluid(doc: Document) {
        for (el in doc.body().allElements) {
            val style = el.attr("style")
            if (style.isNotEmpty()) {
                val fluid = fixedWidthDecl.replace(style) { m ->
                    val n = m.groupValues[1].toInt()
                    if (n >= FLUID_FROM) "width:100%;max-width:${n}px" else m.value
                }.let { fixedMinWidthDecl.replace(it) { m -> if (m.groupValues[1].toInt() >= FLUID_FROM) "min-width:0" else m.value } }
                if (fluid != style) el.attr("style", fluid)
            }
            if (el.tagName() in setOf("table", "td", "th", "img")) {
                val n = el.attr("width").trim().removeSuffix("px").toIntOrNull()
                if (n != null && n >= FLUID_FROM) {
                    el.attr("width", "100%")
                    val own = el.attr("style").trim().trimEnd(';')
                    el.attr("style", (if (own.isEmpty()) "" else "$own;") + "max-width:${n}px")
                }
            }
        }
    }
    private const val BG_MARKER = "dotbg-"
    private val backgroundTags = setOf("table", "thead", "tbody", "tfoot", "tr", "td", "th", "div")
    /**
     * Remote images are only ever fetched over TLS: a plain `http://` URL is upgraded to `https://` (most mail image hosts
     * serve both). Cleartext would let anyone on the network see what is being read; the app does not allow it anyway,
     * so without the upgrade such images simply never load. If the host has no TLS, the image stays broken.
     */
    internal fun httpsOnly(url: String): String = if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    private val plainHttpUrl = Regex("(?i)^https?://[a-z0-9._~:/?#@!$&*+,;=%-]+$")
    private val styleUrl = Regex("(?i)background(?:-image)?\\s*:[^;]*?url\\(\\s*(['\"]?)([^'\")\\s]+)\\1\\s*\\)")

    /** The single background picture of a cell/table, if it is a plain http(s) URL; null otherwise. */
    internal fun backgroundUrl(attr: String, style: String): String? {
        val raw = attr.trim().ifEmpty { styleUrl.find(style)?.groupValues?.get(2)?.trim().orEmpty() }
        return raw.takeIf { it.length <= 2048 && plainHttpUrl.matches(it) }
    }

    // Open-tracking endpoints and services (path patterns are deliberately specific to avoid hiding real images).
    private val trackerHosts = listOf(
        "mmstat.com", "list-manage.com/track/open", "mailchimp.com/track", "sendgrid.net/wf/open", "/wf/open?",
        "track.hubspot.com", "hubspotemail.net/e2t/to", "mandrillapp.com/track/open", "ct.sendgrid.net",
        "mailtrack.io", "mixmax.com/api/track", "yesware.com/t/", "bananatag.com", "pixel.wp.com", "facebook.com/tr",
        "google-analytics.com/collect", "/open.php", "/track/open", "/tracking/open", "/e/o/", "/o.gif", "/open.gif",
        "emltrk.com", "sparkpostmail.com/q/", "exct.net/open", "rs6.net/on.jsp", "klclick", "/pixel.gif", "/pixel.png",
    )

    internal fun isTrackerUrl(url: String): Boolean {
        val u = url.lowercase(Locale.ROOT)
        return trackerHosts.any { it in u }
    }

    private val tinySize = Regex("^0*[0-2](?:px)?$")
    private val hiddenStyle = Regex("(?i)display\\s*:\\s*none|visibility\\s*:\\s*hidden|(?:^|;)\\s*(?:max-)?(?:width|height)\\s*:\\s*0*[0-2](?:px)?\\s*(?:!important)?\\s*(?:;|$)|opacity\\s*:\\s*0(?:\\.0+)?\\s*(?:;|$)")

    /** An invisible or 1–2 px image, or one served by a known open-tracking endpoint: never loaded, even when allowed. */
    internal fun isTrackingPixel(img: org.jsoup.nodes.Element, src: String): Boolean {
        val w = img.attr("width").trim().lowercase(Locale.ROOT)
        val h = img.attr("height").trim().lowercase(Locale.ROOT)
        if (w.matches(tinySize) && h.matches(tinySize)) return true
        if (hiddenStyle.containsMatchIn(img.attr("style"))) return true
        return isTrackerUrl(src)
    }
    private const val MAX_STYLE_TAG_CHARS = 65_536
    private const val MAX_TOTAL_CSS_CHARS = 32_768
    private const val MAX_CSS_RULES = 500
    private const val MAX_DECLARATIONS_PER_RULE = 48
    private const val MAX_SELECTOR_CHARS = 256
    private const val MAX_VALUE_CHARS = 256
}
