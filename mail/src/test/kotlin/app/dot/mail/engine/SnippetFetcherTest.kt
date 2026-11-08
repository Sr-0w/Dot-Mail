package app.dot.mail.engine

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SnippetFetcherTest {
    @Test fun boundedPeekChoosesPlainTextAndLeavesUnread() = runBlocking {
        val server = GreenMail(arrayOf(ServerSetup(0, "127.0.0.1", "imap"), ServerSetup(0, "127.0.0.1", "smtp")))
        server.start()
        try {
            server.setUser("a@example.org", "a", "password")
            val imap = Endpoint("127.0.0.1", server.imap.port, "a", TransportSecurity.NONE)
            val smtp = Endpoint("127.0.0.1", server.smtp.port, "a", TransportSecurity.NONE)
            val client = MailClient(); val credentials = Credentials("password")
            val text = "Readable preview " + "x".repeat(5000) + " SHOULD_NOT_BE_FETCHED"
            client.send(smtp, credentials, Mime.build(OutgoingMail(Address("a@example.org"), listOf(Address("a@example.org")), subject = "Preview", text = text, html = "<p>Other HTML</p>", attachments = listOf(OutgoingAttachment("large.bin", "application/octet-stream", ByteArray(32_000) { 3 })))))
            assertTrue(server.waitForIncomingEmail(5000, 1))
            val initial = client.sync(imap, credentials, "INBOX")
            val header = initial.headers.single(); assertFalse(header.flags.seen)
            val preview = client.snippets(imap, credentials, "INBOX", longArrayOf(header.uid), initial.cursor.uidValidity, maxBytes = 128).getValue(header.uid)
            assertTrue(preview.startsWith("Readable preview")); assertTrue(preview.length <= 128)
            assertFalse(preview.contains("Other HTML")); assertFalse(preview.contains("SHOULD_NOT_BE_FETCHED"))
            assertFalse(client.sync(imap, credentials, "INBOX", initial.cursor).flags.single().seen)
            assertNotNull(runCatching { client.snippets(imap, credentials, "INBOX", longArrayOf(header.uid), initial.cursor.uidValidity + 1) }.exceptionOrNull())
        } finally { server.stop() }
    }
    @Test fun htmlOnlyAndTransferEncodedPrefixesDecodeSafely() = runBlocking {
        val server = GreenMail(arrayOf(ServerSetup(0, "127.0.0.1", "imap"), ServerSetup(0, "127.0.0.1", "smtp")))
        server.start()
        try {
            server.setUser("a@example.org", "a", "password")
            val imap = Endpoint("127.0.0.1", server.imap.port, "a", TransportSecurity.NONE)
            val client = MailClient(); val credentials = Credentials("password")
            // APPEND a fixture with an HTML-only body and an encoded prefix cut inside a base64 quartet.
            val mime = jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties())).apply {
                setFrom("a@example.org"); setRecipients(jakarta.mail.Message.RecipientType.TO, "a@example.org")
                setSubject("Encoded HTML"); sentDate = java.util.Date()
                setContent("<p>Bonjour été 日本語</p>" + "<p>More</p>".repeat(500), "text/html; charset=UTF-8")
                setHeader("Content-Transfer-Encoding", "base64"); saveChanges()
            }
            client.append(imap, credentials, "INBOX", mime)
            val initial = client.sync(imap, credentials, "INBOX")
            val preview = client.snippets(imap, credentials, "INBOX", longArrayOf(initial.headers.single().uid), initial.cursor.uidValidity, maxBytes = 129).values.single()
            assertTrue(preview.startsWith("Bonjour été 日本語")); assertFalse(preview.contains("<p>"))
        } finally { server.stop() }
    }
    @Test fun damagedPlainFallsBackToUnicodeHtmlAndUtf8PrefixesStayWellFormed() = runBlocking {
        val server=GreenMail(arrayOf(ServerSetup(0,"127.0.0.1","imap"),ServerSetup(0,"127.0.0.1","smtp")))
        server.start(); server.setUser("a@example.org","a","password")
        try {
            val client=MailClient(); val credentials=Credentials("password")
            val imap=Endpoint("127.0.0.1",server.imap.port,"a",TransportSecurity.NONE)
            val smtp=Endpoint("127.0.0.1",server.smtp.port,"a",TransportSecurity.NONE)
            val expected="🔥 Smartphones 日本語 e\u0301"
            client.send(smtp,credentials,Mime.build(OutgoingMail(Address("a@example.org"),listOf(Address("a@example.org")),subject="Unicode",text="?? Smartphones",html="<html><head><style>"+"/* layout */".repeat(2000)+"</style></head><body><p>$expected</p></body></html>")))
            val initial=client.sync(imap,credentials,"INBOX")
            val preview=client.snippets(imap,credentials,"INBOX",longArrayOf(initial.headers.single().uid),initial.cursor.uidValidity).values.single()
            assertEquals(expected,preview)
            val raw="From: a@example.org\r\nTo: a@example.org\r\nSubject: Prefix\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n"+"x".repeat(126)+"🔥 日本語 e\u0301"
            val message=jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()),raw.toByteArray(Charsets.UTF_8).inputStream())
            client.append(imap,credentials,"INBOX",message)
            val second=client.sync(imap,credentials,"INBOX")
            val partial=client.snippets(imap,credentials,"INBOX",longArrayOf(second.headers.last().uid),second.cursor.uidValidity,maxBytes=128).values.single()
            assertEquals("x".repeat(126),partial)
            assertFalse(partial.contains('\uFFFD'))
            assertTrue(client.sync(imap,credentials,"INBOX").flags.none { it.seen && it.uid==initial.headers.single().uid })
        } finally { server.stop() }
    }

}
