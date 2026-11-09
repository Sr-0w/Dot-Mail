package app.dot.mail.engine.jmap

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.serialization.json.*

internal class JmapFixture : AutoCloseable {
    data class Record(
        val path: String,
        val method: String,
        val authorization: String?,
        val body: String,
    )

    data class Response(
        val body: ByteArray,
        val status: Int = 200,
        val type: String = "application/json",
        val headers: Map<String, String> = emptyMap(),
        val chunked: Boolean = false,
    )

    val records = ConcurrentLinkedQueue<Record>()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    @Volatile var responder: (Record) -> Response = ::normal
    val uri = URI("https://fixture.invalid/session")
    val config
        get() = JmapConfig(uri)

    // Logical HTTPS origin is still checked by production code; only socket creation is redirected.
    val transport =
        HttpsJmapTransport(3000) { logical ->
            URI("http://127.0.0.1:${server.address.port}${logical.rawPath}")
                .toURL()
                .openConnection() as HttpURLConnection
        }

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val record =
                    Record(
                        exchange.requestURI.path,
                        exchange.requestMethod,
                        exchange.requestHeaders.getFirst("Authorization"),
                        exchange.requestBody.use {
                            it.readNBytes(1024 * 1024 + 1).toString(Charsets.UTF_8)
                        },
                    )
                records.add(record)
                val result = responder(record)
                exchange.responseHeaders.set("Content-Type", result.type)
                result.headers.forEach { (k, v) -> exchange.responseHeaders.set(k, v) }
                exchange.sendResponseHeaders(
                    result.status,
                    if (result.chunked) 0 else result.body.size.toLong(),
                )
                exchange.responseBody.use { it.write(result.body) }
            } catch (_: java.io.IOException) {
                /* Canceled requests deliberately disconnect. */
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    fun session(
        coreGetLimit: Int = 3,
        concurrent: Int = 2,
        api: String = "https://fixture.invalid/api",
        primary: String? = "A",
    ): JsonObject = buildJsonObject {
        put(
            "capabilities",
            buildJsonObject {
                put(
                    JMAP_CORE,
                    buildJsonObject {
                        put("maxObjectsInGet", coreGetLimit)
                        put("maxConcurrentRequests", concurrent)
                        put("maxSizeRequest", 1048576)
                        put("maxCallsInRequest", 16)
                    },
                )
                put(JMAP_MAIL, buildJsonObject {})
            },
        )
        put(
            "accounts",
            buildJsonObject {
                put(
                    "A",
                    buildJsonObject {
                        put("name", "Fixture")
                        put("isPersonal", true)
                        put("isReadOnly", false)
                        put(
                            "accountCapabilities",
                            buildJsonObject { put(JMAP_MAIL, buildJsonObject {}) },
                        )
                    },
                )
            },
        )
        put("primaryAccounts", buildJsonObject { if (primary != null) put(JMAP_MAIL, primary) })
        put("apiUrl", api)
        put("state", "session-1")
    }

    fun result(
        name: String,
        args: JsonObject,
        tag: String = "m0",
        sessionState: String = "session-1",
    ): JsonObject = buildJsonObject {
        put("sessionState", sessionState)
        put(
            "methodResponses",
            buildJsonArray {
                add(
                    buildJsonArray {
                        add(name)
                        add(args)
                        add(tag)
                    }
                )
            },
        )
    }

    fun json(value: JsonElement) = Response(value.toString().toByteArray(Charsets.UTF_8))

    fun error(type: String) =
        json(
            result(
                "error",
                buildJsonObject {
                    put("type", type)
                    put(
                        "description",
                        "secret@fixture.invalid private subject bearer fixture-token",
                    )
                },
            )
        )

    fun email(id: String): JsonObject = buildJsonObject {
        put("id", id)
        put("blobId", "blob-$id")
        put("threadId", "thread-alpha")
        put(
            "mailboxIds",
            buildJsonObject {
                put("M1", true)
                if (id == "E1") put("M2", true)
            },
        )
        put(
            "keywords",
            buildJsonObject {
                if (id == "E1") {
                    put("\$seen", true)
                    put("\$flagged", true)
                }
            },
        )
        put(
            "from",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("email", "friend@fixture.invalid")
                        put("name", "Friend")
                    }
                )
            },
        )
        put("to", JsonNull)
        put("cc", JsonNull)
        put("bcc", JsonNull)
        put("replyTo", JsonNull)
        put("subject", "🔥 Facture 漢字 café e\u0301")
        put("preview", "😀 preview 中文")
        put("receivedAt", "2026-11-09T00:00:00Z")
        put("sentAt", JsonNull)
        put("size", 1234)
        put("hasAttachment", true)
        put("messageId", buildJsonArray { add("$id@fixture.invalid") })
        put("inReplyTo", JsonNull)
        put("references", JsonNull)
    }

    fun normal(record: Record): Response {
        if (record.path == "/session") return json(session())
        val call =
            Json.parseToJsonElement(record.body)
                .jsonObject
                .getValue("methodCalls")
                .jsonArray
                .single()
                .jsonArray
        val name = call[0].jsonPrimitive.content
        val args = call[1].jsonObject
        val account = args.getValue("accountId").jsonPrimitive.content
        val value = buildJsonObject {
            put("accountId", account)
            when (name) {
                "Mailbox/get" -> {
                    put("state", "mailboxes-1")
                    val requested =
                        if (args["ids"] == JsonNull || args["ids"] == null) listOf("M1", "M2")
                        else args.getValue("ids").jsonArray.map { it.jsonPrimitive.content }
                    put(
                        "notFound",
                        JsonArray(
                            requested.filter { it !in setOf("M1", "M2") }.map(::JsonPrimitive)
                        ),
                    )
                    put(
                        "list",
                        buildJsonArray {
                            for ((id, role) in listOf("M1" to "inbox", "M2" to "archive")) if (
                                id in requested
                            )
                                add(
                                    buildJsonObject {
                                        put("id", id)
                                        put("name", if (id == "M1") "Inbox" else "Archive")
                                        put("parentId", JsonNull)
                                        put("role", role)
                                        put("sortOrder", 0)
                                        put("totalEmails", 2)
                                        put("unreadEmails", 1)
                                    }
                                )
                        },
                    )
                }
                "Email/query" -> {
                    val position = args.getValue("position").jsonPrimitive.long
                    val limit = args.getValue("limit").jsonPrimitive.int
                    put("position", position)
                    put("queryState", "query-1")
                    put("canCalculateChanges", true)
                    put("total", 2)
                    put(
                        "ids",
                        JsonArray(
                            listOf("E1", "E2")
                                .drop(position.toInt())
                                .take(limit)
                                .map(::JsonPrimitive)
                        ),
                    )
                }
                "Email/get" -> {
                    val ids = args.getValue("ids").jsonArray.map { it.jsonPrimitive.content }
                    put("state", "emails-1")
                    put("list", JsonArray(ids.filter { it in setOf("E1", "E2") }.map(::email)))
                    put(
                        "notFound",
                        JsonArray(ids.filter { it !in setOf("E1", "E2") }.map(::JsonPrimitive)),
                    )
                }
                "Email/changes" -> {
                    put("oldState", args.getValue("sinceState"))
                    put("newState", "emails-2")
                    put("hasMoreChanges", false)
                    put("created", buildJsonArray { add("E2") })
                    put("updated", buildJsonArray { add("E1") })
                    put("destroyed", buildJsonArray { add("gone") })
                }
                else -> error("Unexpected mutating/unknown method")
            }
        }
        return json(result(name, value, call[2].jsonPrimitive.content))
    }
}
