package app.dot.mail.engine.html

/**
 * @property layoutWidth widest fixed CSS-pixel width the design asks for (0 = fluid).
 * @property responsive the email adapts to narrow screens by itself (`@media (max-width…)`).
 */
data class SanitizedHtml(
    val html: String,
    val blockedRemoteResources: Int,
    val layoutWidth: Int = 0,
    val responsive: Boolean = false,
) {
    val hasRemoteImages: Boolean get() = blockedRemoteResources > 0
}
object HtmlSanitizer {
    fun sanitize(html: String, allowRemoteImages: Boolean = false, cidUris: Map<String, String> = emptyMap()): SanitizedHtml {
        val result = app.dot.mail.engine.HtmlSanitizer.sanitize(html, allowRemoteImages, cidUris)
        return SanitizedHtml(result.html, result.blockedRemoteResources, result.layoutWidth, result.responsive)
    }
}
