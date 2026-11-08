package app.dot.mail.data

import android.content.Context
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.FolderRow
import app.dot.mail.data.local.MailDatabase
import app.dot.mail.data.model.*
import app.dot.mail.engine.Address
import app.dot.mail.engine.Header
import app.dot.mail.engine.RemoteFlags
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Group into conversations" off: one list row per message, virtual thread ids, actions on that message only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageModeTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private val config = AccountConfig("alice@example.org", "Alice", ServerConfig("localhost", 143, Security.NONE, "alice"), ServerConfig("localhost", 25, Security.NONE, "alice"))

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        graph = RealMailGraph(context, db, object : CredentialVault {
            override fun encrypt(accountEmail: String, secret: String) = "encrypted:$secret"
            override fun decrypt(accountEmail: String, encrypted: String) = encrypted.removePrefix("encrypted:")
        }, scheduling = false)
    }

    @After fun cleanup() { graph.scope.cancel(); db.close() }

    private fun header(uid: Long, messageId: String, refs: List<String>, subject: String, at: Long, seen: Boolean = false, from: String = "bob@example.org") =
        Header(uid, messageId, refs, refs.lastOrNull(), listOf(Address(from, "Bob")), listOf(Address("alice@example.org")), emptyList(), emptyList(), emptyList(), subject, at, RemoteFlags(uid, seen, false, false), false)

    private class Fixture(val account: Long, val inbox: FolderRow, val archive: FolderRow, val threadA: Long, val threadB: Long)

    private fun withMail(block: suspend (Fixture) -> Unit) = runBlocking<Unit> {
        val account = graph.addAccount(config, "password")
        val inbox = db.mail().insertFolder(FolderRow(accountId = account, path = "INBOX", name = "INBOX", role = "INBOX", validity = 1)).let { db.mail().folder(it)!! }
        val archive = db.mail().insertFolder(FolderRow(accountId = account, path = "Archive", name = "Archive", role = "ARCHIVE", validity = 2)).let { db.mail().folder(it)!! }
        // Thread A: two messages (a reply to the first); thread B: one message, newest of all.
        val a = graph.upsertHeader(inbox, header(1, "<a1@example.org>", emptyList(), "Topic A", 1_000)).first
        graph.upsertHeader(inbox, header(2, "<a2@example.org>", listOf("<a1@example.org>"), "Re: Topic A", 3_000))
        val b = graph.upsertHeader(inbox, header(3, "<b1@example.org>", emptyList(), "Topic B", 5_000, from = "carol@example.org")).first
        assertThat(a).isNotEqualTo(b)
        block(Fixture(account, inbox, archive, a, b))
    }

    private suspend fun rows(mailbox: Mailbox) = graph.messages(mailbox).asSnapshot()

    @Test fun oneRowPerMessageNewestFirst() = withMail { f ->
        val grouped = graph.threads(Mailbox.InFolder(f.inbox.id)).asSnapshot()
        assertThat(grouped).hasSize(2) // conversations

        val list = rows(Mailbox.InFolder(f.inbox.id))
        assertThat(list.map { it.subject }).containsExactly("Topic B", "Re: Topic A", "Topic A").inOrder()
        assertThat(list.all { it.messageCount == 1 }).isTrue()
        assertThat(list.all { ConversationIds.isMessage(it.threadId) }).isTrue()
        assertThat(list.map { it.threadId }.distinct()).hasSize(3)
        assertThat(list.first().participants.single().email).isEqualTo("carol@example.org")
        assertThat(list.first().unreadCount).isEqualTo(1)
        assertThat(list.first().recipients.single().email).isEqualTo("alice@example.org")
    }

    @Test fun unifiedByRoleAndOtherFoldersAreSeparate() = withMail { f ->
        assertThat(rows(Mailbox.Unified(FolderRole.INBOX))).hasSize(3)
        assertThat(rows(Mailbox.InFolder(f.archive.id))).isEmpty()
        assertThat(rows(Mailbox.Unified(FolderRole.ARCHIVE))).isEmpty()
    }

    @Test fun openingAVirtualIdShowsOnlyThatMessage() = withMail { f ->
        val list = rows(Mailbox.InFolder(f.inbox.id))
        val reply = list.first { it.subject == "Re: Topic A" }
        val shown = graph.thread(reply.threadId).first()
        assertThat(shown.map { it.subject }).containsExactly("Re: Topic A")
        // The same conversation opened the normal way still has both.
        assertThat(graph.thread(f.threadA).first()).hasSize(2)
    }

    @Test fun actionsAffectOnlyThatMessage() = withMail { f ->
        val list = rows(Mailbox.InFolder(f.inbox.id))
        val first = list.first { it.subject == "Topic A" }
        graph.apply(MessageAction.MARK_READ, listOf(first.threadId))
        val after = rows(Mailbox.InFolder(f.inbox.id))
        assertThat(after.first { it.subject == "Topic A" }.unreadCount).isEqualTo(0)
        assertThat(after.first { it.subject == "Re: Topic A" }.unreadCount).isEqualTo(1) // sibling untouched

        graph.apply(MessageAction.ARCHIVE, listOf(first.threadId))
        assertThat(rows(Mailbox.InFolder(f.inbox.id)).map { it.subject }).containsExactly("Topic B", "Re: Topic A").inOrder()
        assertThat(rows(Mailbox.InFolder(f.archive.id)).map { it.subject }).containsExactly("Topic A")
        // The conversation view still shows both messages of the thread.
        assertThat(graph.thread(f.threadA).first().map { it.subject }).containsExactly("Topic A", "Re: Topic A") // a thread spans folders
    }

    @Test fun movingAndDeletingAVirtualIdTouchesOneMessage() = withMail { f ->
        val list = rows(Mailbox.InFolder(f.inbox.id))
        val b = list.first { it.subject == "Topic B" }
        graph.move(listOf(b.threadId), f.archive.id)
        assertThat(rows(Mailbox.InFolder(f.archive.id)).map { it.subject }).containsExactly("Topic B")
        assertThat(rows(Mailbox.InFolder(f.inbox.id))).hasSize(2)
    }
}
