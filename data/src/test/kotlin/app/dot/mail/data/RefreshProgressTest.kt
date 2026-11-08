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
import kotlinx.coroutines.sync.withLock
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RefreshProgressTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private lateinit var server: GreenMail
    private val release=CountDownLatch(1)
    @After fun cleanup() { release.countDown(); if(::graph.isInitialized) graph.scope.cancel(); if(::db.isInitialized) db.close(); if(::server.isInitialized) server.stop() }
    private suspend fun fixture(blockSelect:Int): Pair<Long,CountDownLatch> {
        server=GreenMail(arrayOf(ServerSetup(0,"127.0.0.1","imap"),ServerSetup(0,"127.0.0.1","smtp")))
        server.start(); server.setUser("fixture@example.org","fixture","password")
        val selected=AtomicInteger(); val blocked=CountDownLatch(1)
        val client=MailClient(500) { phase,_ -> if(phase=="select" && selected.incrementAndGet()==blockSelect) { blocked.countDown(); release.await(5,TimeUnit.SECONDS) } }
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),MailDatabase::class.java).allowMainThreadQueries().build()
        graph=RealMailGraph(ApplicationProvider.getApplicationContext(),db,object: CredentialVault {
            override fun encrypt(accountEmail:String,secret:String)=secret
            override fun decrypt(accountEmail:String,encrypted:String)=encrypted
        },client=client,scheduling=false)
        val config=AccountConfig("fixture@example.org","Fixture",ServerConfig("127.0.0.1",server.imap.port,Security.NONE,"fixture"),ServerConfig("127.0.0.1",server.smtp.port,Security.NONE,"fixture"))
        val id=graph.addAccount(config,"password")
        client.createFolder(config.imap.endpoint(),Credentials("password"),null,"Slow")
        client.send(config.smtp.endpoint(),Credentials("password"),Mime.build(OutgoingMail(Address("fixture@example.org"),listOf(Address("fixture@example.org")),subject="Fresh",text="Preview")))
        return id to blocked
    }
    @Test fun inboxRefreshReturnsAfterDatabaseCommitBeforePreviewOrOtherFolders() = runBlocking {
        val (account,blocked)=fixture(2) // second SELECT is the optional Inbox preview, after headers commit
        val refresh=async(Dispatchers.Default) { graph.refresh(Mailbox.Unified(FolderRole.INBOX)) }
        try {
            assertTrue(withContext(Dispatchers.IO) { blocked.await(5,TimeUnit.SECONDS) })
            withTimeout(1000) { refresh.await() }
            val inbox=db.mail().folders(account).single { it.role=="INBOX" }
            assertEquals("Fresh",db.mail().folderMessages(inbox.id).single().subject)
            assertTrue(account in graph.syncing.value)
        } finally { release.countDown() }
        graph.syncLock.withLock { }
        assertEquals(SyncState.Idle,graph.accounts.first().single().syncState)
    }
    @Test fun lateFolderConnectionFailureRemainsVisibleAfterInboxRefreshCompletes() = runBlocking {
        val (account,blocked)=fixture(3) // Slow folder SELECT after Inbox headers and preview
        val refresh=async(Dispatchers.Default) { graph.retrySync(account) }
        try {
            assertTrue(withContext(Dispatchers.IO) { blocked.await(5,TimeUnit.SECONDS) })
            withTimeout(1000) { refresh.await() }
            server.stop()
        } finally { release.countDown() }
        withTimeout(10_000) { graph.syncLock.withLock { } }
        val error=graph.accounts.first().single().syncState as SyncState.Error
        assertTrue(error.errorKind in setOf(ConnectionFailure.SERVER_CLOSED,ConnectionFailure.NETWORK,ConnectionFailure.TIMEOUT))
        assertTrue(error.canRetry); assertFalse(error.message.contains("fixture@example.org"))
    }
    @Test fun persistedTypedErrorsRemainHumanReadableWithoutSchemaChanges() {
        val original=SyncDiagnostics.stored(jakarta.mail.MessagingException("* BYE too many connections"),"INBOX")
        val state=SyncDiagnostics.state(original,false)
        assertEquals(ConnectionFailure.THROTTLED,state.errorKind)
        assertTrue(state.canRetry); assertTrue(state.message.startsWith("Folder INBOX:"))
        assertFalse(state.message.contains("[THROTTLED]"))
        val auth=SyncDiagnostics.state(SyncDiagnostics.stored(jakarta.mail.AuthenticationFailedException("private")),false)
        assertTrue(auth.isAuthError); assertFalse(auth.canRetry)
        assertEquals("Old failure",SyncDiagnostics.state("Old failure",false).message)
    }
}
