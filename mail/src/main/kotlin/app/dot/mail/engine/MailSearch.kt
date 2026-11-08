package app.dot.mail.engine

import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.search.*

/** Shared syntax for local and server search. Unknown operators remain literal free text. */
data class MailSearch(val terms: List<Term>) {
    data class Term(val field: String?, val value: String, val quoted: Boolean = false)
    val needsAttachments: Boolean get() = terms.any { it.field == "has" && it.value.equals("attachment", true) }
    fun serverTerm(): SearchTerm {
        val expressions = terms.mapNotNull { term -> when (term.field) {
            "from" -> FromStringTerm(term.value)
            "to" -> RecipientStringTerm(Message.RecipientType.TO, term.value)
            "subject" -> SubjectTerm(term.value)
            "is" -> when (term.value.lowercase()) { "unread" -> FlagTerm(Flags(Flags.Flag.SEEN), false); "read" -> FlagTerm(Flags(Flags.Flag.SEEN), true); "starred" -> FlagTerm(Flags(Flags.Flag.FLAGGED), true); else -> null }
            "after", "since" -> SentDateTerm(ComparisonTerm.GE, java.util.Date.from(java.time.LocalDate.parse(term.value).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()))
            "before" -> SentDateTerm(ComparisonTerm.LT, java.util.Date.from(java.time.LocalDate.parse(term.value).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()))
            "folder" -> null
            "has" -> null // BODYSTRUCTURE filtering after SEARCH; no content download.
            else -> OrTerm(SubjectTerm(term.value), OrTerm(FromStringTerm(term.value), OrTerm(RecipientStringTerm(Message.RecipientType.TO, term.value), BodyTerm(term.value))))
        } }
        return if (expressions.isEmpty()) FlagTerm(Flags(Flags.Flag.DELETED), false) else AndTerm(expressions.toTypedArray())
    }
    companion object {
        fun parse(query: String): MailSearch = MailSearch(Regex("(?:[^\\s\"]|\"[^\"]*\")+").findAll(query).map { match ->
            val token = match.value
            val field = token.substringBefore(':', "").lowercase()
            val value = token.substringAfter(':').trim('"')
            val supported = field in setOf("from", "to", "subject", "folder") || (field in setOf("before","after","since") && runCatching { java.time.LocalDate.parse(value) }.isSuccess) || (field == "has" && value.equals("attachment", true)) || (field == "is" && value.lowercase() in setOf("unread", "read", "starred"))
            if (supported) Term(field, value, token.contains('"')) else Term(null, token.trim('"'), token.contains('"'))
        }.filter { it.value.isNotBlank() }.toList())
    }
}
