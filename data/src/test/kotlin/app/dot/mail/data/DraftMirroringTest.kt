package app.dot.mail.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DraftMirroringTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private lateinit var server: GreenMail
    private lateinit var config: AccountConfig
    private var account=0L
    private val creds=Credentials("password")
    @Before fun setup() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        db=Room.inMemoryDatabaseBuilder(context,MailDatabase::class.java).allowMainThreadQueries().build()
        graph=RealMailGraph(context,db,object:CredentialVault {
            override fun encrypt(accountEmail:String,secret:String)=secret
            override fun decrypt(accountEmail:String,encrypted:String)=encrypted
        },scheduling=false)
        server=GreenMail(arrayOf(ServerSetup(0,"127.0.0.1","imap"),ServerSetup(0,"127.0.0.1","smtp")))
        server.start(); server.setUser("alice@example.org","alice","password")
        config=AccountConfig("alice@example.org","Alice",ServerConfig("127.0.0.1",server.imap.port,Security.NONE,"alice"),ServerConfig("127.0.0.1",server.smtp.port,Security.NONE,"alice"))
        account=graph.addAccount(config,"password")
        graph.client.createFolder(config.imap.endpoint(),creds,null,"Drafts")
        assertTrue(graph.syncAll())
    }
    @After fun close() { graph.scope.cancel(); db.close(); server.stop() }
    private fun draft(text:String="First")=Draft(accountId=account,identityId=account,to=listOf(EmailAddress("recipient@example.org")),subject="Draft",bodyText=text,bodyHtml="<p>$text</p>")
    private suspend fun ready(id:Long) { db.mail().putDraftMirror(db.mail().draftMirror(id)!!.copy(dueAt=0)) }
    private suspend fun remote()=graph.client.sync(config.imap.endpoint(),creds,"Drafts").headers.filter { !it.flags.deleted }
    private suspend fun listDrafts(): List<ThreadSummary> {
        val folder=db.mail().folders(account).single { it.role=="DRAFTS" }
        val source=graph.mailboxSource("unused",listOf(folder.id),true,account)
        val page=source.load(PagingSource.LoadParams.Refresh<Int>(null,40,false)) as PagingSource.LoadResult.Page
        return page.data.map { graph.summary(it,true) }
    }
    @Test fun savesDebounceReplaceAndHideOwnServerCopy() = runBlocking {
        val id=graph.saveDraft(draft())
        assertTrue(graph.syncAll()); assertTrue(remote().isEmpty())
        graph.saveDraft(draft("Latest").copy(localId=id)); ready(id)
        assertTrue(graph.syncAll())
        val first=remote().single()
        assertEquals("Latest",graph.client.body(config.imap.endpoint(),creds,"Drafts",first.uid).text)
        assertEquals(listOf(id),listDrafts().map { it.draftId })
        graph.saveDraft(draft("Replacement").copy(localId=id)); ready(id)
        assertTrue(graph.syncAll()); val replaced=remote().single()
        assertNotEquals(first.uid,replaced.uid)
        assertEquals("Replacement",graph.client.body(config.imap.endpoint(),creds,"Drafts",replaced.uid).text)
        assertEquals(listOf(id),listDrafts().map { it.draftId })
        assertTrue(graph.syncAll()); assertEquals(1,remote().size)
    }
    private suspend fun remoteTrash()=graph.client.sync(config.imap.endpoint(),creds,"Trash").headers.filter { !it.flags.deleted }
    @Test fun trashingADraftMovesItsLatestVersionToTrash() = runBlocking {
        graph.client.createFolder(config.imap.endpoint(),creds,null,"Trash"); assertTrue(graph.syncAll())
        val id=graph.saveDraft(draft("Never uploaded")) // still debouncing: only the local copy exists
        val token=graph.applyUndoable(MessageAction.TRASH,listOf(-id),Mailbox.Unified(FolderRole.DRAFTS))
        assertEquals(listOf(id),token!!.drafts)
        assertTrue(listDrafts().isEmpty()) // hidden at once
        assertTrue(graph.syncAll()) // still inside the Undo window: nothing moves yet
        assertTrue(remoteTrash().isEmpty()); assertNotNull(db.mail().draft(id))
        ready(id); assertTrue(graph.syncAll())
        assertTrue(remote().isEmpty())
        val trashed=remoteTrash().single()
        assertEquals("Never uploaded",graph.client.body(config.imap.endpoint(),creds,"Trash",trashed.uid).text)
        assertNull(db.mail().draft(id)); assertNull(db.mail().draftMirror(id))
        assertTrue(db.mail().operations(account).isEmpty())
        assertEquals(1,graph.undo(token)) // already on the server: nothing left to cancel
    }
    @Test fun undoBeforeSyncKeepsTheDraft() = runBlocking {
        graph.client.createFolder(config.imap.endpoint(),creds,null,"Trash"); assertTrue(graph.syncAll())
        val id=graph.saveDraft(draft("Keep me")); ready(id); assertTrue(graph.syncAll())
        val token=graph.applyUndoable(MessageAction.TRASH,listOf(-id),Mailbox.Unified(FolderRole.DRAFTS))!!
        assertTrue(listDrafts().isEmpty())
        assertTrue(graph.syncAll()) // the sync started by the action must not beat Undo
        assertTrue(remoteTrash().isEmpty())
        assertEquals(0,graph.undo(token))
        assertEquals(listOf(id),listDrafts().map { it.draftId })
        assertTrue(graph.syncAll())
        assertEquals(1,remote().size); assertTrue(remoteTrash().isEmpty())
    }
    @Test fun withoutATrashFolderTrashingStillDeletes() = runBlocking {
        val id=graph.saveDraft(draft()); ready(id); assertTrue(graph.syncAll()); assertEquals(1,remote().size)
        graph.apply(MessageAction.TRASH,listOf(-id),Mailbox.Unified(FolderRole.DRAFTS))
        assertNull(db.mail().draft(id)); assertTrue(graph.syncAll()); assertTrue(remote().isEmpty())
    }
    @Test fun uncertainAppendRecoversFromDurableStateWithoutDuplicate() = runBlocking {
        val id=graph.saveDraft(draft()); ready(id)
        val mirror=db.mail().draftMirror(id)!!
        db.mail().putDraftMirror(mirror.copy(attemptRevision=mirror.revision))
        val mime=graph.draftMime(db.mail().account(account)!!,draft(),"<dotdraft-${mirror.token}-${mirror.revision}@dotmail.local>")
        val copy=graph.client.appendDraft(config.imap.endpoint(),creds,"Drafts",mirror.token,mirror.revision,mime)
        // Durable state is exactly the crash point after server APPEND, before local acknowledgement.
        assertTrue(graph.syncAll())
        assertEquals(copy.uid,db.mail().draftMirror(id)!!.uid)
        assertEquals(1,remote().size)
        graph.discardDraft(id); assertTrue(graph.syncAll())
        assertTrue(remote().isEmpty()); assertNull(db.mail().draftMirror(id))
    }
    @Test fun editOrDiscardDuringUnacknowledgedAppendSurvives() = runBlocking {
        for(discard in listOf(false,true)) {
            val id=graph.saveDraft(draft()); ready(id)
            val mirror=db.mail().draftMirror(id)!!
            db.mail().putDraftMirror(mirror.copy(attemptRevision=mirror.revision))
            graph.client.appendDraft(config.imap.endpoint(),creds,"Drafts",mirror.token,mirror.revision,
                graph.draftMime(db.mail().account(account)!!,draft(),"<dotdraft-${mirror.token}-${mirror.revision}@dotmail.local>"))
            if(discard) graph.discardDraft(id) else { graph.saveDraft(draft("Newer edit").copy(localId=id)); ready(id) }
            assertTrue(graph.syncAll())
            if(discard) assertTrue(remote().isEmpty()) else {
                val message=remote().single()
                assertEquals("Newer edit",graph.client.body(config.imap.endpoint(),creds,"Drafts",message.uid).text)
                graph.discardDraft(id); assertTrue(graph.syncAll()); assertTrue(remote().isEmpty())
            }
        }
    }
    @Test fun externalReplacementAndOfflineEditKeepBothVersions() = runBlocking {
        val id=graph.saveDraft(draft()); ready(id); assertTrue(graph.syncAll())
        val old=db.mail().draftMirror(id)!!
        graph.saveDraft(draft("Offline local edit").copy(localId=id)); ready(id)
        val external=Mime.build(OutgoingMail(Address("alice@example.org"),listOf(Address("recipient@example.org")),subject="Draft",text="Edited elsewhere",references=listOf("<same-thread@example.org>")))
        graph.client.append(config.imap.endpoint(),creds,"Drafts",external,draft=true)
        graph.client.deleteDraftCopy(config.imap.endpoint(),creds,"Drafts",DraftCopy(old.validity,old.uid))
        assertTrue(graph.syncAll())
        assertEquals(2,remote().size)
        assertEquals("Offline local edit",graph.loadDraft(id)!!.bodyText)
        val rows=listDrafts(); assertEquals(2,rows.size)
        val foreign=rows.single { it.draftId!=id }
        assertTrue(foreign.draftId!!<0)
        val imported=graph.loadDraft(foreign.draftId!!)!!
        assertEquals("Edited elsewhere",imported.bodyText)
        graph.saveDraft(imported.copy(bodyText="Other device draft updated",bodyHtml=null)); ready(imported.localId)
        assertTrue(graph.syncAll()); assertEquals(2,remote().size)
    }
    @Test fun sendRemovesMirrorAndUndoQueuesNewRevision() = runBlocking {
        val id=graph.saveDraft(draft()); ready(id); assertTrue(graph.syncAll())
        val outbox=graph.send(graph.loadDraft(id)!!,60_000)
        assertNull(graph.loadDraft(id)); assertTrue(graph.syncAll()); assertTrue(remote().isEmpty())
        assertTrue(graph.cancelSend(outbox)); ready(id); assertTrue(graph.syncAll())
        assertEquals(1,remote().size); assertNotNull(graph.loadDraft(id))
        graph.discardDraft(id); assertTrue(graph.syncAll()); assertTrue(remote().isEmpty())
    }
    @Test fun changedUidValidityNeverDeletesAnUnrelatedCurrentUid() = runBlocking {
        val id=graph.saveDraft(draft()); ready(id); assertTrue(graph.syncAll())
        val original=db.mail().draftMirror(id)!!
        // A stale checkpoint must never authorize deletion of the same number in a new UID namespace.
        graph.client.deleteDraftCopy(config.imap.endpoint(),creds,"Drafts",DraftCopy(original.validity+1,original.uid))
        assertEquals(1,remote().size)
        graph.saveDraft(draft("Local after reset").copy(localId=id)); ready(id)
        db.mail().putDraftMirror(db.mail().draftMirror(id)!!.copy(validity=original.validity+1))
        assertTrue(graph.syncAll()); assertEquals(2,remote().size)
        assertEquals("Local after reset",graph.loadDraft(id)!!.bodyText)
    }
    @Test fun cleanLocalCopyFollowsExternalDeleteWithoutResurrecting() = runBlocking {
        val id=graph.saveDraft(draft()); ready(id); assertTrue(graph.syncAll())
        val mirror=db.mail().draftMirror(id)!!
        graph.client.deleteDraftCopy(config.imap.endpoint(),creds,"Drafts",DraftCopy(mirror.validity,mirror.uid))
        assertTrue(graph.syncAll()); assertNull(graph.loadDraft(id)); assertTrue(remote().isEmpty())
    }
}
