package app.dot.mail.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.dot.mail.data.local.MESSAGE_META
import app.dot.mail.data.local.SnoozeRow
import app.dot.mail.data.model.ConversationIds
import app.dot.mail.data.model.ThreadSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/*
 * Snooze is local-only: rows in `snoozed` hide a conversation (or one message) from every list until `until`.
 * A WorkManager one-time work wakes them; waking sets `resurfacedAt`, which keeps the row (now not hiding anything)
 * so mailbox lists sort the thread to the top as if new. New mail in a snoozed conversation deletes the row (see Sync).
 */

internal const val RESURFACE_KEEP_MS = 7L * 24 * 3600 * 1000

internal suspend fun RealMailGraph.snoozeLocal(threadIds: Collection<Long>, until: Long) = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    require(until > now) { "Pick a time in the future" }
    val created = mutableListOf<Long>()
    database.withTransaction {
        for (id in threadIds.distinct()) {
            if (id < 0) continue // local drafts can't be snoozed
            if (ConversationIds.isMessage(id)) {
                val m = dao.message(ConversationIds.messageIdOf(id)) ?: continue
                dao.activeSnoozeIdsOfMessage(m.id).forEach { cancelSnoozeWork(it) }
                dao.deleteMessageSnooze(m.id)
                created += dao.insertSnooze(SnoozeRow(accountId = m.accountId, threadId = m.threadId, messageId = m.id, until = until, createdAt = now))
            } else {
                val m = dao.threadMessages(id).firstOrNull() ?: continue
                dao.activeSnoozeIdsOfThread(id).forEach { cancelSnoozeWork(it) }
                dao.deleteAllSnoozesOfThread(id)
                created += dao.insertSnooze(SnoozeRow(accountId = m.accountId, threadId = id, until = until, createdAt = now))
            }
        }
    }
    if (scheduling) created.forEach { WorkScheduling.snooze(context, it, until) }
}

internal suspend fun RealMailGraph.unsnoozeLocal(threadIds: Collection<Long>) = withContext(Dispatchers.IO) {
    database.withTransaction {
        for (id in threadIds.distinct()) {
            if (ConversationIds.isMessage(id)) {
                val m = ConversationIds.messageIdOf(id)
                dao.activeSnoozeIdsOfMessage(m).forEach { cancelSnoozeWork(it) }
                dao.deleteMessageSnooze(m)
                // A thread-level snooze that hides this message wakes too.
                dao.message(m)?.let { row -> dao.activeSnoozeIdsOfThread(row.threadId).forEach { cancelSnoozeWork(it) }; dao.deleteThreadSnooze(row.threadId) }
            } else if (id > 0) {
                dao.activeSnoozeIdsOfThread(id).forEach { cancelSnoozeWork(it) }
                dao.deleteAllSnoozesOfThread(id)
            }
        }
    }
}

private fun RealMailGraph.cancelSnoozeWork(id: Long) { if (scheduling) WorkScheduling.cancelSnooze(context, id) }

/** Wakes everything that is due: marks it resurfaced and posts the (account-controlled) notification. Returns how many woke. */
internal suspend fun RealMailGraph.resurfaceDue(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
    val due = dao.dueSnoozes(now)
    for (z in due) {
        dao.resurfaceSnooze(z.id, now)
        val account = dao.account(z.accountId) ?: continue
        val subject = (z.messageId?.let { dao.message(it) } ?: dao.threadMessages(z.threadId).lastOrNull())?.subject.orEmpty()
        if(!isThreadMuted(z.threadId)) MailNotifications.newMail(context, account, z.messageId?.let(ConversationIds::ofMessage) ?: z.threadId, subject.ifBlank { context.getString(R.string.notif_snoozed_message) })
    }
    dao.pruneSnoozes(now - RESURFACE_KEEP_MS)
    due.size
}

private const val STILL_SNOOZED = "z.resurfacedAt IS NULL"

/** Conversations with any active snooze, soonest wake-up first. */
internal fun RealMailGraph.snoozedThreadsPaging(): Flow<PagingData<ThreadSummary>> = Pager(PagingConfig(pageSize = 40)) {
    dao.page(SimpleSQLiteQuery(
        "SELECT t.* FROM threads t WHERE t.count>0 AND EXISTS(SELECT 1 FROM snoozed z WHERE z.threadId=t.id AND $STILL_SNOOZED) " +
            "ORDER BY (SELECT MIN(z.until) FROM snoozed z WHERE z.threadId=t.id AND $STILL_SNOOZED),t.id",
    ))
}.flow.map { data -> data.map { summary(it, false).copy(snoozedUntil = dao.snoozedUntilOfThread(it.id)) } }

/** Single messages with an active snooze (their own, or their thread's), soonest wake-up first. */
internal fun RealMailGraph.snoozedMessagesPaging(): Flow<PagingData<ThreadSummary>> = Pager(PagingConfig(pageSize = 40)) {
    val match = "$STILL_SNOOZED AND (z.messageId=messages.id OR (z.messageId IS NULL AND z.threadId=messages.threadId))"
    dao.pageMessages(SimpleSQLiteQuery(
        "SELECT $MESSAGE_META FROM messages WHERE EXISTS(SELECT 1 FROM snoozed z WHERE $match) " +
            "ORDER BY (SELECT MIN(z.until) FROM snoozed z WHERE $match),id",
    ))
}.flow.map { data -> data.map { messageSummary(it).copy(snoozedUntil = dao.snoozedUntilOfMessage(it.id, it.threadId)) } }

/** Repairs the scheduling gap if a process dies after copying snoozes but before enqueuing work. */
internal suspend fun RealMailGraph.restoreSnoozeWork() {
    if(scheduling) dao.allSnoozes().filter { it.resurfacedAt==null }.forEach { WorkScheduling.snooze(context,it.id,it.until) }
}
