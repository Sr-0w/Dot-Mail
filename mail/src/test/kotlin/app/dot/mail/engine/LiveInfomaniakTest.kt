package app.dot.mail.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Explicitly opted-in test. Only test-generated self-mail is written/moved. Never enable protocol debug. */
class LiveInfomaniakTest {
    @Test fun imapAndBothSmtpTransports() = runBlocking {
        val path = Path.of(System.getProperty("user.home"), ".config", "dotmail", "test-account.env")
        assumeTrue("Live interop requires DOTMAIL_LIVE=1 and an external credentials file", System.getenv("DOTMAIL_LIVE") == "1" && Files.isRegularFile(path))
        var phase = "read external credentials"
        try {
            val config = Files.readAllLines(path).filter { it.isNotBlank() && !it.trimStart().startsWith('#') }.associate { line ->
                val key = line.substringBefore('=').trim().removePrefix("export ")
                var value = line.substringAfter('=', "").trim()
                if (value.length >= 2 && ((value.first() == '\'' && value.last() == '\'') || (value.first() == '"' && value.last() == '"'))) value = value.substring(1, value.length - 1)
                key to value
            }
            val email = requireNotNull(config["DOTMAIL_TEST_EMAIL"])
            val credentials = Credentials(requireNotNull(config["DOTMAIL_TEST_PASSWORD"]))
            val imap = Endpoint("mail.infomaniak.com", 993, email, TransportSecurity.SSL_TLS)
            val client = MailClient(timeoutMs = 30_000)
            phase = "IMAP folder listing"
            val folders = client.folders(imap, credentials)
            assertTrue(folders.any { it.role == Role.INBOX })
            val inbox = folders.first { it.role == Role.INBOX }.path
            val trash = folders.firstOrNull { it.role == Role.TRASH }?.path ?: error("No existing Trash folder; test must not create one")
            println("LIVE IMAP TLS: OK; folders=${folders.size}; roles=${folders.groupingBy { it.role }.eachCount()}")
            phase = "bounded INBOX header sync"
            val initial = client.sync(imap, credentials, inbox, limit = 20)
            assertTrue(initial.headers.size <= 20)
            println("LIVE bounded header sync: OK; headers=${initial.headers.size}; condstore=${initial.cursor.highestModSeq >= 0}")
            var fetchedBody = false
            initial.headers.lastOrNull()?.let { header ->
                phase = "existing message body fetch"
                client.body(imap, credentials, inbox, header.uid, initial.cursor.uidValidity)
                fetchedBody = true
                println("LIVE body fetch: OK (contents withheld)")
            }
            // Recover only our interrupted test runs, identified by exact self-addresses, ID format and known fixture body.
            phase = "clean up generated mail from interrupted test runs"
            val interrupted = client.search(imap, credentials, inbox, "subject:\"Dot Mail interoperability\"", limit = 50).filter { h ->
                h.messageId?.matches(Regex("<dotmail-live-[0-9a-f-]{36}@dotmail[.]invalid>")) == true && h.from.any { it.email == email } && h.to.any { it.email == email }
            }
            var cleaned = 0
            interrupted.forEach { header ->
                val body = client.body(imap, credentials, inbox, header.uid)
                if (body.text?.startsWith("Opt-in Dot Mail interoperability test. This message will be moved to Trash by the test.") == true && body.attachments.any { it.name == "dotmail-interop.txt" }) {
                    client.move(imap, credentials, inbox, longArrayOf(header.uid), trash); cleaned++
                }
            }
            if (cleaned > 0) println("LIVE interrupted test messages moved to Trash: $cleaned")
            for ((port, security) in listOf(587 to TransportSecurity.STARTTLS, 465 to TransportSecurity.SSL_TLS)) {
                val marker = "Dot Mail interoperability ${UUID.randomUUID()}"
                val id = "<dotmail-live-${UUID.randomUUID()}@dotmail.invalid>"
                val payload = "Dot Mail live integration attachment. No mailbox data is included.\n".toByteArray()
                val mime = Mime.build(OutgoingMail(Address(email), listOf(Address(email)), subject = marker, text = "Opt-in Dot Mail interoperability test. This message will be moved to Trash by the test.", attachments = listOf(OutgoingAttachment("dotmail-interop.txt", "text/plain", payload)), messageId = id))
                phase = "SMTP $port $security send to self"
                client.send(Endpoint("mail.infomaniak.com", port, email, security), credentials, mime)
                println("LIVE SMTP $port $security: OK")
                phase = "IMAP find generated message for SMTP $port"
                var found: Header? = null
                repeat(30) {
                    if (found == null) {
                        found = client.search(imap, credentials, inbox, "subject:\"$marker\"", limit = 20).firstOrNull { it.messageId == id && it.subject == marker }
                        if (found == null) delay(2_000)
                    }
                }
                val header = requireNotNull(found) { "Self-mail did not arrive before timeout" }
                try {
                    phase = "generated body/attachment verification for SMTP $port"
                    val body = client.body(imap, credentials, inbox, header.uid)
                    assertArrayEquals(payload, body.attachments.single { it.name == "dotmail-interop.txt" }.bytes)
                    fetchedBody = true
                    println("LIVE IMAP search and attachment round trip $port: OK")
                } finally {
                    // Even a failed assertion must not leave this uniquely identified test mail in INBOX.
                    phase = "verify move of generated SMTP $port message"
                    val outcome = client.move(imap, credentials, inbox, longArrayOf(header.uid), trash)
                    var inTrash = false
                    repeat(10) {
                        if (!inTrash) {
                            inTrash = client.search(imap, credentials, trash, "subject:\"$marker\"", limit = 20).any { it.messageId == id }
                            if (!inTrash) delay(1_000)
                        }
                    }
                    println("LIVE MOVE $port: outcome=$outcome; exact test message in Trash=$inTrash")
                    assertTrue(inTrash)
                    println("LIVE generated message moved to Trash $port: OK")
                }
            }
            assertTrue(fetchedBody)
        } catch (e: Throwable) {
            // Exceptions from providers may contain account/server data; no original message or cause in reports.
            throw AssertionError("Live interop failed at $phase (${e.javaClass.simpleName}); sensitive provider details withheld")
        }
    }
}
