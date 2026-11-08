package app.dot.mail.data

import androidx.paging.PagingData
import app.dot.mail.data.model.Account
import app.dot.mail.data.model.AccountConfig
import app.dot.mail.data.model.Attachment
import app.dot.mail.data.model.Draft
import app.dot.mail.data.model.EmailAddress
import app.dot.mail.data.model.Folder
import app.dot.mail.data.model.Mailbox
import app.dot.mail.data.model.Message
import app.dot.mail.data.model.OutboxItem
import app.dot.mail.data.model.MessageAction
import app.dot.mail.data.model.ThreadSummary
import app.dot.mail.data.model.VerifyResult
import kotlinx.coroutines.flow.Flow

/*
 * Contract between UI and data. The UI only talks to these interfaces, obtained from [MailGraph].
 * Rules:
 *  - Reads are Flows backed by the local database: the UI is always offline-first.
 *  - Writes apply to the local DB immediately (optimistic) and are queued for the server;
 *    they never throw for network reasons.
 *  - Suspend functions are main-safe.
 */

interface AccountRepository {
    fun oauthAvailability(provider: app.dot.mail.data.model.OAuthProvider): app.dot.mail.data.model.OAuthAvailability

    /** Launch the returned Custom Tabs Intent from the visible Activity. accountId reauthorizes that account. */
    suspend fun beginOAuth(email: String, provider: app.dot.mail.data.model.OAuthProvider, accountId: Long? = null): android.content.Intent

    /** Consume the callback once. Throws a safe OAuthException; never log the callback URI. */
    suspend fun completeOAuth(resultUri: android.net.Uri): Long

    val accounts: Flow<List<Account>>

    /** Autodiscovery: built-in providers, then Mozilla ISPDB / autoconfig, then MX-based guess. Null if nothing found. */
    suspend fun discover(email: String): AccountConfig?

    /** Logs in to IMAP and SMTP without saving anything. */
    suspend fun verify(config: AccountConfig, password: String): VerifyResult

    /** Stores the account (credentials encrypted with Android Keystore) and starts the first sync. Returns account id. */
    suspend fun addAccount(config: AccountConfig, password: String): Long

    /** Verify IMAP and SMTP before replacing a password/app password. OAuth uses browser reauthorization. */
    suspend fun updatePassword(accountId: Long, password: String): VerifyResult =
        VerifyResult.ConnectionFailed("Password changes are not available")

    suspend fun removeAccount(accountId: Long)

    // ---- Settings backup. Never includes credentials: restored accounts need their password (or browser sign-in) again. ----

    /** JSON (object) with accounts, identities, signatures, per-account settings, folder notify switches, sync and notification rules. */
    suspend fun exportBackup(): String = "{}"

    /** What importing [json] (the string produced by [exportBackup]) would change. Changes nothing. */
    suspend fun previewBackup(json: String): app.dot.mail.data.model.BackupPreview = app.dot.mail.data.model.BackupPreview(emptyList(), error = "Not supported")

    /** Applies it: existing accounts (matched by address) get their identities and preferences, missing ones are re-created signed out. */
    suspend fun applyBackup(json: String): app.dot.mail.data.model.BackupPreview = app.dot.mail.data.model.BackupPreview(emptyList(), error = "Not supported")

    /** Changes the name shown for the account (and used as sender name of its primary identity). */
    suspend fun renameAccount(accountId: Long, displayName: String) {}

    /** Sender identities of an account, the default first. The account address itself is always one of them and cannot be deleted. */
    fun identities(accountId: Long): Flow<List<app.dot.mail.data.model.Identity>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /** Inserts (id == 0) or updates an identity. Returns its id. Throws IllegalArgumentException for an invalid address. */
    suspend fun saveIdentity(identity: app.dot.mail.data.model.Identity): Long = throw UnsupportedOperationException()

    /** Deletes an added identity; the account's own address is permanent. Drafts using it fall back to the default. */
    suspend fun deleteIdentity(accountId: Long, identityId: Long) {}

    suspend fun setDefaultIdentity(accountId: Long, identityId: Long) {}
}

interface MailRepository {
    /** Opt-in persistent Inbox connections. Keeps a foreground notification and can use more battery. */
    val instantNotifications: Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(false)
    /** Enable from a visible app with an account. False if unavailable or the platform refuses service startup. */
    suspend fun setInstantNotificationsEnabled(enabled: Boolean): Boolean = false

    fun folders(accountId: Long): Flow<List<Folder>>

    /** Re-applies the user's classification rules to already cached mail (category views update; nothing moves). */
    suspend fun reclassifyAll() {}

    fun threads(mailbox: Mailbox, options: app.dot.mail.data.model.ListOptions): Flow<PagingData<ThreadSummary>> = threads(mailbox)
    fun threads(mailbox: Mailbox): Flow<PagingData<ThreadSummary>>

    /**
     * The same list with one row per message (Settings → "Group into conversations" off). Rows are [ThreadSummary]s with
     * messageCount = 1 whose threadId is a *virtual* id, see [app.dot.mail.data.model.ConversationIds]; passing it to
     * thread(), apply(), move() affects that single message only.
     */
    fun messages(mailbox: Mailbox): Flow<PagingData<ThreadSummary>> = threads(mailbox)
    /** Per-message list with the inbox sort and quick filters. */
    fun messages(mailbox: Mailbox, options: app.dot.mail.data.model.ListOptions): Flow<PagingData<ThreadSummary>> = messages(mailbox)

    /** Searches local cache immediately; server search results are merged in as they arrive. */
    fun search(query: String, accountId: Long? = null): Flow<PagingData<ThreadSummary>>

    /** Scoped full-content search; keep collecting searchProgress to show partial indexing/server failures. */
    fun search(query: String, scope: app.dot.mail.data.model.SearchScope): Flow<PagingData<app.dot.mail.data.model.SearchResult>> = kotlinx.coroutines.flow.flowOf(PagingData.empty())
    val searchIndexProgress: Flow<app.dot.mail.data.model.SearchIndexProgress> get() = kotlinx.coroutines.flow.flowOf(app.dot.mail.data.model.SearchIndexProgress())
    fun searchProgress(query: String, scope: app.dot.mail.data.model.SearchScope): Flow<app.dot.mail.data.model.SearchProgress> = kotlinx.coroutines.flow.flowOf(app.dot.mail.data.model.SearchProgress())

    /** Messages of a conversation, oldest first. Bodies download on demand via [loadBody]. */
    fun thread(threadId: Long): Flow<List<Message>>

    /** Ensures the body (and inline parts) of a message are cached locally. */
    suspend fun loadBody(messageId: Long)

    /** Downloads an attachment if needed and returns a content:// URI shareable via FileProvider. */
    suspend fun openAttachment(attachment: Attachment): String

    @Deprecated("Pass the displayed sourceMailbox to scope conversation actions")
    suspend fun apply(action: MessageAction, threadIds: Collection<Long>)

    @Deprecated("Pass the displayed sourceMailbox to avoid moving other folder copies")
    suspend fun move(threadIds: Collection<Long>, targetFolderId: Long)

    /** Apply only to messages visible in [sourceMailbox], including per-account folders in a unified role. */
    suspend fun apply(action: MessageAction, threadIds: Collection<Long>, sourceMailbox: Mailbox) = apply(action, threadIds)

    /** Like [apply], and for archive/trash/spam returns what [undo] needs to put the messages back (null otherwise). */
    suspend fun applyUndoable(action: MessageAction, threadIds: Collection<Long>, sourceMailbox: Mailbox): app.dot.mail.data.model.UndoToken? {
        apply(action, threadIds, sourceMailbox); return null
    }

    /** Moves the messages of [token] back to their original folders. Returns how many could not be moved back. */
    suspend fun undo(token: app.dot.mail.data.model.UndoToken): Int = token.moves.size

    /** Move only messages visible in [sourceMailbox]; other copies/replies in the conversation stay put. */
    suspend fun move(threadIds: Collection<Long>, targetFolderId: Long, sourceMailbox: Mailbox) = move(threadIds, targetFolderId)

    /**
     * Every real (non-inline) attachment of every account, newest message first, for the attachment browser.
     * [kind] null = all types; [query] matches the file name or the sender. Mail in Trash and Spam is left out.
     * Listing never downloads anything: [AttachmentItem.isDownloaded] says whether a local copy exists.
     */
    fun attachments(kind: app.dot.mail.data.model.AttachmentKind? = null, query: String = ""): Flow<PagingData<app.dot.mail.data.model.AttachmentItem>> =
        kotlinx.coroutines.flow.flowOf(PagingData.empty())

    // ---- Muting: new mail in a muted conversation stays unread but never notifies. Virtual (message-mode) ids mute their conversation. ----

    suspend fun setMuted(threadIds: Collection<Long>, muted: Boolean) { throw UnsupportedOperationException() }

    /** Real thread ids currently muted (UI draws a small glyph on those rows). */
    fun mutedThreads(): Flow<Set<Long>> = kotlinx.coroutines.flow.flowOf(emptySet())

    // ---- Snooze: local only, nothing changes on the server. Ids may be real or virtual (ConversationIds) thread ids. ----

    /** Hides the conversations (or single messages) until [untilMillis], then they return to the top of the list, with a notification if the account allows it. */
    suspend fun snooze(threadIds: Collection<Long>, untilMillis: Long) { throw UnsupportedOperationException() }

    /** Brings snoozed conversations back at once. */
    suspend fun unsnooze(threadIds: Collection<Long>) { throw UnsupportedOperationException() }

    /** Number of currently snoozed items (drives the drawer entry). The list itself is Mailbox.Unified(FolderRole.SNOOZED), ordered by wake-up time. */
    fun snoozedCount(): Flow<Int> = kotlinx.coroutines.flow.flowOf(0)

    // ---- Folder management. Local change is immediate; the server command is queued and replayed on sync. ----
    // All throw FolderException(reason) for refusals. If the server later refuses (name taken meanwhile, folder
    // gone), the server wins: the local change is rolled back and the account shows a sync error saying why.

    /** Creates a folder called [name] under [parentFolderId] (null = top level). Returns its id. */
    suspend fun createFolder(accountId: Long, parentFolderId: Long?, name: String): Long = throw UnsupportedOperationException()

    suspend fun renameFolder(folderId: Long, newName: String) { throw UnsupportedOperationException() }

    /** Moves a folder (with its subfolders) under [newParentFolderId] (null = top level). */
    suspend fun moveFolder(folderId: Long, newParentFolderId: Long?) { throw UnsupportedOperationException() }

    /**
     * Deletes a custom folder without subfolders. Non-empty folders need [moveContentsTo] (e.g. the Trash): the messages
     * are moved there first, and the folder is deleted on the server only once they have all left it.
     */
    suspend fun deleteFolder(folderId: Long, moveContentsTo: Long? = null) { throw UnsupportedOperationException() }

    /** Pull-to-refresh: syncs the given mailbox now. */
    suspend fun refresh(mailbox: Mailbox)

    /** Retry an account's Inbox now; remaining folders continue in the background and report account errors. */
    suspend fun retrySync(accountId:Long) { refresh(Mailbox.Unified(app.dot.mail.data.model.FolderRole.INBOX)) }

    /** Contact suggestions from addresses seen in mail. */
    suspend fun suggestContacts(prefix: String, limit: Int = 8): List<EmailAddress>
}

interface ComposeRepository {
    /** Queues an attendee reply to the organizer using the invited account identity; returns undo-capable outbox id. */
    suspend fun sendCalendarReply(messageId: Long, partstat: app.dot.mail.engine.calendar.Ics.PartStat): Long =
        throw UnsupportedOperationException("Calendar replies are not available")

    val outbox: Flow<List<OutboxItem>>

    /**
     * Retries a failed queued send or saving its Sent copy. Future sendAt is preserved.
     * NEEDS_REVIEW requires explicit duplicate-risk acknowledgement before SMTP can run again.
     * False if missing, no longer retryable, or processing is active; refresh and try again.
     */
    suspend fun retrySend(outboxId: Long, confirmPossibleDuplicate: Boolean = false): Boolean

    /** Removes only the local queued/review item; never recalls server mail. False during active delivery. */
    suspend fun deleteOutbox(outboxId: Long): Boolean

    /** Persists locally (auto-save). Returns the draft's local id. */
    suspend fun saveDraft(draft: Draft): Long

    suspend fun loadDraft(localId: Long): Draft?

    suspend fun discardDraft(localId: Long)

    /** Prepares a reply / reply-all / forward of a message. */
    suspend fun prepareReply(messageId: Long, mode: ReplyMode): Draft

    /**
     * Queues the draft for sending after [undoWindowMs] (or at scheduledAt). Survives process death.
     * Returns an outbox id usable with [cancelSend].
     */
    suspend fun send(draft: Draft, undoWindowMs: Long = 5_000): Long

    /** True if it was cancelled in time; the draft is restored. */
    suspend fun cancelSend(outboxId: Long): Boolean
}

enum class ReplyMode { REPLY, REPLY_ALL, FORWARD }

/** Service locator built once in Application.onCreate. Kept tiny on purpose (no DI framework). */
interface MailGraph {
    val accounts: AccountRepository
    val mail: MailRepository
    val compose: ComposeRepository
}
