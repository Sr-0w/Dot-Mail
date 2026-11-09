package app.dot.mail.data.model

/*
 * Domain models shared by the UI (:app) and the data layer (:data).
 * This file is the contract between the two; change it deliberately and tell the team.
 * Timestamps are epoch milliseconds (UTC).
 */

data class Account(
    val id: Long,
    val displayName: String,
    val email: String,
    /** Stable per-account accent, index into the design system's account palette. */
    val colorIndex: Int,
    val identities: List<Identity> = emptyList(),
    val syncState: SyncState = SyncState.Idle,
    /** When the last *complete, error-free* sync of this account finished; 0 = never. Persisted across restarts. */
    val lastSyncedAt: Long = 0L,
)

data class Identity(
    val id: Long,
    val accountId: Long,
    val name: String,
    val email: String,
    /** Plain-text signature (also used as the text part when [signatureHtml] is set). */
    val signature: String? = null,
    val isDefault: Boolean = false,
    /** Optional Reply-To address for messages sent with this identity. */
    val replyTo: String? = null,
    /** Rich signature for the HTML composer. */
    val signatureHtml: String? = null,
    /** File name (in [app.dot.mail.data.SignatureLogos]) of an optional inline logo sent as a `cid:` part. */
    val signatureLogo: String? = null,
    /** Insert the signature automatically in new messages / in replies and forwards. */
    val signatureOnNew: Boolean = true,
    val signatureOnReply: Boolean = true,
)

sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data class Error(
        val message: String, val isAuthError: Boolean = false, val errorKind: app.dot.mail.engine.ConnectionFailure? = null,
        /** Finer cause when [errorKind] is OTHER (or absent), and the folder role the failure happened in, if any. */
        val problem: SyncProblem? = null, val folderRole: String? = null,
    ) : SyncState {
        val canRetry:Boolean get() = !isAuthError
    }
}

enum class FolderRole {
    INBOX, SENT, DRAFTS, ARCHIVE, TRASH, SPAM, ALL_MAIL, STARRED, CUSTOM,
    /** Virtual: only used as Mailbox.Unified(SNOOZED), the list of snoozed conversations. Never stored on a folder. */
    SNOOZED,
    /** Virtual built-in newsletter category, across accounts; never a server folder. */
    NEWSLETTERS,
    /** Virtual optional category views (opt-in in Settings); mail stays in its folder and in the Inbox. */
    PURCHASES, BILLS, NOTIFICATIONS, SOCIAL,
}

/** The classifier category a virtual category view shows, or null for ordinary roles. */
fun FolderRole.category(): app.dot.mail.engine.text.Category? = when (this) {
    FolderRole.NEWSLETTERS -> app.dot.mail.engine.text.Category.NEWSLETTER
    FolderRole.PURCHASES -> app.dot.mail.engine.text.Category.PURCHASE
    FolderRole.BILLS -> app.dot.mail.engine.text.Category.BILL
    FolderRole.NOTIFICATIONS -> app.dot.mail.engine.text.Category.NOTIFICATION
    FolderRole.SOCIAL -> app.dot.mail.engine.text.Category.SOCIAL
    else -> null
}

/** The virtual view that shows [this] category (PERSONAL has none). */
fun app.dot.mail.engine.text.Category.viewRole(): FolderRole? = FolderRole.entries.firstOrNull { it.category() == this }

data class Folder(
    val id: Long,
    val accountId: Long,
    /** Full IMAP path, e.g. "INBOX/Receipts". */
    val path: String,
    /** Last path segment, for display. */
    val name: String,
    val role: FolderRole,
    val unreadCount: Int,
    val totalCount: Int,
)

/** What a message list is showing. */
sealed interface Mailbox {
    /** Same role across every account, e.g. the unified inbox. */
    data class Unified(val role: FolderRole) : Mailbox
    data class InFolder(val folderId: Long) : Mailbox
    /** A user-defined smart folder ([SmartFolder.id]): a saved filter across accounts and folders. */
    data class Smart(val id: String) : Mailbox
}

/** How a conversation list is ordered. */
enum class InboxSort { DATE, SENDER, UNREAD_FIRST, STARRED_FIRST }

/** Quick filters on top of any list; all selected filters must match. */
data class ThreadFilters(
    val unread: Boolean = false,
    val starred: Boolean = false,
    val attachments: Boolean = false,
    /** Addressed to one of my own identities. */
    val toMe: Boolean = false,
    /** From someone I already have in my contacts (people I have corresponded with). */
    val fromContacts: Boolean = false,
) {
    val any: Boolean get() = unread || starred || attachments || toMe || fromContacts
}

/** Sort + quick filters applied to a list by the data layer. */
data class ListOptions(val sort: InboxSort = InboxSort.DATE, val filters: ThreadFilters = ThreadFilters())

/**
 * A saved search shown in the drawer. Empty [accountIds] / [roles] mean "all accounts" / "every folder except
 * Trash, Spam and Drafts". Text conditions match case-insensitively anywhere in the sender, recipients or subject.
 */
data class SmartFolder(
    val id: String,
    val name: String,
    val accountIds: Set<Long> = emptySet(),
    val roles: Set<FolderRole> = emptySet(),
    val from: String = "",
    val to: String = "",
    val subject: String = "",
    val category: app.dot.mail.engine.text.Category? = null,
    val unreadOnly: Boolean = false,
    val starredOnly: Boolean = false,
    val hasAttachment: Boolean = false,
    /** Inclusive start / exclusive end of the message date, epoch millis. */
    val afterMillis: Long? = null,
    val beforeMillis: Long? = null,
)

data class EmailAddress(val email: String, val name: String? = null) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: email
}

/** One row in a message list: a conversation within one account. */
data class ThreadSummary(
    val threadId: Long,
    val accountId: Long,
    val subject: String,
    /** Distinct senders, most recent last. */
    val participants: List<EmailAddress>,
    val snippet: String,
    val lastMessageAt: Long,
    val messageCount: Int,
    val unreadCount: Int,
    val isStarred: Boolean,
    val hasAttachments: Boolean,
    val draftId: Long? = null,
    val recipients: List<EmailAddress> = emptyList(),
    /** Wake-up time (epoch ms); only set for rows of the Snoozed list. */
    val snoozedUntil: Long? = null,
    val category: app.dot.mail.engine.text.Category? = null,
    /** Explicit encryption MIME classification of the message represented by this row. */
    val encryptionHint: app.dot.mail.engine.EncryptionHint = app.dot.mail.engine.EncryptionHint.NONE,
) {
    val isUnread: Boolean get() = unreadCount > 0
}

data class Attachment(
    val id: Long,
    val messageId: Long,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Content-ID for inline images referenced as cid: in HTML. */
    val contentId: String?,
    val isInline: Boolean,
    /** content:// or file:// URI once downloaded, else null. */
    val localUri: String?,
)

data class Message(
    val id: Long,
    val accountId: Long,
    val folderId: Long,
    val threadId: Long,
    val from: EmailAddress,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress>,
    val bcc: List<EmailAddress>,
    val replyTo: List<EmailAddress>,
    val subject: String,
    val sentAt: Long,
    val snippet: String,
    val isUnread: Boolean,
    val isStarred: Boolean,
    val body: MessageBody?,
    val attachments: List<Attachment>,
    /** RFC 5322 Message-ID, used for replies. */
    val messageIdHeader: String?,
    val references: List<String>,
    /** Parsed List-Unsubscribe (mailto: or https:) if present. */
    val unsubscribe: String?,
    /** RFC 8058 one-click unsubscribe endpoint (https POST "List-Unsubscribe=One-Click"), when the sender offers it. */
    val unsubscribeOneClick: String? = null,
    val listId: String? = null,
    val category: app.dot.mail.engine.text.Category? = null,
)

/** Body is null on [Message] until it has been downloaded. */
data class MessageBody(
    val plainText: String?,
    /** Raw, UNSANITISED HTML. Only ever render via the app's sanitising viewer. */
    val html: String?,
    val hasRemoteImages: Boolean,
    /** The private file retains the complete body; rendering is bounded to protect the app heap. */
    val isTruncated: Boolean = false,
    /** OpenPGP content to decrypt/verify through OpenKeychain; null for ordinary mail. */
    val pgp: PgpContent? = null,
)

/** OpenPGP shapes of a message body. Paths point into the app-private body directory. */
sealed interface PgpContent {
    /** PGP/MIME encrypted: [ciphertext] is the armoured payload; nothing readable is stored. */
    data class Encrypted(val ciphertext: String) : PgpContent
    /** PGP/MIME signed: the exact signed entity and its detached signature. */
    data class Signed(val signedEntity: String, val signature: String) : PgpContent
    /** Inline armour in the plain text (decrypt or verify the text itself). */
    data object InlineEncrypted : PgpContent
    data object InlineSigned : PgpContent
    /** Explicitly encrypted MIME that Dot Mail cannot decrypt (for example S/MIME enveloped-data). */
    data object Unsupported : PgpContent
}

enum class MessageAction { MARK_READ, MARK_UNREAD, STAR, UNSTAR, ARCHIVE, TRASH, SPAM, NOT_SPAM, DELETE_FOREVER }

/** A message being written. */
data class Draft(
    val localId: Long = 0,
    val accountId: Long,
    val identityId: Long?,
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val subject: String = "",
    val bodyText: String = "",
    /** content:// URIs picked by the user. */
    val attachmentUris: List<String> = emptyList(),
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    /** Epoch ms; null = send now (after undo window). */
    val scheduledAt: Long? = null,
    /**
     * Editor HTML for a formatted message, or null for plain text. When set, [bodyText] must hold its
     * text/plain rendering (EmailHtml.toPlainText); the message is sent as multipart/alternative.
     */
    val bodyHtml: String? = null,
    /** OpenPGP (via OpenKeychain): sign / encrypt this message when sending. Encrypted drafts are never mirrored to the server. */
    val pgpSign: Boolean = false,
    val pgpEncrypt: Boolean = false,
    /** S/MIME: sign / encrypt this message when sending. Encrypted drafts are never mirrored to the server. */
    val smimeSign: Boolean = false,
    val smimeEncrypt: Boolean = false,
)

// ---------- Account setup ----------

enum class Security { SSL_TLS, STARTTLS, NONE }

enum class AuthType { PASSWORD, OAUTH2 }

data class ServerConfig(
    val host: String,
    val port: Int,
    val security: Security,
    /** Login name; often the full email address. */
    val username: String,
)

data class AccountConfig(
    val email: String,
    val displayName: String,
    val imap: ServerConfig,
    val smtp: ServerConfig,
    val authType: AuthType = AuthType.PASSWORD,
    /** Shown in setup, e.g. "Gmail requires an app password". */
    val providerHint: String? = null,
    /** References Android KeyChain entries; private keys and certificates never live in Room/account JSON. */
    val smime: SmimeAccountConfig = SmimeAccountConfig(),
)

/** Account-scoped S/MIME defaults. Missing this object in older account JSON means S/MIME is disabled. */
data class SmimeAccountConfig(
    val signingKeyChainAlias: String? = null,
    val decryptionKeyChainAlias: String? = null,
    val signByDefault: Boolean = false,
    val encryptByDefault: Boolean = false,
)

sealed interface VerifyResult {
    data object Ok : VerifyResult
    data class AuthFailed(val message: String) : VerifyResult
    /** [kind] lets the UI show a translated reason; [message] is English for logs and tests. */
    data class ConnectionFailed(val message: String, val kind: app.dot.mail.engine.ConnectionFailure? = null) : VerifyResult
    data class CertificateProblem(val message: String) : VerifyResult
}

/** Active local sends. Completed/cancelled/deleted items disappear from the outbox flow. */
enum class OutboxState { QUEUED, SENDING, SAVING_SENT, NEEDS_REVIEW }

data class OutboxItem(
    val id: Long,
    val accountId: Long,
    val subject: String,
    val recipients: List<EmailAddress>,
    /** Earliest send time, including the undo window; WorkManager may run later. */
    val sendAt: Long,
    val state: OutboxState,
    val error: String?,
    val canRetry: Boolean,
    val canDelete: Boolean,
    /** The saved draft this message was sent from (0 for system messages such as calendar replies). */
    val draftId: Long = 0,
    /** Still waiting locally (not claimed for SMTP): it can be reopened in the composer or moved to another time. */
    val canEdit: Boolean = false,
    val canReschedule: Boolean = false,
)

// OAuth client IDs belong to the build maintainer; no project borrows another app's registration.
enum class OAuthProvider { GOOGLE, MICROSOFT }
data class OAuthAvailability(val provider: OAuthProvider, val available: Boolean, val reason: String?)
/** Machine-readable reason of an [OAuthException]; the UI maps it to a translated message. */
enum class OAuthError { NOT_AVAILABLE, INVALID_EMAIL, ACCOUNT_GONE, WRONG_ACCOUNT, STORAGE, INVALID_CALLBACK, NO_PENDING, STATE_LOST, BUILD_CHANGED, COMPLETE_FAILED, PROVIDER_REJECTED, ACCOUNT_CHANGED, EXPIRED, DENIED, PROVIDER_RESPONSE, OTHER }
class OAuthException(message: String, val code: OAuthError = OAuthError.OTHER) : Exception(message)

enum class MailActionFailure { FOLDER_UNAVAILABLE, ACCOUNT_MISMATCH, OPERATION_PENDING, SOURCE_REQUIRED }

/**
 * What exactly was refused. The UI turns each kind into text in the user's language; [MailActionException.message] stays English
 * for logs and tests only. [MailActionException.detail] carries a value for the text (e.g. the missing folder role), never prose.
 */
enum class ActionErrorKind {
    MESSAGE_MOVING, MESSAGE_FOLDER_GONE, DESTINATION_OTHER_ACCOUNT, MOVE_SYNCING, MOVE_SENDING, SOURCE_REQUIRED,
    NO_ROLE_FOLDER, MESSAGE_SYNCING, DESTINATION_GONE, LOCAL_DRAFT_MOVE, MIXED_ACCOUNTS, DRAFT_ACCOUNT_CHANGE, OTHER,
}
class MailActionException(
    val reason: MailActionFailure, message: String,
    val kind: ActionErrorKind = ActionErrorKind.OTHER, val detail: String? = null,
) : Exception(message)

/** Why a synchronisation failed, beyond the connection-level [app.dot.mail.engine.ConnectionFailure]. */
enum class SyncProblem {
    SERVER_NAME, TIMEOUT, UNREACHABLE, TLS, FOLDER_UNAVAILABLE, CONNECTION_CLOSED, PARSE, DATABASE, EMPTY_RESULT, INCONSISTENT,
    HEADERS_UNREADABLE, AUTH, OTHER,
}
/** [kind] and [connection] let the UI explain a failed refresh in the user's language; [message] is English for logs. */
class MailSyncException(message: String, val kind: SyncProblem = SyncProblem.OTHER, val connection: app.dot.mail.engine.ConnectionFailure? = null) : Exception(message)

/** Why a folder command was refused before anything was sent to the server. */
enum class FolderFailure {
    /** INBOX, Sent, Drafts, Trash, Spam, Archive... cannot be renamed, moved or deleted. */
    PROTECTED_FOLDER,
    /** Empty, too long, or contains the hierarchy separator or IMAP wildcards. */
    INVALID_NAME,
    ALREADY_EXISTS,
    NOT_FOUND,
    /** Deleting a folder that still holds messages without saying where they go. */
    NOT_EMPTY,
    /** Parent/target from another account, or the folder itself or one of its subfolders. */
    INVALID_TARGET,
    /** Delete a folder's subfolders (or move them away) first. */
    HAS_SUBFOLDERS,
    /** A previous change to this folder has not reached the server yet. */
    PENDING,
}
class FolderException(val reason: FolderFailure, message: String) : Exception(message)

/** Enough to put messages back where an archive/trash/spam action took them from: (message id, original folder id). */
data class UndoToken(val moves: List<Pair<Long, Long>>, val drafts: List<Long> = emptyList())
