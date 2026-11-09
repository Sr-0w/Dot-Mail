package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.util.Properties
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThreadRepairTest {
    private lateinit var context: Context
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private lateinit var folder: FolderRow
    private var account = 0L
    private var nextUid = 1L
    private val dao
        get() = db.mail()

    private val vault =
        object : CredentialVault {
            override fun encrypt(accountEmail: String, secret: String) = secret

            override fun decrypt(accountEmail: String, encrypted: String) = encrypted
        }

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences("dotmail_notification_rules", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        NotificationRules.resetForTest()
        db =
            Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        graph = RealMailGraph(context, db, vault, scheduling = false)
        account =
            graph.addAccount(
                AccountConfig(
                    "me@example.org",
                    "Fixture",
                    ServerConfig("localhost", 1, Security.NONE, "me"),
                    ServerConfig("localhost", 1, Security.NONE, "me"),
                ),
                "fixture",
            )
        folder =
            dao.folder(
                dao.insertFolder(
                    FolderRow(
                        accountId = account,
                        path = "INBOX",
                        name = "Inbox",
                        role = "INBOX",
                        validity = 7,
                    )
                )
            )!!
    }

    @After
    fun close() {
        graph.scope.cancel()
        db.close()
        NotificationRules.resetForTest()
    }

    private fun header(
        mid: String?,
        at: Long = 100,
        refs: List<String> = emptyList(),
        reply: String? = null,
        gmail: Long? = null,
        subject: String = "Re: Shared",
    ) =
        Header(
            nextUid++,
            mid,
            refs,
            reply,
            listOf(Address("friend@example.org")),
            listOf(Address("me@example.org")),
            emptyList(),
            emptyList(),
            emptyList(),
            subject,
            at,
            RemoteFlags(1, false, false, false),
            false,
            gmailThreadId = gmail,
        )

    private suspend fun group(): Long =
        dao.insertThread(ThreadRow(accountId = account, subject = "Shared")).also {
            dao.queueThreadRepair(ThreadRepairRow(it))
        }

    private suspend fun add(thread: Long, h: Header, legacy: Boolean = false, id: Long = 0): Long {
        val raw =
            JSONObject(Codec.header(h))
                .apply {
                    if (legacy) {
                        remove("threadingRevision")
                        remove("inReplyTo")
                        remove("gmailThreadId")
                    }
                }
                .toString()
        return dao.insertMessage(
            MessageRow(
                id = id,
                accountId = account,
                folderId = folder.id,
                threadId = thread,
                uid = h.uid,
                header = raw,
                subject = h.subject,
                sentAt = h.sentAt,
                unread = true,
                starred = false,
                hasAttachments = false,
                messageIdKey = h.messageId,
            )
        )
    }

    private suspend fun thread(message: Long) = dao.message(message)!!.threadId

    private suspend fun expectCancellation(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected simulated process interruption")
        } catch (_: CancellationException) {}
    }

    @Test
    fun faultySubjectMergeSplitsButReplyChainsAndPhysicalCopiesStayTogether() = runBlocking {
        val old = group()
        val later = add(old, header("<later>", at = 200), id = 10)
        val oldest = add(old, header("<oldest>", at = 100), id = 20)
        val reply =
            add(
                old,
                header("<reply>", at = 300, refs = listOf("<oldest>"), subject = "Changed subject"),
            )
        val duplicate = add(old, header("<oldest>", at = 100))
        dao.putKey(ThreadKeyRow(account, "subject:shared|peers:bad", old))
        dao.putKey(ThreadKeyRow(account, "id:<later>", old))
        graph.rebuildThread(old)
        assertTrue(graph.threadRepair.run())
        assertEquals(old, thread(oldest))
        assertEquals(old, thread(reply))
        assertEquals(old, thread(duplicate))
        val split = thread(later)
        assertNotEquals(old, split)
        assertEquals(split, dao.threadKey(account, "id:<later>"))
        assertNull(dao.threadKey(account, "subject:shared|peers:bad"))
        assertEquals(2, dao.threadRow(old)!!.count)
        assertEquals(1, dao.threadRow(split)!!.count)
        assertTrue(graph.threadRepair.run())
        assertEquals(split, thread(later))
        assertFalse(dao.hasThreadRepairs())
    }

    @Test
    fun orphanReferencesAndGmailThreadsAreAuthoritativeWhileLegacyFallbackIsBounded() =
        runBlocking {
            val old = group()
            val a = add(old, header("<a>", refs = listOf("<missing>")))
            val b = add(old, header("<b>", refs = listOf("<missing>"), at = 200))
            val g1 = add(old, header("<g1>", gmail = 100, at = 300))
            val g2 = add(old, header("<g2>", gmail = 100, at = 400))
            val g3 = add(old, header("<g3>", gmail = 200, refs = listOf("<g1>"), at = 500))
            val legacy1 = add(old, header(null, at = 1000))
            val legacy2 = add(old, header(null, at = 2000))
            val far = add(old, header(null, at = 32L * 86400000))
            assertTrue(graph.threadRepair.run())
            assertEquals(thread(a), thread(b))
            assertEquals(thread(g1), thread(g2))
            assertNotEquals(thread(g1), thread(g3))
            assertEquals(thread(legacy1), thread(legacy2))
            assertNotEquals(thread(legacy1), thread(a))
            assertNotEquals(thread(far), thread(legacy1))
        }

    @Test
    fun splitPreservesBodiesAttachmentsOperationsAndCopiesThreadSnoozesOnly() = runBlocking {
        val old = group()
        val first = add(old, header("<a>"))
        val second = add(old, header("<b>", at = 200))
        val body = "x".repeat(3 * 1024 * 1024)
        dao.updateMessageRaw(dao.message(second)!!.copy(text = body, bodyLoaded = true))
        val attachment =
            dao.insertAttachment(
                AttachmentRow(
                    messageId = second,
                    partId = "1",
                    name = "fixture",
                    mime = "text/plain",
                    size = 12,
                    cid = null,
                    inline = false,
                    localUri = "content://fixture/unchanged",
                )
            )
        val op =
            dao.insertOperation(
                OperationRow(
                    accountId = account,
                    folderId = folder.id,
                    validity = 7,
                    uid = 2,
                    action = "FLAGS",
                    messageLocalId = second,
                    desiredSeen = true,
                )
            )
        val snooze =
            dao.insertSnooze(
                SnoozeRow(accountId = account, threadId = old, until = 99999, createdAt = 50)
            )
        dao.insertSnooze(
            SnoozeRow(
                accountId = account,
                threadId = old,
                messageId = second,
                until = 88888,
                createdAt = 60,
                resurfacedAt = 70,
            )
        )
        graph.setMuted(listOf(old), true)
        assertTrue(graph.threadRepair.run())
        val split = thread(second)
        assertEquals(old, thread(first))
        assertNotEquals(old, split)
        assertEquals("content://fixture/unchanged", dao.attachment(attachment)!!.localUri)
        assertEquals(second, dao.operations(account).single { it.id == op }.messageLocalId)
        var storedCharacters = 0
        while (storedCharacters < body.length) {
            val chunk = dao.bodyChunk(second, storedCharacters + 1, 32768)!!.text!!
            assertTrue(chunk.all { it == 'x' })
            assertTrue(chunk.isNotEmpty())
            storedCharacters += chunk.length
        }
        assertEquals(body.length, storedCharacters)
        assertEquals(99999L, dao.snoozedUntilOfThread(split))
        val z = dao.threadSnoozes(split)
        assertEquals(2, z.size)
        assertEquals(50L, z.single { it.messageId == null }.createdAt)
        assertEquals(70L, z.single { it.messageId != null }.resurfacedAt)
        assertEquals(snooze, dao.threadSnoozes(old).single().id)
        assertTrue(graph.isThreadMuted(split))
        assertTrue(graph.mutedThreads().first().containsAll(listOf(old, split)))
    }

    @Test
    fun interruptionDuringWriteRollsBackSplitKeysAndCheckpoint() = runBlocking {
        val old = group()
        val a = add(old, header("<a>"))
        val b = add(old, header("<b>", at = 200))
        val interrupted =
            ThreadRepair(
                graph,
                onPhase = {
                    if (it == ThreadRepair.Phase.UPDATING) throw CancellationException("fixture")
                },
            )
        expectCancellation { interrupted.run() }
        assertEquals(old, thread(a))
        assertEquals(old, thread(b))
        assertTrue(dao.hasThreadRepairs())
        assertTrue(graph.threadRepair.run())
        assertNotEquals(old, thread(b))
    }

    @Test
    fun committedSplitSurvivesRestartBeforeMutePreferencesWereWritten() = runBlocking {
        val old = group()
        add(old, header("<a>"))
        val b = add(old, header("<b>", at = 200))
        graph.setMuted(listOf(old), true)
        val interrupted =
            ThreadRepair(
                graph,
                onPhase = {
                    if (it == ThreadRepair.Phase.COMMITTED) throw CancellationException("fixture")
                },
            )
        expectCancellation { interrupted.run() }
        val split = thread(b)
        NotificationRules.resetForTest()
        assertFalse(NotificationRules.get(context).isMuted(split))
        assertTrue(graph.isThreadMuted(split))
        assertTrue(graph.mutedThreads().first().contains(split))
        assertFalse(dao.hasThreadRepairs())
        graph.threadRepair.flushMutes()
        NotificationRules.resetForTest()
        assertTrue(NotificationRules.get(context).isMuted(split))
        assertTrue(dao.repairMutes().isEmpty())
        assertTrue(graph.threadRepair.run())
        assertEquals(split, thread(b))
    }

    @Test
    fun preferenceCommitBeforeJournalDeleteIsIdempotentAndExplicitUnmuteWins() = runBlocking {
        val old = group()
        add(old, header("<a>"))
        val b = add(old, header("<b>", at = 200))
        graph.setMuted(listOf(old), true)
        val interrupted =
            ThreadRepair(
                graph,
                onPhase = {
                    if (it == ThreadRepair.Phase.MUTES_DURABLE)
                        throw CancellationException("fixture")
                },
            )
        expectCancellation { interrupted.run() }
        val split = thread(b)
        assertTrue(dao.hasRepairMute(split))
        NotificationRules.resetForTest()
        assertTrue(NotificationRules.get(context).isMuted(split))
        graph.setMuted(listOf(split), false)
        graph.threadRepair.flushMutes()
        assertFalse(graph.isThreadMuted(split))
        assertTrue(graph.isThreadMuted(old))
    }

    @Test
    fun concurrentHeaderChangeInvalidatesPlanAndRetryKeepsNewRelationship() = runBlocking {
        val old = group()
        add(old, header("<a>"))
        val bh = header("<b>", at = 200)
        val b = add(old, bh)
        val racing =
            ThreadRepair(
                graph,
                onPhase = { phase ->
                    if (phase == ThreadRepair.Phase.PLANNED)
                        dao.setHeader(b, Codec.header(bh.copy(inReplyTo = "<a>")))
                },
            )
        assertFalse(racing.run())
        assertEquals(old, thread(b))
        assertTrue(dao.hasThreadRepairs())
        assertTrue(graph.threadRepair.run())
        assertEquals(old, thread(b))
    }

    @Test
    fun missingHistoricalEvidenceNeverGuessesAndTransientFailuresRemainRetryable() = runBlocking {
        val old = group()
        add(old, header("<a>"), legacy = true)
        val b = add(old, header("<b>", at = 200), legacy = true)
        val offline =
            ThreadRepair(graph, recover = { _, _ -> throw java.net.SocketTimeoutException() })
        assertFalse(offline.run())
        assertTrue(dao.hasThreadRepairs())
        assertEquals(old, thread(b))
        assertTrue(ThreadRepair(graph, recover = { _, _ -> emptyMap() }).run())
        assertEquals(old, thread(b))
        assertFalse(dao.hasThreadRepairs())
        val status =
            db.openHelper.readableDatabase.query("SELECT state FROM thread_repairs").use {
                it.moveToFirst()
                it.getString(0)
            }
        assertEquals("SOURCE_UNAVAILABLE", status)
    }

    @Test
    fun singleMessagesNeedNoNetworkAndResurfacedSnoozesDoNotNotifyAgain() = runBlocking {
        val single = group()
        val m = add(single, header("<single>"), legacy = true)
        val old = group()
        add(old, header("<a>"))
        val b = add(old, header("<b>", at = 200))
        dao.insertSnooze(
            SnoozeRow(
                accountId = account,
                threadId = old,
                until = 100,
                createdAt = 20,
                resurfacedAt = 120,
            )
        )
        val repair = ThreadRepair(graph, recover = { _, _ -> error("Singleton should not fetch") })
        assertTrue(repair.run())
        assertEquals(single, thread(m))
        assertEquals(120L, dao.threadSnoozes(thread(b)).single().resurfacedAt)
        assertTrue(dao.dueSnoozes(1000).isEmpty())
    }

    @Test
    fun manyIndependentMessagesArePagedAndDoNotAcquireTheSyncMutex() = runBlocking {
        val old = group()
        repeat(240) { add(old, header("<message-$it>", at = it.toLong())) }
        graph.setMuted(listOf(old), true)
        graph.syncLock.lock()
        try {
            withTimeout(15000) { assertTrue(graph.threadRepair.run()) }
        } finally {
            graph.syncLock.unlock()
        }
        val distinct =
            db.openHelper.readableDatabase
                .query("SELECT COUNT(DISTINCT threadId) FROM messages")
                .use {
                    it.moveToFirst()
                    it.getInt(0)
                }
        assertEquals(240, distinct)
        // The journal crosses its 200-row read boundary; every copied mute must be durable.
        NotificationRules.resetForTest()
        assertEquals(240, NotificationRules.get(context).muted.value.size)
        assertTrue(dao.repairMutes().isEmpty())
    }

    @Test
    fun v9FixtureWithFaultyMergesRepairsOnceAndKeepsOriginalOldestId() =
        runBlocking<Unit> {
            val name = "thread-repair-v9-fixture.db"
            context.deleteDatabase(name)
            graph.scope.cancel()
            db.close()
            val headers =
                listOf(
                    header("<unrelated>", at = 300),
                    header("<root>", at = 100),
                    header("<reply>", at = 200, reply = "<root>"),
                )
            val schema =
                javaClass.classLoader!!
                    .getResourceAsStream("app.dot.mail.data.local.MailDatabase/9.json")!!
                    .use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
            val helper =
                androidx.sqlite.db.framework
                    .FrameworkSQLiteOpenHelperFactory()
                    .create(
                        androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                            .name(name)
                            .callback(
                                object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(9) {
                                    override fun onCreate(
                                        sql: androidx.sqlite.db.SupportSQLiteDatabase
                                    ) {
                                        val entities = schema.getJSONArray("entities")
                                        for (i in 0 until entities.length()) {
                                            val entity = entities.getJSONObject(i)
                                            sql.execSQL(
                                                entity
                                                    .getString("createSql")
                                                    .replace(
                                                        "\${TABLE_NAME}",
                                                        entity.getString("tableName"),
                                                    )
                                            )
                                            val indices = entity.getJSONArray("indices")
                                            for (j in 0 until indices.length()) sql.execSQL(
                                                indices
                                                    .getJSONObject(j)
                                                    .getString("createSql")
                                                    .replace(
                                                        "\${TABLE_NAME}",
                                                        entity.getString("tableName"),
                                                    )
                                            )
                                        }
                                        val setup = schema.getJSONArray("setupQueries")
                                        for (i in 0 until setup.length()) sql.execSQL(
                                            setup.getString(i)
                                        )
                                        sql.execSQL(
                                            "INSERT INTO accounts(id,email,name,color,config,secret,authError) VALUES(1,'fixture@example.org','Fixture',0,'{}','ciphertext',0)"
                                        )
                                        sql.execSQL(
                                            "INSERT INTO folders(id,accountId,path,name,role,unread,total,validity,nextUid,modSeq) VALUES(1,1,'INBOX','Inbox','INBOX',3,3,7,4,-1)"
                                        )
                                        sql.execSQL(
                                            "INSERT INTO threads(id,accountId,subject,participants,snippet,lastAt,count,unread,starred,attachments) VALUES(42,1,'Shared','[]','',300,3,3,0,0)"
                                        )
                                        for ((index, h) in headers.withIndex()) {
                                            val old =
                                                JSONObject(Codec.header(h))
                                                    .apply {
                                                        remove("threadingRevision")
                                                        remove("inReplyTo")
                                                        remove("gmailThreadId")
                                                    }
                                                    .toString()
                                            sql.execSQL(
                                                "INSERT INTO messages(id,accountId,folderId,threadId,uid,header,subject,sentAt,unread,starred,hasAttachments,snippet,text,html,bodyLoaded,remoteImages,senderSearch,recipientSearch,snippetFetched,bodyPath,messageIdKey) VALUES(?,1,1,42,?,?,'Shared',?,1,0,0,'','cached body',NULL,1,0,'friend','me',1,NULL,NULL)",
                                                arrayOf<Any>(index + 1, h.uid, old, h.sentAt),
                                            )
                                        }
                                        sql.execSQL(
                                            "INSERT INTO thread_keys(accountId,`key`,threadId) VALUES(1,'subject:shared|peers:old',42)"
                                        )
                                        sql.execSQL(
                                            "INSERT INTO snoozed(id,accountId,threadId,messageId,until,createdAt,resurfacedAt) VALUES(5,1,42,NULL,999999,50,NULL)"
                                        )
                                    }

                                    override fun onUpgrade(
                                        sql: androidx.sqlite.db.SupportSQLiteDatabase,
                                        oldVersion: Int,
                                        newVersion: Int,
                                    ) = error("Room owns upgrades")
                                }
                            )
                            .build()
                    )
            helper.writableDatabase
            helper.close()
            fun open() =
                Room.databaseBuilder(context, MailDatabase::class.java, name)
                    .addMigrations(MIGRATION_9_10, MIGRATION_10_11)
                    .allowMainThreadQueries()
                    .build()
            db = open()
            graph = RealMailGraph(context, db, vault, scheduling = false)
            assertTrue(dao.hasThreadRepairs())
            assertEquals(42L, thread(1))
            NotificationRules.get(context).setMutedDurably(listOf(42), true)
            val repair =
                ThreadRepair(
                    graph,
                    recover = { _, rows -> rows.associate { it.id to headers[it.id.toInt() - 1] } },
                )
            assertTrue(repair.run())
            val split = thread(1)
            assertNotEquals(42L, split)
            assertEquals(42L, thread(2))
            assertEquals(42L, thread(3))
            assertEquals("cached body", dao.bodyChunk(1, 1, 100)!!.text)
            assertEquals(999999L, dao.snoozedUntilOfThread(split))
            assertTrue(graph.isThreadMuted(split))
            graph.scope.cancel()
            db.close()
            db = open()
            graph = RealMailGraph(context, db, vault, scheduling = false)
            assertFalse(dao.hasThreadRepairs())
            assertTrue(graph.threadRepair.run())
            assertEquals(split, thread(1))
            // New threads never enter the one-time upgrade queue.
            dao.insertThread(ThreadRow(accountId = 1, subject = "New"))
            assertFalse(dao.hasThreadRepairs())
            context.deleteDatabase(name)
        }

    @Test
    fun greenMailRecoversOmittedReplyHeaderWithoutReadingBodyOrMarkingSeen() = runBlocking {
        val server = GreenMail(ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP))
        server.start()
        try {
            val user = server.setUser("me@example.org", "me", "fixture")
            fun message(mid: String, reply: String?) =
                MimeMessage(Session.getInstance(Properties())).apply {
                    setFrom(InternetAddress("friend@example.org"))
                    setRecipients(jakarta.mail.Message.RecipientType.TO, "me@example.org")
                    subject = "Shared"
                    setText("body stays unread")
                    saveChanges()
                    setHeader("Message-ID", mid)
                    if (reply != null) setHeader("In-Reply-To", reply)
                }
            user.deliver(message("<a>", null))
            user.deliver(message("<b>", "<a>"))
            user.deliver(message("<independent>", null))
            val cfg =
                AccountConfig(
                    "me@example.org",
                    "Fixture",
                    ServerConfig("127.0.0.1", server.imap.port, Security.NONE, "me"),
                    ServerConfig("127.0.0.1", 1, Security.NONE, "me"),
                )
            val stored = dao.account(account)!!
            dao.accountAuthorization(account, stored.secret, Codec.config(cfg))
            val sync =
                graph.client.sync(
                    cfg.imap.endpoint(),
                    Credentials("fixture"),
                    "INBOX",
                    windowDays = 0,
                )
            dao.updateFolder(folder.copy(validity = sync.cursor.uidValidity))
            folder = dao.folder(folder.id)!!
            val old = group()
            val ids = sync.headers.map { add(old, it, legacy = true) }
            val archive =
                dao.folder(
                    dao.insertFolder(
                        FolderRow(
                            accountId = account,
                            path = "Archive",
                            name = "Archive",
                            role = "ARCHIVE",
                        )
                    )
                )!!
            graph.moveMessage(dao.message(ids[2])!!, archive)
            assertTrue(dao.message(ids[2])!!.uid < 0)
            assertTrue(graph.threadRepair.run())
            assertEquals(thread(ids[0]), thread(ids[1]))
            assertNotEquals(thread(ids[0]), thread(ids[2]))
            assertTrue(ids.none { dao.message(it)!!.bodyLoaded })
            val after =
                graph.client.sync(
                    cfg.imap.endpoint(),
                    Credentials("fixture"),
                    "INBOX",
                    windowDays = 0,
                )
            assertEquals(3, after.headers.size)
            assertTrue(after.headers.none { it.flags.seen })
        } finally {
            server.stop()
        }
    }
}
