package app.dot.mail.data

import app.dot.mail.data.model.Attachment
import app.dot.mail.data.model.EmailAddress
import app.dot.mail.data.model.Message
import app.dot.mail.data.model.MessageBody
import app.dot.mail.data.model.SmimeContent
import app.dot.mail.data.model.SmimeDecryption
import app.dot.mail.engine.Address
import app.dot.mail.engine.Mime
import app.dot.mail.engine.OutgoingAttachment
import app.dot.mail.engine.OutgoingMail
import app.dot.mail.engine.Pgp
import app.dot.mail.engine.SmimeCertificatePolicy
import app.dot.mail.engine.SmimeCertificateUse
import app.dot.mail.engine.SmimeCertificateVerdict
import app.dot.mail.engine.SmimeDecryptionIdentity
import app.dot.mail.engine.SmimeEngine
import app.dot.mail.engine.SmimeMode
import app.dot.mail.engine.SmimeParts
import app.dot.mail.engine.SmimeRecipientCertificate
import app.dot.mail.engine.SmimeSigningIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Attachments inside decrypted mail: listed from memory, never cached, written only on demand and removed with the reader. */
class DecryptedAttachmentsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cert(name: String): X509Certificate = javaClass.classLoader!!.getResourceAsStream("smime/$name.crt.pem")!!.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
    private fun key(name: String): PrivateKey {
        val pem = javaClass.classLoader!!.getResourceAsStream("smime/$name.key.pem")!!.bufferedReader().readText()
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))))
    }
    private val trusted = object : SmimeCertificatePolicy {
        override fun validate(certificate: X509Certificate, mailbox: String, use: SmimeCertificateUse) = SmimeCertificateVerdict.Trusted
        override fun validateChain(certificates: Collection<X509Certificate>, mailbox: String, use: SmimeCertificateUse) = SmimeCertificateVerdict.Trusted
    }
    private val pdf = ByteArray(2048) { (it % 251).toByte() }
    private val png = ByteArray(300) { (it * 7).toByte() }

    private fun mailWithAttachments() = OutgoingMail(
        from = Address("alice@example.org", "Alice"), to = listOf(Address("bob@example.org")), subject = "Secret with files",
        text = "see attached\r\n", html = "<p>logo: <img src=\"cid:logo1\"></p>",
        attachments = listOf(OutgoingAttachment("report.pdf", "application/pdf", pdf), OutgoingAttachment("logo.png", "image/png", png, contentId = "logo1")),
    )

    private fun receiveEncrypted(): File {
        val variant = SmimeEngine(certificatePolicy = trusted).build(
            mailWithAttachments(), SmimeMode.SIGN_AND_ENCRYPT, SmimeSigningIdentity(cert("id-alice"), key("id-alice")),
            listOf(SmimeRecipientCertificate("bob@example.org", cert("id-bob"))), cert("id-alice"),
        ).single()
        val dir = tmp.newFolder()
        Mime.cache(jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()), variant.mimeBytes.inputStream()), dir)
        return dir
    }

    private fun encryptedMessage(dir: File) = Message(
        id = 7, accountId = 1, folderId = 1, threadId = 1, from = EmailAddress("alice@example.org"), to = listOf(EmailAddress("bob@example.org")),
        cc = emptyList(), bcc = emptyList(), replyTo = emptyList(), subject = "Secret", sentAt = 0, snippet = "", isUnread = false,
        isStarred = false, attachments = emptyList(), messageIdHeader = null, references = emptyList(), unsubscribe = null,
        body = MessageBody(null, null, false, smime = SmimeContent.Encrypted(File(dir, SmimeParts.ENCRYPTED_FILE).path)),
    )

    @Test fun `decrypted S MIME mail lists its attachments with name, size and type and persists nothing`() = runBlocking {
        val dir = receiveEncrypted()
        val before = dir.list()!!.toSet()
        val result = decryptSmimeMessage(encryptedMessage(dir), { SmimeDecryptionIdentity(cert("id-bob"), key("id-bob")) }, trusted, null) as SmimeDecryption.Done
        val files = result.body.decryptedAttachments
        assertEquals(setOf("report.pdf", "logo.png"), files.map { it.fileName }.toSet()) // the inner smime.p7s is not a file
        val report = files.single { it.fileName == "report.pdf" }
        assertEquals("application/pdf", report.mimeType); assertEquals(2048L, report.sizeBytes)
        assertArrayEquals(pdf, report.memory!!.bytes)
        assertTrue(report.id < 0 && files.map { it.id }.distinct().size == files.size) // never a database id
        val logo = files.single { it.fileName == "logo.png" }
        assertEquals("logo1", logo.contentId); assertEquals("image/png", logo.mimeType); assertEquals(300L, logo.sizeBytes)
        assertTrue(logo.localUri == null && report.localUri == null) // nothing was written anywhere
        assertEquals(before, dir.list()!!.toSet()) // the cache directory still holds only the envelope
    }

    @Test fun `decrypted OpenPGP style MIME lists its attachments too`() {
        val entity = Mime.build(mailWithAttachments().copy(messageId = "<x@example.org>"))
        val out = java.io.ByteArrayOutputStream().also { entity.writeTo(it) }
        val parsed = Pgp.parseDecrypted(out.toByteArray(), mime = true)
        val files = parsed.toDecryptedAttachments(messageId = 3)
        assertEquals(setOf("report.pdf", "logo.png"), files.map { it.fileName }.toSet())
        assertTrue(files.all { it.messageId == 3L && it.memory != null && it.sizeBytes == it.memory!!.bytes.size.toLong() })
        assertTrue(Pgp.parseDecrypted("inline pgp text".toByteArray(), mime = false).toDecryptedAttachments(3).isEmpty())
    }

    private fun attachment(name: String = "a.pdf", bytes: ByteArray = pdf) = parsedAttachment(name, bytes)
    private fun parsedAttachment(name: String, bytes: ByteArray) = app.dot.mail.engine.ParsedBody(null, null, listOf(app.dot.mail.engine.MimeAttachment("1", name, "application/pdf", null, false, bytes))).toDecryptedAttachments(1).single()

    @Test fun `a file is written only on demand, under the reader's token, and removed with it`() {
        val root = tmp.newFolder()
        val files = DecryptedFiles(root) { "content://test/${it.relativeTo(root).path}" }
        val uri = files.write("reader-1", attachment("my/evil..name.pdf")) { it.replace('/', '_') }
        val file = File(root, uri.removePrefix("content://test/"))
        assertArrayEquals(pdf, file.readBytes())
        assertTrue(file.path.contains("attachments/decrypted-open/reader-1/"))
        assertFalse(file.name.contains('/'))
        val other = files.write("reader-2", attachment("b.pdf")) { it }
        files.clear("reader-1")
        assertFalse(file.exists())
        assertTrue(File(root, other.removePrefix("content://test/")).exists()) // another reader's files are untouched
        files.clearAll()
        assertFalse(File(root, DecryptedFiles.ROOT).exists())
    }

    @Test fun `only attachments of a decrypted message can be written`() {
        val files = DecryptedFiles(tmp.newFolder()) { it.path }
        val ordinary = Attachment(5, 1, "x.pdf", "application/pdf", 1, null, false, null)
        val failure = runCatching { files.write("t", ordinary) { it } }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is IllegalArgumentException)
    }
}
