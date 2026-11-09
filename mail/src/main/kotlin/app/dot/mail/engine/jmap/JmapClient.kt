package app.dot.mail.engine.jmap

import app.dot.mail.engine.Address
import app.dot.mail.engine.Credentials
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Read-only JMAP entry point obtained from MailClient.jmap. No arbitrary method/write escape hatch.
 */
class JmapClient
internal constructor(
    private val config: JmapConfig,
    private val credentials: Credentials,
    private val transport: JmapHttpTransport,
) {
    private val approvedOrigins =
        (config.approvedApiOrigins + config.sessionUrl).map(::origin).toSet()
    private val gate = Semaphore(2)
    private val json = Json { isLenient = false }

    init {
        endpoint(config.sessionUrl)
        require(
            credentials.secret.isNotBlank() &&
                credentials.secret.length <= 16384 &&
                credentials.secret.all { it.code in 0x21..0x7e }
        ) {
            "Invalid JMAP bearer credential"
        }
    }

    suspend fun session(): JmapSession {
        val root = request(config.sessionUrl, "GET")
        val capabilities = root.obj("capabilities")
        if (JMAP_CORE !in capabilities || JMAP_MAIL !in capabilities)
            throw JmapException(JmapErrorKind.UNSUPPORTED)
        val core = capabilities.obj(JMAP_CORE)
        val limits =
            JmapLimits(
                core.positive("maxObjectsInGet", 200),
                core.positive("maxSizeRequest", 1024 * 1024),
                core.positive("maxConcurrentRequests", 2),
            )
        if (core.positive("maxCallsInRequest", 1) < 1) malformed()
        val api = uri(root.string("apiUrl"))
        endpoint(api)
        val accounts =
            root.obj("accounts").mapNotNull { (id, value) ->
                val account = value as? JsonObject ?: malformed()
                val accountCapabilities = account.obj("accountCapabilities")
                if (JMAP_MAIL !in accountCapabilities) null
                else
                    JmapAccount(
                        checkedId(id),
                        account.string("name"),
                        account.boolean("isPersonal"),
                        account.boolean("isReadOnly"),
                    )
            }
        if (accounts.isEmpty()) throw JmapException(JmapErrorKind.UNSUPPORTED)
        val primary = root.obj("primaryAccounts").optionalString(JMAP_MAIL)
        if (primary != null && accounts.none { it.id == primary }) malformed()
        return JmapSession(api, root.state("state"), accounts, primary, limits)
    }

    suspend fun mailboxes(
        session: JmapSession,
        accountId: String? = null,
        ids: List<String>? = null,
    ): JmapObjects<JmapMailbox> {
        val account = session.account(accountId).id
        val requested = ids?.let { checkedIds(it, session.limits.maxObjectsInGet) }
        val reply =
            call(
                session,
                "Mailbox/get",
                buildJsonObject {
                    put("accountId", account)
                    put("ids", requested?.asJson() ?: JsonNull)
                    put(
                        "properties",
                        listOf(
                                "id",
                                "name",
                                "parentId",
                                "role",
                                "sortOrder",
                                "totalEmails",
                                "unreadEmails",
                            )
                            .asJson(),
                    )
                },
            )
        val data = reply.value
        data.account(account)
        val rows =
            data.array("list").map { value ->
                val o = value as? JsonObject ?: malformed()
                JmapMailbox(
                    o.id(),
                    o.string("name"),
                    o.optionalString("parentId")?.let(::checkedId),
                    o.optionalString("role"),
                    o.unsigned("sortOrder"),
                    o.unsigned("totalEmails"),
                    o.unsigned("unreadEmails"),
                )
            }
        val missing = data.ids("notFound").toSet()
        validateGet(requested, rows.map { it.id }, missing)
        return JmapObjects(account, data.state("state"), rows, missing, reply.sessionState)
    }

    suspend fun queryEmails(
        session: JmapSession,
        accountId: String? = null,
        mailboxId: String? = null,
        position: Long = 0,
        limit: Int = 50,
    ): JmapQuery {
        require(position >= 0 && limit in 1..200) { "Invalid JMAP page" }
        val account = session.account(accountId).id
        mailboxId?.let(::checkedId)
        val reply =
            call(
                session,
                "Email/query",
                buildJsonObject {
                    put("accountId", account)
                    put("position", position)
                    put("limit", limit)
                    if (mailboxId != null)
                        put("filter", buildJsonObject { put("inMailbox", mailboxId) })
                    put(
                        "sort",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("property", "receivedAt")
                                    put("isAscending", false)
                                }
                            )
                        },
                    )
                    put("collapseThreads", false)
                    put("calculateTotal", true)
                },
            )
        val data = reply.value
        data.account(account)
        val ids = data.ids("ids")
        if (ids.size > limit || ids.distinct().size != ids.size) malformed()
        val total =
            if (data["total"] == null || data["total"] == JsonNull) null else data.unsigned("total")
        return JmapQuery(
            account,
            data.state("queryState"),
            ids,
            data.unsigned("position"),
            total,
            data.boolean("canCalculateChanges"),
            reply.sessionState,
        )
    }

    suspend fun emails(
        session: JmapSession,
        ids: List<String>,
        accountId: String? = null,
    ): JmapObjects<JmapEmail> {
        val account = session.account(accountId).id
        val requested = checkedIds(ids, session.limits.maxObjectsInGet)
        val reply =
            call(
                session,
                "Email/get",
                buildJsonObject {
                    put("accountId", account)
                    put("ids", requested.asJson())
                    put("properties", emailProperties.asJson())
                },
            )
        val data = reply.value
        data.account(account)
        val rows = data.array("list").map { value -> email(value as? JsonObject ?: malformed()) }
        val missing = data.ids("notFound").toSet()
        validateGet(requested, rows.map { it.id }, missing)
        return JmapObjects(account, data.state("state"), rows, missing, reply.sessionState)
    }

    suspend fun changes(
        session: JmapSession,
        sinceState: String,
        accountId: String? = null,
        maxChanges: Int = 200,
    ): JmapChanges {
        require(maxChanges in 1..200) { "Invalid JMAP change limit" }
        checkedState(sinceState)
        val account = session.account(accountId).id
        val reply =
            call(
                session,
                "Email/changes",
                buildJsonObject {
                    put("accountId", account)
                    put("sinceState", sinceState)
                    put("maxChanges", maxChanges)
                },
            )
        val data = reply.value
        data.account(account)
        val old = data.state("oldState")
        val next = data.state("newState")
        val more = data.boolean("hasMoreChanges")
        val created = data.ids("created").toSet()
        val updated = data.ids("updated").toSet()
        val destroyed = data.ids("destroyed").toSet()
        if (old != sinceState || (more && old == next)) malformed()
        // RFC 8620 §5.2 explicitly permits overlap between these lists. Preserve it; callers
        // reconcile destroyed last rather than resurrecting a created-then-destroyed object.
        if (
            data.array("created").size + data.array("updated").size + data.array("destroyed").size >
                maxChanges
        )
            malformed()
        return JmapChanges(
            account,
            old,
            next,
            created,
            updated,
            destroyed,
            more,
            reply.sessionState,
        )
    }

    private data class Reply(val value: JsonObject, val sessionState: String)

    private suspend fun call(session: JmapSession, name: String, args: JsonObject): Reply {
        endpoint(session.apiUrl)
        val body =
            buildJsonObject {
                    put("using", listOf(JMAP_CORE, JMAP_MAIL).asJson())
                    put(
                        "methodCalls",
                        buildJsonArray {
                            add(
                                buildJsonArray {
                                    add(name)
                                    add(args)
                                    add("m0")
                                }
                            )
                        },
                    )
                }
                .toString()
                .toByteArray(Charsets.UTF_8)
        if (body.size > minOf(session.limits.maxSizeRequest, 1024 * 1024))
            throw JmapException(JmapErrorKind.LIMIT)
        val root = request(session.apiUrl, "POST", body, session.limits.maxConcurrentRequests == 1)
        val responses = root.array("methodResponses")
        if (responses.size != 1) malformed()
        val response = responses.single() as? JsonArray ?: malformed()
        if (response.size != 3 || response[2].asString() != "m0") malformed()
        val actual = response[0].asString()
        val value = response[1] as? JsonObject ?: malformed()
        if (actual == "error") {
            val raw = value.string("type")
            val safe = raw.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9]{0,63}")) }
            throw JmapException(JmapErrorKind.METHOD, methodError = safe)
        }
        if (actual != name) malformed()
        return Reply(value, root.state("sessionState"))
    }

    private val single = Semaphore(1)

    private suspend fun request(
        uri: URI,
        method: String,
        body: ByteArray? = null,
        serial: Boolean = false,
    ): JsonObject {
        endpoint(uri)
        suspend fun execute(): JsonObject =
            gate.withPermit {
                val response =
                    transport.execute(
                        JmapHttpRequest(
                            uri,
                            method,
                            mapOf(
                                "Authorization" to "Bearer ${credentials.secret}",
                                "Accept" to "application/json",
                                "Content-Type" to "application/json",
                            ),
                            body,
                        )
                    )
                when (response.status) {
                    200 -> Unit
                    401,
                    403 -> throw JmapException(JmapErrorKind.AUTH, response.status)
                    429 -> throw JmapException(JmapErrorKind.RATE_LIMIT, response.status)
                    408,
                    500,
                    502,
                    503,
                    504 -> throw JmapException(JmapErrorKind.TRANSIENT, response.status)
                    in 300..399 -> throw JmapException(JmapErrorKind.ENDPOINT, response.status)
                    else -> throw JmapException(JmapErrorKind.HTTP, response.status)
                }
                if (response.body.size > 2 * 1024 * 1024) throw JmapException(JmapErrorKind.LIMIT)
                if (
                    response.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) !=
                        "application/json"
                )
                    malformed()
                val text =
                    try {
                        Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(response.body))
                            .toString()
                    } catch (e: java.nio.charset.CharacterCodingException) {
                        throw JmapException(JmapErrorKind.PROTOCOL)
                    }
                checkDepth(text)
                try {
                    json.parseToJsonElement(text) as? JsonObject ?: malformed()
                } catch (e: kotlinx.serialization.SerializationException) {
                    throw JmapException(JmapErrorKind.PROTOCOL)
                } catch (e: IllegalArgumentException) {
                    throw JmapException(JmapErrorKind.PROTOCOL)
                }
            }
        return withContext(Dispatchers.IO) {
            if (serial) single.withPermit { execute() } else execute()
        }
    }

    private fun endpoint(uri: URI) {
        if (origin(uri) !in approvedOrigins || uri.rawQuery != null)
            throw JmapException(JmapErrorKind.ENDPOINT)
    }

    companion object {
        private val emailProperties =
            listOf(
                "id",
                "blobId",
                "threadId",
                "mailboxIds",
                "keywords",
                "from",
                "to",
                "cc",
                "bcc",
                "replyTo",
                "subject",
                "preview",
                "receivedAt",
                "sentAt",
                "size",
                "hasAttachment",
                "messageId",
                "inReplyTo",
                "references",
            )

        fun wellKnown(domain: String): URI {
            require(domain.isNotBlank() && domain.none { it in "/:@?#" || it.isWhitespace() }) {
                "Invalid JMAP domain"
            }
            return uri("https://$domain/.well-known/jmap").also(::origin)
        }

        private fun uri(value: String): URI =
            try {
                URI(value)
            } catch (_: java.net.URISyntaxException) {
                throw JmapException(JmapErrorKind.ENDPOINT)
            }

        private fun origin(uri: URI): String {
            if (
                !uri.scheme.equals("https", true) ||
                    uri.host.isNullOrBlank() ||
                    uri.userInfo != null ||
                    uri.fragment != null ||
                    uri.port !in -1..65535 ||
                    uri.port == 0
            )
                throw JmapException(JmapErrorKind.ENDPOINT)
            return URI(
                    "https",
                    null,
                    uri.host.lowercase(Locale.ROOT),
                    if (uri.port == 443) -1 else uri.port,
                    null,
                    null,
                    null,
                )
                .toASCIIString()
        }

        private fun malformed(): Nothing = throw JmapException(JmapErrorKind.PROTOCOL)

        private fun checkedId(value: String): String {
            if (value.isBlank() || value.length > 255 || value.any { it.isISOControl() })
                malformed()
            return value
        }

        private fun checkedState(value: String): String {
            if (value.length > 4096) throw JmapException(JmapErrorKind.LIMIT)
            return value
        }

        private fun checkedIds(ids: List<String>, limit: Int): List<String> {
            require(ids.size <= minOf(limit, 200)) { "Invalid JMAP object count" }
            return ids.map(::checkedId).distinct()
        }

        private fun JsonElement.asString(): String =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed()

        private fun JsonObject.string(key: String) = getValueOrFail(key).asString()

        private fun JsonObject.getValueOrFail(key: String): JsonElement = this[key] ?: malformed()

        private fun JsonObject.optionalString(key: String): String? =
            if (this[key] == null || this[key] == JsonNull) null else string(key)

        private fun JsonObject.obj(key: String): JsonObject =
            this[key] as? JsonObject ?: malformed()

        private fun JsonObject.array(key: String): JsonArray =
            this[key] as? JsonArray ?: malformed()

        private fun JsonObject.boolean(key: String): Boolean =
            (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: malformed()

        private fun JsonObject.unsigned(key: String): Long =
            (this[key] as? JsonPrimitive)
                ?.takeUnless { it.isString }
                ?.longOrNull
                ?.takeIf { it >= 0 } ?: malformed()

        private fun JsonObject.positive(key: String, cap: Int): Int =
            unsigned(key).takeIf { it > 0 }?.coerceAtMost(cap.toLong())?.toInt() ?: malformed()

        private fun JsonObject.state(key: String) = checkedState(string(key))

        private fun JsonObject.id() = checkedId(string("id"))

        private fun JsonObject.ids(key: String) = array(key).map { checkedId(it.asString()) }

        private fun JsonObject.account(expected: String) {
            if (string("accountId") != expected) malformed()
        }

        private fun List<String>.asJson() = JsonArray(map(::JsonPrimitive))

        private fun validateGet(
            requested: List<String>?,
            found: List<String>,
            missing: Set<String>,
        ) {
            if (
                found.size > 1000 ||
                    found.distinct().size != found.size ||
                    found.any { it in missing }
            )
                malformed()
            if (requested != null && found.toSet() + missing != requested.toSet()) malformed()
            if (requested == null && missing.isNotEmpty()) malformed()
        }

        private fun JsonObject.trueKeys(key: String): Set<String> =
            obj(key)
                .map { (id, value) ->
                    if (value !is JsonPrimitive || value.isString || value.booleanOrNull != true)
                        malformed()
                    checkedId(id)
                }
                .toSet()

        private fun JsonObject.addresses(key: String): List<Address> {
            if (this[key] == null || this[key] == JsonNull) return emptyList()
            return array(key).map { value ->
                val o = value as? JsonObject ?: malformed()
                Address(o.string("email"), o.optionalString("name"))
            }
        }

        private fun JsonObject.optionalIds(key: String): List<String> =
            if (this[key] == null || this[key] == JsonNull) emptyList()
            else array(key).map { it.asString() }

        private fun instant(value: String): Instant =
            try {
                Instant.parse(value)
            } catch (_: java.time.format.DateTimeParseException) {
                malformed()
            }

        private fun email(o: JsonObject) =
            JmapEmail(
                o.id(),
                checkedId(o.string("blobId")),
                checkedId(o.string("threadId")),
                o.trueKeys("mailboxIds"),
                o.trueKeys("keywords"),
                o.addresses("from"),
                o.addresses("to"),
                o.addresses("cc"),
                o.addresses("bcc"),
                o.addresses("replyTo"),
                o.string("subject"),
                o.string("preview"),
                instant(o.string("receivedAt")),
                o.optionalString("sentAt")?.let(::instant),
                o.unsigned("size"),
                o.boolean("hasAttachment"),
                o.optionalIds("messageId"),
                o.optionalIds("inReplyTo"),
                o.optionalIds("references"),
            )

        private fun checkDepth(text: String) {
            var quoted = false
            var escaped = false
            var depth = 0
            for (ch in text) {
                if (quoted) {
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
                } else
                    when (ch) {
                        '"' -> quoted = true
                        '{',
                        '[' -> {
                            depth++
                            if (depth > 64) throw JmapException(JmapErrorKind.LIMIT)
                        }
                        '}',
                        ']' -> depth--
                    }
            }
        }
    }
}
