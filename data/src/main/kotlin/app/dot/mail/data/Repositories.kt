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
    val accounts: Flow<List<Account>>

    /** Autodiscovery: built-in providers, then Mozilla ISPDB / autoconfig, then MX-based guess. Null if nothing found. */
    suspend fun discover(email: String): AccountConfig?

    /** Logs in to IMAP and SMTP without saving anything. */
    suspend fun verify(config: AccountConfig, password: String): VerifyResult

    /** Stores the account (credentials encrypted with Android Keystore) and starts the first sync. Returns account id. */
    suspend fun addAccount(config: AccountConfig, password: String): Long

    suspend fun removeAccount(accountId: Long)
}

interface MailRepository {
    fun folders(accountId: Long): Flow<List<Folder>>

    fun threads(mailbox: Mailbox): Flow<PagingData<ThreadSummary>>

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
