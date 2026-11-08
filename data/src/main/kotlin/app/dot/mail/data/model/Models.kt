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
)

sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data class Error(val message: String, val isAuthError: Boolean = false) : SyncState
}

enum class FolderRole { INBOX, SENT, DRAFTS, ARCHIVE, TRASH, SPAM, ALL_MAIL, STARRED, CUSTOM }

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
}

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
)

/** Body is null on [Message] until it has been downloaded. */
data class MessageBody(
    val plainText: String?,
    /** Raw, UNSANITISED HTML. Only ever render via the app's sanitising viewer. */
    val html: String?,
    val hasRemoteImages: Boolean,
    /** The private file retains the complete body; rendering is bounded to protect the app heap. */
    val isTruncated: Boolean = false,
)

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
)

sealed interface VerifyResult {
    data object Ok : VerifyResult
    data class AuthFailed(val message: String) : VerifyResult
    data class ConnectionFailed(val message: String) : VerifyResult
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
)

// OAuth client IDs belong to the build maintainer; no project borrows another app's registration.
enum class OAuthProvider { GOOGLE, MICROSOFT }
data class OAuthAvailability(val provider: OAuthProvider, val available: Boolean, val reason: String?)
class OAuthException(message: String) : Exception(message)

enum class MailActionFailure { FOLDER_UNAVAILABLE, ACCOUNT_MISMATCH, OPERATION_PENDING }
class MailActionException(val reason: MailActionFailure, message: String) : Exception(message)
class MailSyncException(message: String) : Exception(message)

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
