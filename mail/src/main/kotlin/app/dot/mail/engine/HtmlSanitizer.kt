package app.dot.mail.engine

import org.jsoup.Jsoup
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import java.util.Locale

/** Defense in depth. The renderer must also disable JS and intercept ALL network requests. */
object HtmlSanitizer {
    data class Result(val html: String, val hasRemoteImages: Boolean, val blockedRemoteResources: Int)
    private val tags = arrayOf("a", "abbr", "b", "blockquote", "br", "caption", "center", "code", "col", "colgroup", "dd", "del", "div", "dl", "dt", "em", "font", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "img", "li", "ol", "p", "pre", "s", "small", "span", "strong", "sub", "sup", "table", "tbody", "td", "th", "thead", "tr", "u", "ul")
    // Deliberately value-restrict CSS: no functions, escapes, comments, at-rules, custom properties or URLs.
    private val properties = setOf("color", "background-color", "font-family", "font-size", "font-weight", "font-style", "text-align", "text-decoration", "line-height", "letter-spacing", "white-space", "vertical-align", "border", "border-color", "border-width", "border-style", "border-collapse", "border-spacing", "padding", "padding-top", "padding-bottom", "padding-left", "padding-right", "margin", "margin-top", "margin-bottom", "margin-left", "margin-right", "width", "height", "max-width", "min-width", "display")
    fun sanitize(input: String, allowRemoteImages: Boolean = false, cidUris: Map<String, String> = emptyMap()): Result {
        val doc = Jsoup.parse(input)
        doc.outputSettings().prettyPrint(false)
        var remote = false
        var blocked = 0
        for (el in doc.allElements) {
            if (el.hasAttr("srcset") || el.hasAttr("background")) { remote = true; blocked++ }
            val style = el.attr("style")
            if (Regex("(?i)url\\s*\\(|@import|\\\\").containsMatchIn(if (el.tagName() == "style") el.data() else style)) { remote = true; blocked++ }
            if (el.tagName() == "img") {
                val src = el.attr("src").trim()
                if (src.startsWith("cid:", true)) {
                    val uri = cidUris[src.substring(4).trim('<', '>')]
                    // Only app-supplied content URIs can survive; raw file paths / data / network cannot.
                    if (uri != null && uri.startsWith("content://")) el.attr("src", uri) else el.removeAttr("src")
                } else if (src.isNotEmpty()) {
                    remote = true
                    if (!allowRemoteImages || !Regex("(?i)^https?://[^\\s]+$").matches(src)) { el.removeAttr("src"); blocked++ }
                }
            }
            el.removeAttr("srcset").removeAttr("background")
            if (style.isNotEmpty()) {
                val safe = style.split(';').mapNotNull { declaration ->
                    val pair = declaration.split(':', limit = 2)
                    if (pair.size != 2) return@mapNotNull null
                    val key = pair[0].trim().lowercase(Locale.ROOT)
                    val value = pair[1].trim()
                    if (key in properties && value.matches(Regex("[a-zA-Z0-9#.,%\\s'\"+/-]+"))) "$key:$value" else null
                }.joinToString(";")
                el.attr("style", safe)
            }
        }
        val safe = Safelist().addTags(*tags)
            .addAttributes(":all", "style", "title", "dir", "lang")
            .addAttributes("a", "href").addProtocols("a", "href", "https", "http", "mailto")
            .addAttributes("img", "src", "alt", "width", "height").addProtocols("img", "src", "content")
            .addAttributes("td", "colspan", "rowspan", "align", "valign", "width")
            .addAttributes("th", "colspan", "rowspan", "align", "valign", "width")
            .addAttributes("table", "width", "cellpadding", "cellspacing", "border")
            .addAttributes("font", "color", "face", "size")
        if (allowRemoteImages) safe.addProtocols("img", "src", "https", "http")
        val cleaned = Cleaner(safe).clean(doc)
        cleaned.outputSettings().prettyPrint(false)
        cleaned.select("a").attr("rel", "noreferrer noopener")
        return Result(cleaned.body().html(), remote, blocked)
    }
}
