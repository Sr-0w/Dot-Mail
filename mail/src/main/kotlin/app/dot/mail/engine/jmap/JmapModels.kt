package app.dot.mail.engine.jmap

import app.dot.mail.engine.Address
import java.net.URI
import java.time.Instant

const val JMAP_CORE = "urn:ietf:params:jmap:core"
const val JMAP_MAIL = "urn:ietf:params:jmap:mail"

/** Origins are explicit HTTPS authorities, never inferred from a shared domain suffix. */
data class JmapConfig(val sessionUrl: URI, val approvedApiOrigins: Set<URI> = emptySet())

enum class JmapErrorKind {
    AUTH,
    RATE_LIMIT,
    TRANSIENT,
    HTTP,
    ENDPOINT,
    PROTOCOL,
    LIMIT,
    UNSUPPORTED,
    METHOD,
    ACCOUNT_SELECTION,
}

/** Provider descriptions, URLs, credentials and response payloads never appear in this message. */
class JmapException(
    val kind: JmapErrorKind,
    val status: Int? = null,
    val methodError: String? = null,
    cause: Throwable? = null,
) : Exception("JMAP ${kind.name}${status?.let { " (HTTP $it)" }.orEmpty()}", cause) {
    val canRetry: Boolean
        get() = kind in setOf(JmapErrorKind.RATE_LIMIT, JmapErrorKind.TRANSIENT)

    val requiresResync: Boolean
        get() = methodError == "cannotCalculateChanges"
}

data class JmapLimits(
    val maxObjectsInGet: Int,
    val maxSizeRequest: Int,
    val maxConcurrentRequests: Int,
)

data class JmapAccount(
    val id: String,
    val name: String,
    val isPersonal: Boolean,
    val isReadOnly: Boolean,
)

data class JmapSession(
    val apiUrl: URI,
    val state: String,
    val mailAccounts: List<JmapAccount>,
    val primaryMailAccountId: String?,
    val limits: JmapLimits,
) {
    /** Never choose an arbitrary shared account when a Session exposes several accounts. */
    fun account(id: String? = null): JmapAccount {
        if (id != null)
            return mailAccounts.firstOrNull { it.id == id }
                ?: throw JmapException(JmapErrorKind.ACCOUNT_SELECTION)
        primaryMailAccountId?.let { primary ->
            mailAccounts
                .firstOrNull { it.id == primary }
                ?.let {
                    return it
                }
        }
        return mailAccounts.filter { it.isPersonal }.singleOrNull()
            ?: mailAccounts.singleOrNull()
            ?: throw JmapException(JmapErrorKind.ACCOUNT_SELECTION)
    }
}

data class JmapMailbox(
    val id: String,
    val name: String,
    val parentId: String?,
    val role: String?,
    val sortOrder: Long,
    val totalEmails: Long,
    val unreadEmails: Long,
)

data class JmapEmail(
    val id: String,
    val blobId: String,
    val threadId: String,
    val mailboxIds: Set<String>,
    val keywords: Set<String>,
    val from: List<Address>,
    val to: List<Address>,
    val cc: List<Address>,
    val bcc: List<Address>,
    val replyTo: List<Address>,
    val subject: String,
    val preview: String,
    val receivedAt: Instant,
    val sentAt: Instant?,
    val size: Long,
    val hasAttachments: Boolean,
    val messageIds: List<String>,
    val inReplyTo: List<String>,
    val references: List<String>,
) {
    val unread: Boolean
        get() = "\$seen" !in keywords

    val starred: Boolean
        get() = "\$flagged" in keywords
}

data class JmapObjects<T>(
    val accountId: String,
    val state: String,
    val list: List<T>,
    val notFound: Set<String>,
    val sessionState: String,
)

data class JmapQuery(
    val accountId: String,
    val queryState: String,
    val ids: List<String>,
    val position: Long,
    val total: Long?,
    val canCalculateChanges: Boolean,
    val sessionState: String,
)

data class JmapChanges(
    val accountId: String,
    val oldState: String,
    val newState: String,
    val created: Set<String>,
    val updated: Set<String>,
    val destroyed: Set<String>,
    val hasMoreChanges: Boolean,
    val sessionState: String,
) {
    /**
     * Fetch only objects surviving this change page; a destroyed ID may also be in created/updated.
     */
    val upsertIds: Set<String>
        get() = (created + updated) - destroyed
}

/** Secret-bearing headers/body intentionally excluded from diagnostic representations. */
class JmapHttpRequest(
    val uri: URI,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
    val maxResponseBytes: Int = 2 * 1024 * 1024,
) {
    override fun toString() = "JmapHttpRequest(method=$method)"
}

class JmapHttpResponse(
    val status: Int,
    val body: ByteArray,
    val contentType: String? = "application/json",
) {
    override fun toString() = "JmapHttpResponse(status=$status, bytes=${body.size})"
}

fun interface JmapHttpTransport {
    suspend fun execute(request: JmapHttpRequest): JmapHttpResponse
}
