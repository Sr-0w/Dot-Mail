package app.dot.mail.data

import android.content.Context
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import kotlinx.coroutines.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sort, quick filters and smart folders against a real (in-memory) database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmartFolderQueryTest {
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

    private fun header(uid: Long, from: String, subject: String, seen: Boolean = true, starred: Boolean = false, attach: Boolean = false, sent: Long = uid * 1000, to: String = "alice@example.org") =
        Header(uid, "<$uid@example.org>", emptyList(), null, listOf(Address(from)), listOf(Address(to)), emptyList(), emptyList(), emptyList(), subject, sent, RemoteFlags(uid, seen, starred, false), attach)

    private suspend fun seed(): Map<String, Long> {
        val account = graph.addAccount(config, "password")
        val inbox = db.mail().insertFolder(FolderRow(accountId = account, path = "INBOX", name = "INBOX", role = "INBOX", validity = 1)).let { db.mail().folder(it)!! }
        val trash = db.mail().insertFolder(FolderRow(accountId = account, path = "Trash", name = "Trash", role = "TRASH", validity = 1)).let { db.mail().folder(it)!! }
        val ids = mutableMapOf<String, Long>()
        ids["zed"] = graph.upsertHeader(inbox, header(1, "zed@x.org", "Plan", seen = false)).first
        ids["amy"] = graph.upsertHeader(inbox, header(2, "amy@x.org", "Invoice March", starred = true, attach = true)).first
        ids["bob"] = graph.upsertHeader(inbox, header(3, "bob@x.org", "Lunch", to = "other@x.org")).first
        ids["bin"] = graph.upsertHeader(trash, header(4, "amy@x.org", "Invoice old", attach = true)).first
        return ids
    }

    private suspend fun list(mailbox: Mailbox, options: ListOptions): List<Long> = graph.threads(mailbox, options).asSnapshot().map { s: ThreadSummary -> s.threadId }

    @Test fun sortsAndQuickFilters() = runBlocking<Unit> {
        val ids = seed()
        val inbox = Mailbox.Unified(FolderRole.INBOX)
        assertEquals(listOf(ids["bob"], ids["amy"], ids["zed"]), list(inbox, ListOptions()))
        assertEquals(listOf(ids["zed"], ids["bob"], ids["amy"]), list(inbox, ListOptions(sort = InboxSort.UNREAD_FIRST)))
        assertEquals(listOf(ids["amy"], ids["bob"], ids["zed"]), list(inbox, ListOptions(sort = InboxSort.STARRED_FIRST)))
        assertEquals(listOf(ids["amy"], ids["bob"], ids["zed"]), list(inbox, ListOptions(sort = InboxSort.SENDER)))
        assertEquals(listOf(ids["zed"]!!), list(inbox, ListOptions(filters = ThreadFilters(unread = true))))
        assertEquals(listOf(ids["amy"]!!), list(inbox, ListOptions(filters = ThreadFilters(starred = true))))
        assertEquals(listOf(ids["amy"]!!), list(inbox, ListOptions(filters = ThreadFilters(attachments = true))))
        assertEquals(setOf(ids["amy"], ids["zed"]), list(inbox, ListOptions(filters = ThreadFilters(toMe = true))).toSet())
        assertEquals(emptyList<Long>(), list(inbox, ListOptions(filters = ThreadFilters(unread = true, starred = true))))
    }

    @Test fun smartFolderSelectsAcrossFoldersAndSkipsTrashByDefault() = runBlocking<Unit> {
        val ids = seed()
        graph.smartFolders.replaceAll(emptyList())
        graph.smartFolders.save(SmartFolder("s1", "Invoices", subject = "invoice"))
        assertEquals(listOf(ids["amy"]!!), list(Mailbox.Smart("s1"), ListOptions()))
        graph.smartFolders.save(SmartFolder("s2", "Invoice attachments incl. trash", roles = setOf(FolderRole.INBOX, FolderRole.TRASH), subject = "invoice", hasAttachment = true))
        assertEquals(setOf(ids["amy"], ids["bin"]), list(Mailbox.Smart("s2"), ListOptions()).toSet())
        graph.smartFolders.save(SmartFolder("s3", "Unread from zed", from = "zed", unreadOnly = true))
        assertEquals(listOf(ids["zed"]!!), list(Mailbox.Smart("s3"), ListOptions()))
        assertEquals(emptyList<Long>(), list(Mailbox.Smart("s3"), ListOptions(filters = ThreadFilters(starred = true))))
        assertEquals(emptyList<Long>(), list(Mailbox.Smart("missing"), ListOptions()))
        graph.smartFolders.replaceAll(emptyList())
    }
}
