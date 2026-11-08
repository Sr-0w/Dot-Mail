package app.dot.mail.ui.inbox

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.dot.mail.R
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
import kotlinx.coroutines.flow.distinctUntilChanged
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

class InboxViewModel(
    private val graph: MailGraph,
    private val saved: SavedStateHandle,
    /** Settings → "Group into conversations"; false lists single messages. */
    private val grouped: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
    /** Application resources: messages built here follow the app language. */
    private val resources: android.content.res.Resources,
    private val listPrefs: app.dot.mail.data.ListPrefs,
    private val smartFolders: app.dot.mail.data.SmartFolderStore,
) : ViewModel() {
    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any) = resources.getString(id, *args)
    private fun plural(@androidx.annotation.PluralsRes id: Int, n: Int) = resources.getQuantityString(id, n, n)


    private val _mailbox = MutableStateFlow(restoreMailbox())
    val mailbox: StateFlow<Mailbox> = _mailbox.asStateFlow()

    private val focusedAccount = MutableStateFlow<Long?>(saved["focusedAccount"])
    /** The folder last opened from the drawer, so its title survives focusing another account. */
    private val openedFolder = MutableStateFlow<Folder?>(null)

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _syncing = MutableStateFlow(false)
    /** A user-requested sync is still running (after the pull spinner handed off to the discreet progress line). */
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

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
                title = app.dot.mail.ui.common.Format.roleName(resources, box.role),
                subtitle = if (accts.size > 1) str(R.string.inbox_all_accounts) else accts.firstOrNull()?.email,
                role = box.role,
            )
            is Mailbox.Smart -> InboxHeader(
                title = smartFolders.get(box.id)?.name.orEmpty(),
                subtitle = str(R.string.drawer_smart_folders),
                role = null,
            )
            is Mailbox.InFolder -> {
                val folder = d.folders.firstOrNull { it.id == box.folderId } ?: opened?.takeIf { it.id == box.folderId }
                val account = accts.firstOrNull { it.id == folder?.accountId }
                InboxHeader(
                    title = folder?.let { if (it.role == FolderRole.CUSTOM) it.name else app.dot.mail.ui.common.Format.roleName(resources, it.role) } ?: "",
                    subtitle = account?.email,
                    role = folder?.role,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxHeader(str(R.string.inbox_role_inbox), null, FolderRole.INBOX))

    /** Quick filters; they apply to whichever list is open and reset when another list is opened. */
    private val _filters = MutableStateFlow(app.dot.mail.data.model.ThreadFilters())
    val filters: StateFlow<app.dot.mail.data.model.ThreadFilters> = _filters.asStateFlow()
    val sort: StateFlow<app.dot.mail.data.model.InboxSort> = listPrefs.sort
    fun setSort(sort: app.dot.mail.data.model.InboxSort) = listPrefs.setSort(sort)
    fun setFilters(transform: (app.dot.mail.data.model.ThreadFilters) -> app.dot.mail.data.model.ThreadFilters) { _filters.update(transform) }
    fun clearFilters() { _filters.value = app.dot.mail.data.model.ThreadFilters() }

    @OptIn(ExperimentalCoroutinesApi::class)
    val threads: Flow<PagingData<ThreadSummary>> =
        kotlinx.coroutines.flow.combine(_mailbox, grouped.distinctUntilChanged(), listPrefs.sort, _filters) { box, group, sort, filters -> Triple(box, group, app.dot.mail.data.model.ListOptions(sort, filters)) }
            .flatMapLatest { (box, group, options) -> if (group) graph.mail.threads(box, options) else graph.mail.messages(box) }.cachedIn(viewModelScope)


    fun open(mailbox: Mailbox) {
        if (mailbox is Mailbox.InFolder) {
            drawer.value.folders.firstOrNull { it.id == mailbox.folderId }?.let { openedFolder.value = it }
        }
        _selection.value = emptySet()
        if (mailbox != _mailbox.value) _filters.value = app.dot.mail.data.model.ThreadFilters()
        _mailbox.value = mailbox
        when (mailbox) {
            is Mailbox.Smart -> { saved["mailboxSmart"] = mailbox.id; saved.remove<Long>("mailboxFolder"); saved.remove<String>("mailboxRole") }
            is Mailbox.Unified -> { saved["mailboxRole"] = mailbox.role.name; saved.remove<Long>("mailboxFolder"); saved.remove<String>("mailboxSmart") }
            is Mailbox.InFolder -> { saved["mailboxFolder"] = mailbox.folderId; saved.remove<String>("mailboxRole"); saved.remove<String>("mailboxSmart") }
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
        val source = _mailbox.value
        viewModelScope.launch {
            runCatching { graph.mail.apply(action, ids.toList(), source) }
                .onSuccess {
                    _selection.value = emptySet()
                    confirmation(action, count)?.let { _events.send(it) }
                }
                .onFailure { _events.send(it.message ?: str(R.string.inbox_error_action_failed)) }
        }
    }

    fun snooze(ids: Collection<Long>, untilMillis: Long) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { graph.mail.snooze(ids.toList(), untilMillis) }
                .onSuccess {
                    _selection.value = emptySet()
                    _events.send(plural(R.plurals.inbox_snoozed_toast, ids.size))
                }
                .onFailure { _events.send(it.message ?: str(R.string.inbox_error_snooze_failed)) }
        }
    }

    fun mute(ids: Collection<Long>, muted: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { graph.mail.setMuted(ids.toList(), muted) }
                .onSuccess {
                    _selection.value = emptySet()
                    _events.send(str(if (muted) R.string.inbox_muted_toast else R.string.inbox_unmuted_toast))
                }
                .onFailure { _events.send(it.message ?: str(R.string.inbox_error_mute_failed)) }
        }
    }

    fun unsnooze(ids: Collection<Long> = _selection.value) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { graph.mail.unsnooze(ids.toList()) }
                .onSuccess { _selection.value = emptySet(); _events.send(plural(R.plurals.inbox_unsnoozed_toast, ids.size)) }
                .onFailure { _events.send(it.message ?: str(R.string.inbox_error_unsnooze_failed)) }
        }
    }

    fun moveSelection(folderId: Long) {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        val source = _mailbox.value
        viewModelScope.launch {
            runCatching { graph.mail.move(ids, folderId, source) }
                .onSuccess {
                    _selection.value = emptySet()
                    _events.send(plural(R.plurals.inbox_moved_toast, ids.size))
                }
                .onFailure { _events.send(it.message ?: str(R.string.inbox_error_move_failed)) }
        }
    }

    fun refresh() {
        if (_refreshing.value || _syncing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            _syncing.value = true
            val started = System.currentTimeMillis()
            val sync = launch {
                // Bounded: nothing may stay "syncing" forever if a server hangs.
                runCatching { kotlinx.coroutines.withTimeout(60_000) { graph.mail.refresh(_mailbox.value) } }
                    .onFailure { _events.send(if (it is kotlinx.coroutines.TimeoutCancellationException) str(R.string.inbox_sync_still_running) else it.message ?: str(R.string.inbox_error_refresh_failed)) }
                _syncing.value = false
            }
            // The pull spinner is only the "got it" confirmation: it leaves after a short beat (never over the list
            // for a whole slow IMAP sync) and the header progress line takes over. A true→false flip inside one frame
            // never reaches PullToRefreshBox, hence the minimum.
            kotlinx.coroutines.withTimeoutOrNull(SPINNER_MAX_MS) { sync.join() }
            kotlinx.coroutines.delay((SPINNER_MIN_MS - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            _refreshing.value = false
        }
    }

    private fun confirmation(action: MessageAction, n: Int): String? = when (action) {
        MessageAction.ARCHIVE -> plural(R.plurals.inbox_archived_toast, n)
        MessageAction.TRASH -> plural(R.plurals.inbox_trashed_toast, n)
        MessageAction.SPAM -> plural(R.plurals.inbox_spam_toast, n)
        MessageAction.NOT_SPAM -> plural(R.plurals.inbox_not_spam_toast, n)
        MessageAction.DELETE_FOREVER -> plural(R.plurals.inbox_deleted_toast, n)
        else -> null
    }

    private fun restoreMailbox(): Mailbox {
        saved.get<String>("mailboxSmart")?.let { return Mailbox.Smart(it) }
        saved.get<Long>("mailboxFolder")?.let { return Mailbox.InFolder(it) }
        val role = saved.get<String>("mailboxRole")?.let { runCatching { FolderRole.valueOf(it) }.getOrNull() }
        return Mailbox.Unified(role ?: FolderRole.INBOX)
    }

    private companion object {
        const val SPINNER_MIN_MS = 600L
        const val SPINNER_MAX_MS = 900L
    }

    /** Retry a failed sync for one account (the error banner's "Retry"). */
    fun retrySync(accountId: Long) {
        viewModelScope.launch { runCatching { graph.mail.retrySync(accountId) } }
    }
}
