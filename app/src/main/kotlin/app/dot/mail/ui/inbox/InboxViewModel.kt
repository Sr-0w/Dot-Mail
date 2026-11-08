package app.dot.mail.ui.inbox

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.dot.mail.data.MailGraph
import app.dot.mail.data.model.Account
import app.dot.mail.data.model.Folder
import app.dot.mail.data.model.FolderRole
import app.dot.mail.data.model.Mailbox
import app.dot.mail.data.model.MessageAction
import app.dot.mail.data.model.ThreadSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DrawerState(
    val accounts: List<Account> = emptyList(),
    /** Account whose folders the drawer lists. */
    val focusedAccountId: Long? = null,
    val folders: List<Folder> = emptyList(),
)

data class InboxHeader(val title: String, val subtitle: String?, val role: FolderRole?)

class InboxViewModel(private val graph: MailGraph, private val saved: SavedStateHandle) : ViewModel() {

    private val _mailbox = MutableStateFlow(restoreMailbox())
    val mailbox: StateFlow<Mailbox> = _mailbox.asStateFlow()

    private val focusedAccount = MutableStateFlow<Long?>(saved["focusedAccount"])
    /** The folder last opened from the drawer, so its title survives focusing another account. */
    private val openedFolder = MutableStateFlow<Folder?>(null)

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _events = Channel<String>(Channel.BUFFERED)
    /** One-off messages for the snackbar. */
    val events: Flow<String> = _events.receiveAsFlow()

    val accounts: StateFlow<List<Account>> =
        graph.accounts.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val drawer: StateFlow<DrawerState> = combine(accounts, focusedAccount) { list, focused ->
        list to (focused?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id)
    }.flatMapLatest { (list, focused) ->
        if (focused == null) flowOf(DrawerState(list, null, emptyList()))
        else combine(flowOf(list), graph.mail.folders(focused)) { l, f -> DrawerState(l, focused, f) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DrawerState())

    /** Folders of every account, used to resolve titles for InFolder mailboxes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val header: StateFlow<InboxHeader> = combine(_mailbox, drawer, accounts, openedFolder) { box, d, accts, opened ->
        when (box) {
            is Mailbox.Unified -> InboxHeader(
                title = app.dot.mail.ui.common.Format.roleName(box.role),
                subtitle = if (accts.size > 1) "All accounts" else accts.firstOrNull()?.email,
                role = box.role,
            )
            is Mailbox.InFolder -> {
                val folder = d.folders.firstOrNull { it.id == box.folderId } ?: opened?.takeIf { it.id == box.folderId }
                val account = accts.firstOrNull { it.id == folder?.accountId }
                InboxHeader(
                    title = folder?.let { if (it.role == FolderRole.CUSTOM) it.name else app.dot.mail.ui.common.Format.roleName(it.role) } ?: "",
                    subtitle = account?.email,
                    role = folder?.role,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxHeader("Inbox", null, FolderRole.INBOX))

    @OptIn(ExperimentalCoroutinesApi::class)
    val threads: Flow<PagingData<ThreadSummary>> =
        _mailbox.flatMapLatest { graph.mail.threads(it) }.cachedIn(viewModelScope)

    fun open(mailbox: Mailbox) {
        if (mailbox is Mailbox.InFolder) {
            drawer.value.folders.firstOrNull { it.id == mailbox.folderId }?.let { openedFolder.value = it }
        }
        _selection.value = emptySet()
        _mailbox.value = mailbox
        when (mailbox) {
            is Mailbox.Unified -> { saved["mailboxRole"] = mailbox.role.name; saved.remove<Long>("mailboxFolder") }
            is Mailbox.InFolder -> { saved["mailboxFolder"] = mailbox.folderId; saved.remove<String>("mailboxRole") }
        }
    }

    fun focusAccount(accountId: Long) {
        focusedAccount.value = accountId
        saved["focusedAccount"] = accountId
    }

    fun toggleSelection(threadId: Long) = _selection.update { if (threadId in it) it - threadId else it + threadId }
    fun clearSelection() { _selection.value = emptySet() }

    fun act(action: MessageAction, ids: Collection<Long> = _selection.value) {
        if (ids.isEmpty()) return
        val count = ids.size
        viewModelScope.launch {
            runCatching { graph.mail.apply(action, ids.toList()) }
                .onSuccess {
                    _selection.value = emptySet()
                    confirmation(action, count)?.let { _events.send(it) }
                }
                .onFailure { _events.send(it.message ?: "That action couldn't be completed") }
        }
    }

    fun moveSelection(folderId: Long) {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { graph.mail.move(ids, folderId) }
                .onSuccess {
                    _selection.value = emptySet()
                    _events.send(if (ids.size == 1) "Moved" else "Moved ${ids.size} conversations")
                }
                .onFailure { _events.send(it.message ?: "Couldn't move") }
        }
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            // Bounded: the spinner must never get stuck if a sync hangs.
            runCatching { kotlinx.coroutines.withTimeout(60_000) { graph.mail.refresh(_mailbox.value) } }
                .onFailure { _events.send(if (it is kotlinx.coroutines.TimeoutCancellationException) "Still syncing in the background" else it.message ?: "Couldn't refresh") }
            _refreshing.value = false
        }
    }

    private fun confirmation(action: MessageAction, n: Int): String? {
        val what = if (n == 1) "Conversation" else "$n conversations"
        return when (action) {
            MessageAction.ARCHIVE -> "$what archived"
            MessageAction.TRASH -> "$what moved to trash"
            MessageAction.SPAM -> "$what marked as spam"
            MessageAction.NOT_SPAM -> "$what moved to inbox"
            MessageAction.DELETE_FOREVER -> "$what deleted"
            else -> null
        }
    }

    private fun restoreMailbox(): Mailbox {
        saved.get<Long>("mailboxFolder")?.let { return Mailbox.InFolder(it) }
        val role = saved.get<String>("mailboxRole")?.let { runCatching { FolderRole.valueOf(it) }.getOrNull() }
        return Mailbox.Unified(role ?: FolderRole.INBOX)
    }
}
