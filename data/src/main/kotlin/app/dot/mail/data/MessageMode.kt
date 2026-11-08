package app.dot.mail.data

import androidx.paging.Pager
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
internal fun RealMailGraph.messagesPaging(mailbox: Mailbox): Flow<PagingData<ThreadSummary>> = flow {
    val (where, args) = when (mailbox) {
        is Mailbox.InFolder -> {
            if (dao.folder(mailbox.folderId)?.role == "DRAFTS") { emitAll(threads(mailbox)); return@flow }
            "folderId=?" to listOf<Any>(mailbox.folderId)
        }
        is Mailbox.Unified -> {
            if (mailbox.role == FolderRole.DRAFTS) { emitAll(threads(mailbox)); return@flow }
            "folderId IN (SELECT id FROM folders WHERE role=?)" to listOf<Any>(mailbox.role.name)
        }
    }
    val sql = "SELECT $MESSAGE_META FROM messages WHERE $where ORDER BY sentAt DESC,id DESC"
    emitAll(
        Pager(PagingConfig(pageSize = 40)) { dao.pageMessages(SimpleSQLiteQuery(sql, args.toTypedArray())) }
            .flow.map { data -> data.map(::messageSummary) },
    )
}

internal fun messageSummary(row: MessageRow): ThreadSummary = ThreadSummary(
    threadId = ConversationIds.ofMessage(row.id), accountId = row.accountId, subject = row.subject,
    participants = Codec.addressField(row.header, "from"), snippet = row.snippet, lastMessageAt = row.sentAt,
    messageCount = 1, unreadCount = if (row.unread) 1 else 0, isStarred = row.starred, hasAttachments = row.hasAttachments,
    recipients = Codec.addressField(row.header, "to"),
)
