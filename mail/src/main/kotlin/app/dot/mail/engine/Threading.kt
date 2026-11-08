package app.dot.mail.engine

import java.util.Locale

object Threading {
    fun baseSubject(subject: String): String = subject.trim()
        .replace(Regex("(?i)^(?:(?:re|fw|fwd|aw|sv)(?:\\[\\d+])?:\\s*)+"), "")
        .replace(Regex("\\s+"), " ")
    fun normalizeSubject(subject: String): String = baseSubject(subject).lowercase(Locale.ROOT)
    fun ids(value: String?): List<String> = Regex("<[^<>\\s]+>").findAll(value.orEmpty()).map { it.value }.toList()
    /** Authoritative identifiers only. Subject fallback is deliberately a separate, gated lookup. */
    fun keys(header: Header): List<String> = buildList {
        header.gmailThreadId?.takeIf { it > 0 }?.let { add("gmail:$it") }
        if (header.gmailThreadId == null) {
            header.references.forEach { add("id:$it") }
            header.inReplyTo?.let { add("id:$it") }
        }
        header.messageId?.let { add("id:$it") }
    }.distinct()
    fun mayFallback(header: Header): Boolean = header.gmailThreadId == null && header.references.isEmpty() && header.inReplyTo == null &&
        (header.messageId == null || Regex("(?i)^(?:re|fw|fwd|aw|sv)(?:\\[\\d+])?:").containsMatchIn(header.subject.trim()))
    fun fallbackKey(header: Header): String? {
        val subject = normalizeSubject(header.subject).takeIf { it.isNotEmpty() } ?: return null
        val peers = (header.from + header.to + header.cc).map { it.email.lowercase(Locale.ROOT) }.distinct().sorted().joinToString(",")
        return "subject:$subject|peers:$peers"
    }
}
