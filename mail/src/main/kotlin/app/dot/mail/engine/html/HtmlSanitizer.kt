package app.dot.mail.engine.html

data class SanitizedHtml(val html: String, val blockedRemoteResources: Int) {
    val hasRemoteImages: Boolean get() = blockedRemoteResources > 0
}
object HtmlSanitizer {
    fun sanitize(html: String, allowRemoteImages: Boolean = false, cidUris: Map<String, String> = emptyMap()): SanitizedHtml {
        val result = app.dot.mail.engine.HtmlSanitizer.sanitize(html, allowRemoteImages, cidUris)
        return SanitizedHtml(result.html, result.blockedRemoteResources)
    }
}
