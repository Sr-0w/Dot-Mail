package app.dot.mail.data

import android.content.Context
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.AttachmentRow
import app.dot.mail.data.local.FolderRow
import app.dot.mail.data.local.MailDatabase
import app.dot.mail.data.model.*
import app.dot.mail.engine.Address
import app.dot.mail.engine.Header
import app.dot.mail.engine.RemoteFlags
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class AttachmentKindTest {
    @Test fun `mime types fall into exactly one bucket`() {
        assertThat(AttachmentKind.of("image/png")).isEqualTo(AttachmentKind.IMAGES)
        assertThat(AttachmentKind.of("IMAGE/JPEG")).isEqualTo(AttachmentKind.IMAGES)
        assertThat(AttachmentKind.of("application/pdf")).isEqualTo(AttachmentKind.PDFS)
        for (m in listOf("text/plain", "text/csv", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel", "application/vnd.oasis.opendocument.text", "application/rtf")) {
            assertThat(AttachmentKind.of(m)).isEqualTo(AttachmentKind.DOCUMENTS)
        }
        for (m in listOf("application/zip", "application/octet-stream", "audio/mpeg", "video/mp4", "text/calendar")) {
            assertThat(AttachmentKind.of(m)).isEqualTo(AttachmentKind.OTHER)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AttachmentBrowserTest {
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

    private fun header(uid: Long, id: String, subject: String, at: Long, from: String) =
        Header(uid, id, emptyList(), null, listOf(Address(from, from.substringBefore('@'))), listOf(Address("alice@example.org")), emptyList(), emptyList(), emptyList(), subject, at, RemoteFlags(uid, true, false, false), true)

    private suspend fun attach(messageId: Long, name: String, mime: String, inline: Boolean = false, local: String? = null) =
        db.mail().insertAttachment(AttachmentRow(messageId = messageId, partId = name, name = name, mime = mime, size = 10, cid = null, inline = inline, localUri = local))

    private fun withMail(block: suspend (inbox: FolderRow, trash: FolderRow) -> Unit) = runBlocking<Unit> {
        val account = graph.addAccount(config, "password")
        val inbox = db.mail().insertFolder(FolderRow(accountId = account, path = "INBOX", name = "INBOX", role = "INBOX", validity = 1)).let { db.mail().folder(it)!! }
        val trash = db.mail().insertFolder(FolderRow(accountId = account, path = "Trash", name = "Trash", role = "TRASH", validity = 2)).let { db.mail().folder(it)!! }
        block(inbox, trash)
    }

    private suspend fun names(kind: AttachmentKind? = null, query: String = "") = graph.attachments(kind, query).asSnapshot().map { it.attachment.fileName }

    @Test fun listsRealAttachmentsNewestFirstAndLeavesOutInlineAndTrash() = withMail { inbox, trash ->
        graph.upsertHeader(inbox, header(1, "<1@x>", "Old", 1_000, "anna@example.org")); val m1 = db.mail().byUid(inbox.id, 1)!!.id
        graph.upsertHeader(inbox, header(2, "<2@x>", "New", 9_000, "marc@example.org")); val m2 = db.mail().byUid(inbox.id, 2)!!.id
        graph.upsertHeader(trash, header(3, "<3@x>", "Binned", 5_000, "anna@example.org")); val m3 = db.mail().byUid(trash.id, 3)!!.id
        attach(m1, "old.pdf", "application/pdf"); attach(m2, "new.png", "image/png", local = "content://x/new.png")
        attach(m2, "logo.png", "image/png", inline = true); attach(m3, "binned.pdf", "application/pdf")

        assertThat(names()).containsExactly("new.png", "old.pdf").inOrder()
        val first = graph.attachments().asSnapshot().first()
        assertThat(first.isDownloaded).isTrue()
        assertThat(first.from.email).isEqualTo("marc@example.org")
        assertThat(first.subject).isEqualTo("New")
        assertThat(first.sentAt).isEqualTo(9_000)
        assertThat(graph.attachments().asSnapshot().last().isDownloaded).isFalse()
    }

    @Test fun typeFiltersAgreeWithTheKotlinClassification() = withMail { inbox, _ ->
        graph.upsertHeader(inbox, header(1, "<1@x>", "Files", 1_000, "anna@example.org")); val m = db.mail().byUid(inbox.id, 1)!!.id
        val mimes = listOf("image/png", "image/jpeg", "application/pdf", "text/plain", "application/msword", "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/zip", "application/octet-stream", "audio/mpeg", "text/calendar")
        mimes.forEachIndexed { i, mime -> attach(m, "f$i", mime) }
        for (kind in AttachmentKind.entries) {
            val expected = mimes.mapIndexedNotNull { i, mime -> "f$i".takeIf { AttachmentKind.of(mime) == kind } }
            assertThat(names(kind)).containsExactlyElementsIn(expected)
        }
        assertThat(names()).hasSize(mimes.size)
    }

    @Test fun searchMatchesFileNameOrSenderAndEscapesWildcards() = withMail { inbox, _ ->
        graph.upsertHeader(inbox, header(1, "<1@x>", "A", 1_000, "anna@example.org")); val a = db.mail().byUid(inbox.id, 1)!!.id
        graph.upsertHeader(inbox, header(2, "<2@x>", "B", 2_000, "marc@example.org")); val b = db.mail().byUid(inbox.id, 2)!!.id
        attach(a, "invoice_2026.pdf", "application/pdf"); attach(b, "photo.jpg", "image/jpeg"); attach(b, "100%.txt", "text/plain")
        assertThat(names(query = "invoice")).containsExactly("invoice_2026.pdf")
        assertThat(names(query = "marc")).containsExactly("photo.jpg", "100%.txt")
        assertThat(names(query = "100%")).containsExactly("100%.txt") // % is not a wildcard
        assertThat(names(query = "_")).containsExactly("invoice_2026.pdf") // neither is _
        assertThat(names(AttachmentKind.IMAGES, "marc")).containsExactly("photo.jpg")
        assertThat(names(query = "nothing")).isEmpty()
    }
}
