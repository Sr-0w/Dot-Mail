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
    val signatureLogo: String? = null,
    val signatureOnNew: Boolean = true,
    val signatureOnReply: Boolean = true,
) {
    /** Stable id of the signature owner (the identity id; the account id for a bare account). */
    val key: Long get() = identityId ?: accountId
    val hasSignature: Boolean get() = !signature.isNullOrBlank() || !signatureHtml.isNullOrBlank() || signatureLogo != null
}

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
    /** The signature is kept out of the editor text: it is appended when the draft body is assembled. */
    val signatureEnabled: Boolean = false,
    /** Explicit per-message choice of another sender's signature; null follows the From identity. */
    val signatureSourceKey: Long? = null,
    /** True for replies and forwards (they use the "replies" default of the signature). */
    val isReplyContext: Boolean = false,
    /** OpenKeychain is installed: Sign/Encrypt are offered. */
    val pgpAvailable: Boolean = false,
    /** While encrypting: recipients without a public key, and whether the sender's own key is missing. */
    val pgpMissing: List<String> = emptyList(),
    val pgpOwnKeyMissing: Boolean = false,
    val pgpChecking: Boolean = false,
) {
    /** The sender whose signature is currently attached to this message, or null when none. */
    val signatureSender: Sender? get() = if (!signatureEnabled) null else
        (signatureSourceKey?.let { key -> senders.firstOrNull { it.key == key } } ?: sender)?.takeIf { it.hasSignature }
    val sender: Sender? get() = draft?.let { d -> senders.firstOrNull { it.accountId == d.accountId && it.identityId == d.identityId } ?: senders.firstOrNull { it.accountId == d.accountId } }
    val hasRecipients: Boolean get() = draft?.let { it.to.isNotEmpty() || it.cc.isNotEmpty() || it.bcc.isNotEmpty() } == true ||
        pending.values.any { it.isNotBlank() }
    val isEmpty: Boolean get() = draft?.let { !hasRecipients && it.subject.isBlank() && editableText.isBlank() && quotedText.isNullOrBlank() && it.attachmentUris.isEmpty() } != false
    /** True when both Cc and Bcc have no chips and no pending input, allowing Cc/Bcc to collapse. */
    val canCollapseCcBcc: Boolean
        get() = draft?.let { it.cc.isEmpty() && it.bcc.isEmpty() } != false &&
            pending[RecipientField.CC].isNullOrBlank() &&
            pending[RecipientField.BCC].isNullOrBlank()
    /** Combined editor HTML (including any quoted tail) when in rich-text mode, or null in plain-text mode. */
    val composedHtml: String? get() = if (isRichText) ComposeViewModel.combineBodyHtml(bodyHtml, quotedText, ComposeViewModel.richSignatureBlock(signatureSender)) else null
}

/** The send was stopped before anything was queued (e.g. a recipient has no key). */
private class PgpBlocked(message: String) : Exception(message)

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
    /** OpenPGP via OpenKeychain; null or unavailable hides Sign/Encrypt. */
    private val pgp: app.dot.mail.ui.pgp.PgpService? = null,
    /** (sign, encrypt) defaults of an account's settings for new messages. */
    private val pgpDefaults: (Long) -> Pair<Boolean, Boolean> = { false to false },
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
    /** Once the user removed/changed the signature by hand, From changes no longer re-apply the defaults. */
    private var signatureTouched = false
    private var pgpTouched = false
    private val keyCache = HashMap<String, Boolean>()

    init {
        viewModelScope.launch {
            val accounts = graph.accounts.accounts.first()
            val senders = accounts.flatMap { a ->
                a.identities.ifEmpty { listOf(Identity(a.id, a.id, a.displayName, a.email, isDefault = true)) }
                    .map { Sender(a.id, it.id, it.name, it.email, it.signature, it.signatureHtml, it.isDefault, it.signatureLogo, it.signatureOnNew, it.signatureOnReply) }
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

            val isReply = route.replyToMessageId > 0
            var sigEnabled = false
            var sigSource: Long? = null

            var editable: String
            val initialHtml: String
            if (isExistingDraft) {
                var html = ""
                if (isRich) {
                    val split = splitSignature(extractEditableHtml(draft.bodyHtml.orEmpty(), quoted))
                    html = split.second
                    split.first?.let { sigEnabled = true; sigSource = it.takeIf { id -> id != activeSender?.key } }
                }
                initialHtml = html
                editable = if (isRich && baseEditable.isBlank() && initialHtml.isNotBlank()) {
                    EmailHtml.toPlainText(initialHtml)
                } else {
                    baseEditable
                }
                if (!isRich) {
                    // Plain drafts store the block as "-- \n<sig>" before the quote: recognise any known signature.
                    val trimmed = editable.trimEnd()
                    val match = senders.filter { it.accountId == draft.accountId }.firstOrNull { sn ->
                        plainSignatureBlock(sn)?.let { trimmed == it || trimmed.endsWith("\n$it") } == true
                    }
                    if (match != null) {
                        sigEnabled = true
                        sigSource = match.key.takeIf { it != activeSender?.key }
                        editable = trimmed.removeSuffix(plainSignatureBlock(match)!!).trimEnd()
                    }
                }
                signatureTouched = true
            } else {
                sigEnabled = activeSender?.let { signatureDefault(it, isReply) } == true
                initialHtml = if (isRich && baseEditable.isNotBlank()) EmailHtml.fromPlainText(baseEditable) else ""
                editable = if (isRich && initialHtml.isNotBlank()) EmailHtml.toPlainText(initialHtml) else baseEditable
            }

            val base = ComposeState(
                loaded = true, senders = senders, isRichText = isRich, editableText = editable, bodyHtml = initialHtml,
                quotedText = quoted, signatureEnabled = sigEnabled, signatureSourceKey = sigSource, isReplyContext = isReply,
                pgpAvailable = pgp?.available() == true,
                draft = draft.copy(identityId = activeSender?.identityId ?: draft.identityId),
            )
            val pgpOn = base.pgpAvailable && !isExistingDraft
            val pgpDefault = if (pgpOn) pgpDefaults(draft.accountId) else false to false
            if (isExistingDraft) pgpTouched = true
            val prefilled = base.draft!!.copy(
                pgpSign = if (pgpOn) pgpDefault.first else draft.pgpSign, pgpEncrypt = if (pgpOn) pgpDefault.second else draft.pgpEncrypt,
                to = draft.to + route.to.map(::parseAddress),
                cc = draft.cc + route.cc.map(::parseAddress),
                bcc = draft.bcc + route.bcc.map(::parseAddress),
                subject = route.subject ?: draft.subject,
                bodyText = plainBody(base, editable),
                bodyHtml = if (isRich) htmlBody(base, initialHtml) else null,
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
                    signatureEnabled = sigEnabled,
                    signatureSourceKey = sigSource,
                    isReplyContext = isReply,
                    pgpAvailable = pgp?.available() == true,
                )
            }
            watchPgpKeys()
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
            val fullPlain = plainBody(s, plainEditable)
            val fullHtml = htmlBody(s, editorHtml)
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
            val fullPlain = plainBody(s, plainEditable)
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
            val fullPlain = plainBody(s, plainEditable)
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
            val fullPlain = plainBody(s, s.editableText)
            val fullHtml = htmlBody(s, html)
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
        val currentDraft = d
        if (currentDraft.accountId == sender.accountId) {
            edit { it.copy(identityId = sender.identityId) }
        } else {
            // Drafts belong to an account: save a fresh copy under the new one, then drop the old.
            val oldId = currentDraft.localId
            edit { it.copy(localId = 0, accountId = sender.accountId, identityId = sender.identityId) }
            if (oldId > 0) viewModelScope.launch {
                save()
                writeLock.withLock { runCatching { graph.compose.discardDraft(oldId) } }
            }
        }
        if (!pgpTouched && _state.value.pgpAvailable && d.accountId != sender.accountId) {
            val (sign, encrypt) = pgpDefaults(sender.accountId)
            edit { it.copy(pgpSign = sign, pgpEncrypt = encrypt) }
        }
        if (!pgpTouched && _state.value.pgpAvailable && d.accountId != sender.accountId) {
            val (sign, encrypt) = pgpDefaults(sender.accountId)
            edit { it.copy(pgpSign = sign, pgpEncrypt = encrypt) }
        }
        // The signature lives outside the editor text, so the swap can never touch what the user wrote.
        _state.update { s ->
            val enabled = if (signatureTouched) s.signatureEnabled else signatureDefault(sender, s.isReplyContext)
            refreshBodies(s.copy(signatureEnabled = enabled, signatureSourceKey = s.signatureSourceKey?.takeIf { it != sender.key }))
        }
    }

    /** Per-message: leave the signature out of this message. */
    fun removeSignature() {
        signatureTouched = true
        _state.update { refreshBodies(it.copy(signatureEnabled = false, signatureSourceKey = null)) }
    }

    /** Per-message: attach [sender]'s signature (or, for the From identity, its own). */
    fun useSignatureOf(sender: Sender) {
        signatureTouched = true
        _state.update { s ->
            refreshBodies(s.copy(signatureEnabled = true, signatureSourceKey = sender.key.takeIf { it != s.sender?.key }))
        }
    }

    /** Recomputes the draft's plain/HTML bodies after a signature change. */
    private fun refreshBodies(s: ComposeState): ComposeState {
        val d = s.draft ?: return s
        return s.copy(draft = d.copy(
            bodyText = plainBody(s, s.editableText),
            bodyHtml = if (s.isRichText) htmlBody(s, s.bodyHtml) else null,
        ))
    }

    private fun plainBody(s: ComposeState, editable: String) =
        combineBodyText(editable, s.quotedText, plainSignatureBlock(s.signatureSender))

    private fun htmlBody(s: ComposeState, editorHtml: String) =
        combineBodyHtml(editorHtml, s.quotedText, richSignatureBlock(s.signatureSender))

    // ---- OpenPGP -------------------------------------------------------------------------------------------------------

    private val _pgpPrompt = MutableStateFlow<android.app.PendingIntent?>(null)
    /** An OpenKeychain screen (passphrase, key choice) the UI must launch; answer with [pgpResult]. */
    val pgpPrompt: StateFlow<android.app.PendingIntent?> = _pgpPrompt.asStateFlow()
    private var pgpAnswer: kotlinx.coroutines.CompletableDeferred<android.content.Intent?>? = null

    private suspend fun interact(p: android.app.PendingIntent): android.content.Intent? {
        val answer = kotlinx.coroutines.CompletableDeferred<android.content.Intent?>()
        pgpAnswer = answer
        _pgpPrompt.value = p
        return try { answer.await() } finally { _pgpPrompt.value = null; pgpAnswer = null }
    }

    /** The result of the launched OpenKeychain screen; null = the user backed out. */
    fun pgpResult(data: android.content.Intent?) { pgpAnswer?.complete(data) }

    fun setPgp(sign: Boolean? = null, encrypt: Boolean? = null) {
        pgpTouched = true
        edit { it.copy(pgpSign = sign ?: it.pgpSign, pgpEncrypt = encrypt ?: it.pgpEncrypt) }
    }

    private fun recipientsOf(d: Draft) = (d.to + d.cc + d.bcc).map { it.email }.distinctBy { it.lowercase() }

    private suspend fun hasKey(email: String): Boolean = keyCache.getOrPut(email.lowercase()) { pgp?.hasKey(email) == true }

    /** While encrypting, keeps [ComposeState.pgpMissing] in step with the recipients (cached lookups, debounced). */
    private fun watchPgpKeys() {
        if (pgp == null || !pgp.available()) return
        viewModelScope.launch {
            _state.map { s -> Triple(s.draft?.pgpEncrypt == true, s.draft?.let(::recipientsOf).orEmpty(), s.sender?.email) }
                .distinctUntilChanged().debounce(350).collect { (encrypt, recipients, own) ->
                    if (!encrypt) { _state.update { it.copy(pgpMissing = emptyList(), pgpOwnKeyMissing = false, pgpChecking = false) }; return@collect }
                    _state.update { it.copy(pgpChecking = true) }
                    val missing = recipients.filterNot { hasKey(it) }
                    val ownMissing = own != null && !hasKey(own)
                    _state.update { it.copy(pgpMissing = missing, pgpOwnKeyMissing = ownMissing, pgpChecking = false) }
                }
        }
    }

    /** Drops To/Cc/Bcc entries that have no public key (the inline warning's "Remove them"). */
    fun removeRecipientsWithoutKey() {
        val missing = _state.value.pgpMissing.map { it.lowercase() }.toSet()
        edit { d -> d.copy(to = d.to.filterNot { it.email.lowercase() in missing }, cc = d.cc.filterNot { it.email.lowercase() in missing }, bcc = d.bcc.filterNot { it.email.lowercase() in missing }) }
    }

    /** The inline warning's "Send unencrypted": turns encryption off for this message and sends it. */
    fun sendUnencrypted() { setPgp(encrypt = false); send() }

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
                        bodyHtml = if (sNow.isRichText) htmlBody(sNow, sNow.bodyHtml) else null,
                        scheduledAt = scheduledAt,
                    )
                    val saved = graph.compose.saveDraft(current)
                    val queued = if (current.pgpSign || current.pgpEncrypt) {
                        val service = requireNotNull(pgp) { "OpenPGP is not available" }
                        if (current.pgpEncrypt) {
                            val missing = recipientsOf(current).filterNot { hasKey(it) }
                            if (missing.isNotEmpty()) {
                                _state.update { it.copy(pgpMissing = missing) }
                                throw PgpBlocked(str(R.string.compose_pgp_error_missing_keys, "Some recipients have no key"))
                            }
                        }
                        val own = sNow.sender?.email ?: current.to.firstOrNull()?.email.orEmpty()
                        graph.compose.sendProtected(current.copy(localId = saved), undoSeconds() * 1_000L, service.protector(own, ::interact))
                    } else graph.compose.send(current.copy(localId = saved), undoWindowMs = undoSeconds() * 1_000L)
                    saved to queued
                }
            }.onSuccess { (draftId, outboxId) ->
                UndoSendBus.pending.value = UndoSendBus.Pending(outboxId, draftId, scheduledAt)
                _closed.value = true
            }.onFailure { e ->
                finished = false
                _state.update {
                    it.copy(
                        sending = false,
                        error = when {
                            e is PgpBlocked -> e.message
                            e is app.dot.mail.ui.pgp.OpenKeychain.PgpException && e.cancelled -> str(R.string.compose_pgp_error_cancelled, "Sending was cancelled")
                            e is app.dot.mail.ui.pgp.OpenKeychain.PgpException -> str(R.string.compose_pgp_error_failed, "OpenPGP couldn't protect this message")
                            else -> e.message ?: str(R.string.compose_error_send_failed, "Couldn't queue the message")
                        },
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
            bodyHtml = if (sNow.isRichText) htmlBody(sNow, sNow.bodyHtml) else null,
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

        /** Whether [sender]'s signature is added by default to a new message or to a reply/forward. */
        internal fun signatureDefault(sender: Sender, isReply: Boolean): Boolean =
            sender.hasSignature && (if (isReply) sender.signatureOnReply else sender.signatureOnNew)

        /** Combines the user's [editableText], the optional plain [signature] block and the collapsed [quotedText] tail for `Draft.bodyText`. */
        internal fun combineBodyText(editableText: String, quotedText: String?, signature: String? = null): String {
            val head = when {
                signature == null -> editableText
                editableText.isBlank() -> "\n\n$signature"
                else -> "${editableText.trimEnd()}\n\n$signature"
            }
            return when {
                quotedText.isNullOrBlank() -> head
                head.isBlank() -> "\n\n$quotedText"
                else -> "${head.trimEnd()}\n\n$quotedText"
            }
        }

        /** Combines the editor's [editorHtml] fragment, the marked signature block and the quoted tail for `Draft.bodyHtml`. */
        internal fun combineBodyHtml(editorHtml: String, quotedText: String?, signatureBlock: String? = null): String {
            val head = if (signatureBlock == null) editorHtml else {
                (if (editorHtml.isBlank()) "<p><br></p>" else editorHtml) + signatureBlock
            }
            if (quotedText.isNullOrBlank()) return head
            val quoteHtml = EmailHtml.fromPlainText(quotedText)
            return if (head.isBlank()) quoteHtml else "$head$quoteHtml"
        }

        private const val SIG_OPEN = "<div data-dot-sig=\""

        /**
         * Splits the marked signature block (always generated by [richSignatureBlock], so it contains no nested
         * `div`) off [html]. Returns the owner key (null when there is none) and the HTML without the block.
         */
        internal fun splitSignature(html: String): Pair<Long?, String> {
            val start = html.indexOf(SIG_OPEN)
            if (start < 0) return null to html
            val idEnd = html.indexOf("\">", start + SIG_OPEN.length)
            val close = if (idEnd < 0) -1 else html.indexOf("</div>", idEnd)
            val id = if (idEnd < 0) null else html.substring(start + SIG_OPEN.length, idEnd).toLongOrNull()
            if (id == null || close < 0) return null to html
            val rest = html.removeRange(start, close + "</div>".length)
            return id to (if (rest == "<p><br></p>") "" else rest)
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

        /** [sender]'s signature text as an HTML fragment (rich when set, else the escaped plain text), or null. */
        internal fun signatureBodyHtml(sender: Sender?): String? {
            if (sender == null) return null
            val rich = sender.signatureHtml?.trim()?.takeIf { it.isNotEmpty() }
            return rich ?: sender.signature?.trim()?.takeIf { it.isNotEmpty() }?.let { plain ->
                plain.lines().joinToString("<br>") { line ->
                    line.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\"", "&quot;")
                }
            }
        }

        /** Formats [sender]'s marked rich signature block (`<div data-dot-sig=id><p>-- <br>…</p><p><img cid></p></div>`), or null if none. */
        internal fun richSignatureBlock(sender: Sender?): String? {
            if (sender == null || !sender.hasSignature) return null
            val logo = sender.signatureLogo?.let { "<p><img src=\"cid:${app.dot.mail.data.SignatureLogos.CID}\" alt=\"\"></p>" }.orEmpty()
            val text = signatureBodyHtml(sender)?.let { "<p>-- <br>$it</p>" }.orEmpty()
            return "$SIG_OPEN${sender.key}\">$text$logo</div>"
        }
    }
}
