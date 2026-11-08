package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.MailDatabase
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OutboxRescheduleTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private val config = AccountConfig("alice@example.org", "Alice", ServerConfig("127.0.0.1", 1, Security.NONE, "alice"), ServerConfig("127.0.0.1", 1, Security.NONE, "alice"))

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MailDatabase::class.java).allowMainThreadQueries().build()
        graph = RealMailGraph(ApplicationProvider.getApplicationContext(), db, object : CredentialVault {
            override fun encrypt(accountEmail: String, secret: String) = secret
            override fun decrypt(accountEmail: String, encrypted: String) = encrypted
        }, scheduling = false)
    }
    @After fun close() { graph.scope.cancel(); db.close() }

    private suspend fun queue(at: Long?): Pair<Long, Long> {
        val account = graph.addAccount(config, "password")
        val draftId = graph.saveDraft(Draft(accountId = account, identityId = null, to = listOf(EmailAddress("bob@example.org")), subject = "Plan", bodyText = "hello"))
        val outboxId = graph.send(Draft(localId = draftId, accountId = account, identityId = null, to = listOf(EmailAddress("bob@example.org")), subject = "Plan", bodyText = "hello", scheduledAt = at), 0)
        return outboxId to draftId
    }

    @Test fun aScheduledMessageIsListedAsEditableAndMovable() = runBlocking<Unit> {
        val due = System.currentTimeMillis() + 3_600_000
        val (outboxId, draftId) = queue(due)
        val item = graph.outbox.first().single { it.id == outboxId }
        assertTrue(item.canEdit); assertTrue(item.canReschedule)
        assertEquals(draftId, item.draftId)
        assertTrue(item.sendAt >= due)
    }

    @Test fun rescheduleMovesTheDueTimeAndRejectsPastOrTooSoonTimes() = runBlocking<Unit> {
        val (outboxId, _) = queue(System.currentTimeMillis() + 3_600_000)
        val later = System.currentTimeMillis() + 7_200_000
        assertTrue(graph.rescheduleSend(outboxId, later))
        assertEquals(later, db.mail().outbox(outboxId)!!.dueAt)
        assertFalse(graph.rescheduleSend(outboxId, System.currentTimeMillis() - 1))
        assertFalse(graph.rescheduleSend(outboxId, System.currentTimeMillis() + 10_000)) // under the minimum lead
        assertEquals(later, db.mail().outbox(outboxId)!!.dueAt) // unchanged by the rejected calls
    }

    @Test fun aMessageThatDeliveryAlreadyClaimedCannotBeMoved() = runBlocking<Unit> {
        val (outboxId, _) = queue(System.currentTimeMillis() + 3_600_000)
        assertEquals(1, db.mail().transitionOutbox(outboxId, "PENDING", "SUBMITTING"))
        assertFalse(graph.rescheduleSend(outboxId, System.currentTimeMillis() + 7_200_000))
        val item = graph.outbox.first().single { it.id == outboxId }
        assertFalse(item.canEdit); assertFalse(item.canReschedule)
        assertFalse(graph.cancelSend(outboxId)) // and it cannot be pulled back for editing either
    }

    @Test fun editingReturnsTheDraftAndRemovesTheQueuedItem() = runBlocking<Unit> {
        val (outboxId, draftId) = queue(System.currentTimeMillis() + 3_600_000)
        assertTrue(graph.cancelSend(outboxId))
        val draft = graph.loadDraft(draftId)!!
        assertEquals("Plan", draft.subject)
        assertEquals("hello", draft.bodyText)
        assertTrue(graph.outbox.first().none { it.id == outboxId })
    }
}
