package app.dot.mail.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class FullContentSearchTest {
    private lateinit var db:MailDatabase
    private lateinit var graph:RealMailGraph
    private var account=0L; private var inbox=0L
    @Before fun setup()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        db=Room.inMemoryDatabaseBuilder(context,MailDatabase::class.java).allowMainThreadQueries().build()
        graph=RealMailGraph(context,db,object:CredentialVault {
            override fun encrypt(accountEmail:String,secret:String)=secret
            override fun decrypt(accountEmail:String,encrypted:String)=encrypted
        },scheduling=false)
        graph.searchIndex.ensure()
        val config=AccountConfig("fixture@example.org","Fixture",ServerConfig("127.0.0.1",1,Security.NONE,"fixture"),ServerConfig("127.0.0.1",1,Security.NONE,"fixture"))
        account=graph.addAccount(config,"password")
        inbox=db.mail().insertFolder(FolderRow(accountId=account,path="INBOX",name="Inbox",role="INBOX",validity=7))
    }
    @After fun close() { graph.scope.cancel(); db.close() }
    private suspend fun message(subject:String="Fixture",text:String?=null,html:String?=null,folder:Long=inbox,thread:Long?=null,date:Long=System.currentTimeMillis(),sender:String="Élodie <elodie@example.org>",recipient:String="Jürgen <jurgen@example.org>"):Long {
        val tid=thread ?: db.mail().insertThread(ThreadRow(accountId=account,subject=subject,count=1,lastAt=date))
        val header=Header(date,"<$date@example.org>",emptyList(),null,listOf(Address("elodie@example.org","Élodie")),listOf(Address("jurgen@example.org","Jürgen")),emptyList(),emptyList(),emptyList(),subject,date,RemoteFlags(date,false,false,false),false)
        return db.mail().insertMessage(MessageRow(accountId=account,folderId=folder,threadId=tid,uid=date,header=Codec.header(header),subject=subject,sentAt=date,unread=true,starred=false,hasAttachments=false,text=text,html=html,bodyLoaded=text!=null||html!=null,senderSearch=sender,recipientSearch=recipient))
    }
    private suspend fun results(query:String,scope:SearchScope=SearchScope(includeServer=false)):List<IndexedSearchRow> {
        val page=graph.fullSearch.source(query,scope).load(PagingSource.LoadParams.Refresh(null,40,false))
        if(page is PagingSource.LoadResult.Error) throw page.throwable
        return (page as PagingSource.LoadResult.Page).data
    }
    @Test fun indexesBodiesHtmlNamesAndFrenchGermanWithoutCaseOrAccentSensitivity()=runBlocking {
        val id=message(subject="Réunion Straße",text="Le café est fermé. Übermorgen",html="<style>secretstyle</style><script>secretcode</script><p>Facture complémentaire 日本語 🔥</p>")
        db.mail().insertAttachment(AttachmentRow(messageId=id,partId="2",name="Reçu-énergie.pdf",mime="application/pdf",size=10,cid=null,inline=false,localUri=null))
        assertEquals(0,graph.searchIndex.drain())
        for(query in listOf("CAFE","strasse","ubermor","complementaire","日本語","recu","from:elodie","to:jurgen","subject:reun")) assertEquals(query,listOf(id),results(query).map { it.messageId })
        assertTrue(results("secretstyle").isEmpty()); assertTrue(results("secretcode").isEmpty())
        assertEquals(SearchIndexProgress(1,1,false),graph.searchIndex.progress.value)
    }
    @Test fun phrasePrefixRankingScopesAndAllTermsBelongToOneMessage()=runBlocking {
        val body=message(subject="Other",text="quiet blue river",date=100)
        val subject=message(subject="Quiet blue river",text="content",date=99)
        val reverse=message(subject="blue quiet river",text="else",date=98)
        val thread=db.mail().message(body)!!.threadId
        message(subject="separate",text="isolatedword",thread=thread,date=97)
        graph.searchIndex.drain()
        assertEquals(listOf(subject,body),results("\"quiet blue\"").map { it.messageId })
        assertEquals(subject,results("quiet").first().messageId)
        assertEquals(3,results("qui").size)
        assertTrue(results("river isolatedword").isEmpty())
        assertTrue(results("river",SearchScope(accountIds=setOf(999),includeServer=false)).isEmpty())
        assertTrue(results("river",SearchScope(folderIds=setOf(999),includeServer=false)).isEmpty())
        assertEquals(listOf(body,subject,reverse),results("river",SearchScope(includeServer=false,sort=SearchSort.NEWEST)).map { it.messageId })
        assertEquals(listOf(reverse,subject,body),results("river",SearchScope(includeServer=false,sort=SearchSort.OLDEST)).map { it.messageId })
    }
    @Test fun changesDeleteAndEvictionCannotLeaveBodyOrFilenameHits()=runBlocking {
        val id=message(text="oldsecret")
        graph.searchIndex.drain(); assertEquals(1,results("oldsecret").size)
        val row=db.mail().message(id)!!
        db.mail().updateMessageRaw(row.copy(text="newsecret",bodyLoaded=true))
        assertEquals(1,db.mail().searchPendingCount()); graph.searchIndex.drain()
        assertTrue(results("oldsecret").isEmpty()); assertEquals(1,results("newsecret").size)
        db.mail().updateMessageRaw(row.copy(bodyLoaded=false,text=null,html=null,bodyPath=null))
        graph.searchIndex.drain(); assertTrue(results("newsecret").isEmpty())
        db.mail().deleteMessage(id); assertTrue(results("Fixture").isEmpty())
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM message_fts").use { it.moveToFirst(); assertEquals(0,it.getInt(0)) }
    }
    @Test fun boundedBackfillResumesAndFiltersAreLiveWithoutReindexing()=runBlocking {
        repeat(35) { message(subject="invoice $it",date=it+1L) }
        assertEquals(3,graph.searchIndex.drain())
        assertEquals(32,graph.searchIndex.progress.value.indexed)
        graph.searchIndex.drain(); assertEquals(35,results("invoice is:unread folder:Inbox before:2020-01-01").size)
        val row=db.mail().message(results("invoice").first().messageId)!!
        db.mail().updateMessage(row.copy(unread=false,starred=true,hasAttachments=true))
        assertEquals(0,db.mail().searchPendingCount())
        assertEquals(listOf(row.id),results("invoice is:read is:starred has:attachment").map { it.messageId })
        for(query in listOf("\" OR *","subject:\"x OR y\"","(NEAR/1)","before:invalid")) results(query)
    }
    @Test fun serverFallbackFindsOldBodyWithoutDownloadAndSurvivesWindowSync()=runBlocking {
        val server=com.icegreen.greenmail.util.GreenMail(arrayOf(com.icegreen.greenmail.util.ServerSetup(0,"127.0.0.1","imap"),com.icegreen.greenmail.util.ServerSetup(0,"127.0.0.1","smtp")))
        server.start(); server.setUser("remote@example.org","remote","password")
        try {
            val config=AccountConfig("remote@example.org","Remote",ServerConfig("127.0.0.1",server.imap.port,Security.NONE,"remote"),ServerConfig("127.0.0.1",server.smtp.port,Security.NONE,"remote"))
            val aid=graph.addAccount(config,"password")
            val fid=db.mail().insertFolder(FolderRow(accountId=aid,path="INBOX",name="Inbox",role="INBOX"))
            val mime=Mime.build(OutgoingMail(Address("remote@example.org"),listOf(Address("remote@example.org")),subject="Old fixture",text="buriedkeyword",attachments=listOf(OutgoingAttachment("Ancienne-facture.pdf","application/pdf",byteArrayOf(1,2,3)))))
            mime.sentDate=java.util.Date(946684800000); mime.saveChanges()
            graph.client.send(config.smtp.endpoint(),Credentials("password"),mime)
            val scope=SearchScope(accountIds=setOf(aid),folderIds=setOf(fid))
            graph.fullSearch.serverSearch("buriedkeyword",scope,networkAvailable=true)
            val matches=results("buriedkeyword",scope)
            assertEquals(1,matches.size); assertTrue(matches.single().serverOnly); assertFalse(matches.single().bodyAvailable)
            val found=db.mail().message(matches.single().messageId)!!
            assertTrue(found.unread); assertFalse(found.bodyLoaded)
            assertTrue(db.mail().folder(fid)!!.validity>0)
            graph.searchIndex.drain()
            assertEquals(listOf(found.id),results("ancienne",scope).map { it.messageId })
            assertTrue(graph.syncAll(accountIds=setOf(aid)))
            assertNotNull(db.mail().message(found.id))
            graph.loadBody(found.id); graph.searchIndex.drain()
            assertEquals(1,results("buriedkeyword",scope.copy(includeServer=false)).size)
            val remote=graph.client.sync(config.imap.endpoint(),Credentials("password"),"INBOX",limit=500,windowDays=0)
            assertFalse(remote.flags.single().seen)
        } finally { server.stop() }
    }
    @Test fun boundedContentStripsExecutableMarkupAndTruncatesLargeDocuments()=runBlocking {
        val id=message(text="visible "+"x".repeat(SearchIndex.TEXT_LIMIT)+"hiddenend",html="<script>hiddenjs</script><p>visiblehtml</p>")
        graph.searchIndex.drain()
        assertEquals(listOf(id),results("visible").map { it.messageId })
        assertEquals(listOf(id),results("visiblehtml").map { it.messageId })
        assertTrue(results("hiddenend").isEmpty()); assertTrue(results("hiddenjs").isEmpty())
        db.openHelper.writableDatabase.query("SELECT length(body) FROM search_documents WHERE messageId=$id").use { it.moveToFirst(); assertTrue(it.getInt(0)<=SearchIndex.TEXT_LIMIT) }
    }
    @Test fun concurrentRevisionAndRestartCannotDropAnUnindexedEdit()=runBlocking {
        val id=message(text="initialtext")
        val old=db.mail().searchPending(1).single()
        val row=db.mail().message(id)!!
        db.mail().updateMessageRaw(row.copy(text="latesttext"))
        db.mail().finishSearchIndex(id,old.revision)
        assertEquals(1,db.mail().searchPendingCount())
        val restarted=SearchIndex(graph)
        assertEquals(0,restarted.drain())
        assertEquals(listOf(id),results("latesttext").map { it.messageId })
        assertTrue(results("initialtext").isEmpty())
    }
    @Test fun attachmentRemovalReindexesAndUnavailablePdfSupportDoesNotDownload()=runBlocking {
        val id=message()
        db.mail().insertAttachment(AttachmentRow(messageId=id,partId="2",name="secret-filename.pdf",mime="application/pdf",size=10,cid=null,inline=false,localUri=null))
        graph.searchIndex.drain(); assertEquals(1,results("filename").size)
        db.mail().clearAttachments(id); graph.searchIndex.drain()
        assertTrue(results("filename").isEmpty())
        assertFalse(PdfText.supported)
        assertNull(PdfText.extract(ApplicationProvider.getApplicationContext(),"content://missing/pdf"))
    }
    @Test fun pageResultUsesTheMatchingOlderMessageAndOriginalAccentedExcerpt()=runBlocking {
        val id=message(subject="Earlier",text="prefix ".repeat(100)+"🔥 Le café est prêt",date=100)
        val thread=db.mail().message(id)!!.threadId
        message(subject="Latest unrelated",text="different content",thread=thread,date=101)
        graph.rebuildThread(thread); graph.searchIndex.drain()
        val result=graph.fullSearch.result(results("cafe").single(),"cafe")
        assertEquals(id,result.messageId); assertEquals(inbox,result.folderId)
        assertEquals("Earlier",result.subject); assertEquals("Latest unrelated",result.thread.subject)
        assertTrue(result.bodyAvailable); assertFalse(result.serverOnly)
        assertTrue(result.snippet.length<230)
        assertEquals("café",result.snippet.substring(result.highlights.single().start,result.highlights.single().endExclusive))
    }
    @Test fun highlightOffsetsPreserveOriginalAccentsCombiningMarksAndSurrogates() {
        assertEquals("a",SearchText.prefix("a🔥b",2))
        assertEquals("a",SearchText.prefix("ae\u0301b",2))
        val original="🔥 café e\u0301 Straße 日本語"
        val ranges=SearchText.highlights(original,listOf("cafe","e","strasse","日本語"))
        assertTrue(ranges.any { original.substring(it.start,it.endExclusive)=="café" })
        assertTrue(ranges.any { original.substring(it.start,it.endExclusive)=="e\u0301" })
        assertTrue(ranges.any { original.substring(it.start,it.endExclusive)=="Straße" })
        val punctuated="quiet—blue riverbed and river"
        assertEquals("quiet—blue",SearchText.highlights(punctuated,listOf("quiet blue")).single().let { punctuated.substring(it.start,it.endExclusive) })
        assertTrue(SearchText.highlights("kabob",listOf("bob")).isEmpty())
        val excerpt=SearchText.excerpt("x".repeat(61)+original+"y".repeat(400),listOf("cafe"))
        assertTrue(excerpt.first.contains("café")); assertFalse(excerpt.first.contains('\uFFFD'))
    }
}
