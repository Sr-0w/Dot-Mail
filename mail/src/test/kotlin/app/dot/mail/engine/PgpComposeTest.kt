package app.dot.mail.engine

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64
import java.util.Properties

class PgpComposeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val attachment = OutgoingAttachment("notes é.txt", "text/plain", "attachment body".toByteArray())
    private val mail = OutgoingMail(
        Address("alice@example.org", "Alice"), listOf(Address("bob@example.org")), subject = "Réunion ☀ confidentielle",
        text = "Bonjour Bob ✓\nSecond line", html = "<p>Bonjour <b>Bob</b> ✓</p>", attachments = listOf(attachment),
        messageId = "<fixed@example.org>", inReplyTo = "<parent@example.org>",
    )

    private fun parse(bytes: ByteArray) = MimeMessage(Session.getInstance(Properties()), bytes.inputStream())

    // A stand-in for OpenKeychain: "ciphertext" is the armoured base64 of the plaintext, "signature" a hash armour.
    private fun fakeEncrypt(plain: ByteArray) = ("-----BEGIN PGP MESSAGE-----\n\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(plain) + "\n-----END PGP MESSAGE-----\n").toByteArray()
    private fun fakeDecrypt(armor: ByteArray): ByteArray = Base64.getMimeDecoder().decode(String(armor).lines().filter { !it.startsWith("-----") }.joinToString(""))
    private fun fakeSignature(data: ByteArray) = ("-----BEGIN PGP SIGNATURE-----\n\n" + Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(data)) + "\n-----END PGP SIGNATURE-----\n").toByteArray()

    @Test fun `entity is a CRLF MIME entity without trailing break and without message headers`() {
        val entity = PgpMime.entity(mail)
        val text = String(entity, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("Content-Type: multipart/mixed"))
        assertFalse(text.contains("\nFrom:")); assertFalse(text.contains("Subject:")); assertFalse(text.contains("Message-ID:"))
        assertFalse(text.replace("\r\n", "").contains('\n'))
        assertFalse(text.endsWith("\r\n"))
    }

    @Test fun `signed message has the RFC 3156 structure and signs exactly the transmitted bytes`() {
        val entity = PgpMime.entity(mail)
        val raw = PgpMime.assembleSigned(mail, entity, fakeSignature(entity))
        val message = parse(raw)
        assertTrue(message.contentType.startsWith("multipart/signed"))
        assertTrue(message.contentType.contains("micalg=pgp-sha256"))
        assertTrue(message.contentType.contains("protocol=\"application/pgp-signature\""))
        assertEquals("Réunion ☀ confidentielle", message.subject)
        assertEquals("<fixed@example.org>", message.messageID)
        assertEquals("1.0", message.getHeader("MIME-Version", null))
        assertEquals("<parent@example.org>", message.getHeader("In-Reply-To", null))
        val parts = message.content as MimeMultipart
        assertEquals(2, parts.count)
        assertTrue(parts.getBodyPart(1).contentType.startsWith("application/pgp-signature"))

        // The reader keeps the signed entity byte for byte: it must equal what we sent to be signed.
        val dir = tmp.newFolder()
        val body = Mime.cache(parse(raw), dir)
        val signed = java.io.File(dir, Pgp.SIGNED_FILE).readBytes()
        assertArrayEquals(entity, signed)
        val signature = java.io.File(dir, Pgp.SIGNATURE_FILE).readText()
        assertTrue(signature.contains("BEGIN PGP SIGNATURE"))
        assertTrue(body.text!!.readText().contains("Bonjour Bob ✓"))
        assertEquals(listOf("notes é.txt"), body.attachments.map { it.name })
    }

    @Test fun `encrypted message has the RFC 3156 structure`() {
        val entity = PgpMime.entity(mail)
        val raw = PgpMime.assembleEncrypted(mail, fakeEncrypt(entity))
        val message = parse(raw)
        assertTrue(message.contentType.startsWith("multipart/encrypted"))
        assertTrue(message.contentType.contains("protocol=\"application/pgp-encrypted\""))
        val parts = message.content as MimeMultipart
        assertEquals(2, parts.count)
        assertTrue(parts.getBodyPart(0).contentType.startsWith("application/pgp-encrypted"))
        assertEquals("Version: 1", String(parts.getBodyPart(0).inputStream.readBytes()).trim())
        assertTrue(parts.getBodyPart(1).contentType.startsWith("application/octet-stream"))
        assertTrue(parts.getBodyPart(1).fileName == "encrypted.asc")
        // Nothing readable leaks outside the ciphertext.
        val wire = String(raw, Charsets.ISO_8859_1)
        assertFalse(wire.contains("Bonjour")); assertFalse(wire.contains("attachment body")); assertFalse(wire.contains("notes"))
    }

    @Test fun `encrypted message round-trips through the read side parser`() {
        val entity = PgpMime.entity(mail)
        val raw = PgpMime.assembleEncrypted(mail, fakeEncrypt(entity))
        val dir = tmp.newFolder()
        val body = Mime.cache(parse(raw), dir)
        assertNull(body.text); assertTrue(body.attachments.isEmpty()) // only the ciphertext is kept
        val ciphertext = java.io.File(dir, Pgp.ENCRYPTED_FILE).readBytes()
        val decrypted = fakeDecrypt(ciphertext)
        assertArrayEquals(entity, decrypted)
        val parsed = Pgp.parseDecrypted(decrypted, mime = true)
        assertEquals("Bonjour Bob ✓\nSecond line", parsed.text!!.trim().replace("\r\n", "\n"))
        assertEquals("<p>Bonjour <b>Bob</b> ✓</p>", parsed.html!!.trim())
        assertEquals(listOf("notes é.txt"), parsed.attachments.map { it.name })
        assertEquals("attachment body", String(parsed.attachments.single().bytes))
    }

    @Test fun `plain text only mail also works and boundaries never collide with content`() {
        val simple = mail.copy(html = null, attachments = emptyList(), text = "--=_dotmail-fake\nplain")
        val entity = PgpMime.entity(simple)
        assertTrue(String(entity).startsWith("Content-Type: text/plain"))
        val raw = PgpMime.assembleSigned(simple, entity, fakeSignature(entity))
        val dir = tmp.newFolder()
        Mime.cache(parse(raw), dir)
        assertArrayEquals(entity, java.io.File(dir, Pgp.SIGNED_FILE).readBytes())
    }
}
