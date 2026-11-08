package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import app.dot.mail.engine.calendar.Ics
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import jakarta.mail.Multipart
import jakarta.mail.internet.ContentType
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CalendarReplyTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private lateinit var server: GreenMail
    private lateinit var config: AccountConfig
    private var account=0L
    private fun clearProviderPaths() {
        // Robolectric gives each method a different filesDir; AndroidX caches roots statically by authority.
        val cache=androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible=true }.get(null) as MutableMap<*,*>
        cache.clear()
    }
    @Before fun setup() = runBlocking {
        clearProviderPaths()
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),MailDatabase::class.java).allowMainThreadQueries().build()
        graph=RealMailGraph(ApplicationProvider.getApplicationContext(),db,object: CredentialVault {
            override fun encrypt(accountEmail:String,secret:String)=secret
            override fun decrypt(accountEmail:String,encrypted:String)=encrypted
        },scheduling=false)
        server=GreenMail(arrayOf(ServerSetup(0,"127.0.0.1","imap"),ServerSetup(0,"127.0.0.1","smtp")))
        server.start(); server.setUser("alice@example.org","alice","password"); server.setUser("organizer@example.org","organizer","password")
        config=AccountConfig("alice@example.org","Alice",ServerConfig("127.0.0.1",server.imap.port,Security.NONE,"alice"),ServerConfig("127.0.0.1",server.smtp.port,Security.NONE,"alice"))
        account=graph.addAccount(config,"password")
    }
    @After fun close() { graph.scope.cancel(); db.close(); server.stop(); clearProviderPaths() }
    private suspend fun invitation(attendee:String="alias@example.org",method:String="REQUEST",status:String="CONFIRMED"): Long {
        val calendar=listOf("BEGIN:VCALENDAR","VERSION:2.0","METHOD:$method","BEGIN:VEVENT","UID:meeting-123","SEQUENCE:4","DTSTAMP:20261108T080000Z","DTSTART:20261109T100000Z","DTEND:20261109T110000Z","ORGANIZER:mailto:organizer@example.org","ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:$attendee","SUMMARY:Team meeting","STATUS:$status","END:VEVENT","END:VCALENDAR").joinToString("\r\n")
        val messageId="<invite-${UUID.randomUUID()}@example.org>"
        graph.client.send(config.smtp.endpoint(),Credentials("password"),Mime.build(OutgoingMail(Address("organizer@example.org"),listOf(Address("alice@example.org")),subject="Invitation",text="Join",attachments=listOf(OutgoingAttachment("invite.ics","text/calendar",calendar.toByteArray())),messageId=messageId)))
        assertTrue(graph.syncAll())
        return db.mail().folderMessages(db.mail().folders(account).single { it.role=="INBOX" }.id).single { Codec.field(it.header,"messageId")==messageId }.id
    }
    @Test fun repliesFromInvitedAliasToOrganizerThroughNormalOutbox() = runBlocking {
        val alias=graph.saveIdentity(Identity(0,account,"Alias","alias@example.org"))
        val message=invitation()
        for(stat in listOf(Ics.PartStat.ACCEPTED,Ics.PartStat.TENTATIVE,Ics.PartStat.DECLINED)) {
            val outbox=graph.sendCalendarReply(message,stat)
            val queued=db.mail().outbox(outbox)!!
            assertEquals(alias,Codec.draft(queued.draft).identityId)
            val calendar=org.json.JSONObject(queued.draft).getString("calendarReply")
            val reply=Ics.parse(calendar).single()
            assertEquals(Ics.Method.REPLY,reply.method); assertEquals(4,reply.sequence)
            assertEquals("meeting-123",reply.uid); assertEquals(stat,reply.attendees.single().partStat)
            assertEquals("alias@example.org",reply.attendees.single().email)
            db.openHelper.writableDatabase.execSQL("UPDATE outbox SET dueAt=0 WHERE id=?",arrayOf(outbox))
            assertTrue(graph.deliverOutbox(outbox))
            assertEquals("SENT",db.mail().outbox(outbox)!!.state)
        }
        val replies=server.receivedMessages.filter { it.subject!="Invitation" }.distinctBy { it.messageID }
        assertEquals(3,replies.size)
        replies.forEach { sent ->
            assertEquals("alias@example.org",(sent.from.single() as jakarta.mail.internet.InternetAddress).address)
            assertEquals("organizer@example.org",(sent.allRecipients.single() as jakarta.mail.internet.InternetAddress).address)
            assertTrue(sent.isMimeType("multipart/alternative"))
            val parts=sent.content as Multipart
            assertEquals("REPLY",ContentType(parts.getBodyPart(1).contentType).getParameter("method"))
        }
        assertNotNull(db.mail().message(message))
    }
    @Test fun refusesForeignIdentityCancelledEventsAndNeedsAction() = runBlocking {
        val foreign=invitation("someone-else@example.org")
        assertEquals("No invitation for an identity of this account",runCatching { graph.sendCalendarReply(foreign,Ics.PartStat.ACCEPTED) }.exceptionOrNull()?.message)
        val cancelled=invitation("alice@example.org",status="CANCELLED")
        assertEquals("No invitation for an identity of this account",runCatching { graph.sendCalendarReply(cancelled,Ics.PartStat.ACCEPTED) }.exceptionOrNull()?.message)
        assertTrue(runCatching { graph.sendCalendarReply(foreign,Ics.PartStat.NEEDS_ACTION) }.isFailure)
        assertTrue(db.mail().activeOutbox().isEmpty())
    }
    @Test fun removedInvitedIdentityCannotSilentlyFallBackToAnotherSender() = runBlocking {
        val alias=graph.saveIdentity(Identity(0,account,"Alias","alias@example.org"))
        val outbox=graph.sendCalendarReply(invitation(),Ics.PartStat.ACCEPTED)
        graph.deleteIdentity(account,alias)
        db.openHelper.writableDatabase.execSQL("UPDATE outbox SET dueAt=0 WHERE id=?",arrayOf(outbox))
        assertTrue(runCatching { graph.deliverOutbox(outbox) }.isFailure)
        assertEquals(1,server.receivedMessages.size)
        assertEquals("PENDING",db.mail().outbox(outbox)!!.state)
    }
    @Test fun undoCancelsReplyWithoutCreatingAnOrdinaryTextDraft() = runBlocking {
        val message=invitation("alice@example.org")
        val outbox=graph.sendCalendarReply(message,Ics.PartStat.ACCEPTED)
        assertTrue(graph.cancelSend(outbox))
        assertEquals("CANCELLED",db.mail().outbox(outbox)!!.state)
        assertTrue(db.mail().draftBatch(0,100).isEmpty())
        assertNotNull(db.mail().message(message))
    }
}
