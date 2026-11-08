package app.dot.mail.ui.thread

import app.dot.mail.data.model.Mailbox

/**
 * The conversations of the list the reader was opened from, in the order that list shows them (so filter and sort are
 * already applied), plus the mailbox it belongs to. Neighbours are only ever resolved against a list recorded for the
 * reader's own source mailbox: opened from search or a notification (no source), or after the user switched mailbox,
 * there is no "next" and swiping does nothing.
 */
data class ConversationOrder(val source: Mailbox?, val ids: List<Long>) {
    /** Next (older in the list) conversation after [current] for a reader opened from [from], or null. */
    fun next(current: Long, from: Mailbox?): Long? = neighbour(current, from, +1)

    /** Previous conversation before [current] for a reader opened from [from], or null. */
    fun previous(current: Long, from: Mailbox?): Long? = neighbour(current, from, -1)

    private fun neighbour(current: Long, from: Mailbox?, step: Int): Long? {
        if (from == null || from != source) return null
        val at = ids.indexOf(current)
        if (at < 0) return null
        return ids.getOrNull(at + step)
    }

    companion object { val None = ConversationOrder(null, emptyList()) }
}

/** The order the inbox last displayed, shared with the reader. */
object ThreadOrder {
    @Volatile var current: ConversationOrder = ConversationOrder.None
        private set

    fun record(source: Mailbox?, ids: List<Long>) { current = ConversationOrder(source, ids) }
}
