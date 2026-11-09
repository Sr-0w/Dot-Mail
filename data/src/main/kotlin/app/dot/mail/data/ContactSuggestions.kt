package app.dot.mail.data

import app.dot.mail.data.model.EmailAddress

/**
 * Recipient suggestions from two sources: people from mail history first (most recent first, as the database returns
 * them), then address-book contacts. Duplicates (same address, any case or surrounding spaces) keep the history entry, but a missing display name
 * is filled from the address book. At most [limit] results.
 */
internal fun mergeSuggestions(history: List<EmailAddress>, addressBook: List<EmailAddress>, limit: Int): List<EmailAddress> {
    val out = LinkedHashMap<String, EmailAddress>()
    for (h in history) out.putIfAbsent(h.email.trim().lowercase(), h)
    for (b in addressBook) {
        val key = b.email.trim().lowercase()
        val existing = out[key]
        if (existing == null) out[key] = b
        else if (existing.name.isNullOrBlank() && !b.name.isNullOrBlank()) out[key] = existing.copy(name = b.name)
    }
    return out.values.take(limit)
}
