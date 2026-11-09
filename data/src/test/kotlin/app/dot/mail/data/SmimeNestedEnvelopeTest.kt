package app.dot.mail.data

import app.dot.mail.engine.Mime
import app.dot.mail.engine.SmimeParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/** An enveloped-data part is "this message's ciphertext" only when it is the signed content of a wrapper, never when it merely travels along. */
class SmimeNestedEnvelopeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val session = jakarta.mail.Session.getInstance(java.util.Properties())
    private fun resource(path: String) = SmimeNestedEnvelopeTest::class.java.classLoader!!.getResourceAsStream("smime-interop/$path")!!.use { it.readBytes() }

    /** The base64 of a real CMS EnvelopedData (made for Bob), as it would sit in a forwarded smime.p7m. */
    private val envelopeBase64: String by lazy {
        val cms = jakarta.mail.internet.MimeMessage(session, resource("cases/enc-rsa-pkcs1-aes256.eml").inputStream()).inputStream.use { it.readBytes() }
        Base64.getMimeEncoder(64, "\r\n".toByteArray()).encodeToString(cms)
    }

    private fun p7mPart() = "Content-Type: application/pkcs7-mime; smime-type=enveloped-data; name=\"smime.p7m\"\r\nContent-Transfer-Encoding: base64\r\nContent-Disposition: attachment; filename=\"smime.p7m\"\r\n\r\n$envelopeBase64\r\n"

    private fun cache(raw: String): Pair<File, app.dot.mail.engine.CachedBody> {
        val dir = tmp.newFolder()
        return dir to Mime.cache(jakarta.mail.internet.MimeMessage(session, raw.toByteArray(Charsets.ISO_8859_1).inputStream()), dir)
    }

    @Test fun `a forwarded smime p7m in a multipart mixed message stays an ordinary attachment`() {
        val (dir, cached) = cache(
            "From: Mallory <mallory@example.test>\r\nTo: Bob <bob-rsa@example.test>\r\nSubject: fwd\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"b\"\r\n\r\n" +
                "--b\r\nContent-Type: text/plain\r\n\r\nplease see the attached file\r\n--b\r\n${p7mPart()}--b--\r\n",
        )
        assertFalse("the forwarded envelope must not be taken for this message's ciphertext", File(dir, SmimeParts.ENCRYPTED_FILE).exists())
        assertEquals(listOf("smime.p7m"), cached.attachments.map { it.name })
        assertTrue(cached.text!!.readText().contains("please see the attached file"))
    }

    @Test fun `an envelope next to other parts inside a signed entity is an attachment too`() {
        // multipart/signed whose signed entity is a multipart/mixed carrying the .p7m: the entity is NOT the envelope itself.
        val inner = "Content-Type: multipart/mixed; boundary=\"i\"\r\n\r\n--i\r\nContent-Type: text/plain\r\n\r\nsigned text\r\n--i\r\n${p7mPart()}--i--\r\n"
        val signature = Base64.getMimeEncoder(64, "\r\n".toByteArray()).encodeToString(ByteArray(64) { it.toByte() })
        val (dir, cached) = cache(
            "From: Mallory <mallory@example.test>\r\nTo: Bob <bob-rsa@example.test>\r\nSubject: fwd signed\r\nMIME-Version: 1.0\r\n" +
                "Content-Type: multipart/signed; protocol=\"application/pkcs7-signature\"; micalg=sha-256; boundary=\"o\"\r\n\r\n" +
                "--o\r\n$inner\r\n--o\r\nContent-Type: application/pkcs7-signature; name=\"smime.p7s\"\r\nContent-Transfer-Encoding: base64\r\n\r\n$signature\r\n--o--\r\n",
        )
        assertFalse(File(dir, SmimeParts.ENCRYPTED_FILE).exists())
        assertEquals(listOf("smime.p7m"), cached.attachments.map { it.name })
    }

    @Test fun `an envelope that is exactly the signed entity is the message's own ciphertext`() {
        val signature = Base64.getMimeEncoder(64, "\r\n".toByteArray()).encodeToString(ByteArray(64) { it.toByte() })
        val (dir, cached) = cache(
            "From: Alice <alice-rsa@example.test>\r\nTo: Bob <bob-rsa@example.test>\r\nSubject: wrapped\r\nMIME-Version: 1.0\r\n" +
                "Content-Type: multipart/signed; protocol=\"application/pkcs7-signature\"; micalg=sha-256; boundary=\"o\"\r\n\r\n" +
                "--o\r\n${p7mPart()}\r\n--o\r\nContent-Type: application/pkcs7-signature; name=\"smime.p7s\"\r\nContent-Transfer-Encoding: base64\r\n\r\n$signature\r\n--o--\r\n",
        )
        assertTrue(File(dir, SmimeParts.ENCRYPTED_FILE).isFile)
        assertTrue(File(dir, SmimeParts.SIGNED_FILE).isFile)
        assertTrue(cached.attachments.isEmpty())
    }
}
