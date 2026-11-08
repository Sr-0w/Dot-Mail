package app.dot.mail.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dot.mail.data.MailGraph
import app.dot.mail.data.ReplyMode
import app.dot.mail.data.model.Draft
import app.dot.mail.data.model.EmailAddress
import app.dot.mail.data.model.Identity
import app.dot.mail.ui.ComposeRoute
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A sender choice: an account's identity (or the account itself when it has none). */
data class Sender(val accountId: Long, val identityId: Long?, val name: String, val email: String)

enum class RecipientField { TO, CC, BCC }

data class ComposeState(
    val loaded: Boolean = false,
    val draft: Draft? = null,
    val senders: List<Sender> = emptyList(),
    val showCcBcc: Boolean = false,
    val suggestions: List<EmailAddress> = emptyList(),
    val suggestFor: RecipientField? = null,
    val error: String? = null,
    val sending: Boolean = false,
    val savedOnce: Boolean = false,
    /** Text typed in a recipient field but not yet turned into a chip. */
    val pending: Map<RecipientField, String> = emptyMap(),
) {
    val sender: Sender? get() = draft?.let { d -> senders.firstOrNull { it.accountId == d.accountId && it.identityId == d.identityId } ?: senders.firstOrNull { it.accountId == d.accountId } }
    val hasRecipients: Boolean get() = draft?.let { it.to.isNotEmpty() || it.cc.isNotEmpty() || it.bcc.isNotEmpty() } == true ||
        pending.values.any { it.isNotBlank() }
    val isEmpty: Boolean get() = draft?.let { !hasRecipients && it.subject.isBlank() && it.bodyText.isBlank() && it.attachmentUris.isEmpty() } != false
}

/** Hands an in-flight send to the root so the undo snackbar can outlive the compose screen. */
object UndoSendBus {
    data class Pending(val outboxId: Long, val draftId: Long, val scheduledAt: Long?)
    val pending = MutableStateFlow<Pending?>(null)
}

/** Outlives screens: final draft saves and discards must complete after the ViewModel is cleared. */
private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

@OptIn(FlowPreview::class)
class ComposeViewModel(private val graph: MailGraph, private val route: ComposeRoute, private val undoSeconds: () -> Int) : ViewModel() {
    private val _state = MutableStateFlow(ComposeState())
    val state: StateFlow<ComposeState> = _state.asStateFlow()

    private val _closed = MutableStateFlow(false)
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    private var suggestJob: Job? = null
    /** save / send / discard never overlap (autosave vs. send was a duplicate-draft risk). */
    private val writeLock = Mutex()
    /** Set once send or discard starts: autosave and close must not resurrect the draft. */
    @Volatile private var finished = false

    init {
        viewModelScope.launch {
            val accounts = graph.accounts.accounts.first()
            val senders = accounts.flatMap { a ->
                a.identities.ifEmpty { listOf(Identity(0, a.id, a.displayName, a.email, isDefault = true)) }
                    .map { Sender(a.id, it.id.takeIf { id -> id > 0 }, it.name, it.email) }
            }
            val draft = when {
                route.draftId > 0 -> graph.compose.loadDraft(route.draftId)
                route.replyToMessageId > 0 -> graph.compose.prepareReply(
                    route.replyToMessageId,
                    route.replyMode?.let { runCatching { ReplyMode.valueOf(it) }.getOrNull() } ?: ReplyMode.REPLY,
                )
                else -> null
            } ?: run {
                val first = senders.firstOrNull()
                Draft(accountId = first?.accountId ?: 0, identityId = first?.identityId)
            }
            val prefilled = draft.copy(
                to = draft.to + route.to.map(::parseAddress),
                cc = draft.cc + route.cc.map(::parseAddress),
                bcc = draft.bcc + route.bcc.map(::parseAddress),
                subject = route.subject ?: draft.subject,
                bodyText = route.body?.let { if (draft.bodyText.isBlank()) it else it + "\n\n" + draft.bodyText } ?: draft.bodyText,
                attachmentUris = draft.attachmentUris + route.attachments,
            )
            _state.update {
                it.copy(loaded = true, draft = prefilled, senders = senders, showCcBcc = prefilled.cc.isNotEmpty() || prefilled.bcc.isNotEmpty())
            }
            // Autosave, debounced: a crash or process death never loses more than ~1.5 s of typing.
            _state.map { it.draft }.distinctUntilChanged().drop(1).debounce(1_500).collect {
                val s = _state.value
                if (!finished && !_closed.value && !s.isEmpty) save()
            }
        }
    }

    fun edit(transform: (Draft) -> Draft) = _state.update { s -> s.draft?.let { s.copy(draft = transform(it), error = null) } ?: s }

    fun setSender(sender: Sender) {
        val d = _state.value.draft ?: return
        if (d.accountId == sender.accountId) {
            edit { it.copy(identityId = sender.identityId) }
            return
        }
        // Drafts belong to an account: save a fresh copy under the new one, then drop the old.
        val oldId = d.localId
        edit { it.copy(localId = 0, accountId = sender.accountId, identityId = sender.identityId) }
        if (oldId > 0) viewModelScope.launch {
            save()
            writeLock.withLock { runCatching { graph.compose.discardDraft(oldId) } }
        }
    }

    fun showCcBcc() = _state.update { it.copy(showCcBcc = true) }

    fun addRecipient(field: RecipientField, raw: String): Boolean {
        val parsed = raw.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map(::parseAddress)
        if (parsed.isEmpty()) return true
        val invalid = parsed.firstOrNull { !isValidEmail(it.email) }
        if (invalid != null) {
            _state.update { it.copy(error = "“${invalid.email}” isn't a valid address") }
            return false
        }
        edit { d ->
            when (field) {
                RecipientField.TO -> d.copy(to = (d.to + parsed).distinctBy { it.email.lowercase() })
                RecipientField.CC -> d.copy(cc = (d.cc + parsed).distinctBy { it.email.lowercase() })
                RecipientField.BCC -> d.copy(bcc = (d.bcc + parsed).distinctBy { it.email.lowercase() })
            }
        }
        clearSuggestions()
        return true
    }

    fun addSuggestion(field: RecipientField, address: EmailAddress) {
        edit { d ->
            when (field) {
                RecipientField.TO -> d.copy(to = (d.to + address).distinctBy { it.email.lowercase() })
                RecipientField.CC -> d.copy(cc = (d.cc + address).distinctBy { it.email.lowercase() })
                RecipientField.BCC -> d.copy(bcc = (d.bcc + address).distinctBy { it.email.lowercase() })
            }
        }
        clearSuggestions()
    }

    fun removeRecipient(field: RecipientField, address: EmailAddress) = edit { d ->
        when (field) {
            RecipientField.TO -> d.copy(to = d.to - address)
            RecipientField.CC -> d.copy(cc = d.cc - address)
            RecipientField.BCC -> d.copy(bcc = d.bcc - address)
        }
    }

    fun query(field: RecipientField, prefix: String) {
        suggestJob?.cancel()
        if (prefix.length < 2) { clearSuggestions(); return }
        suggestJob = viewModelScope.launch {
            val found = runCatching { graph.mail.suggestContacts(prefix) }.getOrDefault(emptyList())
            _state.update { it.copy(suggestions = found, suggestFor = field) }
        }
    }

    fun setPending(field: RecipientField, text: String) = _state.update { it.copy(pending = it.pending + (field to text)) }

    /** Turns any typed-but-uncommitted recipient text into chips. False if something is invalid. */
    private fun commitPending(): Boolean {
        var ok = true
        _state.value.pending.forEach { (field, text) ->
            if (text.isNotBlank()) {
                if (addRecipient(field, text)) _state.update { it.copy(pending = it.pending - field) } else ok = false
            }
        }
        return ok
    }

    fun clearSuggestions() = _state.update { it.copy(suggestions = emptyList(), suggestFor = null) }

    fun addAttachments(uris: List<String>) = edit { it.copy(attachmentUris = (it.attachmentUris + uris).distinct()) }
    fun removeAttachment(uri: String) = edit { it.copy(attachmentUris = it.attachmentUris - uri) }

    fun send(scheduledAt: Long? = null) {
        if (_state.value.sending || finished) return
        if (!commitPending()) return
        val s = _state.value
        val d = s.draft ?: return
        when {
            s.senders.isEmpty() -> { _state.update { it.copy(error = "Add an account before sending") }; return }
            !s.hasRecipients -> { _state.update { it.copy(error = "Add at least one recipient") }; return }
        }
        _state.update { it.copy(sending = true) }
        finished = true
        viewModelScope.launch {
            runCatching {
                writeLock.withLock {
                    val current = _state.value.draft ?: d
                    val saved = graph.compose.saveDraft(current.copy(scheduledAt = scheduledAt))
                    saved to graph.compose.send(current.copy(localId = saved, scheduledAt = scheduledAt), undoWindowMs = undoSeconds() * 1_000L)
                }
            }.onSuccess { (draftId, outboxId) ->
                UndoSendBus.pending.value = UndoSendBus.Pending(outboxId, draftId, scheduledAt)
                _closed.value = true
            }.onFailure { e ->
                finished = false
                _state.update { it.copy(sending = false, error = e.message ?: "Couldn't queue the message") }
            }
        }
    }

    /** Back/close: keep a draft if there's anything worth keeping. Returns true if a draft was saved. */
    fun close(): Boolean {
        commitPending()
        val keep = !_state.value.isEmpty && !finished
        _closed.value = true
        // The final save must outlive this ViewModel, which is cleared as the screen closes.
        if (keep) appScope.launch { save(force = true) }
        return keep
    }

    fun discard() {
        if (finished) return
        finished = true
        _closed.value = true
        appScope.launch {
            writeLock.withLock {
                val id = _state.value.draft?.localId ?: 0
                if (id > 0) graph.compose.discardDraft(id)
            }
        }
    }

    private suspend fun save(force: Boolean = false) = writeLock.withLock {
        if (finished && !force) return@withLock
        val d = _state.value.draft ?: return@withLock
        if (d.accountId == 0L) return@withLock
        val id = runCatching { graph.compose.saveDraft(d) }.getOrNull() ?: return@withLock
        _state.update { s -> s.copy(draft = s.draft?.copy(localId = id), savedOnce = true) }
    }

    companion object {
        // local@label(.label)+ : no empty labels (no "..", no trailing dot), TLD ≥ 2 chars.
        private val EMAIL = Regex("^[^@\\s<>]+@[^@\\s<>.]+(\\.[^@\\s<>.]+)*\\.[^@\\s<>.]{2,}$")
        fun isValidEmail(s: String) = EMAIL.matches(s.trim())

        /** Accepts "Name <a@b.c>" or "a@b.c". */
        fun parseAddress(raw: String): EmailAddress {
            val t = raw.trim()
            val lt = t.lastIndexOf('<')
            val gt = t.lastIndexOf('>')
            return if (lt >= 0 && gt > lt) {
                EmailAddress(t.substring(lt + 1, gt).trim(), t.substring(0, lt).trim().trim('"').ifBlank { null })
            } else {
                EmailAddress(t.trim('<', '>'))
            }
        }
    }
}
