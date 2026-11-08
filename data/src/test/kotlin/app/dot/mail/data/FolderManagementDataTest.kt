package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class FolderManagementDataTest {
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

    private class Fixture(val green: GreenMail, val account: Long, val imap: Endpoint, val creds: Credentials)

    private fun withServer(block: suspend (Fixture) -> Unit) = runBlocking<Unit> {
        val green = GreenMail(arrayOf(ServerSetup(0, "127.0.0.1", "imap"), ServerSetup(0, "127.0.0.1", "smtp")))
        green.start()
        try {
            green.setUser("alice@example.org", "alice", "password")
            val local = config.copy(imap = config.imap.copy(host = "127.0.0.1", port = green.imap.port), smtp = config.smtp.copy(host = "127.0.0.1", port = green.smtp.port))
            val account = graph.addAccount(local, "password")
            assertThat(graph.syncAll()).isTrue()
            block(Fixture(green, account, local.imap.endpoint(), Credentials("password")))
        } finally { green.stop() }
    }

    private suspend fun serverPaths(f: Fixture) = graph.client.folders(f.imap, f.creds).map { it.path }
    private suspend fun localPaths(f: Fixture) = db.mail().folders(f.account).map { it.path }
    private suspend fun failure(block: suspend () -> Unit): FolderFailure =
        (runCatching { block() }.exceptionOrNull() as? FolderException ?: error("expected a FolderException")).reason

    @Test fun createRenameDeleteRoundTrip() = withServer { f ->
        val work = graph.createFolder(f.account, null, "Work")
        assertThat(localPaths(f)).contains("Work")
        assertThat(serverPaths(f)).doesNotContain("Work") // queued, not yet sent
        assertThat(graph.syncAll()).isTrue()
        assertThat(serverPaths(f)).contains("Work")

        val projects = graph.createFolder(f.account, work, "Projects")
        val sep = db.mail().folder(work)!!.let { graph.folderSeparator(f.account) }
        assertThat(sep).isEqualTo(".") // learned from the server during sync, GreenMail uses "."
        assertThat(db.mail().folder(projects)!!.path).isEqualTo("Work.Projects")
        assertThat(graph.syncAll()).isTrue()
        assertThat(serverPaths(f)).contains("Work.Projects")

        // The parent can't be removed or renamed under a pending change, and not while it has subfolders.
        assertThat(failure { graph.deleteFolder(work) }).isEqualTo(FolderFailure.HAS_SUBFOLDERS)

        graph.renameFolder(projects, "Clients")
        assertThat(db.mail().folder(projects)!!.path).isEqualTo("Work.Clients")
        assertThat(failure { graph.renameFolder(projects, "Other") }).isEqualTo(FolderFailure.PENDING)
        assertThat(graph.syncAll()).isTrue()
        assertThat(serverPaths(f)).contains("Work.Clients")
        assertThat(serverPaths(f)).doesNotContain("Work.Projects")

        graph.deleteFolder(projects)
        assertThat(graph.folders(f.account).first().map { it.id }).doesNotContain(projects) // hidden at once
        assertThat(graph.syncAll()).isTrue()
        assertThat(serverPaths(f)).doesNotContain("Work.Clients")
        assertThat(db.mail().folder(projects)).isNull()
        assertThat(db.mail().operations(f.account)).isEmpty()
    }

    @Test fun refusalsAreTyped() = withServer { f ->
        val inbox = db.mail().folders(f.account).first { it.role == "INBOX" }.id
        assertThat(failure { graph.renameFolder(inbox, "Mail") }).isEqualTo(FolderFailure.PROTECTED_FOLDER)
        assertThat(failure { graph.deleteFolder(inbox) }).isEqualTo(FolderFailure.PROTECTED_FOLDER)
        assertThat(failure { graph.moveFolder(inbox, null) }).isEqualTo(FolderFailure.PROTECTED_FOLDER)
        for (bad in listOf("", "  ", "a/b", "a.b", "a*", "..", "x".repeat(101))) {
            assertThat(failure { graph.createFolder(f.account, null, bad) }).isEqualTo(FolderFailure.INVALID_NAME)
        }
        val a = graph.createFolder(f.account, null, "Alpha")
        assertThat(graph.syncAll()).isTrue()
        assertThat(failure { graph.createFolder(f.account, null, "alpha") }).isEqualTo(FolderFailure.ALREADY_EXISTS)
        val b = graph.createFolder(f.account, a, "Beta")
        assertThat(graph.syncAll()).isTrue()
        assertThat(failure { graph.moveFolder(a, b) }).isEqualTo(FolderFailure.INVALID_TARGET) // into its own subfolder
        assertThat(failure { graph.moveFolder(a, a) }).isEqualTo(FolderFailure.INVALID_TARGET)
        assertThat(failure { graph.deleteFolder(9_999) }).isEqualTo(FolderFailure.NOT_FOUND)
    }

    @Test fun deletingAFolderThatNeverReachedTheServerNeedsNoServerCall() = withServer { f ->
        val id = graph.createFolder(f.account, null, "Scratch")
        graph.deleteFolder(id)
        assertThat(db.mail().folder(id)).isNull()
        assertThat(db.mail().operations(f.account)).isEmpty()
    }

    @Test fun renamedAndDeletedElsewhereAreReconciled() = withServer { f ->
        graph.client.createFolder(f.imap, f.creds, null, "Old")
        graph.client.createFolder(f.imap, f.creds, null, "Gone")
        assertThat(graph.syncAll()).isTrue()
        val old = db.mail().folders(f.account).first { it.path == "Old" }
        val gone = db.mail().folders(f.account).first { it.path == "Gone" }

        graph.client.renameFolder(f.imap, f.creds, "Old", null, "New")
        graph.client.deleteFolder(f.imap, f.creds, "Gone")
        assertThat(graph.syncAll()).isTrue()

        val paths = localPaths(f)
        assertThat(paths).contains("New"); assertThat(paths).doesNotContain("Old"); assertThat(paths).doesNotContain("Gone")
        assertThat(db.mail().folder(gone.id)).isNull()
        // Same row (and so the same cached messages) when the server kept UIDVALIDITY across the rename; a plain
        // delete + create is the acceptable fallback for servers that don't.
        val renamed = db.mail().folders(f.account).first { it.path == "New" }
        if (old.validity > 0 && renamed.validity == old.validity) assertThat(renamed.id).isEqualTo(old.id)
    }

    @Test fun serverWinsWhenARenameLosesTheRace() = withServer { f ->
        graph.client.createFolder(f.imap, f.creds, null, "Mine")
        assertThat(graph.syncAll()).isTrue()
        val mine = db.mail().folders(f.account).first { it.path == "Mine" }.id

        graph.renameFolder(mine, "Taken") // accepted locally: "Taken" is not known yet...
        graph.client.createFolder(f.imap, f.creds, null, "Taken") // ...but another client creates it first
        graph.syncAll()

        assertThat(db.mail().folder(mine)!!.path).isEqualTo("Mine") // rolled back
        assertThat(db.mail().folder(mine)!!.name).isEqualTo("Mine")
        assertThat(db.mail().operations(f.account)).isEmpty()
        val state = graph.accounts.first().single().syncState
        assertThat(state).isInstanceOf(SyncState.Error::class.java)
        assertThat((state as SyncState.Error).message).contains("Couldn't rename folder")
    }

    @Test fun deletingANonEmptyFolderMovesTheMessagesFirstAndNeverLosesThem() = withServer { f ->
        val keep = graph.createFolder(f.account, null, "Keep")
        assertThat(graph.syncAll()).isTrue()
        val smtp = Endpoint("127.0.0.1", f.green.smtp.port, "alice", TransportSecurity.NONE)
        graph.client.send(smtp, f.creds, Mime.build(OutgoingMail(Address("alice@example.org"), listOf(Address("alice@example.org")), subject = "Precious", text = "x")))
        assertThat(f.green.waitForIncomingEmail(5000, 1)).isTrue()
        val uid = graph.client.sync(f.imap, f.creds, "INBOX").headers.single().uid
        graph.client.move(f.imap, f.creds, "INBOX", longArrayOf(uid), "Keep")
        assertThat(graph.syncAll()).isTrue()
        assertThat(db.mail().folderMessages(keep)).hasSize(1)

        assertThat(failure { graph.deleteFolder(keep) }).isEqualTo(FolderFailure.NOT_EMPTY)
        val inbox = db.mail().folders(f.account).first { it.role == "INBOX" }.id
        graph.deleteFolder(keep, moveContentsTo = inbox)
        assertThat(db.mail().folderMessages(keep)).isEmpty()
        assertThat(db.mail().folderMessages(inbox)).hasSize(1) // optimistic

        graph.syncAll()
        // The message is in the Inbox on the server; the folder is gone, or (server without UIDPLUS/MOVE) kept with a warning.
        assertThat(graph.client.sync(f.imap, f.creds, "INBOX").existingUids).hasSize(1)
    }
}
