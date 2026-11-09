package app.dot.mail.data

import app.dot.mail.data.model.Message
import app.dot.mail.data.model.SmimeCertificateInfo
import app.dot.mail.data.model.SmimeContent
import app.dot.mail.data.model.SmimeSignatureView
import app.dot.mail.data.model.SmimeSignatureView.State
import app.dot.mail.engine.SmimeCertificatePolicy
import app.dot.mail.engine.SmimeCertificateVerdict
import app.dot.mail.engine.SmimeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate

/** The largest signed entity verified in memory; bigger messages show as unreadable rather than risk the heap. */
private const val MAX_SIGNED_ENTITY = 32L * 1024 * 1024
private const val MAX_SIGNATURE = 1L * 1024 * 1024

/**
 * Verifies a cached S/MIME signature. Order of the verdict: a signature that does not match the content is INVALID
 * whatever the certificate; then a certificate issued for another address than From is SENDER_MISMATCH; then the trust
 * policy decides between VALID_TRUSTED and VALID_UNTRUSTED. Several signers: the weakest one wins.
 */
internal suspend fun verifySmimeSignature(
    message: Message,
    policy: SmimeCertificatePolicy,
    certs: SmimeCertStore?,
): SmimeSignatureView? {
    val signed = message.body?.smime as? SmimeContent.Signed ?: return null
    return withContext(Dispatchers.IO) {
        val entityFile = File(signed.signedEntity); val signatureFile = File(signed.signature)
        if (!entityFile.isFile || !signatureFile.isFile || entityFile.length() > MAX_SIGNED_ENTITY || signatureFile.length() > MAX_SIGNATURE) {
            return@withContext SmimeSignatureView(State.UNREADABLE)
        }
        val from = SmimeCertStore.normalize(message.from.email)
        val verification = try {
            SmimeEngine(certificatePolicy = policy).verifyDetached(entityFile.readBytes(), signatureFile.readBytes(), from)
        } catch (e: Exception) {
            return@withContext SmimeSignatureView(State.UNREADABLE)
        }
        val views = verification.signers.map { signer ->
            val info = signer.certificate.info()
            when {
                !signer.cryptographicSignatureValid -> SmimeSignatureView(State.INVALID, signer = info)
                from !in info.mailboxes -> SmimeSignatureView(State.SENDER_MISMATCH, signer = info)
                signer.certificateVerdict == SmimeCertificateVerdict.Trusted -> SmimeSignatureView(State.VALID_TRUSTED, signer = info)
                else -> SmimeSignatureView(State.VALID_UNTRUSTED, (signer.certificateVerdict as? SmimeCertificateVerdict.Rejected)?.reason, info)
            }
        }
        // Remember certificates from intact signatures for encrypted replies (the store re-checks the mailbox; cache != trust).
        if (certs != null) verification.signers.filter { it.cryptographicSignatureValid }.forEach { signer ->
            runCatching { certs.collect(message.accountId, from, signer.certificate, true, signerIntermediates(signer.certificate, signer.certificateCandidates)) }
        }
        views.maxByOrNull { severity(it.state) } ?: SmimeSignatureView(State.UNREADABLE)
    }
}

private fun severity(state: State) = when (state) {
    State.VALID_TRUSTED -> 0
    State.VALID_UNTRUSTED -> 1
    State.SENDER_MISMATCH -> 2
    State.UNREADABLE -> 3
    State.INVALID -> 4
}

private fun signerIntermediates(leaf: X509Certificate, all: List<X509Certificate>): List<X509Certificate> =
    all.filter { it != leaf && it.basicConstraints >= 0 }

internal fun X509Certificate.info() = SmimeCertificateInfo(
    subject = subjectX500Principal.name,
    issuer = issuerX500Principal.name,
    mailboxes = SmimeCertStore.certificateMailboxes(this),
    notBefore = notBefore.time,
    notAfter = notAfter.time,
    sha256 = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02X".format(it) },
)
