package app.dot.mail.engine

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Properties

class PgpMimeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun message(raw: String) = MimeMessage(Session.getInstance(Properties()), raw.replace("\n", "\r\n").byteInputStream())

    private val signedEntity = "Content-Type: text/plain; charset=utf-8\n" +
        "Content-Transfer-Encoding: quoted-printable\n" +
        "\n" +
        "Hello, signed world =E2=9C=93\n"

    @Test fun `signed PGP-MIME keeps the exact signed entity and the signature, and still shows the text`() {
        val raw = """
            |From: a@x.org
            |Subject: s
            |MIME-Version: 1.0
            |Content-Type: multipart/signed; micalg=pgp-sha256; protocol="application/pgp-signature"; boundary="b1"
            |
            |--b1
            |${signedEntity.trimEnd('\n')}
            |--b1
            |Content-Type: application/pgp-signature; name="signature.asc"
            |
            |-----BEGIN PGP SIGNATURE-----
            |abc
            |-----END PGP SIGNATURE-----
            |--b1--
            |""".trimMargin()
        val dir = tmp.newFolder()
        val body = Mime.cache(message(raw), dir)
        val signed = java.io.File(dir, Pgp.SIGNED_FILE).readText()
        // RFC 3156: the signed bytes are the entity exactly as transmitted, with CRLF line endings.
        assertEquals(signedEntity.trimEnd('\n').replace("\n", "\r\n") + "\r\n", signed + if (signed.endsWith("\r\n")) "" else "\r\n")
        assertTrue(java.io.File(dir, Pgp.SIGNATURE_FILE).readText().contains("BEGIN PGP SIGNATURE"))
        assertTrue(body.text!!.readText().contains("Hello, signed world ✓"))
        assertTrue(body.attachments.none { it.mimeType == "application/pgp-signature" }) // verified, not listed as a file
    }

    @Test fun `encrypted PGP-MIME keeps only the ciphertext`() {
        val raw = """
            |From: a@x.org
            |Subject: e
            |MIME-Version: 1.0
            |Content-Type: multipart/encrypted; protocol="application/pgp-encrypted"; boundary="b2"
            |
            |--b2
            |Content-Type: application/pgp-encrypted
            |
            |Version: 1
            |--b2
            |Content-Type: application/octet-stream; name="encrypted.asc"
            |
            |-----BEGIN PGP MESSAGE-----
            |xyz
            |-----END PGP MESSAGE-----
            |--b2--
            |""".trimMargin()
        val dir = tmp.newFolder()
        val body = Mime.cache(message(raw), dir)
        assertTrue(java.io.File(dir, Pgp.ENCRYPTED_FILE).readText().contains("BEGIN PGP MESSAGE"))
        assertTrue(body.attachments.isEmpty())
        assertNull(body.text)
        assertFalse(java.io.File(dir, Pgp.SIGNED_FILE).exists())
    }

    @Test fun `inline armour is detected only at line starts`() {
        assertEquals(Pgp.Inline.ENCRYPTED, Pgp.inline("hi\n-----BEGIN PGP MESSAGE-----\nabc"))
        assertEquals(Pgp.Inline.SIGNED, Pgp.inline("-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256"))
        assertNull(Pgp.inline("quote: -----BEGIN PGP MESSAGE----- inside text"))
        assertNull(Pgp.inline(null))
    }

    @Test fun `decrypted MIME entity becomes text and html`() {
        val entity = "Content-Type: multipart/alternative; boundary=\"x\"\r\n\r\n--x\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nSecret ✓\r\n--x\r\nContent-Type: text/html; charset=utf-8\r\n\r\n<p>Secret ✓</p>\r\n--x--\r\n"
        val parsed = Pgp.parseDecrypted(entity.toByteArray(), mime = true)
        assertTrue(parsed.text!!.contains("Secret ✓"))
        assertTrue(parsed.html!!.contains("<p>Secret ✓</p>"))
        assertEquals("plain words", Pgp.parseDecrypted("plain words".toByteArray(), mime = false).text)
    }
}
