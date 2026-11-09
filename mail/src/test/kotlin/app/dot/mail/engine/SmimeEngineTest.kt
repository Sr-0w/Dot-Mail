package app.dot.mail.engine

import jakarta.mail.Session
import jakarta.mail.Multipart
import jakarta.mail.internet.MimeMessage
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import java.io.ByteArrayOutputStream

class SmimeEngineTest {
    private data class Identity(val email: String, val pair: KeyPair, val certificate: X509Certificate) {
        fun signing() = SmimeSigningIdentity(certificate, pair.private)
        fun decrypting() = SmimeDecryptionIdentity(certificate, pair.private)
        fun recipient() = SmimeRecipientCertificate(email, certificate)
    }

    private val serial = AtomicLong()
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val fixturePolicy = object : SmimeCertificatePolicy {
        override fun validate(certificate: X509Certificate, mailbox: String, use: SmimeCertificateUse): SmimeCertificateVerdict {
            val san = certificate.subjectAlternativeNames.orEmpty().firstOrNull { it.size >= 2 && it[0] == 1 }?.get(1) as? String
            val keyUsage = certificate.keyUsage
            val usageOk = when (use) {
                SmimeCertificateUse.SIGNING -> keyUsage?.getOrNull(0) == true
                SmimeCertificateUse.ENCRYPTION -> keyUsage?.getOrNull(2) == true
            }
            return if (san.equals(mailbox, true) && certificate.notBefore.before(Date.from(now)) && certificate.notAfter.after(Date.from(now)) && usageOk) {
                SmimeCertificateVerdict.Trusted
            } else SmimeCertificateVerdict.Rejected("fixture policy mismatch")
        }

        override fun validateChain(
            certificates: Collection<X509Certificate>,
            mailbox: String,
            use: SmimeCertificateUse,
        ): SmimeCertificateVerdict = certificates.firstOrNull()?.let { validate(it, mailbox, use) }
            ?: SmimeCertificateVerdict.Rejected("empty fixture chain")
    }
    private val engine = SmimeEngine(certificatePolicy = fixturePolicy)

    private fun identity(email: String, signing: Boolean = true, encryption: Boolean = true): Identity {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = generator.generateKeyPair()
        val subject = X500Name("CN=$email")
        val start = Date.from(now.minusSeconds(86_400))
        val end = Date.from(now.plusSeconds(365L * 86_400))
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(serial.incrementAndGet()),
            start,
            end,
            subject,
            pair.public,
        )
        val usage = (if (signing) KeyUsage.digitalSignature else 0) or (if (encryption) KeyUsage.keyEncipherment else 0)
        builder.addExtension(Extension.keyUsage, true, KeyUsage(usage))
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_emailProtection))
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.rfc822Name, email)))
        val certificate = JcaX509CertificateConverter()
            .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            .getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)))
        certificate.verify(pair.public)
        return Identity(email, pair, certificate)
    }

    private fun mail() = OutgoingMail(
        from = Address("sender@example.test", "Sender"),
        to = listOf(Address("alice@example.test")),
        cc = listOf(Address("bob@example.test")),
        bcc = listOf(Address("hidden@example.test")),
        subject = "S/MIME fixture",
        text = "plain body ✓",
        html = "<p>html body ✓</p>",
        attachments = listOf(OutgoingAttachment("proof.txt", "text/plain", "attachment".toByteArray())),
        messageId = "<smime-fixture@example.test>",
    )

    private fun parse(bytes: ByteArray) = MimeMessage(Session.getInstance(Properties()), bytes.inputStream())

    @Test fun `sign uses detached CMS and never emits Bcc header`() {
        val signer = identity("sender@example.test")
        val message = mail()
        val variants = engine.build(message, SmimeMode.SIGN, signer = signer.signing())
        assertEquals(3, variants.size)
        val variant = variants.first()
        val parsed = parse(variant.mimeBytes)
        assertEquals("alice@example.test", variant.envelopeRecipient)
        assertFalse(parsed.getHeader("Bcc").orEmpty().isNotEmpty())
        assertTrue(parsed.contentType.startsWith("multipart/signed", ignoreCase = true))
        val result = engine.verifySignedMime(variant.mimeBytes, expectedSignerMailbox = "sender@example.test")
        assertTrue(result.allCryptographicallyValid)
        assertTrue(result.allTrustedAndValid)
        assertEquals(SmimeCertificateVerdict.Trusted, result.signers.single().certificateVerdict)
    }

    @Test fun `verification preserves raw folded MIME entity bytes`() {
        val signer = identity("sender@example.test")
        val entity = (
            "Content-Type: text/plain;\r\n charset=UTF-8\r\n" +
                "Content-Transfer-Encoding: 8bit\r\n\r\ninterop body"
            ).toByteArray(Charsets.ISO_8859_1)
        val signature = BouncyCastleSmimeCms().signDetached(entity, signer.signing())
        val signedMime = SmimeMime.signedMessage(
            mail().copy(bcc = emptyList()),
            SmimeMime.signedEntity(entity, signature),
        )
        val verified = engine.verifySignedMime(signedMime, "sender@example.test")
        assertTrue(verified.allTrustedAndValid)
    }

    @Test fun `tampering with signed MIME entity makes signature invalid`() {
        val signer = identity("sender@example.test")
        val message = mail().copy(bcc = emptyList())
        val variant = engine.build(message, SmimeMode.SIGN, signer = signer.signing()).first()
        val (entity, signature) = signedParts(parse(variant.mimeBytes))
        val tampered = entity.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val result = engine.verifyDetached(tampered, signature, expectedSignerMailbox = "sender@example.test")
        assertFalse(result.allCryptographicallyValid)
        assertEquals(null, result.signers.single().certificateVerdict)

        val wrongIdentity = engine.verifyDetached(entity, signature, expectedSignerMailbox = "someone-else@example.test")
        assertTrue(wrongIdentity.allCryptographicallyValid)
        assertFalse(wrongIdentity.allTrustedAndValid)
        assertTrue(wrongIdentity.signers.single().certificateVerdict is SmimeCertificateVerdict.Rejected)
    }

    @Test fun `encryption produces recipient-isolated envelopes and decrypts the requested entity`() {
        val alice = identity("alice@example.test", signing = false)
        val bob = identity("bob@example.test", signing = false)
        val hidden = identity("hidden@example.test", signing = false)
        val sender = identity("sender@example.test", signing = false)
        val message = mail()
        val variants = engine.build(
            message,
            SmimeMode.ENCRYPT,
            recipientCertificates = listOf(alice.recipient(), bob.recipient(), hidden.recipient()),
            senderDecryptionCertificate = sender.certificate,
        )
        assertEquals(listOf("alice@example.test", "bob@example.test", "hidden@example.test"), variants.map { it.envelopeRecipient })
        val aliceMessage = parse(variants.first().mimeBytes)
        assertTrue(aliceMessage.getHeader("Bcc").orEmpty().isEmpty())
        assertTrue(aliceMessage.contentType.startsWith("application/pkcs7-mime", ignoreCase = true))
        val decrypted = engine.decryptMime(variants.first().mimeBytes, alice.decrypting())
        val rendered = Pgp.parseDecrypted(decrypted, mime = true)
        assertTrue(rendered.text.orEmpty().contains("plain body ✓"))
        assertTrue(rendered.html.orEmpty().contains("html body ✓"))
        assertTrue(rendered.attachments.any { it.name == "proof.txt" })
        assertFalse(runCatching { engine.decryptMime(variants.first().mimeBytes, bob.decrypting()) }.isSuccess)
    }

    @Test fun `sign then encrypt decrypts to a verifiable detached signed MIME entity`() {
        val sender = identity("sender@example.test")
        val recipient = identity("alice@example.test", signing = false)
        val cc = identity("bob@example.test", signing = false)
        val bcc = identity("hidden@example.test", signing = false)
        val variant = engine.build(
            mail(),
            SmimeMode.SIGN_AND_ENCRYPT,
            signer = sender.signing(),
            recipientCertificates = listOf(recipient.recipient(), cc.recipient(), bcc.recipient()),
        ).first()
        val decryptedSignedMime = engine.decryptMime(variant.mimeBytes, recipient.decrypting())
        val signed = parse(decryptedSignedMime)
        assertTrue(signed.contentType.startsWith("multipart/signed", ignoreCase = true))
        assertTrue(engine.verifySignedMime(decryptedSignedMime, expectedSignerMailbox = "sender@example.test").allTrustedAndValid)
    }

    @Test fun `missing recipient certificate, unknown revocation and invalid signing material fail closed`() {
        val mail = mail()
        val noCert = runCatching { engine.build(mail, SmimeMode.ENCRYPT) }.exceptionOrNull() as SmimeProtectionException
        assertEquals(SmimeFailure.RECIPIENT_CERTIFICATE_MISSING, noCert.failure)

        val recipient = identity("alice@example.test", signing = false)
        val unknownRevocation = SmimeEngine(certificatePolicy = object : SmimeCertificatePolicy {
            override fun validate(certificate: X509Certificate, mailbox: String, use: SmimeCertificateUse) =
                SmimeCertificateVerdict.RevocationUnknown
            override fun validateChain(certificates: Collection<X509Certificate>, mailbox: String, use: SmimeCertificateUse) =
                SmimeCertificateVerdict.RevocationUnknown
        })
        val unknown = runCatching {
            unknownRevocation.build(mail, SmimeMode.ENCRYPT, recipientCertificates = listOf(recipient.recipient()))
        }.exceptionOrNull() as SmimeProtectionException
        assertEquals(SmimeFailure.RECIPIENT_REVOCATION_UNKNOWN, unknown.failure)

        val badSigner = identity("sender@example.test", signing = false, encryption = true)
        val rejected = runCatching { engine.build(mail, SmimeMode.SIGN, signer = badSigner.signing()) }.exceptionOrNull() as SmimeProtectionException
        assertEquals(SmimeFailure.SIGNER_CERTIFICATE_REJECTED, rejected.failure)
    }

    private fun signedParts(message: MimeMessage): Pair<ByteArray, ByteArray> {
        val multipart = message.content as Multipart
        val content = ByteArrayOutputStream().also { multipart.getBodyPart(0).writeTo(it) }.toByteArray()
        val entity = if (content.size >= 2 && content[content.lastIndex - 1] == 13.toByte() && content.last() == 10.toByte()) {
            content.copyOf(content.size - 2)
        } else content
        return entity to multipart.getBodyPart(1).inputStream.use { it.readBytes() }
    }

}
