package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import com.icegreen.greenmail.util.*
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
class OfflineDownloadsTest {
    private lateinit var db:MailDatabase; private lateinit var graph:RealMailGraph; private lateinit var server:GreenMail
    private lateinit var config:AccountConfig
    private var account=0L
    private val bodyReads=AtomicInteger(); private val attachmentReads=AtomicInteger()
    @Before fun setup()=runBlocking {
        val paths=androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible=true }.get(null) as MutableMap<*,*>
        synchronized(paths) { paths.clear() }
        val context=ApplicationProvider.getApplicationContext<Context>()
        db=Room.inMemoryDatabaseBuilder(context,MailDatabase::class.java).allowMainThreadQueries().build()
        server=GreenMail(arrayOf(ServerSetup(0,"127.0.0.1","imap"),ServerSetup(0,"127.0.0.1","smtp"))); server.start(); server.setUser("fixture@example.org","fixture","password")
        config=AccountConfig("fixture@example.org","Fixture",ServerConfig("127.0.0.1",server.imap.port,Security.NONE,"fixture"),ServerConfig("127.0.0.1",server.smtp.port,Security.NONE,"fixture"))
        graph=RealMailGraph(context,db,object:CredentialVault {
            override fun encrypt(accountEmail:String,secret:String)=secret
            override fun decrypt(accountEmail:String,encrypted:String)=encrypted
        },client=MailClient(timing={ phase,_ -> if(phase=="body_parts") bodyReads.incrementAndGet() else if(phase=="attachment_part") attachmentReads.incrementAndGet() }),scheduling=false)
        graph.storage.availableBytes={1024L*1024*1024}; graph.storage.downloads.networkAllowed={true}; graph.storage.downloads.charging={true}
        graph.searchIndex.ensure(); account=graph.addAccount(config,"password")
    }
    @After fun close() { graph.scope.cancel(); db.close(); server.stop() }
    private suspend fun append(count:Int,attachments:Boolean=false)=graph.client.withImapSession(config.imap.endpoint(),Credentials("password")) {
        repeat(count) { index -> graph.client.append(config.imap.endpoint(),Credentials("password"),"INBOX",Mime.build(OutgoingMail(Address("fixture@example.org"),listOf(Address("fixture@example.org")),subject="Fixture $index",text="Cached body $index 🔥 日本語",attachments=if(attachments) listOf(OutgoingAttachment("réception東京🔥.txt","text/plain","attachment $index".toByteArray())) else emptyList(),messageId="<$index@fixture.invalid>"))) }
        // append() is for Sent and deliberately sets Seen; fixtures start unread before exercising downloads.
        val uids=graph.client.sync(config.imap.endpoint(),Credentials("password"),"INBOX",windowDays=0).flags.map { it.uid }.toLongArray()
        graph.client.setFlags(config.imap.endpoint(),Credentials("password"),"INBOX",uids,seen=false)
    }
    private suspend fun complete(manager:StorageManager=graph.storage,pageSize:Int=200) {
        withTimeout(60000) { repeat(40) { when(manager.downloads.run(account,pageSize)) { OfflinePass.DONE->return@withTimeout; OfflinePass.RETRY->error("Unexpected retry"); OfflinePass.MORE->yield() } }; error("Backfill did not finish") }
    }
    @Test fun allHeadersBackfillPastInitialWindowAndResumeFromDurableUidCheckpoint()=runBlocking {
        append(23)
        val limited=graph.client.sync(config.imap.endpoint(),Credentials("password"),"INBOX",limit=5,windowDays=0)
        val folder=FolderRow(accountId=account,path="INBOX",name="Inbox",role="INBOX",validity=limited.cursor.uidValidity,nextUid=limited.cursor.uidNext,total=23)
        val id=db.mail().insertFolder(folder)
        limited.headers.forEach { graph.upsertHeader(folder.copy(id=id),it) }
        assertEquals(5,db.mail().cachedMessageCount(account))
        graph.storage.preferences.save(account,StoragePolicy(retention=RetentionMode.ALL,maxBytes=0,wifiOnly=false))
        assertEquals(OfflinePass.MORE,graph.storage.downloads.run(account,7))
        val checkpoint=db.mail().offlineFolder(id)!!
        assertTrue(checkpoint.beforeUid>0); assertFalse(checkpoint.complete)
        graph.pauseOfflineDownload(account)
        assertEquals(OfflinePass.DONE,graph.storage.downloads.run(account,7))
        assertEquals(checkpoint,db.mail().offlineFolder(id))
        val restarted=StorageManager(graph).apply { availableBytes={1024L*1024*1024}; downloads.networkAllowed={true} }
        restarted.preferences.pause(account,false)
        complete(restarted,7)
        assertEquals(23,db.mail().cachedMessageCount(account)); assertTrue(db.mail().offlineFolder(id)!!.complete)
        assertEquals(0,bodyReads.get())
        assertEquals(OfflineState.COMPLETE,restarted.preferences.progress(account).state)
        assertFalse(graph.client.sync(config.imap.endpoint(),Credentials("password"),"INBOX",limit=500,windowDays=0).flags.any { it.seen })
    }
    @Test fun bodyThenAttachmentModeReusesFilesPreservesIdsAndClearPausesAutomaticDownloads()=runBlocking {
        append(4,attachments=true); assertTrue(graph.syncAll(accountIds=setOf(account)))
        graph.storage.preferences.save(account,StoragePolicy(retention=RetentionMode.ALL,maxBytes=0,downloadBodies=true,wifiOnly=false))
        complete()
        assertEquals(4,bodyReads.get()); assertEquals(0,attachmentReads.get())
        val folder=db.mail().folders(account).single { it.role=="INBOX" }
        val messages=db.mail().folderMessages(folder.id)
        assertTrue(messages.all { it.bodyLoaded })
        val first=db.mail().attachments(messages.first().id).single()
        assertNull(first.localUri)
        assertTrue(graph.openAttachment(graph.attachment(first)).startsWith("content://")); assertEquals(1,attachmentReads.get())
        graph.storage.preferences.save(account,graph.storage.preferences.policy(account).copy(downloadAttachments=true))
        graph.resumeOfflineDownload(account); complete()
        assertEquals(4,bodyReads.get()); assertEquals(4,attachmentReads.get())
        assertEquals(first.id,db.mail().attachments(first.messageId).single().id)
        val cachedReads=bodyReads.get()+attachmentReads.get()
        graph.resumeOfflineDownload(account); complete()
        assertEquals(cachedReads,bodyReads.get()+attachmentReads.get())
        assertTrue(graph.clearCachedMail(account).freedBytes>0)
        assertEquals(OfflinePass.DONE,graph.storage.downloads.run(account)); assertEquals(cachedReads,bodyReads.get()+attachmentReads.get())
        graph.resumeOfflineDownload(account); complete()
        assertEquals(first.id,db.mail().attachments(first.messageId).single().id)
        assertTrue(graph.storage.files.exists(db.mail().attachments(first.messageId).single().localUri))
        assertFalse(graph.client.sync(config.imap.endpoint(),Credentials("password"),"INBOX",limit=500,windowDays=0).flags.any { it.seen })
    }
    @Test fun savingOneMessageDownloadsItWithoutResumingThePausedBulkJob()=runBlocking {
        append(2); assertTrue(graph.syncAll(accountIds=setOf(account)))
        val folder=db.mail().folders(account).single { it.role=="INBOX" }
        val rows=db.mail().folderMessages(folder.id)
        graph.pauseOfflineDownload(account)
        graph.setOfflineSaved(listOf(rows.first().id),true)
        assertEquals(OfflinePass.DONE,graph.storage.downloads.run(account,pinnedOnly=true))
        assertTrue(db.mail().message(rows.first().id)!!.bodyLoaded)
        assertFalse(db.mail().message(rows.last().id)!!.bodyLoaded)
        assertTrue(graph.storage.preferences.paused(account))
        assertEquals(setOf(rows.first().id),graph.offlineSavedMessages().first())
        assertEquals(1,bodyReads.get())
    }

    @Test fun waitingNetworkChargingAndDeviceSpaceNeverStartBodyDownloads()=runBlocking {
        append(1); assertTrue(graph.syncAll(accountIds=setOf(account)))
        graph.storage.preferences.save(account,StoragePolicy(retention=RetentionMode.ALL,maxBytes=0,downloadBodies=true,chargingOnly=true))
        graph.storage.downloads.networkAllowed={false}
        assertEquals(OfflinePass.DONE,graph.storage.downloads.run(account)); assertEquals(OfflineState.WAITING_FOR_NETWORK,graph.offlineProgress(account).first().state)
        graph.storage.downloads.networkAllowed={true}; graph.storage.downloads.charging={false}
        assertEquals(OfflinePass.DONE,graph.storage.downloads.run(account)); assertEquals(OfflineState.WAITING_FOR_CHARGING,graph.offlineProgress(account).first().state)
        graph.storage.downloads.charging={true}; graph.storage.availableBytes={0}
        assertEquals(OfflinePass.DONE,graph.storage.downloads.run(account)); assertEquals(OfflineState.DEVICE_FULL,graph.offlineProgress(account).first().state)
        assertEquals(0,bodyReads.get())
    }
}
