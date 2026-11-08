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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SnippetRepairTest {
    private lateinit var db:MailDatabase
    private lateinit var graph:RealMailGraph
    private lateinit var settings:AccountSettingsStore
    private val fetches=AtomicInteger()
    private val config=AccountConfig("fixture@example.org","Fixture",ServerConfig("127.0.0.1",1,Security.NONE,"fixture"),ServerConfig("127.0.0.1",1,Security.NONE,"fixture"))
    private val expected="🔥 日本語 e\u0301"
    @Before fun setup() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        db=Room.inMemoryDatabaseBuilder(context,MailDatabase::class.java).allowMainThreadQueries().build()
        settings=AccountSettingsStore.get(context)
        graph=RealMailGraph(context,db,object:CredentialVault {
            override fun encrypt(accountEmail:String,secret:String)=secret
            override fun decrypt(accountEmail:String,encrypted:String)=encrypted
        },client=MailClient(timing={phase,_ -> if(phase=="snippet_bodies") fetches.incrementAndGet() }),scheduling=false)
    }
    @After fun close() { graph.scope.cancel(); db.close() }
    @Test fun nextSyncRepairsStoredRemotePreviewAndCachedBodyWithoutRepeatedDownloads() = runBlocking {
        val server=GreenMail(arrayOf(ServerSetup(0,"127.0.0.1","imap"),ServerSetup(0,"127.0.0.1","smtp")))
        server.start(); server.setUser("fixture@example.org","fixture","password")
        try {
            val local=config.copy(imap=config.imap.copy(port=server.imap.port),smtp=config.smtp.copy(port=server.smtp.port))
            val account=graph.addAccount(local,"password")
            settings.markSnippetRevision(account,SNIPPET_REVISION)
            graph.client.send(local.smtp.endpoint(),Credentials("password"),Mime.build(OutgoingMail(Address("fixture@example.org"),listOf(Address("fixture@example.org")),subject="Unicode fixture",text="?? plain",html="<p>$expected</p>")))
            assertTrue(graph.syncAll())
            val folder=db.mail().folders(account).single { it.role=="INBOX" }
            val row=db.mail().folderMessages(folder.id).single()
            assertEquals(expected,row.snippet); assertFalse(row.bodyLoaded)
            db.mail().setSnippet(row.id,"?? old cached preview")
            settings.markSnippetRevision(account,1)
            assertTrue(graph.syncAll())
            assertEquals(expected,db.mail().message(row.id)!!.snippet)
            assertFalse(db.mail().message(row.id)!!.bodyLoaded)
            assertEquals(SNIPPET_REVISION,settings.snippetRevision(account))
            val afterRepair=fetches.get()
            assertTrue(graph.syncAll()); assertEquals(afterRepair,fetches.get())
            graph.loadBody(row.id)
            db.mail().setSnippet(row.id,"?? old full-body preview")
            settings.markSnippetRevision(account,1)
            assertTrue(graph.syncAll())
            assertEquals(afterRepair,fetches.get())
            assertEquals(expected,graph.thread(row.threadId).first().single().snippet)
            assertEquals(expected,graph.summary(db.mail().threadRow(row.threadId)!!).snippet)
            assertEquals(0,db.mail().pendingSnippetRepairs(account))
        } finally { server.stop() }
    }
    @Test fun cachedRepairIsBoundedAndContinuesBeyondNewestTwoHundred() = runBlocking {
        val account=graph.addAccount(config,"password")
        val folder=db.mail().insertFolder(FolderRow(accountId=account,path="INBOX",name="Inbox",role="INBOX",validity=7))
        val thread=db.mail().insertThread(ThreadRow(accountId=account,subject="Unicode"))
        repeat(205) { index ->
            val uid=(index+1).toLong()
            val header=Header(uid,"<$uid@example.org>",emptyList(),null,listOf(Address("fixture@example.org")),emptyList(),emptyList(),emptyList(),emptyList(),"Unicode",uid,RemoteFlags(uid,true,false,false),false)
            db.mail().insertMessage(MessageRow(accountId=account,folderId=folder,threadId=thread,uid=uid,header=Codec.header(header),subject="Unicode",sentAt=uid,unread=false,starred=false,hasAttachments=false,snippet="?? old",text="?? plain",html="<p>$expected</p>",bodyLoaded=true,snippetFetched=true))
        }
        settings.markSnippetRevision(account,1)
        graph.prepareSnippetRepair(account)
        assertEquals(205,db.mail().pendingSnippetRepairs(account))
        val remote=RemoteFolder("INBOX","Inbox",Role.INBOX,0,205)
        graph.prefetchFolderPreviews(db.mail().account(account)!!,config,Credentials("password"),db.mail().folder(folder)!!,remote,7)
        assertEquals(5,db.mail().pendingSnippetRepairs(account))
        graph.prefetchFolderPreviews(db.mail().account(account)!!,config,Credentials("password"),db.mail().folder(folder)!!,remote,7)
        assertEquals(0,db.mail().pendingSnippetRepairs(account))
        assertTrue(db.mail().folderMessages(folder).all { it.snippet==expected && it.bodyLoaded && it.snippetFetched })
        assertEquals(0,fetches.get())
    }
    @Test fun repairCandidatesBelowNewestWindowAreNotStranded() {
        val rows=(1L..205L).map { id -> MessageRow(id,1,1,1,id,"{}","",id,false,false,false,snippet=if(id<=5) "?? old" else "new",snippetFetched=id>5) }
        assertEquals(setOf(1L,2L,3L,4L,5L),previewCandidates(rows).map { it.id }.toSet())
    }
}
