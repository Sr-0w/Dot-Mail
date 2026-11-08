package app.dot.mail.engine

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderManagementTest {
    private fun withServer(block: suspend (Endpoint, Credentials, MailClient, GreenMail) -> Unit) = runBlocking {
        val green = GreenMail(arrayOf(ServerSetup(0, "127.0.0.1", "imap"), ServerSetup(0, "127.0.0.1", "smtp")))
        green.start()
        try {
            green.setUser("alice@example.org", "alice", "password")
            block(Endpoint("127.0.0.1", green.imap.port, "alice", TransportSecurity.NONE), Credentials("password"), MailClient(), green)
        } finally { green.stop() }
    }

    private suspend fun paths(c: MailClient, e: Endpoint, cr: Credentials) = c.folders(e, cr).map { it.path }

    @Test fun createRenameDeleteWithHierarchy() = withServer { imap, creds, client, _ ->
        assertEquals("Work", client.createFolder(imap, creds, null, "Work"))
        val projects = client.createFolder(imap, creds, "Work", "Projects")
        val listed = client.folders(imap, creds)
        // The path uses whatever separator the server reports (GreenMail: "."), never a guessed "/".
        val sep = listed.first { it.path == projects }.separator
        assertEquals("Work${sep}Projects", projects)
        assertEquals("Projects", listed.first { it.path == projects }.name)

        val renamed = client.renameFolder(imap, creds, projects, "Work", "Clients")
        assertEquals("Work${sep}Clients", renamed)
        val afterRename = paths(client, imap, creds)
        assertTrue(renamed in afterRename); assertFalse(projects in afterRename)

        // (Moving to another parent is the same RENAME command with a different prefix; GreenMail's in-memory store
        // crashes on cross-parent renames, so only the same-parent rename is exercised against it.)
        val moved = renamed
        client.deleteFolder(imap, creds, moved)
        assertFalse(moved in paths(client, imap, creds))
        client.deleteFolder(imap, creds, moved) // already gone: idempotent
    }

    @Test fun existingAndMissingFoldersAreTypedFailures() = withServer { imap, creds, client, _ ->
        client.createFolder(imap, creds, null, "Receipts")
        val dup = assertThrows(FolderOpException::class.java) { runBlocking { client.createFolder(imap, creds, null, "Receipts") } }
        assertEquals(FolderOpException.Kind.EXISTS, dup.kind)
        val missing = assertThrows(FolderOpException::class.java) { runBlocking { client.renameFolder(imap, creds, "Nope", null, "Else") } }
        assertEquals(FolderOpException.Kind.NOT_FOUND, missing.kind)
        client.createFolder(imap, creds, null, "Other")
        val clash = assertThrows(FolderOpException::class.java) { runBlocking { client.renameFolder(imap, creds, "Other", null, "Receipts") } }
        assertEquals(FolderOpException.Kind.EXISTS, clash.kind)
    }

    @Test fun nonEmptyFolderIsNeverDeleted() = withServer { imap, creds, client, green ->
        client.createFolder(imap, creds, null, "Keep")
        val smtp = Endpoint("127.0.0.1", green.smtp.port, "alice", TransportSecurity.NONE)
        client.send(smtp, creds, Mime.build(OutgoingMail(Address("alice@example.org"), listOf(Address("alice@example.org")), subject = "Hello", text = "x")))
        assertTrue(green.waitForIncomingEmail(5000, 1))
        val uids = client.sync(imap, creds, "INBOX").headers.map { it.uid }.toLongArray()
        client.move(imap, creds, "INBOX", uids, "Keep")
        val refused = assertThrows(FolderOpException::class.java) { runBlocking { client.deleteFolder(imap, creds, "Keep") } }
        assertEquals(FolderOpException.Kind.NOT_EMPTY, refused.kind)
        assertTrue("Keep" in paths(client, imap, creds))
    }
}
