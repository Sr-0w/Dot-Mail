package app.dot.mail.data

import androidx.paging.Pager
import app.dot.mail.data.model.category
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.sqlite.db.SimpleSQLiteQuery
import app.dot.mail.data.local.MESSAGE_META
import app.dot.mail.data.local.MessageRow
import app.dot.mail.data.model.ConversationIds
import app.dot.mail.data.model.FolderRole
import app.dot.mail.data.model.Mailbox
import app.dot.mail.data.model.ThreadSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Messages behind a (real or virtual, see [ConversationIds]) thread id: the whole thread, or the single message. */
internal suspend fun RealMailGraph.threadOrMessageRows(threadId: Long): List<MessageRow> =
    if (ConversationIds.isMessage(threadId)) listOfNotNull(dao.message(ConversationIds.messageIdOf(threadId)))
    else dao.threadMessages(threadId)

/**
 * One row per message, newest first, for a folder or a role across accounts. Drafts keep their conversation-style list
 * (local drafts are not messages). Rows are ThreadSummaries with a virtual thread id so the list UI is unchanged.
 */
internal fun RealMailGraph.messagesPaging(mailbox: Mailbox, options: app.dot.mail.data.model.ListOptions = app.dot.mail.data.model.ListOptions()): Flow<PagingData<ThreadSummary>> = flow {
    if(options.filters.fromContacts) addressBook.refresh()
    val (where, args) = when (mailbox) {
        is Mailbox.Smart -> { emitAll(threads(mailbox, options)); return@flow }
        is Mailbox.InFolder -> {
            if (dao.folder(mailbox.folderId)?.role == "DRAFTS") { emitAll(threads(mailbox, options)); return@flow }
            "folderId=?" to listOf<Any>(mailbox.folderId)
        }
        is Mailbox.Unified -> {
            if (mailbox.role == FolderRole.DRAFTS) { emitAll(threads(mailbox, options)); return@flow }
            if (mailbox.role == FolderRole.SNOOZED) { emitAll(snoozedMessagesPaging()); return@flow }
            if(mailbox.role.category()!=null) "id IN (SELECT n.id FROM messages n JOIN folders f ON f.id=n.folderId WHERE ${categoryPredicate("n","f",mailbox.role.category()!!)})" to emptyList<Any>()
            else "folderId IN (SELECT id FROM folders WHERE role=?)" to listOf<Any>(mailbox.role.name)
        }
    }
    // Quick filters and sort apply here too (conversation grouping off), not only to the conversation list.
    val extra = ThreadQuery.messageFilters(options.filters, if (options.filters.toMe) ownEmailsForFilters() else emptyList())
    val filter = if (extra == null) "" else " AND (${extra.text})"
    val sql = "SELECT $MESSAGE_META FROM messages WHERE $where$filter AND NOT EXISTS(SELECT 1 FROM snoozed z WHERE z.resurfacedAt IS NULL AND (z.messageId=messages.id OR (z.messageId IS NULL AND z.threadId=messages.threadId))) ORDER BY ${ThreadQuery.messageOrderBy(options.sort)}"
    val allArgs = args + (extra?.args ?: emptyList())
    emitAll(
        Pager(PagingConfig(pageSize = 40)) { dao.pageMessages(SimpleSQLiteQuery(sql, allArgs.toTypedArray())) }
            .flow.map { data -> data.map(::messageSummary) },
    )
}

internal fun messageSummary(row: MessageRow): ThreadSummary = ThreadSummary(
    threadId = ConversationIds.ofMessage(row.id), accountId = row.accountId, subject = row.subject,
    participants = Codec.addressField(row.header, "from"), snippet = row.snippet, lastMessageAt = row.sentAt,
    messageCount = 1, unreadCount = if (row.unread) 1 else 0, isStarred = row.starred, hasAttachments = row.hasAttachments,
    recipients = Codec.addressField(row.header, "to"),
    category = messageCategory(row),
)
