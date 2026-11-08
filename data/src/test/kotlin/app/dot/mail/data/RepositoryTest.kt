package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RepositoryTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private val config = AccountConfig("alice@example.org", "Alice", ServerConfig("localhost", 143, Security.NONE, "alice"), ServerConfig("localhost", 25, Security.NONE, "alice"))
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        graph = RealMailGraph(context, db, object : CredentialVault {
            override fun encrypt(accountEmail: String, secret: String) = "encrypted:$secret"
            override fun decrypt(accountEmail: String, encrypted: String) = encrypted.removePrefix("encrypted:")
        }, scheduling = false)
    }
    @After fun cleanup() { graph.scope.cancel(); db.close() }
    @Test fun draftUndoAndCascade() = runBlocking {
        val account = graph.addAccount(config, "password")
        val draft = Draft(accountId = account, identityId = account, to = listOf(EmailAddress("bob@example.org")), subject = "test", bodyText = "hello", references = listOf("<root@example.org>"))
        val id = graph.saveDraft(draft)
        assertEquals(draft.copy(localId = id), graph.loadDraft(id))
        val queued = graph.send(draft.copy(localId = id), 60_000)
        assertNull(graph.loadDraft(id)); assertTrue(graph.cancelSend(queued)); assertFalse(graph.cancelSend(queued))
        assertEquals("hello", graph.loadDraft(id)?.bodyText)
        assertTrue(db.mail().account(account)!!.secret.startsWith("encrypted:"))
        graph.removeAccount(account)
        assertNull(db.mail().outbox(queued)); assertNull(graph.loadDraft(id))
    }
    @Test fun optimisticFlagsAndThreading() = runBlocking {
        val account = graph.addAccount(config, "password")
        val folderId = db.mail().insertFolder(FolderRow(accountId = account, path = "INBOX", name = "INBOX", role = "INBOX", validity = 7))
        val folder = db.mail().folder(folderId)!!
        fun header(uid: Long, messageId: String, refs: List<String>) = Header(uid, messageId, refs, refs.lastOrNull(), listOf(Address("bob@example.org", "Bob")), listOf(Address("alice@example.org")), emptyList(), emptyList(), emptyList(), "Re: Topic", uid * 100, RemoteFlags(uid, false, false, false), false)
        val thread = graph.upsertHeader(folder, header(1, "<one@example.org>", emptyList())).first
        assertEquals(thread, graph.upsertHeader(folder, header(2, "<two@example.org>", listOf("<one@example.org>"))).first)
        assertEquals(2, graph.thread(thread).first().size)
        graph.apply(MessageAction.MARK_READ, listOf(thread))
        assertTrue(graph.thread(thread).first().none { it.isUnread })
        assertEquals(2, db.mail().operations(account).size)
        graph.upsertHeader(folder, header(1, "<one@example.org>", emptyList()))
        assertFalse(db.mail().byUid(folderId, 1)!!.unread)
        assertEquals("bob@example.org", graph.suggestContacts("bo").single().email)
    }
    @Test fun greenMailSyncLoadsBodyOnlyOnDemand() = runBlocking {
        val server = GreenMail(arrayOf(ServerSetup(0, "127.0.0.1", "imap"), ServerSetup(0, "127.0.0.1", "smtp")))
        server.start()
        try {
            server.setUser("alice@example.org", "alice", "password")
            val local = config.copy(imap = config.imap.copy(host = "127.0.0.1", port = server.imap.port), smtp = config.smtp.copy(host = "127.0.0.1", port = server.smtp.port))
            graph.client.send(local.smtp.endpoint(), Credentials("password"), Mime.build(OutgoingMail(Address("alice@example.org"), listOf(Address("alice@example.org")), subject = "Live sync", text = "body", html = "<img src='https://tracker.invalid/a'>")))
            assertTrue(server.waitForIncomingEmail(5000, 1))
            val account = graph.addAccount(local, "password")
            assertTrue(graph.syncAll())
            val inbox = db.mail().folders(account).first { it.role == "INBOX" }
            val row = db.mail().folderMessages(inbox.id).single()
            assertFalse(row.bodyLoaded); assertNull(row.text)
            assertEquals("body", row.snippet); assertTrue(row.snippetFetched)
            graph.loadBody(row.id)
            assertEquals("body", db.mail().message(row.id)!!.text)
            assertTrue(db.mail().message(row.id)!!.remoteImages)
            graph.apply(MessageAction.MARK_READ, listOf(row.threadId))
            assertTrue(graph.syncAll()); assertTrue(db.mail().operations(account).isEmpty())
            assertFalse(db.mail().message(row.id)!!.unread)
        } finally { server.stop() }
    }
    @Test fun localSearchSupportsOperatorsAcrossAccounts() = runBlocking {
        val account = graph.addAccount(config, "password")
        val folder = FolderRow(id = db.mail().insertFolder(FolderRow(accountId = account, path = "INBOX", name = "INBOX", role = "INBOX")), accountId = account, path = "INBOX", name = "INBOX", role = "INBOX")
        graph.upsertHeader(folder, Header(1, "<search@example.org>", emptyList(), null, listOf(Address("bob@example.org", "Bob Smith")), listOf(Address("alice@example.org")), emptyList(), emptyList(), emptyList(), "Weekly Report", 100, RemoteFlags(1, false, true, false), true))
        suspend fun count(query: String, accountId: Long? = null): Int {
            val page = graph.searchSource(query, accountId).load(androidx.paging.PagingSource.LoadParams.Refresh(key = null, loadSize = 40, placeholdersEnabled = false))
            return (page as androidx.paging.PagingSource.LoadResult.Page).data.size
        }
        assertEquals(1, count("from:bob to:alice subject:Report has:attachment is:unread is:starred"))
        assertEquals(1, count("\"Weekly Report\"", account))
        assertEquals(0, count("from:nobody")); assertEquals(0, count("subject:Weekly", account + 100))
        assertEquals(0, count("subject:%"))
    }
    @Test fun outboxDeliversOnceAndAppendsSent() = runBlocking {
        val server = GreenMail(arrayOf(ServerSetup(0, "127.0.0.1", "imap"), ServerSetup(0, "127.0.0.1", "smtp")))
        server.start()
        try {
            server.setUser("alice@example.org", "alice", "password")
            val local = config.copy(imap = config.imap.copy(host = "127.0.0.1", port = server.imap.port), smtp = config.smtp.copy(host = "127.0.0.1", port = server.smtp.port))
            val account = graph.addAccount(local, "password")
            val draft = Draft(accountId = account, identityId = account, to = listOf(EmailAddress("alice@example.org")), subject = "Outbox test", bodyText = "persisted")
            val future = graph.send(draft.copy(scheduledAt = System.currentTimeMillis() + 60_000), 0)
            assertFalse(graph.deliverOutbox(future)); assertTrue(graph.cancelSend(future))
            val id = graph.send(draft, 0)
            assertTrue(graph.deliverOutbox(id)); assertTrue(graph.deliverOutbox(id))
            assertEquals("SENT", db.mail().outbox(id)!!.state)
            assertTrue(server.waitForIncomingEmail(5000, 1))
            assertEquals(1, graph.client.sync(local.imap.endpoint(), Credentials("password"), "Sent").headers.size)
        } finally { server.stop() }
    }
    @Test fun optimisticMoveKeepsContentAndReconcilesUid() = runBlocking {
        val account = graph.addAccount(config, "password")
        val sourceId = db.mail().insertFolder(FolderRow(accountId = account, path = "INBOX", name = "INBOX", role = "INBOX", validity = 1))
        val targetId = db.mail().insertFolder(FolderRow(accountId = account, path = "Archive", name = "Archive", role = "ARCHIVE", validity = 2))
        val header = Header(4, "<move@example.org>", emptyList(), null, listOf(Address("bob@example.org")), emptyList(), emptyList(), emptyList(), emptyList(), "Move me", 100, RemoteFlags(4, false, false, false), false)
        val thread = graph.upsertHeader(db.mail().folder(sourceId)!!, header).first
        val original = db.mail().threadMessages(thread).single()
        db.mail().updateMessage(original.copy(text = "cached", bodyLoaded = true))
        graph.move(listOf(thread), targetId)
        val moved = db.mail().threadMessages(thread).single()
        assertEquals(targetId, moved.folderId); assertTrue(moved.uid < 0); assertEquals("cached", moved.text)
        graph.upsertHeader(db.mail().folder(targetId)!!, header.copy(uid = 99))
        assertEquals(99L, db.mail().threadMessages(thread).single().uid)
        val operation = db.mail().operations(account).single()
        assertEquals(sourceId, operation.folderId); assertEquals(4L, operation.uid)
    }
    @Test fun uidValidityChangeDoesNotApplyQueuedFlagsToReusedUid() = runBlocking {
        val server = GreenMail(arrayOf(ServerSetup(0, "127.0.0.1", "imap"), ServerSetup(0, "127.0.0.1", "smtp")))
        server.start()
        try {
            server.setUser("alice@example.org", "alice", "password")
            val local = config.copy(imap = config.imap.copy(host = "127.0.0.1", port = server.imap.port), smtp = config.smtp.copy(host = "127.0.0.1", port = server.smtp.port))
            graph.client.send(local.smtp.endpoint(), Credentials("password"), Mime.build(OutgoingMail(Address("alice@example.org"), listOf(Address("alice@example.org")), subject = "UID safety", text = "content")))
            assertTrue(server.waitForIncomingEmail(5000, 1))
            val account = graph.addAccount(local, "password"); assertTrue(graph.syncAll())
            val folder = db.mail().folders(account).first { it.role == "INBOX" }
            val message = db.mail().folderMessages(folder.id).single()
            db.mail().updateFolder(folder.copy(validity = folder.validity + 1))
            graph.apply(MessageAction.MARK_READ, listOf(message.threadId))
            assertTrue(graph.syncAll())
            val current = db.mail().folderMessages(folder.id).single()
            assertTrue(current.unread)
            assertTrue(db.mail().operations(account).single().error!!.contains("UIDVALIDITY"))
        } finally { server.stop() }
    }
    @Test fun savedAttachmentsSurviveOriginalGrantLoss() = runBlocking {
        val account = graph.addAccount(config, "password")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(context.filesDir, "attachments/picker/picked-report.txt").apply { parentFile!!.mkdirs(); writeText("persistent bytes") }
        val draft = Draft(accountId = account, identityId = account, to = listOf(EmailAddress("bob@example.org")), attachmentUris = listOf(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.attachments", file).toString()))
        val id = graph.saveDraft(draft)
        val cached = graph.loadDraft(id)!!
        assertTrue(cached.attachmentUris.single().startsWith("content://"))
        file.delete()
        assertEquals(id, graph.saveDraft(draft.copy(localId = id)))
        val queued = graph.send(cached, 60_000)
        val saved = Codec.draft(db.mail().outbox(queued)!!.draft)
        val bytes = context.contentResolver.openInputStream(android.net.Uri.parse(saved.attachmentUris.single()))!!.use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals("persistent bytes", bytes)
    }
    @Test fun cancellationCannotBeRevertedByWorkerPreparation() = runBlocking {
        val account = graph.addAccount(config, "password")
        val outbox = graph.send(Draft(accountId = account, identityId = account, to = listOf(EmailAddress("bob@example.org"))), 0)
        assertTrue(graph.cancelSend(outbox))
        assertEquals(0, db.mail().outboxPath(outbox, "/unused"))
        assertEquals(0, db.mail().transitionOutbox(outbox, "PENDING", "SUBMITTING"))
        assertEquals("CANCELLED", db.mail().outbox(outbox)!!.state)
    }
    @Test fun externalFileUrisCannotSnapshotPrivateData() = runBlocking {
        val account = graph.addAccount(config, "password")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sensitive = java.io.File(context.filesDir, "private-secret.txt").apply { writeText("private fixture") }
        val failure = runCatching { graph.saveDraft(Draft(accountId = account, identityId = account, attachmentUris = listOf(android.net.Uri.fromFile(sensitive).toString()))) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
    @Test fun outboxReviewRequiresAcknowledgementAndProtectsActiveDelivery() = runBlocking {
        val account = graph.addAccount(config, "password")
        val id = graph.send(Draft(accountId = account, identityId = account, to = listOf(EmailAddress("bob@example.org")), subject = "Review"), 60_000)
        assertEquals(OutboxState.QUEUED, graph.outbox.first().single().state)
        db.mail().transitionOutbox(id, "PENDING", "SUBMITTING")
        assertEquals(OutboxState.SENDING, graph.outbox.first().single().state)
        assertFalse(graph.deleteOutbox(id)); assertFalse(graph.retrySend(id, true))
        graph.recoverOutbox()
        val review = graph.outbox.first().single()
        assertEquals(OutboxState.NEEDS_REVIEW, review.state); assertTrue(review.canRetry); assertTrue(review.canDelete)
        assertFalse(graph.retrySend(id)); assertEquals("UNCERTAIN", db.mail().outbox(id)!!.state)
        assertTrue(graph.retrySend(id, confirmPossibleDuplicate = true))
        assertEquals("PENDING", db.mail().outbox(id)!!.state)
        assertEquals(review.sendAt, graph.outbox.first().single().sendAt)
        assertTrue(graph.deleteOutbox(id)); assertTrue(graph.outbox.first().isEmpty())
        assertFalse(graph.deleteOutbox(id)); assertTrue(graph.deliverOutbox(id))
    }
    @Test fun savingSentRetryDoesNotResendSmtp() = runBlocking {
        val server = GreenMail(arrayOf(ServerSetup(0, "127.0.0.1", "imap"), ServerSetup(0, "127.0.0.1", "smtp")))
        server.start()
        try {
            server.setUser("alice@example.org", "alice", "password")
            val local = config.copy(imap = config.imap.copy(host = "127.0.0.1", port = server.imap.port), smtp = config.smtp.copy(host = "127.0.0.1", port = server.smtp.port))
            val account = graph.addAccount(local, "password")
            val id = graph.send(Draft(accountId = account, identityId = account, to = listOf(EmailAddress("alice@example.org")), subject = "Copy retry"), 0)
            assertTrue(graph.deliverOutbox(id)); assertTrue(graph.outbox.first().isEmpty())
            val stored = db.mail().outbox(id)!!
            db.mail().updateOutbox(stored.copy(state = "APPENDING"))
            assertFalse(graph.deleteOutbox(id)); graph.recoverOutbox()
            assertEquals(OutboxState.SAVING_SENT, graph.outbox.first().single().state)
            assertTrue(graph.retrySend(id)); assertEquals("APPEND", db.mail().outbox(id)!!.state)
            assertTrue(graph.deliverOutbox(id))
            assertEquals(1, graph.client.sync(local.imap.endpoint(), Credentials("password"), "INBOX").headers.size)
            assertEquals(1, graph.client.sync(local.imap.endpoint(), Credentials("password"), "Sent").headers.size)
        } finally { server.stop() }
    }
    @Test fun mimeSerializationOnAndroidRuntime() {
        val mime = Mime.build(OutgoingMail(Address("a@example.org"), listOf(Address("b@example.org")), subject = "Été", text = "hello", html = "<b>hello</b>", attachments = listOf(OutgoingAttachment("test.txt", "text/plain", "file".toByteArray()))))
        val output = java.io.ByteArrayOutputStream(); mime.writeTo(output)
        assertTrue(output.size() > 100); assertEquals("hello", Mime.parse(mime).text)
    }
}
