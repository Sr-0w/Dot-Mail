package app.dot.mail.data.model

/**
 * With "Group into conversations" off, the message list shows single messages. Each row reuses the thread machinery
 * (selection, actions, previous/next, the reading screen) by carrying a *virtual thread id*: the message id offset by
 * [MESSAGE_BASE]. Every repository call that takes thread ids understands both kinds, so the UI needs no second code path.
 * Real thread ids are small positive numbers, drafts are negative; virtual ids sit far above any realistic thread id.
 */
object ConversationIds {
    const val MESSAGE_BASE = 1L shl 40

    fun ofMessage(messageId: Long): Long = MESSAGE_BASE + messageId
    fun isMessage(threadId: Long): Boolean = threadId >= MESSAGE_BASE
    fun messageIdOf(threadId: Long): Long = threadId - MESSAGE_BASE
}
