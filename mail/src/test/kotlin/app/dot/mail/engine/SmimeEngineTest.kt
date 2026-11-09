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
import org.bouncycastle.cms.CMSAlgorithm
import org.bouncycastle.cms.CMSAuthEnvelopedData
import org.bouncycastle.cms.CMSAuthEnvelopedDataGenerator
import org.bouncycastle.cms.CMSEnvelopedData
import org.bouncycastle.cms.CMSEnvelopedDataGenerator
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator
import org.bouncycastle.jce.provider.BouncyCastleProvider
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

    private fun identity(
        email: String,
        signing: Boolean = true,
        encryption: Boolean = true,
        validityDays: Long = 365,
        eku: KeyPurposeId = KeyPurposeId.id_kp_emailProtection,
    ): Identity {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = generator.generateKeyPair()
        val subject = X500Name("CN=$email")
        val start = Date.from(now.minusSeconds(86_400))
        val end = Date.from(now.plusSeconds(validityDays * 86_400))
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
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(eku))
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

    @Test fun `detached MIME parsing tolerates transport padding on boundary delimiters`() {
        val signer = identity("sender@example.test")
        val message = engine.build(mail().copy(bcc = emptyList()), SmimeMode.SIGN, signer = signer.signing()).first().mimeBytes
        val parsed = parse(message)
        val boundary = jakarta.mail.internet.ContentType(parsed.contentType).getParameter("boundary")
        val padded = String(message, Charsets.ISO_8859_1)
            .replace("--$boundary--\r\n", "--$boundary-- \t\r\n")
            .replace("--$boundary\r\n", "--$boundary \t\r\n")
            .toByteArray(Charsets.ISO_8859_1)
        assertTrue(engine.verifySignedMime(padded, "sender@example.test").allTrustedAndValid)
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
        assertEquals(CMSAlgorithm.AES256_CBC.id, CMSEnvelopedData(aliceMessage.inputStream).encryptionAlgOID)
        val decrypted = engine.decryptMime(variants.first().mimeBytes, alice.decrypting())
        val rendered = Pgp.parseDecrypted(decrypted, mime = true)
        assertTrue(rendered.text.orEmpty().contains("plain body ✓"))
        assertTrue(rendered.html.orEmpty().contains("html body ✓"))
        assertTrue(rendered.attachments.any { it.name == "proof.txt" })
        val sentCopy = Pgp.parseDecrypted(engine.decryptMime(variants.first().mimeBytes, sender.decrypting()), mime = true)
        assertTrue("Sender's own KeyChain identity must decrypt the sent copy", sentCopy.text.orEmpty().contains("plain body ✓"))
        assertFalse(runCatching { engine.decryptMime(variants.first().mimeBytes, bob.decrypting()) }.isSuccess)
    }

    @Test fun `receives AES CBC EnvelopedData with 128 192 and 256 bit keys`() {
        val recipient = identity("alice@example.test", signing = false)
        val content = "CBC S/MIME interop".toByteArray()
        listOf(CMSAlgorithm.AES128_CBC, CMSAlgorithm.AES192_CBC, CMSAlgorithm.AES256_CBC).forEach { algorithm ->
            val cms = encryptCbc(content, recipient.certificate, algorithm)
            val mime = SmimeMime.envelopedMessage(mail().copy(bcc = emptyList()), cms)
            assertArrayEquals("CBC algorithm ${algorithm.id}", content, engine.decryptMime(mime, recipient.decrypting()))
        }
    }

    @Test fun `receives AES GCM AuthEnvelopedData with all supported key sizes and MIME type`() {
        val recipient = identity("alice@example.test", signing = false)
        val content = "Authenticated S/MIME".toByteArray()
        listOf(CMSAlgorithm.AES128_GCM, CMSAlgorithm.AES192_GCM, CMSAlgorithm.AES256_GCM).forEach { algorithm ->
            val cms = encryptAuthEnveloped(content, recipient.certificate, algorithm)
            assertEquals(algorithm.id, CMSAuthEnvelopedData(cms).encryptionAlgOID)
            val mime = SmimeMime.envelopedMessage(mail().copy(bcc = emptyList()), cms, authenticated = true)
            assertTrue(parse(mime).contentType.contains("smime-type=auth-enveloped-data", ignoreCase = true))
            assertArrayEquals("GCM algorithm ${algorithm.id}", content, engine.decryptMime(mime, recipient.decrypting()))
        }
    }

    @Test fun `recipient certificate choice prefers a valid pin then newest trusted certificate`() {
        val mail = mail().copy(cc = emptyList(), bcc = emptyList())
        val sender = identity("sender@example.test", signing = false)
        val older = identity("alice@example.test", signing = false, validityDays = 120)
        val newer = identity("alice@example.test", signing = false, validityDays = 730)
        val candidates = listOf(older.recipient(), newer.recipient())
        val normal = engine.build(
            mail,
            SmimeMode.ENCRYPT,
            recipientCertificates = candidates,
            senderDecryptionCertificate = sender.certificate,
            now = Date.from(now),
        ).single()
        assertTrue(runCatching { engine.decryptMime(normal.mimeBytes, newer.decrypting()) }.isSuccess)
        assertFalse(runCatching { engine.decryptMime(normal.mimeBytes, older.decrypting()) }.isSuccess)

        val pin = java.security.MessageDigest.getInstance("SHA-256").digest(older.certificate.encoded)
            .joinToString("") { "%02X".format(it) }
        val pinned = engine.build(
            mail,
            SmimeMode.ENCRYPT,
            recipientCertificates = candidates,
            senderDecryptionCertificate = sender.certificate,
            pinnedRecipientCertificateFingerprints = mapOf("ALICE@example.test" to pin),
            now = Date.from(now),
        ).single()
        assertTrue(runCatching { engine.decryptMime(pinned.mimeBytes, older.decrypting()) }.isSuccess)
        assertFalse(runCatching { engine.decryptMime(pinned.mimeBytes, newer.decrypting()) }.isSuccess)
    }

    @Test fun `pinned ineligible certificate fails distinctly and never falls back`() {
        val mail = mail().copy(cc = emptyList(), bcc = emptyList())
        val sender = identity("sender@example.test", signing = false)
        val valid = identity("alice@example.test", signing = false, validityDays = 120)
        val pinnedWrongUsage = identity(
            "alice@example.test",
            signing = false,
            validityDays = 730,
            eku = KeyPurposeId.id_kp_serverAuth,
        )
        val pin = java.security.MessageDigest.getInstance("SHA-256").digest(pinnedWrongUsage.certificate.encoded)
            .joinToString("") { "%02X".format(it) }

        val error = runCatching {
            engine.build(
                mail,
                SmimeMode.ENCRYPT,
                recipientCertificates = listOf(valid.recipient(), pinnedWrongUsage.recipient()),
                senderDecryptionCertificate = sender.certificate,
                pinnedRecipientCertificateFingerprints = mapOf("alice@example.test" to pin),
                now = Date.from(now),
            )
        }.exceptionOrNull() as SmimeProtectionException

        assertEquals(SmimeFailure.RECIPIENT_PINNED_CERTIFICATE_UNAVAILABLE, error.failure)
    }

    @Test fun `newest certificate without emailProtection EKU is skipped`() {
        val mail = mail().copy(cc = emptyList(), bcc = emptyList())
        val sender = identity("sender@example.test", signing = false)
        val valid = identity("alice@example.test", signing = false, validityDays = 120)
        val newerServerOnly = identity(
            "alice@example.test",
            signing = false,
            validityDays = 730,
            eku = KeyPurposeId.id_kp_serverAuth,
        )

        val encrypted = engine.build(
            mail,
            SmimeMode.ENCRYPT,
            recipientCertificates = listOf(valid.recipient(), newerServerOnly.recipient()),
            senderDecryptionCertificate = sender.certificate,
            now = Date.from(now),
        ).single()

        assertTrue(runCatching { engine.decryptMime(encrypted.mimeBytes, valid.decrypting()) }.isSuccess)
        assertFalse(runCatching { engine.decryptMime(encrypted.mimeBytes, newerServerOnly.decrypting()) }.isSuccess)
    }

    @Test fun `rejects DES 3DES and RC2 EnvelopedData with a distinct weak encryption failure`() {
        val recipient = identity("alice@example.test", signing = false)
        listOf(CMSAlgorithm.DES_CBC, CMSAlgorithm.DES_EDE3_CBC, CMSAlgorithm.RC2_CBC).forEach { algorithm ->
            val cms = encryptCbc("weak".toByteArray(), recipient.certificate, algorithm)
            val mime = SmimeMime.envelopedMessage(mail().copy(bcc = emptyList()), cms)
            val error = runCatching { engine.decryptMime(mime, recipient.decrypting()) }.exceptionOrNull() as SmimeProtectionException
            assertEquals("weak algorithm ${algorithm.id}", SmimeFailure.WEAK_CONTENT_ENCRYPTION, error.failure)
        }
    }

    private fun encryptCbc(
        content: ByteArray,
        certificate: X509Certificate,
        algorithm: org.bouncycastle.asn1.ASN1ObjectIdentifier,
    ): ByteArray {
        val provider = BouncyCastleProvider()
        val generator = CMSEnvelopedDataGenerator().apply {
            addRecipientInfoGenerator(JceKeyTransRecipientInfoGenerator(certificate).setProvider(provider))
        }
        val encryptor = JceCMSContentEncryptorBuilder(algorithm).setProvider(provider).build()
        return generator.generate(CMSProcessableByteArray(content), encryptor).encoded
    }

    private fun encryptAuthEnveloped(
        content: ByteArray,
        certificate: X509Certificate,
        algorithm: org.bouncycastle.asn1.ASN1ObjectIdentifier,
    ): ByteArray {
        val provider = BouncyCastleProvider()
        val generator = CMSAuthEnvelopedDataGenerator().apply {
            addRecipientInfoGenerator(JceKeyTransRecipientInfoGenerator(certificate).setProvider(provider))
        }
        val encryptor = JceCMSContentEncryptorBuilder(algorithm).setProvider(provider).build()
        return generator.generate(
            CMSProcessableByteArray(content),
            encryptor as org.bouncycastle.operator.OutputAEADEncryptor,
        ).encoded
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
            senderDecryptionCertificate = sender.certificate,
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

        val recipientWithCert = identity("alice@example.test", signing = false)
        val ccWithCert = identity("bob@example.test", signing = false)
        val bccWithCert = identity("hidden@example.test", signing = false)
        val noSelfCopy = runCatching {
            engine.build(
                mail(), SmimeMode.ENCRYPT,
                recipientCertificates = listOf(recipientWithCert.recipient(), ccWithCert.recipient(), bccWithCert.recipient()),
            )
        }.exceptionOrNull() as SmimeProtectionException
        assertEquals(SmimeFailure.SENDER_DECRYPTION_CERTIFICATE_REQUIRED, noSelfCopy.failure)

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
