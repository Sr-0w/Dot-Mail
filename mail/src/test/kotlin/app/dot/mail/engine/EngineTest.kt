package app.dot.mail.engine

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import jakarta.mail.Flags
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties

class EngineTest {
    @Test fun sanitizesActiveContentAndTracking() {
        val cases = listOf(
            "<script>alert(1)</script><iframe src='https://bad'></iframe><object data='x'></object><form action='x'><input></form>",
            "<img src='https://tracker/pixel' srcset='https://tracker 2x' onerror='evil()'><meta http-equiv='refresh' content='0;url=x'>",
            "<a href='java&#x73;cript:evil()'>link</a><svg><a href='javascript:x'>x</a></svg>",
            "<div style='background:url(https://tracker);color:red' background='https://tracker'>Hi</div>",
            "<style>@import 'https://tracker';</style><p style='background-image:u\\72l(https://tracker);width:expression(x)'>Hi</p>",
            "<img src='data:image/svg+xml,evil'><video poster='https://tracker'><source src='x'></video>",
        )
        cases.forEach { value ->
            val html = HtmlSanitizer.sanitize(value).html
            assertFalse(html.contains("<script")); assertFalse(html.contains("<iframe")); assertFalse(html.contains("javascript:", true))
            assertFalse(html.contains("https://tracker")); assertFalse(html.contains("onerror")); assertFalse(html.contains("expression")); assertFalse(html.contains("<style"))
        }
        assertTrue(HtmlSanitizer.sanitize(cases[1]).hasRemoteImages)
        val cid = HtmlSanitizer.sanitize("<img src='cid:hello'><p style='color:red;padding:8px'>Hi</p>", cidUris = mapOf("hello" to "content://app/part/1"))
        assertTrue(cid.html.contains("content://app/part/1")); assertTrue(cid.html.contains("color:red")); assertFalse(cid.hasRemoteImages)
        assertFalse(HtmlSanitizer.sanitize("<img src='file:///etc/passwd'>").html.contains("file:"))
        assertTrue(HtmlSanitizer.sanitize("<img src='https://example.org/a'>", true).html.contains("https://example.org/a"))
    }
    @Test fun mimeRoundTrip() {
        val out = OutgoingMail(Address("alice@example.org", "Élodie"), listOf(Address("bob@example.org")), subject = "Réunion 日本語", text = "Bonjour ☀", html = "<p>Bonjour ☀<img src='cid:logo'></p>", attachments = listOf(OutgoingAttachment("été.txt", "text/plain", "data".toByteArray()), OutgoingAttachment("logo.png", "image/png", byteArrayOf(1, 2, 3), "logo")), inReplyTo = "<parent@example.org>", references = listOf("<root@example.org>"), messageId = "<stable@example.org>")
        val built = Mime.build(out)
        val bytes = ByteArrayOutputStream().also { built.writeTo(it) }.toByteArray()
        val parsedMessage = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(bytes))
        val parsed = Mime.parse(parsedMessage)
        assertEquals(out.subject, parsedMessage.subject); assertEquals(out.text, parsed.text)
        assertEquals(out.html, parsed.html); assertEquals(2, parsed.attachments.size)
        assertEquals("été.txt", parsed.attachments.single { it.contentId == null }.name); assertEquals("logo", parsed.attachments.single { it.name == "logo.png" }.contentId)
        assertEquals("<stable@example.org>", parsedMessage.messageID)
        assertEquals("<parent@example.org>", parsedMessage.getHeader("In-Reply-To", null))
    }
    @Test fun imapSmtpSyncAndMutation() = runBlocking {
        val green = GreenMail(arrayOf(ServerSetup(0, "127.0.0.1", "imap"), ServerSetup(0, "127.0.0.1", "smtp")))
        green.start()
        try {
            green.setUser("alice@example.org", "alice", "password")
            val imap = Endpoint("127.0.0.1", green.imap.port, "alice", TransportSecurity.NONE)
            val smtp = Endpoint("127.0.0.1", green.smtp.port, "alice", TransportSecurity.NONE)
            val creds = Credentials("password"); val client = MailClient()
            client.verify(imap, smtp, creds)
            val message = Mime.build(OutgoingMail(Address("alice@example.org"), listOf(Address("alice@example.org")), subject = "Hello", text = "Body only on demand", attachments = listOf(OutgoingAttachment("newlines.txt", "text/plain", "line1\nline2\n".toByteArray()))))
            client.send(smtp, creds, message)
            assertTrue(green.waitForIncomingEmail(5000, 1))
            val initial = client.sync(imap, creds, "INBOX")
            assertTrue(initial.reset); assertEquals(1, initial.headers.size)
            val uid = initial.headers.single().uid
            val downloaded = client.body(imap, creds, "INBOX", uid)
            assertEquals("Body only on demand", downloaded.text)
            assertArrayEquals("line1\nline2\n".toByteArray(), downloaded.attachments.single().bytes)
            client.setFlags(imap, creds, "INBOX", longArrayOf(uid), seen = true, starred = true)
            val incremental = client.sync(imap, creds, "INBOX", initial.cursor)
            assertFalse(incremental.reset); assertTrue(incremental.headers.isEmpty()); assertTrue(incremental.flags.single().seen)
            assertEquals(1, client.search(imap, creds, "INBOX", "Hello").size)
            client.append(imap, creds, "Drafts", message, true)
            assertTrue(client.folders(imap, creds).any { it.role == Role.DRAFTS })
            client.append(imap, creds, "Drafts", message, true)
            assertEquals(1, client.sync(imap, creds, "Drafts").headers.size)
            client.move(imap, creds, "INBOX", longArrayOf(uid), "Archive")
            assertEquals(1, client.sync(imap, creds, "Archive").headers.size)
            assertTrue(client.sync(imap, creds, "INBOX").headers.isEmpty())
        } finally { green.stop() }
    }
    @Test fun boundedInitialAndIncrementalPages() = runBlocking {
        val server = GreenMail(arrayOf(ServerSetup(0, "127.0.0.1", "imap"), ServerSetup(0, "127.0.0.1", "smtp")))
        server.start()
        try {
            server.setUser("alice@example.org", "alice", "password")
            val imap = Endpoint("127.0.0.1", server.imap.port, "alice", TransportSecurity.NONE)
            val smtp = Endpoint("127.0.0.1", server.smtp.port, "alice", TransportSecurity.NONE)
            val client = MailClient(); val credentials = Credentials("password")
            suspend fun deliver(subject: String) { client.send(smtp, credentials, Mime.build(OutgoingMail(Address("alice@example.org"), listOf(Address("alice@example.org")), subject = subject, text = "text"))) }
            repeat(4) { deliver("initial $it") }
            assertTrue(server.waitForIncomingEmail(5000, 4))
            val first = client.sync(imap, credentials, "INBOX", limit = 2)
            assertEquals(listOf("initial 2", "initial 3"), first.headers.map { it.subject })
            repeat(3) { deliver("next $it") }
            assertTrue(server.waitForIncomingEmail(5000, 7))
            var cursor = first.cursor
            val subjects = mutableListOf<String>()
            repeat(3) { val page = client.sync(imap, credentials, "INBOX", cursor, limit = 1); subjects += page.headers.single().subject; cursor = page.cursor }
            assertEquals(listOf("next 0", "next 1", "next 2"), subjects)
            assertTrue(client.sync(imap, credentials, "INBOX", cursor).headers.isEmpty())
            assertTrue(client.sync(imap, credentials, "INBOX", cursor.copy(uidValidity = -4), limit = 2).reset)
            assertEquals("from", 7, client.search(imap, credentials, "INBOX", "from:alice@example.org").size)
            assertEquals("subject", 1, client.search(imap, credentials, "INBOX", """subject:"next 1"""").size)
            assertEquals("unread", 7, client.search(imap, credentials, "INBOX", "is:unread").size)
            assertEquals("combined", 1, client.search(imap, credentials, "INBOX", """from:alice@example.org subject:"next 1" is:unread""").size)
        } finally { server.stop() }
    }
    @Test fun searchParserHandlesOperatorsAndPhrases() {
        val parsed = MailSearch.parse("""from:alice to:"Bob Smith" subject:"weekly report" has:attachment is:unread is:starred "free phrase"""")
        assertEquals(7, parsed.terms.size)
        assertEquals("Bob Smith", parsed.terms[1].value)
        assertEquals("weekly report", parsed.terms[2].value)
        assertTrue(parsed.needsAttachments)
        assertEquals(MailSearch.Term(null, "free phrase"), parsed.terms.last())
    }
    @Test fun gmailThreadExtensionParsesWithoutLosingFetchResponse() {
        val protocol = org.eclipse.angus.mail.imap.protocol.IMAPProtocol(ByteArrayInputStream(byteArrayOf()), java.io.PrintStream(ByteArrayOutputStream()), Properties(), false)
        val response = org.eclipse.angus.mail.imap.protocol.FetchResponse(
            org.eclipse.angus.mail.imap.protocol.IMAPResponse("* 1 FETCH (UID 4 X-GM-THRID 123456789)"),
            arrayOf<org.eclipse.angus.mail.imap.protocol.FetchItem>(GmailThreadFetch.item), protocol)
        assertEquals(123456789L, response.extensionItems["X-GM-THRID"])
    }
    @Test fun threadingAndRoles() {
        assertEquals("hello", Threading.normalizeSubject(" Re: Fwd: Hello "))
        assertEquals(Role.ALL_MAIL, MailClient.role("[Gmail]/All Mail", emptyList()))
        assertEquals(Role.SENT, MailClient.role("whatever", listOf("\\Sent")))
        assertEquals(Role.TRASH, MailClient.role("Corbeille", emptyList()))
    }
}
