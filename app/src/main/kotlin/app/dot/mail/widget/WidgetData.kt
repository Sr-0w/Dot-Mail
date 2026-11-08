package app.dot.mail.widget

import androidx.paging.AsyncPagingDataDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import app.dot.mail.data.MailGraph
import app.dot.mail.data.model.FolderRole
import app.dot.mail.data.model.Mailbox
import app.dot.mail.data.model.ThreadSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What the widget shows: total unread in the unified inbox and the newest unread conversations. */
data class WidgetData(val unread: Int, val threads: List<WidgetThread>)

data class WidgetThread(val threadId: Long, val sender: String, val subject: String)

internal object WidgetRepository {
    const val MAX_THREADS = 3

    /** Unread count of all INBOX folders; re-emits whenever it changes. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun unreadCount(graph: MailGraph) = graph.accounts.accounts.flatMapLatest { accounts ->
        if (accounts.isEmpty()) flowOf(0)
        else combine(accounts.map { graph.mail.folders(it.id) }) { perAccount ->
            perAccount.sumOf { folders -> folders.filter { it.role == FolderRole.INBOX }.sumOf { it.unreadCount } }
        }
    }

    /** One-shot read through the MailGraph contract (Room-backed, so offline). Never touches the main thread. */
    suspend fun load(graph: MailGraph, includeThreads: Boolean, unknownSender: String = "(unknown sender)", noSubject: String = "(no subject)"): WidgetData = withContext(Dispatchers.IO) {
        val unread = unreadCount(graph).first()
        if (!includeThreads || unread == 0) return@withContext WidgetData(unread, emptyList())
        val threads = latestUnread(graph).take(MAX_THREADS).map {
            val from = it.participants.lastOrNull()
            WidgetThread(it.threadId, from?.displayName ?: unknownSender, it.subject.ifBlank { noSubject })
        }
        WidgetData(unread, threads)
    }

    /** First page of the unified inbox, filtered to unread. PagingData has no direct read API, so a differ materialises it. */
    private suspend fun latestUnread(graph: MailGraph): List<ThreadSummary> = coroutineScope {
        val differ = AsyncPagingDataDiffer(
            diffCallback = object : DiffUtil.ItemCallback<ThreadSummary>() {
                override fun areItemsTheSame(a: ThreadSummary, b: ThreadSummary) = a.threadId == b.threadId
                override fun areContentsTheSame(a: ThreadSummary, b: ThreadSummary) = a == b
            },
            updateCallback = object : ListUpdateCallback {
                override fun onInserted(position: Int, count: Int) = Unit
                override fun onRemoved(position: Int, count: Int) = Unit
                override fun onMoved(fromPosition: Int, toPosition: Int) = Unit
                override fun onChanged(position: Int, count: Int, payload: Any?) = Unit
            },
            mainDispatcher = Dispatchers.Default,
            workerDispatcher = Dispatchers.Default,
        )
        val collecting = launch { graph.mail.threads(Mailbox.Unified(FolderRole.INBOX)).collect { differ.submitData(it) } }
        // Poll instead of subscribing to onPagesUpdatedFlow: that flow has no replay, so a fast first load could be missed.
        withTimeoutOrNull(3_000) { while (differ.snapshot().items.isEmpty()) delay(40) }
        val items = differ.snapshot().items
        collecting.cancel()
        items.filter { it.unreadCount > 0 }.sortedByDescending { it.lastMessageAt }
    }
}
