package app.dot.mail.engine

import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.search.*

/** Shared syntax for local and server search. Unknown operators remain literal free text. */
data class MailSearch(val terms: List<Term>) {
    data class Term(val field: String?, val value: String)
    val needsAttachments: Boolean get() = terms.any { it.field == "has" && it.value.equals("attachment", true) }
    fun serverTerm(): SearchTerm {
        val expressions = terms.mapNotNull { term -> when (term.field) {
            "from" -> FromStringTerm(term.value)
            "to" -> RecipientStringTerm(Message.RecipientType.TO, term.value)
            "subject" -> SubjectTerm(term.value)
            "is" -> when (term.value.lowercase()) { "unread" -> FlagTerm(Flags(Flags.Flag.SEEN), false); "starred" -> FlagTerm(Flags(Flags.Flag.FLAGGED), true); else -> null }
            "has" -> null // BODYSTRUCTURE filtering after SEARCH; no content download.
            else -> OrTerm(SubjectTerm(term.value), OrTerm(FromStringTerm(term.value), BodyTerm(term.value)))
        } }
        return if (expressions.isEmpty()) FlagTerm(Flags(Flags.Flag.DELETED), false) else AndTerm(expressions.toTypedArray())
    }
    companion object {
        fun parse(query: String): MailSearch = MailSearch(Regex("(?:[^\\s\"]|\"[^\"]*\")+").findAll(query).map { match ->
            val token = match.value
            val field = token.substringBefore(':', "").lowercase()
            val value = token.substringAfter(':').trim('"')
            val supported = field in setOf("from", "to", "subject") || (field == "has" && value.equals("attachment", true)) || (field == "is" && value.lowercase() in setOf("unread", "starred"))
            if (supported) Term(field, value) else Term(null, token.trim('"'))
        }.filter { it.value.isNotBlank() }.toList())
    }
}
