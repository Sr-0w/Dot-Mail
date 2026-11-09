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
    val content = message.body?.smime
    if (content is SmimeContent.Opaque) return withContext(Dispatchers.IO) {
        val file = File(content.cms)
        if (!file.isFile || file.length() > MAX_SIGNED_ENTITY) return@withContext SmimeSignatureView(State.UNREADABLE)
        val from = SmimeCertStore.normalize(message.from.email)
        val verification = try {
            SmimeEngine(certificatePolicy = policy).verifyOpaque(file.readBytes(), from).verification
        } catch (e: Exception) {
            return@withContext SmimeSignatureView(State.UNREADABLE)
        }
        summarize(verification, from, message.accountId, certs)
    }
    val signed = content as? SmimeContent.Signed ?: return null
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
        summarize(verification, from, message.accountId, certs)
    }
}

/** One verdict for all signers (the weakest wins), remembering certificates from intact signatures. */
private suspend fun summarize(verification: app.dot.mail.engine.SmimeVerification, from: String, accountId: Long, certs: SmimeCertStore?): SmimeSignatureView {
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
            runCatching { certs.collect(accountId, from, signer.certificate, true, signerIntermediates(signer.certificate, signer.certificateCandidates)) }
        }
    return views.maxByOrNull { severity(it.state) } ?: SmimeSignatureView(State.UNREADABLE)
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

/**
 * Decrypts an S/MIME envelope in memory and renders it like the reader renders a body. When the decrypted content is itself
 * a detached signature (sign-then-encrypt), that signature is verified too. Plaintext never touches the disk.
 */
internal suspend fun decryptSmimeMessage(
    message: Message,
    keys: suspend (accountId: Long) -> app.dot.mail.engine.SmimeDecryptionIdentity,
    policy: SmimeCertificatePolicy,
    certs: SmimeCertStore?,
): app.dot.mail.data.model.SmimeDecryption? {
    val encrypted = message.body?.smime as? SmimeContent.Encrypted ?: return null
    return withContext(Dispatchers.IO) {
        val file = File(encrypted.envelope)
        if (!file.isFile || file.length() > MAX_SIGNED_ENTITY) return@withContext app.dot.mail.data.model.SmimeDecryption.Failed
        val identity = try { keys(message.accountId) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            return@withContext app.dot.mail.data.model.SmimeDecryption.NoKey
        }
        val engine = SmimeEngine(certificatePolicy = policy)
        val plain = try { engine.decrypt(file.readBytes(), identity) } catch (e: app.dot.mail.engine.SmimeProtectionException) {
            return@withContext when {
                e.failure == app.dot.mail.engine.SmimeFailure.WEAK_CONTENT_ENCRYPTION -> app.dot.mail.data.model.SmimeDecryption.WeakEncryption
                generateSequence<Throwable>(e) { it.cause }.any { (it.message ?: "").contains("exactly one matching recipient") } -> app.dot.mail.data.model.SmimeDecryption.NotForThisKey
                else -> app.dot.mail.data.model.SmimeDecryption.Failed
            }
        }
        val from = SmimeCertStore.normalize(message.from.email)
        // Sign-then-encrypt: the decrypted entity may carry a detached signature or be opaque-signed (Outlook).
        var content = plain
        val signature = when {
            isDetachedSigned(plain) -> runCatching { summarize(engine.verifySignedMime(plain, from), from, message.accountId, certs) }.getOrElse { SmimeSignatureView(State.UNREADABLE) }
            opaqueCms(plain) != null -> runCatching {
                val opaque = engine.verifyOpaque(opaqueCms(plain)!!, from)
                content = opaque.content
                summarize(opaque.verification, from, message.accountId, certs)
            }.getOrElse { SmimeSignatureView(State.UNREADABLE) }
            else -> null
        }
        val parsed = app.dot.mail.engine.Pgp.parseDecrypted(content, mime = true)
        app.dot.mail.data.model.SmimeDecryption.Done(app.dot.mail.data.model.MessageBody(parsed.text, parsed.html, hasRemoteImages = false, decryptedAttachments = parsed.toDecryptedAttachments(message.id)), signature)
    }
}

/** The CMS bytes of an opaque-signed entity (application/pkcs7-mime; smime-type=signed-data), transfer encoding removed, else null. */
private fun opaqueCms(entity: ByteArray): ByteArray? = runCatching {
    val part = jakarta.mail.internet.MimeBodyPart(entity.inputStream())
    if (app.dot.mail.engine.SmimeParts.isOpaqueSigned(part.contentType)) part.inputStream.use { it.readBytes() } else null
}.getOrNull()

private fun isDetachedSigned(entity: ByteArray): Boolean = runCatching {
    val type = jakarta.mail.internet.MimeBodyPart(entity.inputStream()).contentType
    jakarta.mail.internet.ContentType(type).let { it.baseType.equals("multipart/signed", true) && app.dot.mail.engine.SmimeParts.isSignaturePart(it.getParameter("protocol")) }
}.getOrDefault(false)
