package app.dot.mail.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeviceRegressionTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private val config = AccountConfig("a@example.org", "A", ServerConfig("127.0.0.1", 1, Security.NONE, "a"), ServerConfig("127.0.0.1", 1, Security.NONE, "a"))
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        graph = RealMailGraph(context, db, object : CredentialVault {
            override fun encrypt(accountEmail: String, secret: String) = secret
            override fun decrypt(accountEmail: String, encrypted: String) = encrypted
        }, client = MailClient(1000), scheduling = false)
    }
    @After fun close() { graph.scope.cancel(); db.close() }
    private suspend fun fixture(account: Long): Pair<Long, Long> {
        val inbox = db.mail().insertFolder(FolderRow(accountId = account, path = "INBOX", name = "INBOX", role = "INBOX", validity = 7))
        val header = Header(42, "<fixture@example.org>", emptyList(), null, listOf(Address("a@example.org")), listOf(Address("recipient@example.org", "Recipient")), emptyList(), emptyList(), emptyList(), "Subject", 100, RemoteFlags(42,false,false,false),false)
        return inbox to graph.upsertHeader(db.mail().folder(inbox)!!, header).first
    }
    @Test fun flagsFoldIntoPendingMoveAndUndoRestoresRealUid() = runBlocking {
        val account = graph.addAccount(config,"password"); val (inbox, thread) = fixture(account)
        val archive = db.mail().insertFolder(FolderRow(accountId = account, path = "Archive", name = "Archive", role = "ARCHIVE", validity = 8))
        graph.move(listOf(thread), archive)
        graph.apply(MessageAction.MARK_READ, listOf(thread)); graph.apply(MessageAction.STAR, listOf(thread))
        val moved = db.mail().threadMessages(thread).single()
        assertTrue(moved.uid < 0); assertFalse(moved.unread); assertTrue(moved.starred)
        val op = db.mail().operations(account).single(); assertEquals(true,op.desiredSeen); assertEquals(true,op.desiredStarred)
        graph.move(listOf(thread), inbox)
        assertEquals(42L,db.mail().threadMessages(thread).single().uid)
        assertEquals("FLAGS",db.mail().operations(account).single().action)
    }
    @Test fun invalidRoleAndCrossAccountMoveAreTypedAndAtomic() = runBlocking {
        val account = graph.addAccount(config,"password"); val (inbox, thread) = fixture(account)
        val missing = runCatching { graph.apply(MessageAction.ARCHIVE,listOf(thread)) }.exceptionOrNull()
        assertEquals(MailActionFailure.FOLDER_UNAVAILABLE,(missing as MailActionException).reason)
        assertTrue(db.mail().operations(account).isEmpty())
        val other = graph.addAccount(config.copy(email="b@example.org"),"password")
        val target = db.mail().insertFolder(FolderRow(accountId=other,path="Archive",name="Archive",role="ARCHIVE"))
        val mismatch = runCatching { graph.move(listOf(thread),target) }.exceptionOrNull()
        assertEquals(MailActionFailure.ACCOUNT_MISMATCH,(mismatch as MailActionException).reason)
        assertEquals(inbox,db.mail().threadMessages(thread).single().folderId)
    }
    @Test fun folderScopedMoveLeavesSentAndOtherCopiesUntouched() = runBlocking {
        val account = graph.addAccount(config, "password")
        val (inbox, thread) = fixture(account)
        val sent = db.mail().insertFolder(FolderRow(accountId=account,path="Sent",name="Sent",role="SENT"))
        val all = db.mail().insertFolder(FolderRow(accountId=account,path="All",name="All",role="ALL_MAIL"))
        val archive = db.mail().insertFolder(FolderRow(accountId=account,path="Archive",name="Archive",role="ARCHIVE"))
        val original = db.mail().threadMessages(thread).single()
        db.mail().insertMessage(original.copy(id=0,folderId=sent,uid=50))
        db.mail().insertMessage(original.copy(id=0,folderId=all,uid=60))
        graph.move(listOf(thread),archive,Mailbox.InFolder(inbox))
        val rows = db.mail().threadMessages(thread)
        assertEquals(setOf(sent,all,archive),rows.map { it.folderId }.toSet())
        assertEquals(50L,rows.single { it.folderId==sent }.uid)
        assertEquals(60L,rows.single { it.folderId==all }.uid)
        assertEquals(inbox,db.mail().operations(account).single().folderId)
        // An obsolete UI selection must not retarget the already moved row.
        graph.apply(MessageAction.TRASH,listOf(thread),Mailbox.InFolder(inbox))
        assertEquals(1,db.mail().operations(account).size)
    }
    @Test fun trashAndSpamMoveOnlyCurrentFolderMessages() = runBlocking {
        for (action in listOf(MessageAction.TRASH,MessageAction.SPAM)) {
            val account = graph.addAccount(config.copy(email="${action.name}@example.org"),"password")
            val (inbox,thread) = fixture(account)
            val sent = db.mail().insertFolder(FolderRow(accountId=account,path="Sent",name="Sent",role="SENT"))
            val role = if (action==MessageAction.TRASH) "TRASH" else "SPAM"
            val target = db.mail().insertFolder(FolderRow(accountId=account,path=role,name=role,role=role))
            db.mail().insertMessage(db.mail().threadMessages(thread).single().copy(id=0,folderId=sent,uid=55))
            graph.apply(action,listOf(thread),Mailbox.InFolder(inbox))
            assertEquals(setOf(sent,target),db.mail().threadMessages(thread).map { it.folderId }.toSet())
            assertEquals(inbox,db.mail().operations(account).single().folderId)
            assertEquals(55L,db.mail().threadMessages(thread).single { it.folderId==sent }.uid)
        }
    }
    @Test fun unifiedArchiveScopesEachAccountToItsRoleFolder() = runBlocking {
        val accounts = listOf(graph.addAccount(config,"password"),graph.addAccount(config.copy(email="b@example.org"),"password"))
        val threads = accounts.map { account ->
            val (_,thread) = fixture(account)
            val sent = db.mail().insertFolder(FolderRow(accountId=account,path="Sent",name="Sent",role="SENT"))
            db.mail().insertFolder(FolderRow(accountId=account,path="Archive",name="Archive",role="ARCHIVE"))
            db.mail().insertMessage(db.mail().threadMessages(thread).single().copy(id=0,folderId=sent,uid=88))
            thread
        }
        graph.apply(MessageAction.ARCHIVE,threads,Mailbox.Unified(FolderRole.INBOX))
        accounts.forEach { account ->
            assertEquals("INBOX",db.mail().folder(db.mail().operations(account).single().folderId)!!.role)
            assertEquals(88L,db.mail().threadMessages(threads[accounts.indexOf(account)]).single { it.folderId==db.mail().folders(account).single { f -> f.role=="SENT" }.id }.uid)
        }
    }
    @Test fun ambiguousLegacyMoveFailsAndVirtualMessageScopeIsRespected() = runBlocking {
        val account = graph.addAccount(config,"password"); val (inbox,thread)=fixture(account)
        val sent = db.mail().insertFolder(FolderRow(accountId=account,path="Sent",name="Sent",role="SENT"))
        val trash = db.mail().insertFolder(FolderRow(accountId=account,path="Trash",name="Trash",role="TRASH"))
        val source = db.mail().threadMessages(thread).single()
        val sentId = db.mail().insertMessage(source.copy(id=0,folderId=sent,uid=99))
        val failure = runCatching { graph.move(listOf(thread),trash) }.exceptionOrNull()
        assertEquals(MailActionFailure.SOURCE_REQUIRED,(failure as MailActionException).reason)
        assertTrue(db.mail().operations(account).isEmpty())
        graph.apply(MessageAction.TRASH,listOf(ConversationIds.ofMessage(sentId)),Mailbox.InFolder(inbox))
        assertTrue(db.mail().operations(account).isEmpty())
        graph.move(listOf(ConversationIds.ofMessage(source.id)),trash,Mailbox.InFolder(inbox))
        assertEquals(inbox,db.mail().operations(account).single().folderId)
        assertEquals(sent,db.mail().message(sentId)!!.folderId)
    }
    @Test fun localDraftsArePagedNewestFirstAndOpenByExactHandle() = runBlocking {
        val account = graph.addAccount(config,"password")
        val folder = db.mail().insertFolder(FolderRow(accountId=account,path="Drafts",name="Drafts",role="DRAFTS"))
        val old = graph.saveDraft(Draft(accountId=account,identityId=account,subject="Old"))
        val newest = graph.saveDraft(Draft(accountId=account,identityId=account,to=listOf(EmailAddress("to@example.org")),subject="Newest",bodyText="preview"))
        db.mail().putDraft(db.mail().draft(old)!!.copy(updatedAt=1))
        val source = graph.mailboxSource("EXISTS(SELECT 1 FROM messages m WHERE m.threadId=t.id AND m.folderId=?)", listOf(folder),true,account)
        val page = source.load(PagingSource.LoadParams.Refresh<Int>(null,40,false)) as PagingSource.LoadResult.Page
        val summaries = page.data.map { graph.summary(it,true) }
        assertEquals(listOf(newest,old),summaries.map { it.draftId })
        assertEquals("Newest",graph.loadDraft(summaries.first().draftId!!)!!.subject)
        assertEquals("to@example.org",summaries.first().recipients.single().email)
        assertTrue(summaries.all { it.threadId < 0 })
        graph.discardDraft(newest); assertNull(graph.loadDraft(newest))
        val (_, thread) = fixture(account)
        assertEquals("recipient@example.org",graph.summary(db.mail().threadRow(thread)!!).recipients.single().email)
    }
    @Test fun refreshWaitsAndReportsSafeCause() = runBlocking {
        val account = graph.addAccount(config,"password")
        val error = runCatching { graph.refresh(Mailbox.Unified(FolderRole.INBOX)) }.exceptionOrNull()
        assertTrue(error is MailSyncException)
        val state = graph.accounts.first().single { it.id==account }.syncState
        assertTrue(state is SyncState.Error)
        assertFalse((state as SyncState.Error).message.contains("a@example.org"))
    }
    @Test fun protocolResponseRetainsUsefulTextAndScrubsIdentityAndCredentials() {
        val error = Class.forName("org.eclipse.angus.mail.iap.BadCommandException").getConstructor(String::class.java).newInstance("A4 BAD FETCH unsupported for person@example.org password=secret-value token=opaque-value") as Throwable
        val clean = SyncDiagnostics.response(error, listOf("secret-value"))!!
        assertTrue(clean.contains("BAD FETCH unsupported")); assertFalse(clean.contains("person@example.org"))
        assertFalse(clean.contains("secret-value")); assertFalse(clean.contains("opaque-value")); assertTrue(clean.length <= 200)
    }
    @Test fun diagnosticsNeverRetainProviderMessages() {
        val raw = jakarta.mail.MessagingException("token=secret@example.org body=private", java.net.ConnectException("password=do-not-print"))
        val cleaned = SyncDiagnostics.sanitized(raw)
        val text = java.io.StringWriter().also { cleaned.printStackTrace(java.io.PrintWriter(it)) }.toString()
        assertFalse(text.contains("secret@example.org")); assertFalse(text.contains("do-not-print")); assertFalse(text.contains("body=private"))
        assertTrue(text.contains("MessagingException")); assertTrue(text.contains("ConnectException")); assertTrue(text.contains("Server unreachable"))
    }
}
