package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class StorageCacheTest {
    private lateinit var db:MailDatabase; private lateinit var graph:RealMailGraph
    private var account=0L; private var folder=0L; private var uid=1L
    private val config=AccountConfig("fixture@example.org","Fixture",ServerConfig("127.0.0.1",1,Security.NONE,"fixture"),ServerConfig("127.0.0.1",1,Security.NONE,"fixture"))
    @Before fun setup()=runBlocking {
        val paths=androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible=true }.get(null) as MutableMap<*,*>
        synchronized(paths) { paths.clear() }
        val context=ApplicationProvider.getApplicationContext<Context>()
        db=Room.inMemoryDatabaseBuilder(context,MailDatabase::class.java).allowMainThreadQueries().build()
        graph=RealMailGraph(context,db,object:CredentialVault {
            override fun encrypt(accountEmail:String,secret:String)=secret
            override fun decrypt(accountEmail:String,encrypted:String)=encrypted
        },scheduling=false)
        graph.storage.availableBytes={1024L*1024*1024}
        graph.searchIndex.ensure()
        account=graph.addAccount(config,"password")
        folder=db.mail().insertFolder(FolderRow(accountId=account,path="INBOX",name="Inbox",role="INBOX",validity=7))
    }
    @After fun close() { graph.scope.cancel(); db.close() }
    private suspend fun cached(text:String="vanishword",pin:Boolean=false,inFolder:Long=folder):Long {
        val n=uid++
        val header=Header(n,"<$n@fixture.invalid>",emptyList(),null,listOf(Address("sender@example.org")),listOf(Address("fixture@example.org")),emptyList(),emptyList(),emptyList(),"Fixture $n",n,RemoteFlags(n,false,false,false),false)
        val thread=db.mail().insertThread(ThreadRow(accountId=account,subject=header.subject,count=1,lastAt=n))
        val directory=File(graph.context.filesDir,"bodies/$account/${UUID.randomUUID()}").apply { mkdirs() }
        File(directory,"plain.txt").writeText(text); File(directory,".complete").writeText("")
        val id=db.mail().insertMessage(MessageRow(accountId=account,folderId=inFolder,threadId=thread,uid=n,header=Codec.header(header),subject=header.subject,sentAt=n,unread=true,starred=false,hasAttachments=false,bodyPath=directory.path,bodyLoaded=true))
        db.mail().putCacheAccess(CacheAccessRow(id,n,pin)); return id
    }
    private suspend fun attach(id:Long,name:String="fixture.txt"):AttachmentRow {
        val file=graph.attachmentFile(account,"${UUID.randomUUID()}/${graph.safeFileName(name)}").apply { writeText("protected attachment") }
        val row=AttachmentRow(messageId=id,partId="1.0",name=name,mime="text/plain",size=file.length(),cid=null,inline=false,localUri=graph.uri(file))
        return row.copy(id=db.mail().insertAttachment(row))
    }
    @Test fun clearPreservesPinsPendingWritesDraftsAndOutboxReferencesAndRemovesFtsBodyImmediately()=runBlocking {
        val ordinary=cached(); val pinned=cached(pin=true); val pending=cached(); val outgoing=cached()
        val draftFolder=db.mail().insertFolder(FolderRow(accountId=account,path="Drafts",name="Drafts",role="DRAFTS",validity=8))
        val draft=cached(inFolder=draftFolder)
        val row=db.mail().message(pending)!!
        db.mail().insertOperation(OperationRow(accountId=account,folderId=folder,validity=7,uid=row.uid,action="MARK_READ",messageLocalId=pending))
        val shared=attach(outgoing)
        graph.send(Draft(accountId=account,identityId=account,to=listOf(EmailAddress("recipient@example.org")),attachmentUris=listOf(shared.localUri!!)),60000)
        graph.searchIndex.drain()
        val result=graph.clearCachedMail(account)
        assertTrue(result.freedBytes>0); assertTrue(result.skippedProtectedMessages>=4)
        assertFalse(db.mail().message(ordinary)!!.bodyLoaded)
        for(id in listOf(pinned,pending,outgoing,draft)) {
            val kept=db.mail().message(id)!!; assertTrue(kept.bodyLoaded); assertTrue(File(kept.bodyPath!!,"plain.txt").exists())
        }
        assertTrue(graph.storage.files.exists(shared.localUri))
        db.openHelper.writableDatabase.query("SELECT rowid FROM message_fts WHERE message_fts MATCH 'vanishword'").use { cursor ->
            val ids=buildList { while(cursor.moveToNext()) add(cursor.getLong(0)) }
            assertFalse(ordinary in ids); assertTrue(pinned in ids)
        }
        assertTrue(graph.storage.preferences.paused(account))
    }
    @Test fun lruEvictionRespectsPinsAndReservationAccountingCannotSpendTheSameQuotaTwice()=runBlocking {
        val first=cached("1234567890"); val second=cached("1234567890"); val pinned=cached("1234567890",pin=true)
        graph.storage.preferences.save(account,StoragePolicy(maxBytes=30,wifiOnly=false))
        graph.storage.prepareSpace(account,20,false,999)
        assertFalse(db.mail().message(first)!!.bodyLoaded); assertFalse(db.mail().message(second)!!.bodyLoaded)
        assertTrue(db.mail().message(pinned)!!.bodyLoaded)
        val a=graph.storage.reserve(account,8); val b=graph.storage.reserve(account,20)
        assertEquals(8L,a.bytes); assertEquals(12L,b.bytes)
        try { graph.storage.reserve(account,1); fail("Double-spent quota") } catch(e:CacheStorageException) { assertFalse(e.deviceFull) }
        graph.storage.release(a,0); graph.storage.release(b,0)
        assertEquals(20L,graph.storage.reserve(account,20).also { graph.storage.release(it,0) }.bytes)
        graph.storage.availableBytes={StorageManager.RESERVE_BYTES}
        try { graph.storage.checkSpace(account); fail("Expected device-space failure") } catch(e:CacheStorageException) { assertTrue(e.deviceFull) }
    }
    @Test fun activeReaderAndSharedBodyFilesSurviveCleanup()=runBlocking {
        val first=cached("shared body"); val pinned=cached("discarded",pin=true)
        val one=db.mail().message(first)!!; val two=db.mail().message(pinned)!!
        File(two.bodyPath!!).deleteRecursively(); db.mail().updateMessage(two.copy(bodyPath=one.bodyPath))
        graph.clearCachedMail(account)
        assertTrue(File(one.bodyPath!!,"plain.txt").exists()); assertFalse(db.mail().message(first)!!.bodyLoaded)
        val active=cached("active reader")
        val ready=CompletableDeferred<Unit>()
        val reader=launch { graph.thread(db.mail().message(active)!!.threadId).collect { ready.complete(Unit) } }
        ready.await(); graph.clearCachedMail(account); assertTrue(db.mail().message(active)!!.bodyLoaded)
        reader.cancelAndJoin(); graph.clearCachedMail(account); assertFalse(db.mail().message(active)!!.bodyLoaded)
    }
    @Test fun totalLimitIncludesAllAccountsAndProtectedMinimumIsNeverDeleted()=runBlocking {
        val first=cached(); val pinned=cached(pin=true)
        for(id in listOf(first,pinned)) java.io.RandomAccessFile(File(db.mail().message(id)!!.bodyPath!!,"plain.txt"),"rw").use { it.setLength(40L*1024*1024) }
        graph.setGlobalStorageLimit(64L*1024*1024)
        assertFalse(db.mail().message(first)!!.bodyLoaded); assertTrue(db.mail().message(pinned)!!.bodyLoaded)
        val usage=graph.storageUsage()
        assertTrue(usage.totalBytes<=64L*1024*1024)
        assertTrue(usage.accounts.single().protectedBytes>=40L*1024*1024)
        assertEquals(64L*1024*1024,graph.globalStorageLimit.first())
    }
    @Test fun failedConnectionDoesNotEvictExistingCachedMail()=runBlocking {
        val first=cached("1234567890"); val second=cached("1234567890")
        graph.storage.preferences.save(account,StoragePolicy(maxBytes=20,wifiOnly=false))
        val missing=cached("placeholder")
        val row=db.mail().message(missing)!!; File(row.bodyPath!!).deleteRecursively()
        db.mail().updateMessage(row.copy(bodyLoaded=false,bodyPath=null))
        try { graph.loadBody(missing); fail("Expected connection failure") } catch(_:Exception) {}
        assertTrue(db.mail().message(first)!!.bodyLoaded); assertTrue(db.mail().message(second)!!.bodyLoaded)
    }

    @Test fun automaticDownloadWorkHonorsUnmeteredChargingAndExplicitPausePreferences() {
        val wifi=WorkScheduling.offlineRequest(42,StoragePolicy(wifiOnly=true,chargingOnly=true))
        assertEquals(androidx.work.NetworkType.UNMETERED,wifi.workSpec.constraints.requiredNetworkType)
        assertTrue(wifi.workSpec.constraints.requiresCharging())
        assertEquals(42L,wifi.workSpec.input.getLong("accountId",0))
        val cellular=WorkScheduling.offlineRequest(43,StoragePolicy(wifiOnly=false,chargingOnly=false))
        assertEquals(androidx.work.NetworkType.CONNECTED,cellular.workSpec.constraints.requiredNetworkType)
        assertFalse(cellular.workSpec.constraints.requiresCharging())
    }

    @Test fun retentionPrunesAcrossBatchesOfflineAndKeepsPinnedAndSnoozedMail()=runBlocking {
        val pinned=cached(pin=true); val snoozed=cached()
        val snoozedRow=db.mail().message(snoozed)!!
        db.mail().insertSnooze(SnoozeRow(accountId=account,threadId=snoozedRow.threadId,until=System.currentTimeMillis()+60000,createdAt=System.currentTimeMillis()))
        val normal=mutableListOf<Long>(); repeat(405) { normal+=cached("old cache") }
        val policy=StoragePolicy(retention=RetentionMode.LATEST_MESSAGES,latestMessages=3,maxBytes=0,wifiOnly=false)
        graph.storage.preferences.save(account,policy)
        withTimeout(30000) { repeat(10) { if(!graph.storage.downloads.prune(account,policy)) return@withTimeout }; error("Retention did not finish") }
        assertEquals(5,db.mail().cachedMessageCount(account))
        for(id in listOf(pinned,snoozed)+normal.takeLast(3)) assertNotNull(db.mail().message(id))
        assertNull(db.mail().message(normal.first()))
        assertTrue(db.mail().threadMessages(snoozedRow.threadId).isNotEmpty())
        // A day-based policy is independent of the bounded latest-N policy.
        val recent=normal.last(); val row=db.mail().message(recent)!!
        db.mail().updateMessage(row.copy(sentAt=System.currentTimeMillis()))
        val age=policy.copy(retention=RetentionMode.LAST_DAYS,days=1)
        graph.storage.preferences.save(account,age)
        graph.storage.downloads.prune(account,age)
        assertEquals(3,db.mail().cachedMessageCount(account)); assertNotNull(db.mail().message(recent))
    }

    @Test fun unicodeFilenamesAreBoundedInUtf8AndCannotEscapePrivateStorage() {
        val name=graph.safeFileName("réception東京🔥".repeat(40)+"/../../secret")
        assertTrue(name.toByteArray(Charsets.UTF_8).size<=180)
        assertFalse(name.contains('/')); assertFalse(name.contains('\uFFFD'))
        assertEquals("attachment",graph.safeFileName(".."))
        assertNull(graph.storage.files.uriFile("content://${graph.context.packageName}.attachments/mail_attachments/../../secret"))
    }
}
