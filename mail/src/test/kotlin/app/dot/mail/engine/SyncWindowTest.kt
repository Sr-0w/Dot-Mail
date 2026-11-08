package app.dot.mail.engine

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWindowTest {
    @Test fun windowLimitsWhatAFreshSyncDownloads() = runBlocking {
        val green = GreenMail(arrayOf(ServerSetup(0, "127.0.0.1", "imap"), ServerSetup(0, "127.0.0.1", "smtp")))
        green.start()
        try {
            green.setUser("alice@example.org", "alice", "password")
            val imap = Endpoint("127.0.0.1", green.imap.port, "alice", TransportSecurity.NONE)
            val smtp = Endpoint("127.0.0.1", green.smtp.port, "alice", TransportSecurity.NONE)
            val creds = Credentials("password"); val client = MailClient()
            client.send(smtp, creds, Mime.build(OutgoingMail(Address("alice@example.org"), listOf(Address("alice@example.org")), subject = "Today", text = "x")))
            check(green.waitForIncomingEmail(5000, 1))
            val day = 24L * 3600 * 1000
            val now = System.currentTimeMillis()
            // "Now" as seen by the client, 20 days / 60 days later: the message is 20 / 60 days old.
            assertEquals(1, client.sync(imap, creds, "INBOX", now = now + 20 * day, windowDays = 30).headers.size)
            assertEquals(0, client.sync(imap, creds, "INBOX", now = now + 60 * day, windowDays = 30).headers.size)
            assertEquals(0, client.sync(imap, creds, "INBOX", now = now + 20 * day, windowDays = 14).headers.size)
            assertEquals(1, client.sync(imap, creds, "INBOX", now = now + 60 * day, windowDays = 0).headers.size) // all mail
        } finally { green.stop() }
    }
}
