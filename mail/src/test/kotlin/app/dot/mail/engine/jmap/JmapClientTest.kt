package app.dot.mail.engine.jmap

import app.dot.mail.engine.ConnectionFailure
import app.dot.mail.engine.Credentials
import app.dot.mail.engine.MailClient
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*

class JmapClientTest {
    private lateinit var fixture: JmapFixture
    private lateinit var client: JmapClient

    @Before
    fun setup() {
        fixture = JmapFixture()
        client = MailClient().jmap(fixture.config, Credentials("fixture-token"), fixture.transport)
    }

    @After
    fun close() {
        fixture.close()
    }

    private suspend fun failure(kind: JmapErrorKind, block: suspend () -> Unit): JmapException {
        try {
            block()
            fail("Expected $kind")
        } catch (e: JmapException) {
            assertEquals(kind, e.kind)
            return e
        }
        error("Unreachable")
    }

    private fun JsonObject.with(key: String, value: JsonElement) =
        JsonObject(toMutableMap().apply { put(key, value) })

    private fun alterApi(transform: (JsonObject) -> JsonObject) {
        fixture.responder = { record ->
            if (record.path == "/session") fixture.normal(record)
            else {
                val original =
                    Json.parseToJsonElement(fixture.normal(record).body.toString(Charsets.UTF_8))
                        .jsonObject
                fixture.json(transform(original))
            }
        }
    }

    private fun JsonObject.args(transform: (JsonObject) -> JsonObject): JsonObject {
        val response = getValue("methodResponses").jsonArray.single().jsonArray
        return with(
            "methodResponses",
            buildJsonArray {
                add(
                    buildJsonArray {
                        add(response[0])
                        add(transform(response[1].jsonObject))
                        add(response[2])
                    }
                )
            },
        )
    }

    @Test
    fun facadeDiscoversAndReadsMailWithOpaqueIdsAndNoBodyOrWrites() =
        runBlocking<Unit> {
            val session = client.session()
            assertEquals("A", session.account().id)
            val mailboxes = client.mailboxes(session)
            assertEquals(listOf("inbox", "archive"), mailboxes.list.map { it.role })
            val first = client.queryEmails(session, mailboxId = "M1", limit = 1)
            assertEquals(listOf("E1"), first.ids)
            assertEquals(2L, first.total)
            val second = client.queryEmails(session, mailboxId = "M1", position = 1, limit = 1)
            assertEquals(listOf("E2"), second.ids)
            val emails = client.emails(session, listOf("E1", "missing"))
            assertEquals(setOf("missing"), emails.notFound)
            val email = emails.list.single()
            assertEquals("thread-alpha", email.threadId)
            assertEquals(setOf("M1", "M2"), email.mailboxIds)
            assertFalse(email.unread)
            assertTrue(email.starred)
            assertTrue(email.hasAttachments)
            assertEquals("🔥 Facture 漢字 café e\u0301", email.subject)
            assertEquals("😀 preview 中文", email.preview)
            assertEquals(listOf("E1@fixture.invalid"), email.messageIds)
            val changes = client.changes(session, "emails-1")
            assertEquals(setOf("gone"), changes.destroyed)
            assertEquals("emails-2", changes.newState)
            for (record in fixture.records) {
                assertEquals("Bearer fixture-token", record.authorization)
                if (record.method == "POST") {
                    val root = Json.parseToJsonElement(record.body).jsonObject
                    val call = root.getValue("methodCalls").jsonArray.single().jsonArray
                    assertTrue(
                        call[0].jsonPrimitive.content in
                            setOf("Mailbox/get", "Email/query", "Email/get", "Email/changes")
                    )
                    assertFalse(record.body.contains("bodyValues"))
                    assertFalse(record.body.contains("Email/set"))
                }
            }
        }

    @Test
    fun emptyQueryCanBootstrapAnEmailStateWithoutDownloadingAnything() =
        runBlocking<Unit> {
            val session = client.session()
            val result = client.emails(session, emptyList())
            assertEquals("emails-1", result.state)
            assertTrue(result.list.isEmpty())
            assertTrue(result.notFound.isEmpty())
        }

    @Test
    fun explicitAccountSelectionNeverPicksAnArbitrarySharedAccount() =
        runBlocking<Unit> {
            fixture.responder = { record ->
                val account =
                    fixture
                        .session()
                        .getValue("accounts")
                        .jsonObject
                        .getValue("A")
                        .jsonObject
                        .with("isPersonal", JsonPrimitive(false))
                fixture.json(
                    fixture
                        .session(primary = null)
                        .with(
                            "accounts",
                            buildJsonObject {
                                put("shared-a", account)
                                put("shared-b", account)
                            },
                        )
                )
            }
            val session = client.session()
            failure(JmapErrorKind.ACCOUNT_SELECTION) { session.account() }
            assertEquals("shared-b", session.account("shared-b").id)
            failure(JmapErrorKind.ACCOUNT_SELECTION) {
                client.emails(session, listOf("E1"), accountId = "not-authorized")
            }
            assertEquals(1, fixture.records.size)
        }

    @Test
    fun missingCapabilitiesOrUnusableLimitsAreRejected() =
        runBlocking<Unit> {
            fixture.responder = {
                fixture.json(
                    fixture
                        .session()
                        .with(
                            "capabilities",
                            buildJsonObject { put(JMAP_CORE, buildJsonObject {}) },
                        )
                )
            }
            failure(JmapErrorKind.UNSUPPORTED) { client.session() }
            fixture.responder = { fixture.json(fixture.session(coreGetLimit = 0)) }
            failure(JmapErrorKind.PROTOCOL) { client.session() }
        }

    @Test
    fun responseAndGetBatchLimitsAreEnforcedBeforeExtraRequests() =
        runBlocking<Unit> {
            val session = client.session()
            assertEquals(3, session.limits.maxObjectsInGet)
            try {
                client.emails(session, listOf("1", "2", "3", "4"))
                fail()
            } catch (_: IllegalArgumentException) {}
            assertEquals(1, fixture.records.size)
            val tiny = session.copy(limits = session.limits.copy(maxSizeRequest = 2))
            failure(JmapErrorKind.LIMIT) { client.queryEmails(tiny) }
            assertEquals(1, fixture.records.size)
            fixture.responder = {
                JmapFixture.Response(ByteArray(2 * 1024 * 1024 + 1) { 32 }, chunked = true)
            }
            failure(JmapErrorKind.LIMIT) { client.session() }
        }

    @Test
    fun contentLengthCannotBypassTheBoundedReader() =
        runBlocking<Unit> {
            fixture.responder = { JmapFixture.Response(ByteArray(2 * 1024 * 1024 + 1) { 32 }) }
            failure(JmapErrorKind.LIMIT) { client.session() }
        }

    @Test
    fun authenticatedRedirectIsNotFollowed() =
        runBlocking<Unit> {
            fixture.responder = {
                JmapFixture.Response(
                    byteArrayOf(),
                    302,
                    headers = mapOf("Location" to "https://elsewhere.invalid/stolen"),
                )
            }
            failure(JmapErrorKind.ENDPOINT) { client.session() }
            assertEquals(1, fixture.records.size)
        }

    @Test
    fun apiOriginRequiresExplicitApprovalAndDoesNotUseDomainSuffixTrust() =
        runBlocking<Unit> {
            fixture.responder = {
                fixture.json(fixture.session(api = "https://fixture.invalid.evil/api"))
            }
            failure(JmapErrorKind.ENDPOINT) { client.session() }
            assertEquals(1, fixture.records.size)
            val approved =
                MailClient()
                    .jmap(
                        JmapConfig(fixture.uri, setOf(URI("https://fixture.invalid.evil"))),
                        Credentials("fixture-token"),
                        fixture.transport,
                    )
            assertEquals("fixture.invalid.evil", approved.session().apiUrl.host)
        }

    @Test
    fun forgedSessionCannotBypassOriginChecks() =
        runBlocking<Unit> {
            val session = client.session()
            failure(JmapErrorKind.ENDPOINT) {
                client.emails(session.copy(apiUrl = URI("https://evil.invalid/api")), listOf("E1"))
            }
            assertEquals(1, fixture.records.size)
        }

    @Test
    fun insecureUserInfoFragmentAndQueryEndpointsAreRejected() =
        runBlocking<Unit> {
            for (value in
                listOf(
                    "http://fixture.invalid/session",
                    "https://user:secret@fixture.invalid/session",
                    "https://fixture.invalid/session#fragment",
                    "https://fixture.invalid/session?token=secret",
                )) {
                failure(JmapErrorKind.ENDPOINT) {
                    MailClient()
                        .jmap(
                            JmapConfig(URI(value)),
                            Credentials("fixture-token"),
                            fixture.transport,
                        )
                        .session()
                }
            }
            assertTrue(fixture.records.isEmpty())
            assertEquals(
                "https://mail.example.org/.well-known/jmap",
                JmapClient.wellKnown("mail.example.org").toString(),
            )
            try {
                JmapClient.wellKnown("example.org@evil.invalid")
                fail()
            } catch (_: IllegalArgumentException) {}
        }

    @Test
    fun typedHttpFailuresUseExistingConnectionClassification() =
        runBlocking<Unit> {
            for ((status, kind, connection) in
                listOf(
                    Triple(401, JmapErrorKind.AUTH, ConnectionFailure.AUTH),
                    Triple(403, JmapErrorKind.AUTH, ConnectionFailure.AUTH),
                    Triple(429, JmapErrorKind.RATE_LIMIT, ConnectionFailure.THROTTLED),
                    Triple(503, JmapErrorKind.TRANSIENT, ConnectionFailure.NETWORK),
                    Triple(408, JmapErrorKind.TRANSIENT, ConnectionFailure.TIMEOUT),
                    Triple(504, JmapErrorKind.TRANSIENT, ConnectionFailure.TIMEOUT),
                )) {
                fixture.responder = {
                    JmapFixture.Response("private body fixture-token".toByteArray(), status)
                }
                val error = failure(kind) { client.session() }
                assertEquals(connection, ConnectionFailure.classify(error))
                assertFalse(error.toString().contains("fixture-token"))
                assertFalse(error.toString().contains("private body"))
            }
        }

    @Test
    fun methodErrorsInsideHttp200AreSafeAndCannotCalculateChangesRequiresResync() =
        runBlocking<Unit> {
            val session = client.session()
            fixture.responder = { fixture.error("cannotCalculateChanges") }
            val error = failure(JmapErrorKind.METHOD) { client.changes(session, "expired") }
            assertTrue(error.requiresResync)
            assertFalse(error.toString().contains("fixture-token"))
            assertFalse(error.toString().contains("private subject"))
            fixture.responder = { fixture.error("credential@fixture.invalid") }
            assertNull(failure(JmapErrorKind.METHOD) { client.queryEmails(session) }.methodError)
        }

    @Test
    fun missingWrongOrDuplicateCallTagsAreProtocolFailures() =
        runBlocking<Unit> {
            val session = client.session()
            alterApi { root ->
                val response = root.getValue("methodResponses").jsonArray.single().jsonArray
                root.with(
                    "methodResponses",
                    buildJsonArray {
                        add(
                            buildJsonArray {
                                add(response[0])
                                add(response[1])
                                add("other")
                            }
                        )
                    },
                )
            }
            failure(JmapErrorKind.PROTOCOL) { client.queryEmails(session) }
            alterApi { root ->
                root.with(
                    "methodResponses",
                    buildJsonArray {
                        add(root.getValue("methodResponses").jsonArray.single())
                        add(root.getValue("methodResponses").jsonArray.single())
                    },
                )
            }
            failure(JmapErrorKind.PROTOCOL) { client.queryEmails(session) }
        }

    @Test
    fun wrongAccountOrIncompleteGetMustNotLookLikeDeletion() =
        runBlocking<Unit> {
            val session = client.session()
            alterApi { it.args { args -> args.with("accountId", JsonPrimitive("other-account")) } }
            failure(JmapErrorKind.PROTOCOL) { client.emails(session, listOf("E1")) }
            alterApi { it.args { args -> args.with("list", buildJsonArray {}) } }
            failure(JmapErrorKind.PROTOCOL) { client.emails(session, listOf("E1")) }
            alterApi { it.args { args -> args.with("notFound", buildJsonArray { add("E1") }) } }
            failure(JmapErrorKind.PROTOCOL) { client.emails(session, listOf("E1")) }
        }

    @Test
    fun nonProgressingChangesAreRejectedAndLegalOverlapsKeepDestroyedPrecedence() =
        runBlocking<Unit> {
            val session = client.session()
            alterApi {
                it.args { args ->
                    args
                        .with("newState", JsonPrimitive("emails-1"))
                        .with("hasMoreChanges", JsonPrimitive(true))
                }
            }
            failure(JmapErrorKind.PROTOCOL) { client.changes(session, "emails-1") }
            alterApi { it.args { args -> args.with("destroyed", buildJsonArray { add("E1") }) } }
            val overlaps = client.changes(session, "emails-1")
            assertTrue("E1" in overlaps.updated)
            assertTrue("E1" in overlaps.destroyed)
            assertFalse("E1" in overlaps.upsertIds)
            assertEquals(setOf("E2"), overlaps.upsertIds)
        }

    @Test
    fun changedSessionStateIsReturnedToTheCoordinator() =
        runBlocking<Unit> {
            val session = client.session()
            alterApi { it.with("sessionState", JsonPrimitive("new-session")) }
            assertEquals("new-session", client.emails(session, listOf("E1")).sessionState)
            // State is an opaque String, including a valid empty initial state.
            fixture.responder = { record ->
                if (record.path == "/session")
                    fixture.json(fixture.session().with("state", JsonPrimitive("")))
                else fixture.normal(record)
            }
            val empty = client.session()
            assertEquals("", empty.state)
            assertEquals("", client.changes(empty, "").oldState)
        }

    @Test
    fun malformedJsonUtf8TypesAndDeepNestingAreBoundedFailures() =
        runBlocking<Unit> {
            for (response in
                listOf(
                    JmapFixture.Response("{".toByteArray()),
                    JmapFixture.Response(byteArrayOf(0xc3.toByte(), 0x28)),
                    JmapFixture.Response("[]".toByteArray()),
                    JmapFixture.Response("{}".toByteArray(), type = "text/html"),
                )) {
                fixture.responder = { response }
                failure(JmapErrorKind.PROTOCOL) { client.session() }
            }
            fixture.responder = {
                JmapFixture.Response(("[".repeat(100) + "]".repeat(100)).toByteArray())
            }
            failure(JmapErrorKind.LIMIT) { client.session() }
        }

    @Test
    fun idArgumentsCannotInjectAnotherMethod() =
        runBlocking<Unit> {
            val session = client.session()
            val id = "E1\"], [\"Email/set\""
            assertEquals(setOf(id), client.emails(session, listOf(id)).notFound)
            val root = Json.parseToJsonElement(fixture.records.last().body).jsonObject
            val call = root.getValue("methodCalls").jsonArray.single().jsonArray
            assertEquals("Email/get", call[0].jsonPrimitive.content)
            assertEquals(
                id,
                call[1].jsonObject.getValue("ids").jsonArray.single().jsonPrimitive.content,
            )
        }

    @Test
    fun invalidBearerAndRequestDiagnosticsNeverExposeSecrets() =
        runBlocking<Unit> {
            try {
                MailClient()
                    .jmap(fixture.config, Credentials("private\r\nBearer"), fixture.transport)
                fail()
            } catch (e: IllegalArgumentException) {
                assertFalse(e.toString().contains("private"))
            }
            val request =
                JmapHttpRequest(
                    fixture.uri,
                    "POST",
                    mapOf("Authorization" to "Bearer private-secret"),
                    "private body".toByteArray(),
                )
            assertFalse(request.toString().contains("private"))
            assertFalse(
                JmapHttpResponse(200, "private".toByteArray()).toString().contains("private")
            )
        }

    @Test
    fun serverConcurrencyLimitIsRespected() =
        runBlocking<Unit> {
            fixture.responder = { fixture.json(fixture.session(concurrent = 1)) }
            val session = client.session()
            val active = AtomicInteger()
            val peak = AtomicInteger()
            fixture.responder = { record ->
                val n = active.incrementAndGet()
                peak.accumulateAndGet(n, ::maxOf)
                try {
                    Thread.sleep(80)
                    fixture.normal(record)
                } finally {
                    active.decrementAndGet()
                }
            }
            coroutineScope { (1..5).map { async { client.queryEmails(session) } }.awaitAll() }
            assertEquals(1, peak.get())
        }

    @Test
    fun cancellationDisconnectsAnInFlightResponsePromptly() =
        runBlocking<Unit> {
            val session = client.session()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.responder = { record ->
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                fixture.normal(record)
            }
            try {
                val job = launch(Dispatchers.Default) { client.queryEmails(session) }
                assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
                withTimeout(1000) { job.cancelAndJoin() }
            } finally {
                release.countDown()
            }
        }
}
