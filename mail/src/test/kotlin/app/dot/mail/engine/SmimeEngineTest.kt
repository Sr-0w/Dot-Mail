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
                SmimeCertificateUse.ENCRYPTION -> keyUsage?.getOrNull(2) == true || keyUsage?.getOrNull(4) == true // key transport or key agreement
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
        curve: String? = null,
    ): Identity {
        val generator = if (curve == null) KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        else KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec(curve)) }
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
        val usage = (if (signing) KeyUsage.digitalSignature else 0) or (if (encryption) (if (curve == null) KeyUsage.keyEncipherment else KeyUsage.keyAgreement) else 0)
        builder.addExtension(Extension.keyUsage, true, KeyUsage(usage))
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(eku))
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.rfc822Name, email)))
        val certificate = JcaX509CertificateConverter()
            .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            .getCertificate(builder.build(JcaContentSignerBuilder(if (curve == null) "SHA256withRSA" else "SHA256withECDSA").build(pair.private)))
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
        assertEquals(2, variants.size) // To+Cc together, the Bcc recipient alone
        val variant = variants.first()
        val parsed = parse(variant.mimeBytes)
        assertEquals(listOf("alice@example.test", "bob@example.test"), variant.envelopeRecipients)
        assertEquals(listOf("hidden@example.test"), variants[1].envelopeRecipients)
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
        assertEquals(listOf(listOf("alice@example.test", "bob@example.test"), listOf("hidden@example.test")), variants.map { it.envelopeRecipients })
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
        // To and Cc share one envelope; the Bcc recipient can neither open it nor be seen in it, and has their own.
        assertTrue(runCatching { engine.decryptMime(variants.first().mimeBytes, bob.decrypting()) }.isSuccess)
        assertFalse(runCatching { engine.decryptMime(variants.first().mimeBytes, hidden.decrypting()) }.isSuccess)
        assertTrue(runCatching { engine.decryptMime(variants[1].mimeBytes, hidden.decrypting()) }.isSuccess)
        assertFalse(runCatching { engine.decryptMime(variants[1].mimeBytes, alice.decrypting()) }.isSuccess)
        assertTrue(runCatching { engine.decryptMime(variants[1].mimeBytes, sender.decrypting()) }.isSuccess)
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


    // ---- Opaque signing (Outlook "opaque signing"): application/pkcs7-mime; smime-type=signed-data ----

    private fun opaque(signer: Identity, entity: ByteArray): ByteArray {
        val provider = BouncyCastleProvider()
        val generator = org.bouncycastle.cms.CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(
                org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder(org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder().setProvider(provider).build())
                    .build(JcaContentSignerBuilder("SHA256withRSA").build(signer.pair.private), signer.certificate),
            )
            addCertificates(org.bouncycastle.cert.jcajce.JcaCertStore(listOf(signer.certificate)))
        }
        return generator.generate(CMSProcessableByteArray(entity), true).encoded
    }

    private fun entity() = PgpMime.entity(mail().copy(bcc = emptyList()))

    @Test fun `an opaque signature yields the signed entity and a trusted verdict`() {
        val signer = identity("sender@example.test")
        val entity = entity()
        val result = engine.verifyOpaque(opaque(signer, entity), "sender@example.test")
        assertArrayEquals(entity, result.content)
        assertTrue(result.verification.allCryptographicallyValid)
        assertTrue(result.verification.allTrustedAndValid)
        assertEquals(signer.certificate, result.verification.signers.single().certificate)
    }

    @Test fun `a tampered opaque message is cryptographically invalid and never trusted`() {
        val signer = identity("sender@example.test")
        val entity = entity()
        val cms = opaque(signer, entity)
        val at = cms.indexOfSlice("plain body".toByteArray())
        assertTrue(at > 0)
        cms[at] = (cms[at].toInt() xor 1).toByte()
        val result = engine.verifyOpaque(cms, "sender@example.test")
        assertFalse(result.verification.allCryptographicallyValid)
        assertFalse(result.verification.allTrustedAndValid)
        assertEquals(null, result.verification.signers.single().certificateVerdict)
    }

    @Test fun `an opaque signature by a certificate for another address is valid but not trusted for that sender`() {
        val signer = identity("other@example.test")
        val result = engine.verifyOpaque(opaque(signer, entity()), "sender@example.test")
        assertTrue(result.verification.allCryptographicallyValid)
        assertFalse(result.verification.allTrustedAndValid)
        assertTrue(result.verification.signers.single().certificateVerdict is SmimeCertificateVerdict.Rejected)
    }

    @Test fun `a detached signature or garbage is not accepted as opaque`() {
        val signer = identity("sender@example.test")
        val detached = BouncyCastleSmimeCms().signDetached(entity(), signer.signing())
        assertEquals(SmimeFailure.INVALID_CMS, assertThrowsSmime { engine.verifyOpaque(detached, "sender@example.test") })
        assertEquals(SmimeFailure.INVALID_CMS, assertThrowsSmime { engine.verifyOpaque(ByteArray(40) { it.toByte() }, "sender@example.test") })
        assertEquals(SmimeFailure.INVALID_CMS, assertThrowsSmime { engine.verifyOpaque(ByteArray(0), "sender@example.test") })
    }

    private fun assertThrowsSmime(block: () -> Unit): SmimeFailure =
        try { block(); error("expected SmimeProtectionException") } catch (e: SmimeProtectionException) { e.failure }

    private fun ByteArray.indexOfSlice(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) { for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer; return i }
        return -1
    }

    // ---- ECDH key agreement (RFC 5753): EC certificates are common now ----

    private fun decryptable(variant: SmimeMessageVariant, who: Identity): String =
        String(engine.decryptMime(variant.mimeBytes, who.decrypting()), Charsets.ISO_8859_1)

    @Test fun `encrypting to an EC recipient uses ECDH and only that recipient can read it`() {
        val sender = identity("sender@example.test", curve = "secp256r1")
        val alice = identity("alice@example.test", curve = "secp256r1")
        val bob = identity("bob@example.test", curve = "secp384r1")
        val variants = engine.build(
            mail().copy(cc = emptyList(), bcc = emptyList()), SmimeMode.ENCRYPT,
            recipientCertificates = listOf(alice.recipient(), bob.recipient().copy(mailbox = "bob@example.test")),
            senderDecryptionCertificate = sender.certificate,
        )
        // alice is the only To recipient; bob is not on the mail, so only alice's variant exists
        assertEquals(1, variants.size)
        val text = decryptable(variants.single(), alice)
        assertTrue(text.contains("plain body"))
        assertTrue(runCatching { decryptable(variants.single(), bob) }.isFailure)
        // the sender can read their own Sent copy through the self certificate (also EC)
        assertTrue(decryptable(variants.single(), sender).contains("plain body"))
        // the CMS really carries KeyAgreeRecipientInfo, not a key transport
        val cms = org.bouncycastle.cms.CMSEnvelopedData(parse(variants.single().mimeBytes).inputStream.use { it.readBytes() })
        assertTrue(cms.recipientInfos.recipients.all { it is org.bouncycastle.cms.KeyAgreeRecipientInformation })
    }

    @Test fun `RSA and EC recipients of one message each decrypt with their own key, on P-256 and P-384`() {
        val rsa = identity("alice@example.test")
        val p256 = identity("p256@example.test", curve = "secp256r1")
        val p384 = identity("p384@example.test", curve = "secp384r1")
        val sender = identity("sender@example.test")
        val message = mail().copy(to = listOf(Address("alice@example.test"), Address("p256@example.test"), Address("p384@example.test")), cc = emptyList(), bcc = emptyList())
        val variant = engine.build(message, SmimeMode.ENCRYPT, recipientCertificates = listOf(rsa.recipient(), p256.recipient(), p384.recipient()), senderDecryptionCertificate = sender.certificate).single()
        for (who in listOf(rsa, p256, p384, sender)) assertTrue(decryptable(variant, who).contains("plain body"))
    }

    @Test fun `an EC certificate without key agreement or encipherment is refused`() {
        val signOnly = identity("alice@example.test", encryption = false, curve = "secp256r1")
        val sender = identity("sender@example.test")
        val failure = assertThrowsSmime { engine.build(mail().copy(cc = emptyList(), bcc = emptyList()), SmimeMode.ENCRYPT, recipientCertificates = listOf(signOnly.recipient()), senderDecryptionCertificate = sender.certificate) }
        assertEquals(SmimeFailure.RECIPIENT_CERTIFICATE_REJECTED, failure)
    }

    /**
     * Device regression: our own multipart/alternative signed mail showed "changed after signing". JavaMail's writeTo of
     * part 0 appended a CRLF after the nested multipart's closing delimiter. The cache must keep the transmitted bytes.
     */
    @Test fun `own signed multipart message verifies after send and cache`() {
        val signer = identity("sender@example.test")
        val plain = OutgoingMail(from = Address("sender@example.test"), to = listOf(Address("sender@example.test")), subject = "Dot Mail S/MIME test",
            text = "Signed test from Dot Mail.", html = "<p style=\"margin: 0 0 1em\">Signed test from Dot Mail.</p>")
        val variant = engine.build(plain, SmimeMode.SIGN, signer = signer.signing()).single()
        val file = java.io.File.createTempFile("outbox", ".eml").apply { writeBytes(variant.mimeBytes); deleteOnExit() }
        val wire = java.io.ByteArrayOutputStream().also { out -> StoredMimeMessage(file).use { it.writeTo(out) } }.toByteArray()
        assertTrue(engine.verifySignedMime(wire, "sender@example.test").allCryptographicallyValid)
        val dir = java.nio.file.Files.createTempDirectory("cache").toFile().apply { deleteOnExit() }
        Mime.cache(parse(wire), dir)
        val entity = java.io.File(dir, SmimeParts.SIGNED_FILE).readBytes()
        val signature = java.io.File(dir, SmimeParts.SIGNATURE_FILE).readBytes()
        assertArrayEquals(SmimeMime.detachedParts(wire).first, entity)
        assertTrue(engine.verifyDetached(entity, signature, "sender@example.test").allCryptographicallyValid)
    }
}
