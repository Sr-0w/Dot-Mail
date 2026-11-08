package app.dot.mail.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dot.mail.R
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
data class Sender(
    val accountId: Long,
    val identityId: Long?,
    val name: String,
    val email: String,
    val signature: String? = null,
    val signatureHtml: String? = null,
    val isDefault: Boolean = false,
)

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
    /** True when both Cc and Bcc have no chips and no pending input, allowing Cc/Bcc to collapse. */
    val canCollapseCcBcc: Boolean
        get() = draft?.let { it.cc.isEmpty() && it.bcc.isEmpty() } != false &&
            pending[RecipientField.CC].isNullOrBlank() &&
            pending[RecipientField.BCC].isNullOrBlank()
    /** Combined editor HTML (including any quoted tail) when in rich-text mode, or null in plain-text mode. */
    val composedHtml: String? get() = if (isRichText) ComposeViewModel.combineBodyHtml(bodyHtml, quotedText) else null
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
    private val strings: ((Int, Array<out Any>) -> String)? = null,
) : ViewModel() {
    private fun str(resId: Int, fallback: String, vararg args: Any): String =
        strings?.invoke(resId, args) ?: fallback
    private val _state = MutableStateFlow(ComposeState(isRichText = defaultRichText))
    val state: StateFlow<ComposeState> = _state.asStateFlow()

    private val _closed = MutableStateFlow(false)
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    private var suggestJob: Job? = null
    /** save / send / discard never overlap (autosave vs. send was a duplicate-draft risk). */
    private val writeLock = Mutex()
    /** Set once send or discard starts: autosave and close must not resurrect the draft. */
    @Volatile private var finished = false
    /** Exact signature block last inserted automatically (replaced on sender swap if unedited). */
    private var insertedPlainSig: String? = null
    private var insertedRichSig: String? = null
    private var canAutoInsertSignature: Boolean = false

    init {
        viewModelScope.launch {
            val accounts = graph.accounts.accounts.first()
            val senders = accounts.flatMap { a ->
                a.identities.ifEmpty { listOf(Identity(a.id, a.id, a.displayName, a.email, isDefault = true)) }
                    .map { Sender(a.id, it.id, it.name, it.email, it.signature, it.signatureHtml, it.isDefault) }
            }
            val isExistingDraft = route.draftId != 0L
            val draft = when {
                isExistingDraft -> graph.compose.loadDraft(route.draftId)
                route.replyToMessageId > 0 -> graph.compose.prepareReply(
                    route.replyToMessageId,
                    route.replyMode?.let { runCatching { ReplyMode.valueOf(it) }.getOrNull() } ?: ReplyMode.REPLY,
                )
                else -> null
            } ?: run {
                val prefAccountId = defaultAccountId()
                val targetAccount = accounts.firstOrNull { it.id == prefAccountId } ?: accounts.firstOrNull()
                val preferred = senders.firstOrNull { it.accountId == targetAccount?.id && it.isDefault }
                    ?: senders.firstOrNull { it.accountId == targetAccount?.id }
                    ?: senders.firstOrNull()
                Draft(accountId = preferred?.accountId ?: 0, identityId = preferred?.identityId)
            }
            val activeSender = senders.firstOrNull { it.accountId == draft.accountId && it.identityId == draft.identityId }
                ?: senders.firstOrNull { it.accountId == draft.accountId && it.isDefault }
                ?: senders.firstOrNull { it.accountId == draft.accountId }

            val (initialEditable, rawQuoted) = splitEditableAndQuoted(draft.bodyText)
            val quoted = if (route.replyToMessageId > 0 && !includeOriginal()) null else rawQuoted
            val baseEditable = route.body?.let {
                if (initialEditable.isBlank()) it else "$it\n\n$initialEditable"
            } ?: initialEditable

            val isRich = if (isExistingDraft) draft.bodyHtml != null else defaultRichText

            val plainSig = if (!isExistingDraft) plainSignatureBlock(activeSender) else null
            val richSig = if (!isExistingDraft) richSignatureBlock(activeSender) else null
            canAutoInsertSignature = !isExistingDraft
            insertedPlainSig = plainSig
            insertedRichSig = richSig

            val editable: String
            val initialHtml: String
            if (isExistingDraft) {
                initialHtml = if (isRich) {
                    val savedHtml = draft.bodyHtml.orEmpty()
                    extractEditableHtml(savedHtml, quoted)
                } else {
                    ""
                }
                editable = if (isRich && baseEditable.isBlank() && initialHtml.isNotBlank()) {
                    EmailHtml.toPlainText(initialHtml)
                } else {
                    baseEditable
                }
            } else if (isRich) {
                val contentHtml = if (baseEditable.isNotBlank()) EmailHtml.fromPlainText(baseEditable) else ""
                initialHtml = when {
                    richSig != null && contentHtml.isBlank() -> "<p><br></p>$richSig"
                    richSig != null -> "$contentHtml$richSig"
                    else -> contentHtml
                }
                editable = if (initialHtml.isNotBlank()) EmailHtml.toPlainText(initialHtml) else baseEditable
            } else {
                editable = when {
                    plainSig != null && baseEditable.isBlank() -> "\n\n$plainSig"
                    plainSig != null -> "${baseEditable.trimEnd()}\n\n$plainSig"
                    else -> baseEditable
                }
                initialHtml = ""
            }

            val fullPlain = combineBodyText(editable, quoted)
            val fullHtml = if (isRich) combineBodyHtml(initialHtml, quoted) else null
            val prefilled = draft.copy(
                identityId = activeSender?.identityId ?: draft.identityId,
                to = draft.to + route.to.map(::parseAddress),
                cc = draft.cc + route.cc.map(::parseAddress),
                bcc = draft.bcc + route.bcc.map(::parseAddress),
                subject = route.subject ?: draft.subject,
                bodyText = fullPlain,
                bodyHtml = fullHtml,
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
            val fullHtml = combineBodyHtml(editorHtml, s.quotedText)
            s.copy(
                editableText = plainEditable,
                bodyHtml = editorHtml,
                draft = d.copy(bodyText = fullPlain, bodyHtml = fullHtml),
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
            s.copy(
                editableText = plainEditable,
                bodyHtml = "",
                draft = d.copy(bodyText = fullPlain, bodyHtml = null),
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
            s.copy(
                isRichText = false,
                editableText = plainEditable,
                bodyHtml = "",
                draft = s.draft?.copy(bodyText = fullPlain, bodyHtml = null),
            )
        }
    }

    /** Converts the current plain-text body to HTML for the rich-text editor via [EmailHtml.fromPlainText]. */
    fun switchToRichText() {
        _state.update { s ->
            if (s.isRichText) return@update s
            val html = EmailHtml.fromPlainText(s.editableText)
            val fullPlain = combineBodyText(s.editableText, s.quotedText)
            val fullHtml = combineBodyHtml(html, s.quotedText)
            s.copy(
                isRichText = true,
                bodyHtml = html,
                draft = s.draft?.copy(bodyText = fullPlain, bodyHtml = fullHtml),
            )
        }
    }

    /**
     * Inserts a reusable template above whatever is already written (so a signature stays at the end), filling
     * {{first_name}}/{{name}}/{{email}} from the first recipient and {{my_name}} from the sender. Sets the subject
     * only when it is still empty.
     */
    fun insertTemplate(template: app.dot.mail.engine.text.EmailTemplate) {
        val s = _state.value
        val d = s.draft ?: return
        val to = d.to.firstOrNull()
        val me = s.senders.firstOrNull { it.accountId == d.accountId && it.identityId == d.identityId } ?: s.senders.firstOrNull { it.accountId == d.accountId }
        val vars = buildMap {
            to?.let { a ->
                val name = a.name?.takeIf { it.isNotBlank() }
                put("email", a.email)
                put("name", name ?: a.email)
                put("first_name", name?.substringBefore(' ') ?: a.email.substringBefore('@'))
            }
            me?.let { put("my_name", it.name) }
            put("date", java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)))
        }
        val t = app.dot.mail.engine.text.render(template, vars)
        if (d.subject.isBlank() && !t.subject.isNullOrBlank()) edit { it.copy(subject = t.subject!!) }
        if (s.isRichText) {
            val html = t.bodyHtml ?: EmailHtml.fromPlainText(t.bodyText)
            onRichBodyChanged(if (s.bodyHtml.isBlank() || s.bodyHtml == "<p><br></p>") html else html + s.bodyHtml.removePrefix("<p><br></p>"))
        } else {
            val current = s.editableText
            onPlainBodyChanged(if (current.isBlank()) t.bodyText else t.bodyText.trimEnd() + "\n" + current.trimStart('\n').let { if (it.startsWith("-- ")) "\n$it" else it })
        }
    }

    fun toggleQuoteExpanded() = _state.update { it.copy(quoteExpanded = !it.quoteExpanded) }

    fun setSender(sender: Sender) {
        val d = _state.value.draft ?: return
        swapSignatureForSender(sender)
        val currentDraft = _state.value.draft ?: d
        if (currentDraft.accountId == sender.accountId) {
            edit { it.copy(identityId = sender.identityId) }
            return
        }
        // Drafts belong to an account: save a fresh copy under the new one, then drop the old.
        val oldId = currentDraft.localId
        edit { it.copy(localId = 0, accountId = sender.accountId, identityId = sender.identityId) }
        if (oldId > 0) viewModelScope.launch {
            save()
            writeLock.withLock { runCatching { graph.compose.discardDraft(oldId) } }
        }
    }

    /**
     * Replaces the previously auto-inserted signature block with [newSender]'s signature
     * only if the user has not edited the previous signature block.
     */
    private fun swapSignatureForSender(newSender: Sender) {
        val newPlainSig = plainSignatureBlock(newSender)
        val newRichSig = richSignatureBlock(newSender)
        val oldPlainSig = insertedPlainSig
        val oldRichSig = insertedRichSig

        _state.update { s ->
            val d = s.draft ?: return@update s
            if (s.isRichText) {
                val currentHtml = s.bodyHtml.trimEnd()
                val currentPlain = s.editableText.trimEnd()
                val prefixHtml: String? = when {
                    oldRichSig != null && currentHtml.endsWith(oldRichSig) ->
                        currentHtml.removeSuffix(oldRichSig)
                    oldPlainSig != null && (currentPlain == oldPlainSig || currentPlain.endsWith("\n$oldPlainSig")) -> {
                        val idx = currentHtml.lastIndexOf("<p>-- ")
                            .takeIf { it >= 0 }
                            ?: currentHtml.lastIndexOf("-- ").takeIf { it >= 0 }?.let { dash ->
                                currentHtml.lastIndexOf("<p", dash).takeIf { it >= 0 } ?: dash
                            }
                        idx?.let { currentHtml.substring(0, it) }
                    }
                    oldRichSig == null && oldPlainSig == null && canAutoInsertSignature ->
                        currentHtml
                    else -> null
                }
                if (prefixHtml == null) {
                    insertedPlainSig = null
                    insertedRichSig = null
                    canAutoInsertSignature = false
                    return@update s
                }
                insertedPlainSig = newPlainSig
                insertedRichSig = newRichSig
                val nextHtml = when {
                    newRichSig != null && prefixHtml.isBlank() -> "<p><br></p>$newRichSig"
                    newRichSig != null -> "$prefixHtml$newRichSig"
                    prefixHtml == "<p><br></p>" -> ""
                    else -> prefixHtml
                }
                val nextPlain = EmailHtml.toPlainText(nextHtml)
                val fullPlain = combineBodyText(nextPlain, s.quotedText)
                val fullHtml = combineBodyHtml(nextHtml, s.quotedText)
                s.copy(
                    editableText = nextPlain,
                    bodyHtml = nextHtml,
                    draft = d.copy(bodyText = fullPlain, bodyHtml = fullHtml),
                )
            } else {
                val currentPlain = s.editableText.trimEnd()
                val prefixPlain: String? = when {
                    oldPlainSig != null && (currentPlain == oldPlainSig || currentPlain.endsWith("\n$oldPlainSig")) ->
                        currentPlain.removeSuffix(oldPlainSig).trimEnd('\n', '\r')
                    oldPlainSig == null && oldRichSig == null && canAutoInsertSignature ->
                        currentPlain
                    else -> null
                }
                if (prefixPlain == null) {
                    insertedPlainSig = null
                    insertedRichSig = null
                    canAutoInsertSignature = false
                    return@update s
                }
                insertedPlainSig = newPlainSig
                insertedRichSig = newRichSig
                val nextPlain = when {
                    newPlainSig != null && prefixPlain.isEmpty() -> "\n\n$newPlainSig"
                    newPlainSig != null -> "$prefixPlain\n\n$newPlainSig"
                    else -> prefixPlain
                }
                val fullPlain = combineBodyText(nextPlain, s.quotedText)
                s.copy(
                    editableText = nextPlain,
                    bodyHtml = "",
                    draft = d.copy(bodyText = fullPlain, bodyHtml = null),
                )
            }
        }
    }

    fun showCcBcc() = _state.update { it.copy(showCcBcc = true) }

    fun hideCcBcc() = _state.update { s ->
        if (!s.canCollapseCcBcc) s
        else s.copy(
            showCcBcc = false,
            suggestFor = s.suggestFor.takeUnless { it == RecipientField.CC || it == RecipientField.BCC },
            suggestions = if (s.suggestFor == RecipientField.CC || s.suggestFor == RecipientField.BCC) emptyList() else s.suggestions,
        )
    }

    /** Toggles Cc/Bcc visibility (collapses only when both Cc and Bcc are empty). Returns the new `showCcBcc` state. */
    fun toggleCcBcc(): Boolean {
        val s = _state.value
        return if (!s.showCcBcc) {
            showCcBcc()
            true
        } else if (s.canCollapseCcBcc) {
            hideCcBcc()
            false
        } else {
            true
        }
    }

    fun addRecipient(field: RecipientField, raw: String): Boolean {
        val parsed = raw.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map(::parseAddress)
        if (parsed.isEmpty()) return true
        val invalid = parsed.firstOrNull { !isValidEmail(it.email) }
        if (invalid != null) {
            _state.update {
                it.copy(
                    error = str(
                        R.string.compose_error_invalid_address,
                        "“${invalid.email}” isn't a valid address",
                        invalid.email,
                    ),
                )
            }
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
            s.senders.isEmpty() -> {
                _state.update { it.copy(error = str(R.string.compose_error_no_account, "Add an account before sending")) }
                return
            }
            !s.hasRecipients -> {
                _state.update { it.copy(error = str(R.string.compose_error_no_recipients, "Add at least one recipient")) }
                return
            }
        }
        _state.update { it.copy(sending = true) }
        finished = true
        viewModelScope.launch {
            runCatching {
                writeLock.withLock {
                    val sNow = _state.value
                    val base = sNow.draft ?: d
                    val current = base.copy(
                        bodyHtml = if (sNow.isRichText) combineBodyHtml(sNow.bodyHtml, sNow.quotedText) else null,
                        scheduledAt = scheduledAt,
                    )
                    val saved = graph.compose.saveDraft(current)
                    saved to graph.compose.send(current.copy(localId = saved), undoWindowMs = undoSeconds() * 1_000L)
                }
            }.onSuccess { (draftId, outboxId) ->
                UndoSendBus.pending.value = UndoSendBus.Pending(outboxId, draftId, scheduledAt)
                _closed.value = true
            }.onFailure { e ->
                finished = false
                _state.update {
                    it.copy(
                        sending = false,
                        error = e.message ?: str(R.string.compose_error_send_failed, "Couldn't queue the message"),
                    )
                }
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
        val sNow = _state.value
        val d = sNow.draft ?: return@withLock
        if (d.accountId == 0L) return@withLock
        val toSave = d.copy(
            bodyHtml = if (sNow.isRichText) combineBodyHtml(sNow.bodyHtml, sNow.quotedText) else null,
        )
        val id = runCatching { graph.compose.saveDraft(toSave) }.getOrNull() ?: return@withLock
        _state.update { s -> s.copy(draft = s.draft?.copy(localId = id, bodyHtml = toSave.bodyHtml), savedOnce = true) }
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

        /** Combines the editor's [editorHtml] fragment with the collapsed [quotedText] tail for `Draft.bodyHtml`. */
        internal fun combineBodyHtml(editorHtml: String, quotedText: String?): String {
            if (quotedText.isNullOrBlank()) return editorHtml
            val quoteHtml = EmailHtml.fromPlainText(quotedText)
            return if (editorHtml.isBlank()) quoteHtml else "$editorHtml$quoteHtml"
        }

        /** Extracts the editable HTML portion when reopening a saved rich draft that also has a [quotedText] tail. */
        internal fun extractEditableHtml(savedHtml: String, quotedText: String?): String {
            if (savedHtml.isBlank()) return ""
            if (quotedText.isNullOrBlank()) return savedHtml
            val quoteHtml = EmailHtml.fromPlainText(quotedText)
            return if (quoteHtml.isNotEmpty() && savedHtml.endsWith(quoteHtml)) {
                savedHtml.removeSuffix(quoteHtml)
            } else {
                savedHtml
            }
        }

        /** Formats [sender]'s plain-text signature block (`"-- \n<sig>"`), or null if none. */
        internal fun plainSignatureBlock(sender: Sender?): String? {
            if (sender == null) return null
            val sig = sender.signature?.trim()?.takeIf { it.isNotEmpty() }
                ?: sender.signatureHtml?.let { EmailHtml.toPlainText(it).trim() }?.takeIf { it.isNotEmpty() }
                ?: return null
            return "-- \n$sig"
        }

        /** Formats [sender]'s rich-text signature block (`"<p>-- <br>...</p>"`), escaping plain signature when [Sender.signatureHtml] is null. */
        internal fun richSignatureBlock(sender: Sender?): String? {
            if (sender == null) return null
            val rich = sender.signatureHtml?.trim()?.takeIf { it.isNotEmpty() }
            val body = rich ?: sender.signature?.trim()?.takeIf { it.isNotEmpty() }?.let { plain ->
                plain.lines().joinToString("<br>") { line ->
                    line.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\"", "&quot;")
                }
            } ?: return null
            return "<p>-- <br>$body</p>"
        }
    }
}
