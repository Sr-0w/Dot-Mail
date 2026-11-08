package app.dot.mail.engine

import java.util.Locale

object Threading {
    fun baseSubject(subject: String): String = subject.trim()
        .replace(Regex("(?i)^(?:(?:re|fw|fwd|aw|sv)(?:\\[\\d+])?:\\s*)+"), "")
        .replace(Regex("\\s+"), " ")
    fun normalizeSubject(subject: String): String = baseSubject(subject).lowercase(Locale.ROOT)
    fun ids(value: String?): List<String> = Regex("<[^<>\\s]+>").findAll(value.orEmpty()).map { it.value }.toList()
    /** Caller scopes these keys to an account. Resolve reference aliases before subject fallback. */
    fun keys(header: Header): List<String> = buildList {
        header.gmailThreadId?.takeIf { it > 0 }?.let { add("gmail:$it") }
        header.references.firstOrNull()?.let { add("id:$it") }
        header.inReplyTo?.let { add("id:$it") }
        header.messageId?.let { add("id:$it") }
        normalizeSubject(header.subject).takeIf { it.isNotEmpty() }?.let { add("subject:$it") }
    }
}
