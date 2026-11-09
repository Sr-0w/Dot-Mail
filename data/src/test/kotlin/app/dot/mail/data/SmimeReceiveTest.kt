package app.dot.mail.data

import app.dot.mail.data.model.EmailAddress
import app.dot.mail.data.model.Message
import app.dot.mail.data.model.MessageBody
import app.dot.mail.data.model.SmimeContent
import app.dot.mail.data.model.SmimeSignatureView.State
import app.dot.mail.engine.Address
import app.dot.mail.engine.Mime
import app.dot.mail.engine.OutgoingAttachment
import app.dot.mail.engine.OutgoingMail
import app.dot.mail.engine.SmimeCertificatePolicy
import app.dot.mail.engine.SmimeCertificateUse
import app.dot.mail.engine.SmimeCertificateVerdict
import app.dot.mail.engine.SmimeEngine
import app.dot.mail.engine.SmimeMode
import app.dot.mail.engine.SmimeParts
import app.dot.mail.engine.SmimeSigningIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

/** A received S/MIME signed message, end to end: MIME caching → files next to the body → verdict for the reader. */
class SmimeReceiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cert(name: String): X509Certificate = javaClass.classLoader!!.getResourceAsStream("smime/$name.crt.pem")!!.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
    private fun key(name: String): PrivateKey {
        val pem = javaClass.classLoader!!.getResourceAsStream("smime/$name.key.pem")!!.bufferedReader().readText()
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))))
    }
    private fun policy(verdict: SmimeCertificateVerdict) = object : SmimeCertificatePolicy {
        override fun validate(certificate: X509Certificate, mailbox: String, use: SmimeCertificateUse) = verdict
        override fun validateChain(certificates: Collection<X509Certificate>, mailbox: String, use: SmimeCertificateUse) = verdict
    }
    private val trusted = policy(SmimeCertificateVerdict.Trusted)

    /** Alice signs a message with an attachment; it is cached the way sync caches a fetched body. */
    private fun receive(): Pair<File, List<app.dot.mail.engine.CachedPart>> {
        val mail = OutgoingMail(
            from = Address("alice@example.org", "Alice"), to = listOf(Address("bob@example.org")),
            subject = "Signed", text = "Hello Bob,\r\nthis is signed.\r\n",
            attachments = listOf(OutgoingAttachment("notes.txt", "text/plain", "attached".toByteArray())),
        )
        val signed = SmimeEngine(certificatePolicy = trusted).build(mail, SmimeMode.SIGN, SmimeSigningIdentity(cert("id-alice"), key("id-alice"))).first()
        val dir = tmp.newFolder()
        val cached = Mime.cache(jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()), signed.mimeBytes.inputStream()), dir)
        return dir to cached.attachments
    }

    private fun message(dir: File, from: String = "alice@example.org") = Message(
        id = 1, accountId = 1, folderId = 1, threadId = 1, from = EmailAddress(from), to = listOf(EmailAddress("bob@example.org")),
        cc = emptyList(), bcc = emptyList(), replyTo = emptyList(), subject = "Signed", sentAt = 0, snippet = "", isUnread = false,
        isStarred = false, attachments = emptyList(), messageIdHeader = null, references = emptyList(), unsubscribe = null,
        body = MessageBody("Hello", null, false, smime = SmimeContent.Signed(File(dir, SmimeParts.SIGNED_FILE).path, File(dir, SmimeParts.SIGNATURE_FILE).path)),
    )

    @Test fun `signature part is kept for verification and never listed as an attachment`() {
        val (dir, attachments) = receive()
        assertTrue(File(dir, SmimeParts.SIGNED_FILE).isFile)
        assertTrue(File(dir, SmimeParts.SIGNATURE_FILE).isFile)
        assertEquals(listOf("notes.txt"), attachments.map { it.name })
        assertFalse(attachments.any { SmimeParts.isSignaturePart(it.mimeType) })
    }

    @Test fun `intact signature from a trusted certificate for the sender`() = runBlocking {
        val (dir, _) = receive()
        val view = verifySmimeSignature(message(dir), trusted, null)!!
        assertEquals(State.VALID_TRUSTED, view.state)
        assertEquals(setOf("alice@example.org"), view.signer!!.mailboxes)
    }

    @Test fun `intact signature but untrusted certificate is not shown as trusted`() = runBlocking {
        val (dir, _) = receive()
        assertEquals(State.VALID_UNTRUSTED, verifySmimeSignature(message(dir), policy(SmimeCertificateVerdict.Rejected("unknown CA")), null)!!.state)
    }

    @Test fun `certificate for another address than From is a mismatch even if trusted`() = runBlocking {
        val (dir, _) = receive()
        assertEquals(State.SENDER_MISMATCH, verifySmimeSignature(message(dir, from = "ceo@example.org"), trusted, null)!!.state)
    }

    @Test fun `content changed after signing is invalid`() = runBlocking {
        val (dir, _) = receive()
        val entity = File(dir, SmimeParts.SIGNED_FILE)
        entity.writeText(entity.readText().replace("this is signed", "this is forged"))
        assertEquals(State.INVALID, verifySmimeSignature(message(dir), trusted, null)!!.state)
    }

    @Test fun `garbage signature is unreadable, and unsigned mail has no verdict`() = runBlocking {
        val (dir, _) = receive()
        File(dir, SmimeParts.SIGNATURE_FILE).writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(State.UNREADABLE, verifySmimeSignature(message(dir), trusted, null)!!.state)
        assertNull(verifySmimeSignature(message(dir).copy(body = MessageBody("x", null, false)), trusted, null))
    }
}
