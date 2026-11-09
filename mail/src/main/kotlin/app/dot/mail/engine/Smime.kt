package app.dot.mail.engine

import java.security.PrivateKey
import java.security.cert.X509Certificate

/** User-visible operation selected in compose. NONE is deliberately not accepted by the protected builder. */
enum class SmimeMode { NONE, SIGN, ENCRYPT, SIGN_AND_ENCRYPT }

enum class SmimeCertificateUse { SIGNING, ENCRYPTION }

/** Certificate validation is supplied by the platform/data layer; CMS never treats a signature as trust. */
sealed interface SmimeCertificateVerdict {
    data object Trusted : SmimeCertificateVerdict
    data object RevocationUnknown : SmimeCertificateVerdict
    data class Rejected(val reason: String) : SmimeCertificateVerdict
}

interface SmimeCertificatePolicy {
    fun validate(certificate: X509Certificate, mailbox: String, use: SmimeCertificateUse): SmimeCertificateVerdict

    /** Chain material is evidence for path building, never itself a trust anchor. */
    fun validateChain(
        certificates: Collection<X509Certificate>,
        mailbox: String,
        use: SmimeCertificateUse,
    ): SmimeCertificateVerdict = SmimeCertificateVerdict.Rejected("Certificate-chain validation is not configured")
}

data class SmimeSigningIdentity(
    val certificate: X509Certificate,
    val privateKey: PrivateKey,
    /** Leaf first, followed by optional intermediates returned by Android KeyChain. */
    val certificateChain: List<X509Certificate> = listOf(certificate),
)
data class SmimeDecryptionIdentity(val certificate: X509Certificate, val privateKey: PrivateKey)
data class SmimeRecipientCertificate(
    val mailbox: String,
    val certificate: X509Certificate,
    val certificateChain: List<X509Certificate> = listOf(certificate),
)
data class SmimeCmsSignerResult(
    val certificate: X509Certificate,
    val certificateCandidates: List<X509Certificate>,
    val cryptographicSignatureValid: Boolean,
)

/**
 * One SMTP submission: [mimeBytes] goes to exactly [envelopeRecipients]. To and Cc share one variant (they already see each
 * other in the headers); every Bcc recipient gets a variant of their own, so neither the headers nor a CMS RecipientInfo
 * set ever reveals a Bcc recipient to anyone else.
 */
data class SmimeMessageVariant(val envelopeRecipients: List<String>, val mimeBytes: ByteArray)

data class SmimeSignerResult(
    val certificate: X509Certificate,
    val cryptographicSignatureValid: Boolean,
    val certificateVerdict: SmimeCertificateVerdict?,
    /** Every certificate the message carried (plus supplied candidates): path-building evidence, never trust. */
    val certificateCandidates: List<X509Certificate> = emptyList(),
)

data class SmimeVerification(val signers: List<SmimeSignerResult>) {
    val hasSignature: Boolean get() = signers.isNotEmpty()
    val allCryptographicallyValid: Boolean get() = signers.isNotEmpty() && signers.all { it.cryptographicSignatureValid }
    val allTrustedAndValid: Boolean get() = allCryptographicallyValid && signers.all { it.certificateVerdict == SmimeCertificateVerdict.Trusted }
}

enum class SmimeFailure {
    PROTECTION_NOT_SELECTED,
    NO_RECIPIENTS,
    SIGNER_REQUIRED,
    SIGNER_CERTIFICATE_REJECTED,
    SIGNER_REVOCATION_UNKNOWN,
    RECIPIENT_CERTIFICATE_MISSING,
    RECIPIENT_PINNED_CERTIFICATE_UNAVAILABLE,
    RECIPIENT_CERTIFICATE_AMBIGUOUS,
    RECIPIENT_CERTIFICATE_REJECTED,
    RECIPIENT_REVOCATION_UNKNOWN,
    SENDER_DECRYPTION_CERTIFICATE_REQUIRED,
    UNSUPPORTED_CERTIFICATE_KEY,
    WEAK_CONTENT_ENCRYPTION,
    INVALID_CMS,
    NO_SIGNATURE,
    SIGNATURE_CERTIFICATE_MISSING,
}

class SmimeProtectionException(val failure: SmimeFailure, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Cryptographic boundary, injectable for tests and replaceable without changing MIME policy. */
interface SmimeCms {
    fun signDetached(entity: ByteArray, identity: SmimeSigningIdentity): ByteArray
    fun verifyDetached(
        entity: ByteArray,
        signature: ByteArray,
        certificateCandidates: Collection<X509Certificate>,
    ): List<SmimeCmsSignerResult>

    fun encrypt(entity: ByteArray, recipients: Collection<X509Certificate>): ByteArray
    fun decrypt(envelopedData: ByteArray, identity: SmimeDecryptionIdentity): ByteArray
}

/**
 * Builds S/MIME MIME/CMS messages before an outbox item is made sendable. Encrypted mail is split into one SMTP
 * envelope per recipient: each CMS envelope contains only that recipient and (when supplied) the sender's own
 * decryption certificate. This prevents a shared CMS RecipientInfo set from disclosing Bcc identities.
 */
class SmimeEngine(
    private val cms: SmimeCms = BouncyCastleSmimeCms(),
    private val certificatePolicy: SmimeCertificatePolicy,
) {
    fun build(
        mail: OutgoingMail,
        mode: SmimeMode,
        signer: SmimeSigningIdentity? = null,
        recipientCertificates: Collection<SmimeRecipientCertificate> = emptyList(),
        senderDecryptionCertificate: X509Certificate? = null,
        pinnedRecipientCertificateFingerprints: Map<String, String> = emptyMap(),
        now: java.util.Date = java.util.Date(),
    ): List<SmimeMessageVariant> {
        if (mode == SmimeMode.NONE) throw SmimeProtectionException(SmimeFailure.PROTECTION_NOT_SELECTED, "S/MIME protection was not selected")
        val recipients = (mail.to + mail.cc + mail.bcc).distinctBy { normalize(it.email) }
        if (recipients.isEmpty()) throw SmimeProtectionException(SmimeFailure.NO_RECIPIENTS, "At least one recipient is required")

        val signing = mode == SmimeMode.SIGN || mode == SmimeMode.SIGN_AND_ENCRYPT
        val encrypting = mode == SmimeMode.ENCRYPT || mode == SmimeMode.SIGN_AND_ENCRYPT
        val signingIdentity = if (signing) signer ?: throw SmimeProtectionException(SmimeFailure.SIGNER_REQUIRED, "A signing certificate is required") else null
        if (signingIdentity != null) {
            require(signingIdentity.certificateChain.firstOrNull() == signingIdentity.certificate) { "Signing certificate chain must start with the selected certificate" }
            requireTrusted(signingIdentity.certificate, mail.from.email, SmimeCertificateUse.SIGNING, signer = true, chain = signingIdentity.certificateChain)
            requireKeyUsage(signingIdentity.certificate, SmimeCertificateUse.SIGNING)
            ensureSupportedSigningKey(signingIdentity.certificate)
            if (!signingIdentity.privateKey.algorithm.equals(signingIdentity.certificate.publicKey.algorithm, ignoreCase = true)) {
                throw SmimeProtectionException(SmimeFailure.UNSUPPORTED_CERTIFICATE_KEY, "The selected signing key does not match its certificate")
            }
        }

        val certByMailbox = recipientCertificates.groupBy { normalize(it.mailbox) }
        val normalizedPins = pinnedRecipientCertificateFingerprints.mapKeys { normalize(it.key) }
        val recipientCerts = if (encrypting) recipients.map { address ->
            val email = normalize(address.email)
            val matches = certByMailbox[email].orEmpty().distinctBy { it.certificate.serialNumber to it.certificate.issuerX500Principal }
            if (matches.isEmpty()) throw SmimeProtectionException(
                SmimeFailure.RECIPIENT_CERTIFICATE_MISSING,
                "No encryption certificate is available for ${address.email}",
            )
            val evaluated = matches.map { candidate ->
                val certificate = candidate.certificate
                val validNow = !certificate.notBefore.after(now) && !certificate.notAfter.before(now)
                val verdict = if (validNow) {
                    runCatching {
                        certificatePolicy.validateChain(candidate.certificateChain, address.email, SmimeCertificateUse.ENCRYPTION)
                    }.getOrElse { SmimeCertificateVerdict.Rejected("Certificate validation failed") }
                } else SmimeCertificateVerdict.Rejected("Certificate is outside its validity period")
                Triple(candidate, validNow && verdict == SmimeCertificateVerdict.Trusted, verdict)
            }
            val eligible = evaluated.mapNotNull { (candidate, trusted, _) ->
                candidate.takeIf {
                    trusted && permitsUse(it.certificate, SmimeCertificateUse.ENCRYPTION) &&
                        isSupportedRecipientKey(it.certificate)
                }
            }
            val pin = normalizedPins[email]
            val selected = if (pin != null) {
                eligible.firstOrNull { certificateFingerprint(it.certificate).equals(pin, ignoreCase = true) }
                    ?: throw SmimeProtectionException(
                        SmimeFailure.RECIPIENT_PINNED_CERTIFICATE_UNAVAILABLE,
                        "The pinned encryption certificate is unavailable or not eligible for ${address.email}",
                    )
            } else eligible.sortedWith(
                    compareByDescending<SmimeRecipientCertificate> { it.certificate.notAfter.time }
                        .thenBy { certificateFingerprint(it.certificate) },
                ).firstOrNull()
            val cert = selected?.certificate ?: run {
                if (evaluated.any { it.third == SmimeCertificateVerdict.RevocationUnknown }) {
                    throw SmimeProtectionException(
                        SmimeFailure.RECIPIENT_REVOCATION_UNKNOWN,
                        "Certificate revocation status is unknown for ${address.email}",
                    )
                }
                val trustedButUnsupported = evaluated.firstOrNull { (_, trusted, _) -> trusted }?.first?.certificate
                if (trustedButUnsupported != null) {
                    requireKeyUsage(trustedButUnsupported, SmimeCertificateUse.ENCRYPTION)
                    ensureSupportedRecipientKey(trustedButUnsupported)
                }
                throw SmimeProtectionException(
                    SmimeFailure.RECIPIENT_CERTIFICATE_REJECTED,
                    "No currently-valid trusted encryption certificate is available for ${address.email}",
                )
            }
            requireKeyUsage(cert, SmimeCertificateUse.ENCRYPTION)
            ensureSupportedRecipientKey(cert)
            address.email to cert
        } else emptyList()

        val selfCertificate = if (encrypting) (senderDecryptionCertificate
            ?: throw SmimeProtectionException(
                SmimeFailure.SENDER_DECRYPTION_CERTIFICATE_REQUIRED,
                "A sender decryption certificate is required to encrypt a sent copy",
            )).also {
            requireTrusted(it, mail.from.email, SmimeCertificateUse.ENCRYPTION, signer = false)
            requireKeyUsage(it, SmimeCertificateUse.ENCRYPTION)
            ensureSupportedRecipientKey(it)
        } else null

        val visibleMail = mail.copy(bcc = emptyList())
        val entity = PgpMime.entity(visibleMail)
        val signedEntity = if (signingIdentity != null) {
            val detached = cms.signDetached(entity, signingIdentity)
            SmimeMime.signedEntity(entity, detached)
        } else entity

        val visible = (mail.to + mail.cc).distinctBy { normalize(it.email) }
        val visibleKeys = visible.map { normalize(it.email) }.toSet()
        val hidden = mail.bcc.distinctBy { normalize(it.email) }.filter { normalize(it.email) !in visibleKeys }
        val groups = (if (visible.isEmpty()) emptyList() else listOf(visible)) + hidden.map { listOf(it) }
        return groups.map { group ->
            val finalEntity = if (encrypting) {
                val certs = group.map { address -> recipientCerts.single { normalize(it.first) == normalize(address.email) }.second }
                val envelopeRecipients = (certs + listOfNotNull(selfCertificate)).distinctBy { it.serialNumber to it.issuerX500Principal }
                val enveloped = cms.encrypt(signedEntity, envelopeRecipients)
                SmimeMime.envelopedMessage(visibleMail, enveloped)
            } else {
                SmimeMime.signedMessage(visibleMail, signedEntity)
            }
            SmimeMessageVariant(group.map { it.email }, finalEntity)
        }
    }

    /** Verifies CMS integrity and reports certificate trust separately; it never labels an untrusted signer verified. */
    fun verifyDetached(
        entity: ByteArray,
        signature: ByteArray,
        expectedSignerMailbox: String,
        certificateCandidates: Collection<X509Certificate> = emptyList(),
    ): SmimeVerification {
        val signers = try {
            cms.verifyDetached(entity, signature, certificateCandidates)
        } catch (e: Exception) {
            throw SmimeProtectionException(SmimeFailure.INVALID_CMS, "The S/MIME signature could not be parsed or verified", e)
        }
        if (signers.isEmpty()) throw SmimeProtectionException(SmimeFailure.NO_SIGNATURE, "The CMS object contains no signer")
        return SmimeVerification(signers.map { signer ->
            val certificate = signer.certificate
            val valid = signer.cryptographicSignatureValid
            val verdict = if (valid) {
                runCatching { certificatePolicy.validateChain(signer.certificateCandidates, expectedSignerMailbox, SmimeCertificateUse.SIGNING) }
                    .getOrElse { SmimeCertificateVerdict.Rejected("Certificate validation failed") }
            } else null
            SmimeSignerResult(certificate, valid, verdict, signer.certificateCandidates)
        })
    }

    /** Parses a detached S/MIME multipart/signed message before checking CMS integrity and certificate trust. */
    fun verifySignedMime(
        messageBytes: ByteArray,
        expectedSignerMailbox: String,
        certificateCandidates: Collection<X509Certificate> = emptyList(),
    ): SmimeVerification = try {
        // Keep the first MIME entity byte-for-byte. JavaMail's parsed part serialization can
        // normalize headers/folding and thereby break signatures produced by other clients.
        val (entity, signature) = SmimeMime.detachedParts(messageBytes)
        verifyDetached(entity, signature, expectedSignerMailbox, certificateCandidates)
    } catch (e: SmimeProtectionException) {
        throw e
    } catch (e: Exception) {
        throw SmimeProtectionException(SmimeFailure.INVALID_CMS, "The S/MIME signed MIME message is malformed", e)
    }

    /** Decrypts only after a caller-selected private identity matches a CMS recipient. */
    fun decrypt(envelopedData: ByteArray, identity: SmimeDecryptionIdentity): ByteArray = try {
        cms.decrypt(envelopedData, identity)
    } catch (e: SmimeProtectionException) {
        throw e
    } catch (e: Exception) {
        throw SmimeProtectionException(SmimeFailure.INVALID_CMS, "The S/MIME envelope could not be decrypted", e)
    }

    /** Parses the standard application/pkcs7-mime enveloped-data wrapper, then decrypts its CMS object. */
    fun decryptMime(messageBytes: ByteArray, identity: SmimeDecryptionIdentity): ByteArray = try {
        val message = jakarta.mail.internet.MimeMessage(
            jakarta.mail.Session.getInstance(java.util.Properties()),
            messageBytes.inputStream(),
        )
        val contentType = jakarta.mail.internet.ContentType(message.contentType)
        require(contentType.baseType.equals("application/pkcs7-mime", ignoreCase = true)) { "Expected application/pkcs7-mime" }
        val smimeType = contentType.getParameter("smime-type")
        require(smimeType.equals("enveloped-data", ignoreCase = true) ||
            smimeType.equals("auth-enveloped-data", ignoreCase = true)) { "Expected enveloped-data or auth-enveloped-data" }
        decrypt(message.inputStream.use { it.readBytes() }, identity)
    } catch (e: SmimeProtectionException) {
        throw e
    } catch (e: Exception) {
        throw SmimeProtectionException(SmimeFailure.INVALID_CMS, "The S/MIME encrypted MIME message is malformed", e)
    }

    private fun requireTrusted(
        certificate: X509Certificate,
        mailbox: String,
        use: SmimeCertificateUse,
        signer: Boolean,
        chain: Collection<X509Certificate> = listOf(certificate),
    ) {
        val verdict = try { certificatePolicy.validateChain(chain, mailbox, use) }
        catch (e: Exception) { SmimeCertificateVerdict.Rejected("Certificate validation failed") }
        when (verdict) {
            SmimeCertificateVerdict.Trusted -> Unit
            SmimeCertificateVerdict.RevocationUnknown -> throw SmimeProtectionException(
                if (signer) SmimeFailure.SIGNER_REVOCATION_UNKNOWN else SmimeFailure.RECIPIENT_REVOCATION_UNKNOWN,
                "Certificate revocation status is unknown for $mailbox",
            )
            is SmimeCertificateVerdict.Rejected -> throw SmimeProtectionException(
                if (signer) SmimeFailure.SIGNER_CERTIFICATE_REJECTED else SmimeFailure.RECIPIENT_CERTIFICATE_REJECTED,
                "Certificate rejected for $mailbox: ${verdict.reason}",
            )
        }
    }

    private fun requireKeyUsage(certificate: X509Certificate, use: SmimeCertificateUse) {
        if (!permitsUse(certificate, use)) throw SmimeProtectionException(
            if (use == SmimeCertificateUse.SIGNING) SmimeFailure.SIGNER_CERTIFICATE_REJECTED else SmimeFailure.RECIPIENT_CERTIFICATE_REJECTED,
            "Certificate usage does not permit S/MIME ${use.name.lowercase()}",
        )
    }

    /** Shared eligibility/final gate: absent EKU is unrestricted, but a present EKU must allow email protection. */
    private fun permitsUse(certificate: X509Certificate, use: SmimeCertificateUse): Boolean {
        val usage = certificate.keyUsage ?: return false
        val permitted = when (use) {
            SmimeCertificateUse.SIGNING -> usage.getOrNull(0) == true
            SmimeCertificateUse.ENCRYPTION -> usage.getOrNull(2) == true || usage.getOrNull(4) == true
        }
        val eku = certificate.extendedKeyUsage
        return permitted && (eku == null || "1.3.6.1.5.5.7.3.4" in eku)
    }

    private fun ensureSupportedSigningKey(certificate: X509Certificate) {
        when (certificate.publicKey.algorithm.uppercase()) {
            "RSA" -> if ((certificate.publicKey as? java.security.interfaces.RSAKey)?.modulus?.bitLength() !in 2048..16384) {
                throw SmimeProtectionException(SmimeFailure.UNSUPPORTED_CERTIFICATE_KEY, "RSA signing key must be at least 2048 bits")
            }
            "EC", "ECDSA" -> if ((certificate.publicKey as? java.security.interfaces.ECKey)?.params?.curve?.field?.fieldSize != 256) {
                throw SmimeProtectionException(SmimeFailure.UNSUPPORTED_CERTIFICATE_KEY, "Only P-256 signing certificates are supported")
            }
            else -> throw SmimeProtectionException(SmimeFailure.UNSUPPORTED_CERTIFICATE_KEY, "Unsupported S/MIME signing key type")
        }
    }

    private fun ensureSupportedRecipientKey(certificate: X509Certificate) {
        if (!isSupportedRecipientKey(certificate)) {
            if (!certificate.publicKey.algorithm.equals("RSA", ignoreCase = true)) {
                throw SmimeProtectionException(SmimeFailure.UNSUPPORTED_CERTIFICATE_KEY, "Only RSA recipient certificates are currently supported")
            }
            throw SmimeProtectionException(SmimeFailure.UNSUPPORTED_CERTIFICATE_KEY, "RSA recipient key must be at least 2048 bits")
        }
    }

    private fun isSupportedRecipientKey(certificate: X509Certificate): Boolean {
        if (!certificate.publicKey.algorithm.equals("RSA", ignoreCase = true)) return false
        val bits = (certificate.publicKey as? java.security.interfaces.RSAKey)?.modulus?.bitLength() ?: 0
        return bits in 2048..16384
    }

    private fun normalize(mailbox: String) = mailbox.trim().lowercase(java.util.Locale.ROOT)

    private fun certificateFingerprint(certificate: X509Certificate): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02X".format(it) }

}
