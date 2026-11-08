package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImapHardeningTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private lateinit var folder: FolderRow
    private var account = 0L
    private val threadReads = AtomicInteger()
    private val messageWrites = AtomicInteger()
    private val allFolderHeaders = AtomicInteger()

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryCallback(
                    { sql, _ ->
                        if (sql.contains("FROM messages WHERE threadId=", true))
                            threadReads.incrementAndGet()
                        if (
                            sql.trimStart().startsWith("UPDATE", true) &&
                                (sql.contains("`messages`", true) ||
                                    sql.startsWith("UPDATE messages", true))
                        )
                            messageWrites.incrementAndGet()
                        if (
                            sql.contains("FROM messages WHERE folderId=", true) &&
                                sql.contains("header", true) &&
                                !sql.contains("LIMIT", true) &&
                                !sql.contains("AND uid=", true)
                        )
                            allFolderHeaders.incrementAndGet()
                    },
                    Executor { it.run() },
                )
                .build()
        graph =
            RealMailGraph(
                context,
                db,
                object : CredentialVault {
                    override fun encrypt(accountEmail: String, secret: String) = secret

                    override fun decrypt(accountEmail: String, encrypted: String) = encrypted
                },
                scheduling = false,
            )
        account =
            graph.addAccount(
                AccountConfig(
                    "fixture@example.org",
                    "Fixture",
                    ServerConfig("127.0.0.1", 1, Security.NONE, "fixture"),
                    ServerConfig("127.0.0.1", 1, Security.NONE, "fixture"),
                ),
                "fixture-password",
            )
        val id =
            db.mail()
                .insertFolder(
                    FolderRow(
                        accountId = account,
                        path = "INBOX",
                        name = "Inbox",
                        role = "INBOX",
                        validity = 7,
                    )
                )
        folder = db.mail().folder(id)!!
    }

    @After
    fun close() {
        graph.scope.cancel()
        db.close()
    }

    private fun header(
        uid: Long,
        subject: String = "Topic",
        mid: String? = "<m$uid@fixture.invalid>",
        refs: List<String> = emptyList(),
        time: Long = 1000L + uid,
    ) =
        Header(
            uid,
            mid,
            refs,
            refs.lastOrNull(),
            listOf(Address("peer@example.org")),
            listOf(Address("fixture@example.org")),
            emptyList(),
            emptyList(),
            emptyList(),
            subject,
            time,
            RemoteFlags(uid, false, false, false),
            false,
        )

    private val remote
        get() = RemoteFolder("INBOX", "Inbox", Role.INBOX, 0, 0)

    @Test
    fun independentRepliesWithIdenticalSubjectAndPeersNeverMerge() = runBlocking {
        val first = graph.upsertHeader(folder, header(1, "Invoice")).first
        val unrelated = graph.upsertHeader(folder, header(2, "Re: Invoice")).first
        assertNotEquals(first, unrelated)
        val reply =
            graph
                .upsertHeader(
                    folder,
                    header(3, "A changed subject", refs = listOf("<m1@fixture.invalid>")),
                )
                .first
        assertEquals(first, reply)
    }

    @Test
    fun fallbackIsLimitedToUnidentifiedLegacyMailWithTheSamePeersAndTimeWindow() = runBlocking {
        val identified = graph.upsertHeader(folder, header(1)).first
        // Older migrations added messageIdKey without backfilling the existing JSON header.
        db.mail().byUid(folder.id, 1)!!.let {
            db.mail().updateMessage(it.copy(messageIdKey = null))
        }
        // Simulate a pre-fix subject key pointing at an identified conversation.
        db.mail().putKey(ThreadKeyRow(account, Threading.fallbackKey(header(1))!!, identified))
        val legacy = graph.upsertHeader(folder, header(2, mid = null)).first
        assertNotEquals(identified, legacy)
        assertEquals(legacy, graph.upsertHeader(folder, header(3, "Re: Topic", mid = null)).first)
        assertNotEquals(
            legacy,
            graph.upsertHeader(folder, header(4, mid = null, time = 1000L + 31L * 86400000)).first,
        )
    }

    @Test
    fun folderBatchRebuildsAConversationOnceAndNeverProjectsEveryHeader() = runBlocking {
        val headers =
            (1L..180L).map {
                header(it, refs = if (it == 1L) emptyList() else listOf("<m1@fixture.invalid>"))
            }
        threadReads.set(0)
        allFolderHeaders.set(0)
        graph.applyFolderHeaders(
            db.mail().account(account)!!,
            folder,
            remote,
            HeaderSync(
                SyncCursor(7, 181, 3, 180),
                true,
                headers,
                emptyList(),
                headers.map { it.uid }.toSet(),
            ),
        )
        assertTrue(
            "Expected a single batched thread read, got ${threadReads.get()}",
            threadReads.get() <= 1,
        )
        assertEquals(
            "A sync commit must not materialize every header JSON",
            0,
            allFolderHeaders.get(),
        )
        val rows = db.mail().folderMessages(folder.id)
        assertEquals(180, rows.size)
        assertEquals(1, rows.map { it.threadId }.distinct().size)
        assertEquals(180, db.mail().threadRow(rows.first().threadId)!!.count)
    }

    @Test
    fun unchangedFlagInventoryDoesNotRewriteMessagesOrRebuildThreads() = runBlocking {
        (1L..30).forEach { graph.upsertHeader(folder, header(it)) }
        messageWrites.set(0)
        threadReads.set(0)
        graph.applyFolderHeaders(
            db.mail().account(account)!!,
            folder,
            remote,
            HeaderSync(
                SyncCursor(7, 31, 4, 30),
                false,
                emptyList(),
                (1L..30).map { RemoteFlags(it, false, false, false) },
                (1L..30).toSet(),
            ),
        )
        assertEquals(0, messageWrites.get())
        assertEquals(0, threadReads.get())
    }

    @Test
    fun finalFlagInventoryWinsAHeaderRaceAndPreservesLargeLegacyBodies() = runBlocking {
        val original = header(1)
        val thread = graph.upsertHeader(folder, original).first
        val row = db.mail().byUid(folder.id, 1)!!
        db.mail().updateMessage(row.copy(bodyLoaded = true, text = "x".repeat(3000000)))
        graph.applyFolderHeaders(
            db.mail().account(account)!!,
            folder,
            remote,
            HeaderSync(
                SyncCursor(7, 2, 5, 1),
                false,
                listOf(original.copy(flags = RemoteFlags(1, true, false, false))),
                listOf(RemoteFlags(1, false, false, false)),
                setOf(1),
            ),
        )
        assertTrue(db.mail().message(row.id)!!.unread)
        val body = graph.thread(thread).first().single().body!!
        assertEquals(512 * 1024, body.plainText!!.length)
        assertTrue(body.isTruncated)
    }

    @Test
    fun smartFolderActionsTouchOnlyMatchingMessagesAndLeaveSentCopiesAlone() = runBlocking {
        val thread = graph.upsertHeader(folder, header(1)).first
        val original = db.mail().byUid(folder.id, 1)!!
        val sent =
            db.mail()
                .insertFolder(
                    FolderRow(
                        accountId = account,
                        path = "Sent",
                        name = "Sent",
                        role = "SENT",
                        validity = 7,
                    )
                )
        val sentCopy = db.mail().insertMessage(original.copy(id = 0, folderId = sent, uid = 50))
        val other =
            graph
                .upsertHeader(folder, header(3, "Different", refs = listOf("<m1@fixture.invalid>")))
                .first
        assertEquals(thread, other)
        val archive =
            db.mail()
                .insertFolder(
                    FolderRow(
                        accountId = account,
                        path = "Archive",
                        name = "Archive",
                        role = "ARCHIVE",
                        validity = 7,
                    )
                )
        val smart =
            SmartFolder(
                "imap-scope-fixture",
                "Fixture",
                roles = setOf(FolderRole.INBOX),
                subject = "Topic",
            )
        graph.smartFolders.save(smart)
        try {
            graph.apply(
                MessageAction.MARK_READ,
                listOf(ConversationIds.MESSAGE_BASE + original.id),
                Mailbox.Smart(smart.id),
            )
            assertFalse(db.mail().message(original.id)!!.unread)
            assertTrue(
                "A filtered action must not change the Sent copy",
                db.mail().message(sentCopy)!!.unread,
            )
            assertTrue(db.mail().byUid(folder.id, 3)!!.unread)
            graph.move(listOf(thread), archive, Mailbox.Smart(smart.id))
            assertEquals(archive, db.mail().message(original.id)!!.folderId)
            assertEquals(sent, db.mail().message(sentCopy)!!.folderId)
            assertEquals(folder.id, db.mail().byUid(folder.id, 3)!!.folderId)
            assertTrue(db.mail().operations(account).all { it.messageLocalId == original.id })
        } finally {
            graph.smartFolders.delete(smart.id)
        }
    }

    @Test
    fun previewAndMetadataChecksStayBoundedWithoutLosingOldRepairCandidates() = runBlocking {
        val thread = db.mail().insertThread(ThreadRow(accountId = account, subject = "Fixture"))
        for (uid in 1L..260L) db.mail()
            .insertMessage(
                MessageRow(
                    accountId = account,
                    folderId = folder.id,
                    threadId = thread,
                    uid = uid,
                    header = Codec.header(header(uid)),
                    subject = "Fixture",
                    sentAt = uid,
                    unread = true,
                    starred = false,
                    hasAttachments = false,
                )
            )
        assertFalse(db.mail().folderNeedsMetadata(folder.id))
        val recent = db.mail().recentFolderMessages(folder.id)
        assertEquals(200, recent.size)
        assertEquals((61L..260L).toSet(), recent.map { it.uid }.toSet())
        assertTrue(db.mail().folderNeedsPreviews(folder.id))
        val oldBody = db.mail().byUid(folder.id, 1)!!
        db.mail().updateMessage(oldBody.copy(bodyLoaded = true, text = "legacy cache"))
        val oldPreview = db.mail().byUid(folder.id, 2)!!
        db.mail().updateMessage(oldPreview.copy(snippet = "old preview"))
        recent.forEach { db.mail().setSnippet(it.id, "done") }
        assertEquals(listOf(1L), db.mail().localFolderPreviewRepairs(folder.id).map { it.uid })
        assertEquals(listOf(2L), db.mail().remoteFolderPreviewRepairs(folder.id).map { it.uid })
        assertTrue(db.mail().folderNeedsPreviews(folder.id))
        db.mail().setSnippet(oldBody.id, "done")
        db.mail().setSnippet(oldPreview.id, "done")
        assertFalse(db.mail().folderNeedsPreviews(folder.id))
        db.mail().updateMessage(oldPreview.copy(header = "{}", snippetFetched = true))
        assertTrue(db.mail().folderNeedsMetadata(folder.id))
        assertEquals(listOf(2L), db.mail().folderMetadataRepairs(folder.id).map { it.uid })
    }

    @Test
    fun localFlagsAndCachedBodyRemainAvailableWhileTheSyncMutexIsHeld() = runBlocking {
        val thread = graph.upsertHeader(folder, header(1)).first
        val row = db.mail().byUid(folder.id, 1)!!
        db.mail().updateMessage(row.copy(bodyLoaded = true, text = "cached body"))
        graph.syncLock.lock()
        try {
            withTimeout(1500) {
                graph.apply(MessageAction.MARK_READ, listOf(thread), Mailbox.InFolder(folder.id))
                graph.loadBody(row.id)
                assertEquals("cached body", graph.thread(thread).first().single().body!!.plainText)
            }
            assertFalse(db.mail().message(row.id)!!.unread)
        } finally {
            graph.syncLock.unlock()
        }
    }
}
