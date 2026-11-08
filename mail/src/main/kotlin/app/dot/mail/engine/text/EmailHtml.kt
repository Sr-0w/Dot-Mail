package app.dot.mail.engine.text

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Builds outbound message HTML from the compose screen's rich-text editor.
 *
 * The editor emits simple HTML (`<p>`, `<br>`, `<b>`/`<strong>`, `<i>`/`<em>`, `<u>`,
 * `<s>`, `<a href>`, `<ul>`/`<ol>`/`<li>`, `<blockquote>`, `<code>`, `<h1>`–`<h3>`,
 * optional `<span>`/`<img>`, and inline `style` attributes). [compose] turns that into a
 * complete, email-client-safe document: inline styles only — no `<style>`, no `<script>`,
 * no classes — with a neutral font stack, paragraphs at `margin: 0 0 1em`, sane list
 * padding, links restricted to `http`/`https`/`mailto`/`tel`, and only safe colors/styles.
 *
 * The same sanitisation philosophy as `engine.HtmlSanitizer` (allowlist + strict value
 * checks, no functions or escapes in values) is applied, because editor output, the
 * signature and the quoted original are all untrusted HTML.
 */
object EmailHtml {

    /** The original message being replied to. [attribution] is treated as plain text. */
    data class Quote(val attribution: String, val html: String)

    // ------------------------------------------------------------- constants

    private const val P_STYLE = "margin: 0 0 1em"
    private const val H1_STYLE = "margin: 0 0 1em; font-size: 1.6em; font-weight: bold"
    private const val H2_STYLE = "margin: 0 0 1em; font-size: 1.4em; font-weight: bold"
    private const val H3_STYLE = "margin: 0 0 1em; font-size: 1.2em; font-weight: bold"
    private const val H46_STYLE = "margin: 0 0 1em; font-weight: bold"
    private const val LIST_STYLE = "margin: 0 0 1em; padding-left: 1.5em"
    private const val QUOTE_STYLE = "margin: 0 0 1em; padding-left: 1em"
    private const val CODE_STYLE = "font-family: monospace"
    private const val PRE_STYLE = "font-family: monospace; white-space: pre-wrap"
    private const val BODY_STYLE =
        "font-family: -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; " +
            "font-size: 15px; line-height: 1.5"
    /** Exact style required for the quoted-original block. */
    private const val CITE_STYLE = "margin:0 0 0 .8ex;border-left:1px solid #ccc;padding-left:1ex"
    private const val ATTRIBUTION_STYLE = "margin: 0 0 0.5em; font-size: 0.9em; color: #555"
    private const val SIGNATURE_SEPARATOR = "<br>-- <br>"

    /** Tags dropped together with their content. */
    private val DROP_WITH_CONTENT = setOf(
        "script", "style", "noscript", "template", "object", "embed", "iframe", "frame",
        "frameset", "form", "input", "button", "textarea", "select", "option", "svg",
        "math", "link", "meta", "base", "head", "title", "hr",
    )

    /** Tags unwrapped (content kept, tag dropped). */
    private val UNWRAP = setOf(
        "table", "thead", "tbody", "tfoot", "tr", "th", "td", "colgroup", "col",
        "caption", "center", "marquee", "details", "summary",
    )

    /** Tags that survive into the output document. */
    private val ALLOWED = setOf(
        "p", "div", "br", "b", "strong", "i", "em", "u", "s", "strike", "del", "ins",
        "sub", "sup", "a", "ul", "ol", "li", "blockquote", "code", "pre",
        "h1", "h2", "h3", "h4", "h5", "h6", "span", "img",
    )

    /** Forced inline styles per tag; forced declarations win over editor ones. */
    private val FORCED: Map<String, String> = mapOf(
        "p" to P_STYLE, "div" to P_STYLE,
        "h1" to H1_STYLE, "h2" to H2_STYLE, "h3" to H3_STYLE,
        "h4" to H46_STYLE, "h5" to H46_STYLE, "h6" to H46_STYLE,
        "ul" to LIST_STYLE, "ol" to LIST_STYLE,
        "blockquote" to QUOTE_STYLE,
        "code" to CODE_STYLE, "pre" to PRE_STYLE,
    )

    // ------------------------------------------------------------- compose

    /**
     * Editor HTML -> complete, email-client-safe HTML document.
     *
     *  - [signatureHtml] (already HTML) is appended after `<br>-- <br>`, unless blank;
     *  - [quote] is appended last: the attribution line, then the quoted original in
     *    `<blockquote type="cite">` with the standard cite styling.
     */
    fun compose(editorHtml: String, signatureHtml: String? = null, quote: Quote? = null): String {
        val sb = StringBuilder(sanitizeFragment(editorHtml))
        if (signatureHtml != null && !isBlankSignature(signatureHtml)) {
            sb.append(SIGNATURE_SEPARATOR).append(sanitizeFragment(signatureHtml))
        }
        quote?.let { q ->
            sb.append("<p style=\"")
                .append(ATTRIBUTION_STYLE)
                .append("\">")
                .append(escapeHtml(q.attribution))
                .append("</p>")
            sb.append("<blockquote type=\"cite\" style=\"")
                .append(CITE_STYLE)
                .append("\">")
                .append(sanitizeFragment(q.html))
                .append("</blockquote>")
        }
        return buildString {
            append("<!DOCTYPE html><html><head>")
            .append("<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">")
            .append("</head><body style=\"")
            .append(BODY_STYLE)
            .append("\">")
            .append(sb)
            .append("</body></html>")
        }
    }

    private fun isBlankSignature(s: String): Boolean {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.contains("<img", ignoreCase = true)) return false
        val text = Jsoup.parse(trimmed).body()?.text()?.trim().orEmpty()
        return text.isEmpty()
    }

    // ----------------------------------------------------- fragment sanitising

    /** Parses [html], sanitises it in place and returns the cleaned body inner-HTML. */
    private fun sanitizeFragment(html: String): String {
        if (html.isBlank()) return ""
        val doc = Jsoup.parse(html)
        val body = doc.body() ?: return ""
        clean(body)
        return body.html()
    }

    private fun clean(root: Element) {
        // 1) Drop dangerous/irrelevant elements with their content.
        root.allElements.filter { it.tagName().lowercase() in DROP_WITH_CONTENT }.forEach { it.remove() }

        // 2) Walk the rest in document order.
        val stack = ArrayDeque(root.children().toList())
        while (stack.isNotEmpty()) {
            val el = stack.removeFirst()
            if (el.parents().isEmpty()) continue // removed together with an ancestor
            val tag = el.tagName().lowercase()
            val keep = when {
                tag in ALLOWED -> normalizeAttributes(el, tag)
                tag in UNWRAP -> { el.unwrap(); true }
                else -> { el.unwrap(); true } // unknown tag: keep its text
            }
            if (keep) {
                normalizeStyle(el, tag)
                // Push children in document order; unwrap reparents them but they remain in el.
                for (child in el.children()) stack.addLast(child)
            }
        }
    }

    /**
     * Returns true when the element stays. Links with an unsafe scheme are unwrapped
     * (text kept); images without a safe remote src are removed.
     */
    private fun normalizeAttributes(el: Element, tag: String): Boolean = when (tag) {
        "a" -> {
            val href = el.attr("href").trim()
            el.clearAttributes()
            if (href.isNotEmpty() && isSafeHref(href)) el.attr("href", href) else {
                el.unwrap()
                false
            }
        }
        "img" -> {
            val src = el.attr("src").trim()
            val alt = el.attr("alt").trim()
            val width = el.attr("width")
            val height = el.attr("height")
            el.clearAttributes()
            if (!isSafeHref(src)) {
                el.remove()
                false
            } else {
                el.attr("src", src)
                if (alt.isNotEmpty()) el.attr("alt", alt.take(200))
                if (width.toIntOrNull() != null) el.attr("width", width)
                if (height.toIntOrNull() != null) el.attr("height", height)
                true
            }
        }
        else -> {
            // asList() snapshots the attributes so the list stays valid while we remove.
            el.attributes().asList().filter { it.key != "style" }.forEach { el.removeAttr(it.key) }
            true
        }
    }

    /** Merges forced styles with the element's safe editor styles (forced wins). */
    private fun normalizeStyle(el: Element, tag: String) {
        val raw = el.attr("style")
        val forced = FORCED[tag]
        val editor = LinkedHashMap<String, String>()
        if (raw.isNotEmpty() && !raw.contains('@') && !raw.contains('\\')) {
            raw.split(';').forEach { decl ->
                val idx = decl.indexOf(':')
                if (idx <= 0) return@forEach
                val prop = decl.substring(0, idx).trim().lowercase()
                val value = decl.substring(idx + 1).trim()
                if (prop in SAFE_PROPERTIES && valueIsSafe(prop, value)) editor[prop] = value
            }
        }
        val merged = LinkedHashMap<String, String>()
        if (forced != null) forced.split(';').forEach {
            val idx = it.indexOf(':')
            merged[it.substring(0, idx).trim().lowercase()] = it.substring(idx + 1).trim()
        }
        editor.forEach { (k, v) -> merged.putIfAbsent(k, v) }
        val style = merged.entries.joinToString("; ") { (k, v) -> "$k: $v" }
        if (style.isEmpty()) el.removeAttr("style") else el.attr("style", style)
    }

    private val SAFE_PROPERTIES = setOf(
        "color", "background-color", "font-size", "font-weight", "font-style",
        "font-family", "text-decoration", "line-height", "letter-spacing",
        "text-align", "margin", "padding", "white-space", "width", "height",
    )

    private fun valueIsSafe(prop: String, value: String): Boolean {
        if (value.isEmpty()) return false
        if (prop == "color" || prop == "background-color") return isSafeColor(value)
        // No functions or attribute-escaping characters anywhere else: url(), expression(),
        // attr() and double quotes are all out (single quotes are fine, e.g. font names).
        if (value.any { it in "()|\"" }) return false
        return when (prop) {
            "font-size" -> FONT_SIZE.matches(value) && fontSizeInBounds(value)
            "font-weight" -> value in setOf("normal", "bold", "lighter", "bolder") || REGEX_INT3.matches(value)
            "font-style" -> value in setOf("normal", "italic", "oblique")
            "font-family" -> FONT_FAMILY.matches(value)
            "text-decoration" -> value.split(' ').all { it in setOf("underline", "overline", "line-through", "none") } && value.isNotEmpty()
            "line-height" -> LINE_HEIGHT.matches(value) && lineHeightInBounds(value)
            "letter-spacing" -> LETTER_SPACING.matches(value)
            "text-align" -> value in setOf("left", "right", "center", "justify")
            "white-space" -> value in setOf("normal", "nowrap", "pre", "pre-wrap", "pre-line")
            "margin", "padding" -> value.split(' ').filter { it.isNotEmpty() }.let {
                it.size in 1..4 && it.all { part -> LENGTH.matches(part) }
            }
            "width", "height" -> LENGTH.matches(value) || PERCENT.matches(value)
            else -> false
        }
    }

    private fun isSafeColor(value: String): Boolean {
        val v = value.trim()
        if (HEX_COLOR.matches(v)) return true
        if (RGB_VALUE.matches(v)) {
            val nums = v.substringBetween('(', ')').split(',').map { it.trim() }
            return nums.size in 3..4 &&
                nums.take(3).all { it.toIntOrNull() != null && it.toInt() in 0..255 } &&
                (nums.size == 3 || nums[3].toDoubleOrNull() in 0.0..1.0)
        }
        return v.lowercase() in NAMED_COLORS
    }

    private fun fontSizeInBounds(v: String): Boolean {
        val num = v.substringBefore(Regex("[a-z%]+")).toDoubleOrNull() ?: return false
        return when {
            v.endsWith("em") -> num in 0.5..4.0
            v.endsWith("px") -> num in 8.0..60.0
            v.endsWith("pt") -> num in 6.0..45.0
            v.endsWith('%') -> num in 40.0..300.0
            else -> true
        }
    }

    private fun lineHeightInBounds(v: String): Boolean {
        val num = v.toDoubleOrNull() ?: (v.substringBefore(Regex("[a-z%]+")).toDoubleOrNull()) ?: return false
        return num in 0.5..4.0 || (v.endsWith('%') && num in 40.0..300.0)
    }

    private val HEX_COLOR = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    private val RGB_VALUE = Regex("^(?i)rgba?\\(\\s*\\d{1,3}\\s*(?:,\\s*\\d{1,3}\\s*,\\s*\\d{1,3})(?:,\\s*[\\d.]+)?\\s*\\)$")
    private val FONT_SIZE = Regex("^\\d+(\\.\\d+)?(px|pt|em|%)$")
    private val REGEX_INT3 = Regex("^\\d{3}$")
    private val FONT_FAMILY = Regex("^[a-zA-Z0-9_ ,.\"'-]+$")
    private val LINE_HEIGHT = Regex("^\\d+(\\.\\d+)?(px|em|pt|%)?$")
    private val LETTER_SPACING = Regex("^-?\\d+(\\.\\d+)?(px|em|pt)?$")
    private val LENGTH = Regex("^-?\\d+(\\.\\d+)?(px|em|pt|ex|%)$")
    private val PERCENT = Regex("^\\d+(\\.\\d+)?%$")

    /** Common CSS named colors; hex/rgb are always accepted, anything else is dropped. */
    private val NAMED_COLORS = setOf(
        "black", "white", "gray", "grey", "silver", "maroon", "red", "purple", "green",
        "lime", "olive", "yellow", "navy", "blue", "teal", "aqua", "cyan", "fuchsia",
        "pink", "brown", "orange", "gold", "indigo", "violet", "salmon", "coral", "slate",
        "ivory", "beige", "tan", "khaki", "plum", "olive",
        "darkred", "darkgreen", "darkblue", "darkgray", "darkgrey", "darkcyan", "darkmagenta",
        "darkorange", "lightblue", "lightgreen", "lightgray", "lightgrey", "lightpink",
        "lightyellow", "whitesmoke", "snow", "seashell", "lavender", "honeydew", "mintcream",
        "azure", "mistyrose", "linen", "floralwhite", "oldlace", "ghostwhite", "bisque",
        "crimson", "tomato", "chocolate", "firebrick", "indianred", "rosybrown", "sienna",
        "peru", "saddlebrown", "chocolate", "royalblue", "steelblue", "skyblue", "slateblue",
        "mediumblue", "midnightblue", "cornflowerblue", "deeppink", "hotpink", "mediumorchid",
        "orchid", "thistle", "magenta", "forestgreen", "seagreen", "olivedrab", "limegreen",
        "chartreuse", "yellowgreen", "gainsboro", "dimgray", "dimgrey",
    )

    // ----------------------------------------------------- link scheme check

    /** Only http/https/mailto/tel — nothing else ever leaves the anchor. */
    private fun isSafeHref(href: String): Boolean =
        SAFE_HREF.matches(href.trim()) && !href.trim().contains('\u0000')

    private val SAFE_HREF = Regex("^(?i)(https?|mailto|tel):[^\\s<>\"']*$")

    // ------------------------------------------------------------- toPlainText

    /**
     * HTML -> readable text/plain alternative.
     *
     * Paragraphs are separated by a blank line, `<br>` becomes a newline, lists render as
     * `• item` / `1. item` (nested lists are indented two spaces), links as
     * `text <url>` (just the url when the text equals it), blockquote lines are prefixed
     * with `> ` (nested quotes `>> `), entities are decoded, lines never have trailing
     * whitespace and at most one blank line occurs in a row.
     */
    fun toPlainText(html: String): String {
        val doc = Jsoup.parse(html)
        val body = doc.body() ?: return ""
        val out = StringBuilder()
        PlainWalker(out, body).walk()
        return normalize(out.toString())
    }

    private class PlainWalker(private val out: StringBuilder, private val root: Element) {
        /** One entry per open list; the value is the nesting depth of that list. */
        private val listDepth = ArrayDeque<Int>()
        /** Per-depth ordered-list counters. */
        private val counters = mutableMapOf<Int, Int>()

        fun walk() = walkElements(root)

        private fun walkElements(el: Element) {
            for (node in el.childNodes()) walkNode(node)
        }

        /** Blocks never run into preceding inline text: newline first if needed. */
        private fun beforeBlock() {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
        }

        private fun walkNode(node: Node) {
            when (node) {
                is TextNode -> out.append(node.text())
                is Element -> {
                    val tag = node.tagName().lowercase()
                    when (tag) {
                        "script", "style", "noscript", "object", "embed", "iframe", "form",
                        "input", "button", "textarea", "select", "svg", "math", "template",
                        "head", "title", "link", "meta", "hr" -> Unit

                        "br" -> out.append('\n')

                        "img" -> node.attr("alt").trim().takeIf { it.isNotEmpty() }?.let { out.append(it) }

                        "a" -> {
                            val text = snapshot { walkElements(node) }
                            val href = node.attr("href").trim()
                            if (href.isNotEmpty() && EmailHtml.isSafeHref(href) && text.trim() != href) {
                                out.append(text).append(" <").append(href).append(">")
                            } else {
                                out.append(if (text.isEmpty() && href.isNotEmpty()) href else text)
                            }
                        }

                        "ul", "ol" -> {
                            beforeBlock()
                            val depth = listDepth.size
                            val ordered = tag == "ol"
                            if (ordered) counters[depth] = 0
                            listDepth.addLast(depth)
                            val items = mutableListOf<String>()
                            for (li in node.children()) {
                                if (li.tagName().lowercase() != "li") continue
                                val item = renderLi(li, ordered, depth)
                                if (item.isNotEmpty()) items.add(item)
                            }
                            listDepth.removeLast()
                            if (items.isNotEmpty()) out.append(items.joinToString("\n")).append("\n\n")
                        }

                        "li" -> {
                            // Stray <li> outside a list: render like a paragraph.
                            beforeBlock()
                            renderLi(node, ordered = false, depth = 0)
                                .takeIf { it.isNotEmpty() }?.let { out.append(it.replaceFirst("^• ", "")) }
                            out.append("\n\n")
                        }

                        "blockquote" -> {
                            beforeBlock()
                            val inner = snapshot { walkElements(node) }
                            if (inner.trim().isNotEmpty()) {
                                inner.trimEnd().lines().forEach { line ->
                                    out.append(if (line.isEmpty() || line == ">") ">" else "> $line")
                                    out.append('\n')
                                }
                                out.append('\n')
                            }
                        }

                        "td", "th" -> {
                            beforeBlock()
                            walkElements(node)
                            out.append("\n\n")
                        }

                        "table", "thead", "tbody", "tfoot", "tr" -> walkElements(node)

                        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "pre",
                        "section", "article", "aside", "header", "footer", "address",
                        "figure", "figcaption", "nav", "main" -> {
                            beforeBlock()
                            walkElements(node)
                            out.append("\n\n")
                        }

                        else -> walkElements(node) // unknown: keep text, no extra spacing
                    }
                }
                else -> Unit // comments, data nodes
            }
        }

        private fun renderLi(li: Element, ordered: Boolean, depth: Int): String {
            val inner = snapshot { walkElements(li) }.trimEnd('\n')
            if (inner.isBlank()) return ""
            val marker = if (ordered) {
                counters[depth] = counters.getOrDefault(depth, 0) + 1
                "${counters[depth]}."
            } else "•"
            val indent = "  ".repeat(depth)
            val lines = inner.split('\n')
            return buildString {
                append(indent).append(marker).append(' ').append(lines.first())
                for (l in lines.drop(1)) if (l.isNotEmpty()) {
                    append('\n').append(indent).append(l)
                }
            }
        }

        private inline fun snapshot(block: () -> Unit): String {
            val start = out.length
            block()
            val s = out.substring(start)
            out.delete(start, out.length)
            return s
        }
    }

    private fun normalize(raw: String): String {
        val lines = raw.replace('\u00A0', ' ').lines().map {
            it.replace(Regex("[ \\t]+"), " ").trimEnd()
        }
        val kept = mutableListOf<String>()
        for (line in lines) {
            if (line.isEmpty()) {
                if (kept.isNotEmpty() && kept.last() != "") kept.add("")
            } else kept.add(line)
        }
        while (kept.isNotEmpty() && kept.last() == "") kept.removeAt(kept.lastIndex)
        return kept.joinToString("\n")
    }

    // ------------------------------------------------------------- fromPlainText

    /**
     * Plain text -> minimal HTML for the editor: HTML-escaped, blank line separates
     * paragraphs, single newline becomes `<br>`, http(s) URLs and email addresses are
     * auto-linked, and `> `-prefixed lines become nested `<blockquote>`s.
     */
    fun fromPlainText(text: String): String {
        val lines = text.split('\n').map { it.removeSuffix('\r').trim() }
        return renderPlainLines(lines).trim()
    }

    private fun renderPlainLines(lines: List<String>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isEmpty()) { i++; continue }
            val isQuote = line.startsWith("> ")
            val chunk = mutableListOf<String>()
            var j = i
            while (j < lines.size) {
                val l = lines[j]
                if (l.isEmpty()) break
                if (l.startsWith("> ") != isQuote) break
                chunk.add(l); j++
            }
            if (isQuote) {
                val stripped = chunk.map { it.removePrefix("> ") }
                sb.append("<blockquote>").append(renderPlainLines(stripped)).append("</blockquote>")
            } else {
                val rendered = chunk.map { linkify(it) }
                sb.append("<p>").append(rendered.joinToString("<br>")).append("</p>")
            }
            i = j
        }
        return sb.toString()
    }

    /** Escapes the line and auto-links URLs and email addresses (link first, escape pieces). */
    private fun linkify(line: String): String {
        val out = StringBuilder()
        var last = 0
        AUTO_LINK.findAll(line).forEach { m ->
            out.append(escapeHtml(line.substring(last, m.range.first)))
            val value = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')')
            if (value.length > 2) {
                val url = value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)
                val href = if (url) value else "mailto:$value"
                out.append("<a href=\"").append(escapeHtml(href)).append("\">")
                    .append(escapeHtml(value))
                    .append("</a>")
            } else {
                out.append(escapeHtml(value))
            }
            // The trimmed tail punctuation (if any) stays plain text right after the link.
            last = m.range.last + 1 - (m.value.length - value.length)
        }
        out.append(escapeHtml(line.substring(last)))
        return out.toString()
    }

    private val AUTO_LINK = Regex(
        "(?i)(?<![A-Za-z0-9.])https?://[^\\s<>\"']+" +
            "|(?<![A-Za-z0-9._%+\\-:,@])([A-Za-z0-9._%+\\-]+)@([A-Za-z0-9\\-]+(\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,})"
    )

    // ------------------------------------------------------------- helpers

    internal fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
