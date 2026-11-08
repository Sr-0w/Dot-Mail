package app.dot.mail.engine.text

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Builds short, readable plain-text snippets from message bodies, for lists and
 * notifications.
 *
 * Both entry points:
 *  - drop quoted tails (via [QuoteSplitter]);
 *  - collapse all whitespace (including no-break spaces) into single spaces;
 *  - truncate to [max] characters, appending `…` when the text is cut.
 *
 * [fromHtml] additionally uses jsoup to strip `head`, `style`/`script` and other
 * non-content markup, as well as invisible preheader junk (hidden spans/divs,
 * `mso-hide`, `font-size:0`, preheader-ish CSS classes). Link *text* is kept;
 * hrefs are never part of a snippet.
 */
object Snippets {

    private const val ELLIPSIS = "…"

    private val BLOCK_TAGS = setOf(
        "address", "article", "aside", "blockquote", "br", "div", "dd", "dl", "dt",
        "fieldset", "figcaption", "figure", "footer", "header", "h1", "h2", "h3", "h4",
        "h5", "h6", "hr", "li", "main", "nav", "ol", "p", "pre", "section", "table",
        "tbody", "td", "tfoot", "th", "thead", "tr", "ul",
    )

    /** Snippet from a plain-text body; the quoted tail of a reply is dropped. */
    fun fromPlainText(text: String, max: Int = 160): String {
        if (max <= 0) return ""
        return clip(QuoteSplitter.split(text).visible, max)
    }

    /** Snippet from an HTML body; hidden markup and the quoted tail are dropped. */
    fun fromHtml(html: String, max: Int = 160): String {
        if (max <= 0 || html.isBlank()) return ""
        val doc: Document = Jsoup.parse(html)
        val body = doc.body() ?: return ""
        // Non-content markup (jsoup would ignore style/script text anyway; remove
        // explicitly so nothing of theirs can leak through, e.g. into attributes).
        body.select("style, script, noscript, link, meta, title, object, embed, iframe").remove()
        // Invisible preheader junk, by inline style or by well-known CSS classes.
        val hidden = body.allElements.filter { styleIsHidden(it.attr("style")) }
        hidden.forEach { it.remove() }
        body.select("[class~=preheader], [class~=pre-header], [class~=emailhide], [class~=email-hide], [class~=hide], [class~=hidden], [class~=invisible], [class~=no-display]").remove()

        val raw = buildString { walk(body, this) }
        return clip(QuoteSplitter.split(raw.lineSequence().joinToString("\n") { it.trim() }).visible, max)
    }

    /**
     * Extracts text preserving block boundaries as newlines, so quote detection
     * works on HTML-derived text. Skips comments and data nodes.
     */
    private fun walk(el: Element, sb: StringBuilder) {
        for (node: Node in el.childNodes()) {
            when (node) {
                is TextNode -> {
                    // Whitespace-only text nodes are markup indentation, not content —
                    // dropping them keeps quote markers ("> ") at column 0 in the
                    // extracted text.
                    sb.append(node.wholeText)
                }
                is Element -> {
                    val tag = node.tagName().lowercase()
                    if (tag == "br") {
                        sb.append('\n')
                        continue
                    }
                    if (tag in BLOCK_TAGS && sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                    walk(node, sb)
                    if (tag in BLOCK_TAGS) sb.append('\n')
                }
                else -> Unit // comments, CDATA-as-data, processing instructions
            }
        }
    }

    private fun styleIsHidden(style: String): Boolean {
        if (style.isEmpty()) return false
        val s = style.lowercase().replace(" ", "")
        return s.contains("display:none") ||
            s.contains("visibility:hidden") ||
            s.contains("mso-hide") ||
            s.contains("font-size:0") ||
            s.contains("line-height:0")
    }

    private fun clip(text: String, max: Int): String {
        val collapsed = text.replace('\u00A0', ' ').replace(WHITESPACE, " ").trim()
        if (collapsed.length <= max) return collapsed
        return collapsed.substring(0, max - 1).trimEnd() + ELLIPSIS
    }

    private val WHITESPACE = Regex("\\s+")
}
