package app.dot.mail.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dot.mail.data.MailGraph
import app.dot.mail.data.ReplyMode
import app.dot.mail.data.model.Draft
import app.dot.mail.data.model.EmailAddress
import app.dot.mail.data.model.Identity
import app.dot.mail.engine.text.EmailHtml
import app.dot.mail.engine.text.QuoteSplitter
import app.dot.mail.ui.ComposeRoute
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    /** True when composing in formatted (rich-text) mode; false for plain text. */
    val isRichText: Boolean = true,
    /** Editable plain-text body (excluding any collapsed [quotedText] tail). */
    val editableText: String = "",
    /** Editor HTML fragment for the editable body when [isRichText] is true. */
    val bodyHtml: String = "",
    /** Trailing quoted reply/forward plain text shown collapsed under the "•••" toggle. */
    val quotedText: String? = null,
    /** Whether the trailing quoted block is expanded in the compose UI. */
    val quoteExpanded: Boolean = false,
) {
    val sender: Sender? get() = draft?.let { d -> senders.firstOrNull { it.accountId == d.accountId && it.identityId == d.identityId } ?: senders.firstOrNull { it.accountId == d.accountId } }
    val hasRecipients: Boolean get() = draft?.let { it.to.isNotEmpty() || it.cc.isNotEmpty() || it.bcc.isNotEmpty() } == true ||
        pending.values.any { it.isNotBlank() }
    val isEmpty: Boolean get() = draft?.let { !hasRecipients && it.subject.isBlank() && it.bodyText.isBlank() && it.attachmentUris.isEmpty() } != false
    /** Full sanitised outbound HTML document when in rich-text mode, or null in plain-text mode. */
    val composedHtml: String? get() = if (isRichText) ComposeViewModel.composeFullHtml(bodyHtml, quotedText) else null
}

/** Hands an in-flight send to the root so the undo snackbar can outlive the compose screen. */
object UndoSendBus {
    data class Pending(val outboxId: Long, val draftId: Long, val scheduledAt: Long?)
    val pending = MutableStateFlow<Pending?>(null)
}

/** Outlives screens: final draft saves and discards must complete after the ViewModel is cleared. */
private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

@OptIn(FlowPreview::class)
class ComposeViewModel(
    private val graph: MailGraph,
    private val route: ComposeRoute,
    private val undoSeconds: () -> Int,
    private val defaultRichText: Boolean = true,
    private val defaultAccountId: () -> Long = { 0L },
    private val includeOriginal: () -> Boolean = { true },
) : ViewModel() {
    private val _state = MutableStateFlow(ComposeState(isRichText = defaultRichText))
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
                a.identities.sortedByDescending { it.isDefault }
                    .ifEmpty { listOf(Identity(0, a.id, a.displayName, a.email, isDefault = true)) }
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
                val prefAccountId = defaultAccountId()
                val preferred = senders.firstOrNull { it.accountId == prefAccountId } ?: senders.firstOrNull()
                Draft(accountId = preferred?.accountId ?: 0, identityId = preferred?.identityId)
            }
            val (initialEditable, rawQuoted) = splitEditableAndQuoted(draft.bodyText)
            val quoted = if (route.replyToMessageId > 0 && !includeOriginal()) null else rawQuoted
            val editable = route.body?.let {
                if (initialEditable.isBlank()) it else "$it\n\n$initialEditable"
            } ?: initialEditable
            // TODO(Codex): once Draft.bodyHtml: String? lands in Models.kt, infer isRich from
            // `if (route.draftId > 0) draft.bodyHtml != null else defaultRichText` and seed initialHtml.
            val isRich = defaultRichText
            val initialHtml = if (isRich && editable.isNotBlank()) EmailHtml.fromPlainText(editable) else ""
            val fullPlain = combineBodyText(editable, quoted)
            val prefilled = draft.copy(
                to = draft.to + route.to.map(::parseAddress),
                cc = draft.cc + route.cc.map(::parseAddress),
                bcc = draft.bcc + route.bcc.map(::parseAddress),
                subject = route.subject ?: draft.subject,
                bodyText = fullPlain,
                attachmentUris = draft.attachmentUris + route.attachments,
            )
            _state.update {
                it.copy(
                    loaded = true,
                    draft = prefilled,
                    senders = senders,
                    showCcBcc = prefilled.cc.isNotEmpty() || prefilled.bcc.isNotEmpty(),
                    isRichText = isRich,
                    editableText = editable,
                    bodyHtml = initialHtml,
                    quotedText = quoted,
                )
            }
            // Autosave, debounced: a crash or process death never loses more than ~1.5 s of typing.
            _state.map { it.draft to it.bodyHtml }.distinctUntilChanged().drop(1).debounce(1_500).collect {
                val s = _state.value
                if (!finished && !_closed.value && !s.isEmpty) save()
            }
        }
    }

    fun edit(transform: (Draft) -> Draft) = _state.update { s -> s.draft?.let { s.copy(draft = transform(it), error = null) } ?: s }

    /** Updates the editable body from the rich-text editor's HTML fragment. */
    fun onRichBodyChanged(editorHtml: String) {
        _state.update { s ->
            val d = s.draft ?: return@update s
            if (s.bodyHtml == editorHtml && s.isRichText) return@update s
            val plainEditable = EmailHtml.toPlainText(editorHtml)
            val fullPlain = combineBodyText(plainEditable, s.quotedText)
            // TODO(Codex): once Draft.bodyHtml lands in Models.kt, also pass
            // bodyHtml = composeFullHtml(editorHtml, s.quotedText) to d.copy(...)
            s.copy(
                editableText = plainEditable,
                bodyHtml = editorHtml,
                draft = d.copy(bodyText = fullPlain),
                error = null,
            )
        }
    }

    /** Updates the editable body when in plain-text mode. */
    fun onPlainBodyChanged(plainEditable: String) {
        _state.update { s ->
            val d = s.draft ?: return@update s
            if (s.editableText == plainEditable && !s.isRichText) return@update s
            val fullPlain = combineBodyText(plainEditable, s.quotedText)
            // TODO(Codex): once Draft.bodyHtml lands in Models.kt, pass bodyHtml = null to d.copy(...)
            s.copy(
                editableText = plainEditable,
                bodyHtml = "",
                draft = d.copy(bodyText = fullPlain),
                error = null,
            )
        }
    }

    /** Converts the current rich-text body to plain text via [EmailHtml.toPlainText]. */
    fun switchToPlainText() {
        _state.update { s ->
            if (!s.isRichText) return@update s
            val plainEditable = EmailHtml.toPlainText(s.bodyHtml)
            val fullPlain = combineBodyText(plainEditable, s.quotedText)
            // TODO(Codex): once Draft.bodyHtml lands in Models.kt, pass bodyHtml = null to d.copy(...)
            s.copy(
                isRichText = false,
                editableText = plainEditable,
                bodyHtml = "",
                draft = s.draft?.copy(bodyText = fullPlain),
            )
        }
    }

    /** Converts the current plain-text body to HTML for the rich-text editor via [EmailHtml.fromPlainText]. */
    fun switchToRichText() {
        _state.update { s ->
            if (s.isRichText) return@update s
            val html = EmailHtml.fromPlainText(s.editableText)
            val fullPlain = combineBodyText(s.editableText, s.quotedText)
            // TODO(Codex): once Draft.bodyHtml lands in Models.kt, pass
            // bodyHtml = composeFullHtml(html, s.quotedText) to d.copy(...)
            s.copy(
                isRichText = true,
                bodyHtml = html,
                draft = s.draft?.copy(bodyText = fullPlain),
            )
        }
    }

    fun toggleQuoteExpanded() = _state.update { it.copy(quoteExpanded = !it.quoteExpanded) }

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
                    // TODO(Codex): once Draft.bodyHtml lands in Models.kt, ensure current carries
                    // bodyHtml = _state.value.composedHtml when in rich-text mode (or null in plain mode).
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
        // TODO(Codex): once Draft.bodyHtml lands in Models.kt, include bodyHtml = _state.value.composedHtml
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

        /**
         * Splits a draft's [rawBodyText] into the editable prefix and the trailing quoted block.
         * Handles `prepareReply` drafts that begin directly with `\n\n> ...` or `\n\nOn ... wrote:`.
         */
        internal fun splitEditableAndQuoted(rawBodyText: String): Pair<String, String?> {
            if (rawBodyText.isBlank()) return "" to null
            val direct = QuoteSplitter.split(rawBodyText)
            if (direct.quoted != null) return direct.visible to direct.quoted
            val trimmedLead = rawBodyText.trimStart('\n', '\r')
            val withSentinel = QuoteSplitter.split("_\n$trimmedLead")
            if (withSentinel.quoted != null && withSentinel.visible == "_") {
                return "" to withSentinel.quoted
            }
            return rawBodyText to null
        }

        /** Combines the user's [editableText] with the collapsed [quotedText] tail for `Draft.bodyText`. */
        internal fun combineBodyText(editableText: String, quotedText: String?): String = when {
            quotedText.isNullOrBlank() -> editableText
            editableText.isBlank() -> "\n\n$quotedText"
            else -> "${editableText.trimEnd()}\n\n$quotedText"
        }

        /** Builds a complete outbound HTML document via [EmailHtml.compose], including any [quotedText]. */
        internal fun composeFullHtml(editorHtml: String, quotedText: String?): String {
            if (quotedText.isNullOrBlank()) return EmailHtml.compose(editorHtml = editorHtml)
            val lines = quotedText.split('\n').map { it.removeSuffix("\r") }
            val first = lines.firstOrNull().orEmpty()
            return if (!first.startsWith(">") && lines.size > 1) {
                val stripped = lines.drop(1).joinToString("\n") { it.removePrefix(">").removePrefix(" ") }
                EmailHtml.compose(
                    editorHtml = editorHtml,
                    quote = EmailHtml.Quote(attribution = first, html = EmailHtml.fromPlainText(stripped)),
                )
            } else {
                val quoteHtml = EmailHtml.fromPlainText(quotedText)
                val combined = if (editorHtml.isBlank()) quoteHtml else "$editorHtml$quoteHtml"
                EmailHtml.compose(editorHtml = combined)
            }
        }
    }
}
