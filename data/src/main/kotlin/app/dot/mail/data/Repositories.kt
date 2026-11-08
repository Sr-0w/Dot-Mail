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

    suspend fun removeAccount(accountId: Long)

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
    fun folders(accountId: Long): Flow<List<Folder>>

    fun threads(mailbox: Mailbox): Flow<PagingData<ThreadSummary>>

    /**
     * The same list with one row per message (Settings → "Group into conversations" off). Rows are [ThreadSummary]s with
     * messageCount = 1 whose threadId is a *virtual* id, see [app.dot.mail.data.model.ConversationIds]; passing it to
     * thread(), apply(), move() affects that single message only.
     */
    fun messages(mailbox: Mailbox): Flow<PagingData<ThreadSummary>> = threads(mailbox)

    /** Searches local cache immediately; server search results are merged in as they arrive. */
    fun search(query: String, accountId: Long? = null): Flow<PagingData<ThreadSummary>>

    /** Messages of a conversation, oldest first. Bodies download on demand via [loadBody]. */
    fun thread(threadId: Long): Flow<List<Message>>

    /** Ensures the body (and inline parts) of a message are cached locally. */
    suspend fun loadBody(messageId: Long)

    /** Downloads an attachment if needed and returns a content:// URI shareable via FileProvider. */
    suspend fun openAttachment(attachment: Attachment): String

    suspend fun apply(action: MessageAction, threadIds: Collection<Long>)

    suspend fun move(threadIds: Collection<Long>, targetFolderId: Long)

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

    /** Contact suggestions from addresses seen in mail. */
    suspend fun suggestContacts(prefix: String, limit: Int = 8): List<EmailAddress>
}

interface ComposeRepository {
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
