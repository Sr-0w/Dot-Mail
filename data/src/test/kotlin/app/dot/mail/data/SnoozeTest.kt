package app.dot.mail.data

import android.content.Context
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.FolderRow
import app.dot.mail.data.local.MailDatabase
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import com.google.common.truth.Truth.assertThat
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SnoozeTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private val config = AccountConfig("alice@example.org", "Alice", ServerConfig("localhost", 143, Security.NONE, "alice"), ServerConfig("localhost", 25, Security.NONE, "alice"))
    private val hour = 3_600_000L

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        graph = RealMailGraph(context, db, object : CredentialVault {
            override fun encrypt(accountEmail: String, secret: String) = "encrypted:$secret"
            override fun decrypt(accountEmail: String, encrypted: String) = encrypted.removePrefix("encrypted:")
        }, scheduling = false)
    }

    @After fun cleanup() { graph.scope.cancel(); db.close() }

    private fun header(uid: Long, id: String, refs: List<String>, subject: String, at: Long) =
        Header(uid, id, refs, refs.lastOrNull(), listOf(Address("bob@example.org", "Bob")), listOf(Address("alice@example.org")), emptyList(), emptyList(), emptyList(), subject, at, RemoteFlags(uid, false, false, false), false)

    private class Fx(val inbox: FolderRow, val threadA: Long, val threadB: Long)

    private fun withMail(block: suspend (Fx) -> Unit) = runBlocking<Unit> {
        val account = graph.addAccount(config, "password")
        val inbox = db.mail().insertFolder(FolderRow(accountId = account, path = "INBOX", name = "INBOX", role = "INBOX", validity = 1)).let { db.mail().folder(it)!! }
        val a = graph.upsertHeader(inbox, header(1, "<a1@x>", emptyList(), "Topic A", 1_000)).first
        graph.upsertHeader(inbox, header(2, "<a2@x>", listOf("<a1@x>"), "Re: Topic A", 3_000))
        val b = graph.upsertHeader(inbox, header(3, "<b1@x>", emptyList(), "Topic B", 5_000)).first
        block(Fx(inbox, a, b))
    }

    private suspend fun subjects(box: Mailbox) = graph.threads(box).asSnapshot().map { it.subject }
    private suspend fun messageSubjects(box: Mailbox) = graph.messages(box).asSnapshot().map { it.subject }
    private val inboxBox = { f: Fx -> Mailbox.InFolder(f.inbox.id) }

    @Test fun snoozedThreadsDisappearFromEveryList() = withMail { f ->
        graph.snooze(listOf(f.threadA), System.currentTimeMillis() + hour)
        assertThat(subjects(inboxBox(f))).containsExactly("Topic B")
        assertThat(subjects(Mailbox.Unified(FolderRole.INBOX))).containsExactly("Topic B")
        assertThat(messageSubjects(inboxBox(f))).containsExactly("Topic B") // both messages of A are hidden in message mode too
        assertThat(graph.snoozedCount().first()).isEqualTo(1)
        // ...and show up, soonest first, in the Snoozed list.
        assertThat(subjects(Mailbox.Unified(FolderRole.SNOOZED))).containsExactly("Re: Topic A")
        assertThat(messageSubjects(Mailbox.Unified(FolderRole.SNOOZED))).containsExactly("Topic A", "Re: Topic A")
    }

    @Test fun snoozedListIsOrderedByWakeUpTime() = withMail { f ->
        val now = System.currentTimeMillis()
        graph.snooze(listOf(f.threadA), now + 5 * hour)
        graph.snooze(listOf(f.threadB), now + hour)
        assertThat(subjects(Mailbox.Unified(FolderRole.SNOOZED))).containsExactly("Topic B", "Re: Topic A").inOrder()
    }

    @Test fun snoozedRowsCarryTheirWakeUpTime() = withMail { f ->
        val until = System.currentTimeMillis() + 5 * hour
        graph.snooze(listOf(f.threadA), until)
        assertThat(graph.threads(Mailbox.Unified(FolderRole.SNOOZED)).asSnapshot().single().snoozedUntil).isEqualTo(until)
        assertThat(graph.messages(Mailbox.Unified(FolderRole.SNOOZED)).asSnapshot().map { it.snoozedUntil }.distinct()).containsExactly(until)
        assertThat(graph.threads(Mailbox.InFolder(f.inbox.id)).asSnapshot().map { it.snoozedUntil }).containsExactly(null) // ordinary lists have none
    }

    @Test fun dueItemsComeBackOnTopAsIfNew() = withMail { f ->
        val now = System.currentTimeMillis()
        assertThat(subjects(inboxBox(f))).containsExactly("Topic B", "Re: Topic A").inOrder() // B is newer
        graph.snooze(listOf(f.threadA), now + hour)
        assertThat(graph.resurfaceDue(now + hour - 1)).isEqualTo(0) // not yet
        assertThat(subjects(inboxBox(f))).containsExactly("Topic B")
        assertThat(graph.resurfaceDue(now + hour + 1)).isEqualTo(1)
        assertThat(subjects(inboxBox(f))).containsExactly("Re: Topic A", "Topic B").inOrder() // bumped above the newer thread
        assertThat(graph.snoozedCount().first()).isEqualTo(0)
        assertThat(subjects(Mailbox.Unified(FolderRole.SNOOZED))).isEmpty()
        assertThat(graph.resurfaceDue(now + 2 * hour)).isEqualTo(0) // only once
    }

    @Test fun unsnoozeWakesAtOnce() = withMail { f ->
        graph.snooze(listOf(f.threadA), System.currentTimeMillis() + hour)
        graph.unsnooze(listOf(f.threadA))
        assertThat(subjects(inboxBox(f))).containsExactly("Topic B", "Re: Topic A").inOrder()
        assertThat(graph.snoozedCount().first()).isEqualTo(0)
    }

    @Test fun aSingleMessageCanBeSnoozedInMessageMode() = withMail { f ->
        val reply = graph.messages(inboxBox(f)).asSnapshot().first { it.subject == "Re: Topic A" }
        graph.snooze(listOf(reply.threadId), System.currentTimeMillis() + hour)
        assertThat(messageSubjects(inboxBox(f))).containsExactly("Topic B", "Topic A").inOrder()
        assertThat(subjects(inboxBox(f))).containsExactly("Topic B", "Re: Topic A") // the conversation list still shows the thread
        assertThat(messageSubjects(Mailbox.Unified(FolderRole.SNOOZED))).containsExactly("Re: Topic A")
        graph.unsnooze(listOf(reply.threadId))
        assertThat(messageSubjects(inboxBox(f))).hasSize(3)
    }

    @Test fun pastTimesAndDraftsAreRefused() = withMail { f ->
        assertThrows(IllegalArgumentException::class.java) { runBlocking { graph.snooze(listOf(f.threadA), System.currentTimeMillis() - 1) } }
        graph.snooze(listOf(-5L), System.currentTimeMillis() + hour) // local draft ids are ignored
        assertThat(graph.snoozedCount().first()).isEqualTo(0)
    }

    @Test fun newMailInASnoozedConversationWakesIt() = runBlocking<Unit> {
        val green = GreenMail(arrayOf(ServerSetup(0, "127.0.0.1", "imap"), ServerSetup(0, "127.0.0.1", "smtp")))
        green.start()
        try {
            green.setUser("alice@example.org", "alice", "password")
            val local = config.copy(imap = config.imap.copy(host = "127.0.0.1", port = green.imap.port), smtp = config.smtp.copy(host = "127.0.0.1", port = green.smtp.port))
            val account = graph.addAccount(local, "password")
            val smtp = local.smtp.endpoint(); val creds = Credentials("password")
            fun mail(subject: String, id: String, inReplyTo: String? = null) = Mime.build(OutgoingMail(
                Address("alice@example.org"), listOf(Address("alice@example.org")), subject = subject, text = "x",
                inReplyTo = inReplyTo, references = listOfNotNull(inReplyTo), messageId = id,
            ))
            graph.client.send(smtp, creds, mail("Project", "<p1@example.org>"))
            assertThat(green.waitForIncomingEmail(5000, 1)).isTrue()
            assertThat(graph.syncAll()).isTrue()
            val inbox = db.mail().folders(account).first { it.role == "INBOX" }
            val thread = graph.threads(Mailbox.InFolder(inbox.id)).asSnapshot().single().threadId

            graph.snooze(listOf(thread), System.currentTimeMillis() + 10 * hour)
            assertThat(graph.threads(Mailbox.InFolder(inbox.id)).asSnapshot()).isEmpty()
            assertThat(graph.syncAll()).isTrue() // nothing new: stays snoozed
            assertThat(graph.snoozedCount().first()).isEqualTo(1)

            graph.client.send(smtp, creds, mail("Re: Project", "<p2@example.org>", inReplyTo = "<p1@example.org>"))
            assertThat(green.waitForIncomingEmail(5000, 2)).isTrue()
            assertThat(graph.syncAll()).isTrue()
            assertThat(graph.snoozedCount().first()).isEqualTo(0)
            assertThat(graph.threads(Mailbox.InFolder(inbox.id)).asSnapshot().single().messageCount).isEqualTo(2)
        } finally { green.stop() }
    }
}
