package app.dot.mail.data

import android.content.Context
import androidx.paging.testing.asSnapshot
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import app.dot.mail.engine.text.Category
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
@Config(sdk=[28])
class NewsletterClassificationTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private val config=AccountConfig("alice@example.org","Alice",ServerConfig("127.0.0.1",1,Security.NONE,"alice"),ServerConfig("127.0.0.1",1,Security.NONE,"alice"))
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),MailDatabase::class.java).allowMainThreadQueries().build()
        graph=RealMailGraph(ApplicationProvider.getApplicationContext(),db,object:CredentialVault {
            override fun encrypt(accountEmail:String,secret:String)=secret
            override fun decrypt(accountEmail:String,encrypted:String)=encrypted
        },scheduling=false)
    }
    @After fun close() { graph.scope.cancel(); db.close() }
    private fun header(uid:Long,signals:Map<String,List<String>> = emptyMap(),subject:String="Updates")=Header(uid,"<$uid@example.org>",emptyList(),null,listOf(Address("editor@example.org")),listOf(Address("alice@example.org")),emptyList(),emptyList(),emptyList(),subject,uid,RemoteFlags(uid,false,false,false),false,classificationHeaders=signals)
    @Test fun greenMailHeadersClassifyWithoutDownloadingBodiesAndBackfillLegacyRows() = runBlocking {
        val server=GreenMail(arrayOf(ServerSetup(0,"127.0.0.1","imap"),ServerSetup(0,"127.0.0.1","smtp")))
        server.start(); server.setUser("alice@example.org","alice","password")
        try {
            val local=config.copy(imap=config.imap.copy(port=server.imap.port),smtp=config.smtp.copy(port=server.smtp.port))
            val account=graph.addAccount(local,"password")
            val cases=listOf("List-Id" to "Updates <weekly.example.org>","Precedence" to "bulk","X-Mailchimp-EID" to "fixture","X-Campaign-ID" to "fixture","X-GitHub-Reason" to "mention","Auto-Submitted" to "auto-generated")
            for((index,pair) in cases.withIndex()) {
                val mime=Mime.build(OutgoingMail(Address("editor@example.org"),listOf(Address("alice@example.org")),subject="Update $index",text="body remains on server",messageId="<news-$index@example.org>"))
                mime.setHeader(pair.first,pair.second)
                graph.client.send(local.smtp.endpoint(),Credentials("password"),mime)
            }
            val pdf=Mime.build(OutgoingMail(Address("billing@example.org"),listOf(Address("alice@example.org")),subject="Statement",text="body",attachments=listOf(OutgoingAttachment("statement.pdf","application/pdf","%PDF-fixture".toByteArray()))))
            graph.client.send(local.smtp.endpoint(),Credentials("password"),pdf)
            assertTrue(graph.syncAll())
            val inbox=db.mail().folders(account).single { it.role=="INBOX" }
            val rows=db.mail().folderMessages(inbox.id)
            assertEquals(7,rows.size); assertTrue(rows.all { !it.bodyLoaded && it.text==null && it.html==null })
            assertEquals(4,rows.count { messageCategory(it)==Category.NEWSLETTER })
            assertEquals(2,rows.count { messageCategory(it)==Category.NOTIFICATION })
            assertEquals(Category.BILL,messageCategory(rows.single { it.subject=="Statement" }))
            val list=rows.single { it.subject=="Update 0" }
            val message=graph.thread(list.threadId).first().single()
            assertEquals("Updates <weekly.example.org>",message.listId); assertEquals(Category.NEWSLETTER,message.category)
            val legacy=org.json.JSONObject(list.header).apply { remove("category"); remove("listId") }.toString()
            db.mail().updateMessage(list.copy(header=legacy))
            assertTrue(graph.syncAll())
            val restored=db.mail().message(list.id)!!
            assertEquals(Category.NEWSLETTER,messageCategory(restored)); assertFalse(restored.bodyLoaded)
            assertEquals("Updates <weekly.example.org>",Codec.field(restored.header,"listId"))
        } finally { server.stop() }
    }
    @Test fun newsletterSelectionAcrossAccountsExcludesSentTrashSpamAndCannotBeSpoofedBySubject() = runBlocking {
        val accounts=listOf(graph.addAccount(config,"password"),graph.addAccount(config.copy(email="other@example.org"),"password"))
        val wanted=mutableListOf<Long>()
        for(account in accounts) {
            for((i,role) in listOf("INBOX","ARCHIVE","SENT","SPAM","TRASH","DRAFTS").withIndex()) {
                val folder=db.mail().insertFolder(FolderRow(accountId=account,path=role,name=role,role=role,validity=1))
                val thread=graph.upsertHeader(db.mail().folder(folder)!!,header((i+1).toLong(),mapOf("List-Id" to listOf("list.example.org")))).first
                if(role in listOf("INBOX","ARCHIVE")) wanted+=thread
            }
            val folder=db.mail().folders(account).single { it.role=="INBOX" }
            graph.upsertHeader(folder,header(99,subject="quoted \"category\":\"NEWSLETTER\""))
        }
        val source=graph.mailboxSource("EXISTS(SELECT 1 FROM messages m JOIN folders f ON f.id=m.folderId WHERE m.threadId=t.id AND ${newsletterPredicate("m","f")})",emptyList(),false,null)
        val page=source.load(PagingSource.LoadParams.Refresh<Int>(null,40,false)) as PagingSource.LoadResult.Page
        assertEquals(wanted.toSet(),page.data.map { it.id }.toSet())
        val messageRows=graph.messages(Mailbox.Unified(FolderRole.NEWSLETTERS)).asSnapshot()
        assertEquals(wanted.size,messageRows.size)
        assertTrue(messageRows.all { ConversationIds.isMessage(it.threadId) && it.category==Category.NEWSLETTER })
        page.data.forEach { assertEquals(Category.NEWSLETTER,graph.summary(it).category) }
        val selected=wanted.first()
        val sent=db.mail().folders(accounts.first()).single { it.role=="SENT" }
        val sourceRow=db.mail().threadMessages(selected).single()
        db.mail().insertMessage(sourceRow.copy(id=0,folderId=sent.id,uid=555))
        graph.apply(MessageAction.TRASH,listOf(selected),Mailbox.Unified(FolderRole.NEWSLETTERS))
        assertEquals(555L,db.mail().threadMessages(selected).single { it.folderId==sent.id }.uid)
        assertEquals("INBOX",db.mail().folder(db.mail().operations(accounts.first()).single().folderId)!!.role)
    }
}
