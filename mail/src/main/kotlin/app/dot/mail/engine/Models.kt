package app.dot.mail.engine

import java.time.Instant

enum class TransportSecurity { SSL_TLS, STARTTLS, NONE }
data class Endpoint(val host: String, val port: Int, val username: String, val security: TransportSecurity = TransportSecurity.SSL_TLS)
data class Credentials(val secret: String, val oauth2: Boolean = false)
data class Address(val email: String, val name: String? = null)
enum class Role { INBOX, SENT, DRAFTS, ARCHIVE, TRASH, SPAM, ALL_MAIL, STARRED, CUSTOM }
data class RemoteFolder(val path: String, val name: String, val role: Role, val unread: Int, val total: Int, val specialUse: Boolean = false)
data class SyncCursor(val uidValidity: Long = 0, val uidNext: Long = 1, val highestModSeq: Long = -1)
enum class MoveOutcome { MOVED, COPIED_AND_MARKED_DELETED }
data class RemoteFlags(val uid: Long, val seen: Boolean, val starred: Boolean, val deleted: Boolean)
data class Header(
    val uid: Long, val messageId: String?, val references: List<String>, val inReplyTo: String?,
    val from: List<Address>, val to: List<Address>, val cc: List<Address>, val bcc: List<Address>, val replyTo: List<Address>,
    val subject: String, val sentAt: Long, val flags: RemoteFlags, val hasAttachments: Boolean,
    val gmailThreadId: Long? = null, val unsubscribe: String? = null,
)
data class HeaderFailure(val uid: Long, val error: Exception)
data class HeaderSync(val cursor: SyncCursor, val reset: Boolean, val headers: List<Header>, val flags: List<RemoteFlags>, val existingUids: Set<Long>, val failures: List<HeaderFailure> = emptyList())
data class MimeAttachment(val partId: String, val name: String, val mimeType: String, val contentId: String?, val inline: Boolean, val bytes: ByteArray)
data class ParsedBody(val text: String?, val html: String?, val attachments: List<MimeAttachment>)
data class OutgoingAttachment(val name: String, val mimeType: String, val bytes: ByteArray = byteArrayOf(), val contentId: String? = null, val source: jakarta.activation.DataSource? = null)
data class OutgoingMail(
    val from: Address, val to: List<Address>, val cc: List<Address> = emptyList(), val bcc: List<Address> = emptyList(),
    val subject: String, val text: String, val html: String? = null, val attachments: List<OutgoingAttachment> = emptyList(),
    val inReplyTo: String? = null, val references: List<String> = emptyList(),
    val messageId: String? = null, val date: Instant = Instant.now(),
    /** Reply-To header; empty = replies go to [from]. */
    val replyTo: List<Address> = emptyList(),
)

data class CachedPart(val partId: String, val name: String, val mimeType: String, val contentId: String?, val inline: Boolean, val file: java.io.File)
data class CachedBody(val text: java.io.File?, val html: java.io.File?, val attachments: List<CachedPart>)
