package app.dot.mail.data

import app.dot.mail.data.local.MailDao
import app.dot.mail.data.local.SmimeRecipientCertRow
import app.dot.mail.engine.SmimeRecipientCertificate
import kotlinx.coroutines.flow.Flow
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import javax.security.auth.x500.X500Principal

/** Where a stored recipient certificate came from. */
enum class SmimeCertSource { VERIFIED_SIGNATURE, MANUAL }

/** Validity of a stored certificate at a given time. This is dates only; trust is decided by the certificate policy at send. */
enum class SmimeCertValidity { VALID, NOT_YET_VALID, EXPIRED }

/** A candidate leaf with its stored intermediates (leaf not included in [intermediates]). */
class SmimeCandidateChain(val mailbox: String, val leaf: X509Certificate, val intermediates: List<X509Certificate>) {
    /** Leaf first, then the intermediates: the order PKIX path building expects. */
    val chain: List<X509Certificate> get() = listOf(leaf) + intermediates
}

/** One stored recipient certificate, as shown in Settings. */
class StoredSmimeCert(
    val accountId: Long,
    val mailbox: String,
    val fingerprint: String,
    val subject: String,
    val issuer: String,
    val notBefore: Long,
    val notAfter: Long,
    val firstSeen: Long,
    val lastSeen: Long,
    val source: SmimeCertSource,
    val pinned: Boolean,
) {
    fun validity(now: Long): SmimeCertValidity = when { now < notBefore -> SmimeCertValidity.NOT_YET_VALID; now > notAfter -> SmimeCertValidity.EXPIRED; else -> SmimeCertValidity.VALID }
}

/**
 * Recipient certificates for S/MIME encryption, per account. Certificates are collected only from mail whose signature
 * verified cryptographically, and only for the mailbox the certificate itself names, so a signed message cannot plant a
 * certificate for someone else's address. Having a row is a cache, never trust: callers hand the candidates to the
 * certificate policy at the moment of sending.
 */
class SmimeCertStore internal constructor(private val dao: MailDao, private val now: () -> Long = System::currentTimeMillis) {

    /**
     * Records [certificate] for [mailbox] when [signatureVerified] and the certificate names that mailbox. Returns whether
     * it is now stored (an already-known certificate is refreshed; a forgotten one is not brought back).
     */
    suspend fun collect(accountId: Long, mailbox: String, certificate: X509Certificate, signatureVerified: Boolean, intermediates: Collection<X509Certificate> = emptyList()): Boolean {
        if (!signatureVerified) return false
        val box = normalize(mailbox)
        if (box !in certificateMailboxes(certificate)) return false
        return store(accountId, box, certificate, SmimeCertSource.VERIFIED_SIGNATURE, intermediates = intermediates)
    }

    /** Adds a certificate the user chose by hand (always for the mailbox it names); it also revives a forgotten one. */
    suspend fun addManual(accountId: Long, certificate: X509Certificate, intermediates: Collection<X509Certificate> = emptyList()): List<String> {
        val boxes = certificateMailboxes(certificate).toList()
        for (box in boxes) store(accountId, box, certificate, SmimeCertSource.MANUAL, revive = true, intermediates = intermediates)
        return boxes
    }

    private suspend fun store(accountId: Long, box: String, certificate: X509Certificate, source: SmimeCertSource, revive: Boolean = false, intermediates: Collection<X509Certificate> = emptyList()): Boolean {
        val print = fingerprint(certificate)
        val existing = dao.smimeCert(accountId, box, print)
        val t = now()
        val chain = encodeChain(certificate, intermediates)
        if (existing != null) {
            if (existing.state != "ACTIVE" && !revive) return false
            if (existing.state == "ACTIVE") {
                dao.touchSmimeCert(accountId, box, print, t)
                // A later message may carry the intermediates an earlier one lacked; never replace a chain by an empty one.
                if (chain != null && (existing.chainDer == null || chain.size > existing.chainDer.size)) dao.setSmimeChain(accountId, box, print, chain)
                return true
            }
        }
        dao.putSmimeCert(SmimeRecipientCertRow(
            accountId, box, print, certificate.encoded, certificate.subjectX500Principal.name, certificate.issuerX500Principal.name,
            certificate.notBefore.time, certificate.notAfter.time, existing?.firstSeen ?: t, t, source.name, "ACTIVE", pinned = false, chainDer = chain,
        ))
        return true
    }

    /** Usable-by-the-engine candidates for [mailbox]: pinned first, then the latest expiry. Unparsable rows are skipped. */
    suspend fun candidates(accountId: Long, mailbox: String): List<SmimeRecipientCertificate> =
        dao.smimeCerts(accountId, normalize(mailbox)).mapNotNull { row -> parse(row.der)?.let { SmimeRecipientCertificate(row.mailbox, it) } }

    /**
     * Like [candidates] but with the intermediates stored next to each leaf, for PKIX path building: real CA-issued
     * certificates chain through intermediates that Android's trust store does not have.
     */
    suspend fun candidateChains(accountId: Long, mailbox: String): List<SmimeCandidateChain> =
        dao.smimeCerts(accountId, normalize(mailbox)).mapNotNull { row ->
            parse(row.der)?.let { leaf -> SmimeCandidateChain(row.mailbox, leaf, decodeChain(row.chainDer)) }
        }

    /** The pinned certificate's fingerprint for [mailbox], if any. */
    suspend fun pinnedFingerprint(accountId: Long, mailbox: String): String? = dao.smimeCerts(accountId, normalize(mailbox)).firstOrNull { it.pinned }?.fingerprint

    fun observe(accountId: Long): Flow<List<StoredSmimeCert>> = kotlinx.coroutines.flow.flow {
        dao.smimeCertsFlow(accountId).collect { rows -> emit(rows.map { it.toStored() }) }
    }

    /** Pins one certificate for its mailbox (replacing any earlier pin). False when it is not a stored, active certificate. */
    suspend fun pin(accountId: Long, mailbox: String, fingerprint: String): Boolean {
        val box = normalize(mailbox)
        if (dao.smimeCert(accountId, box, fingerprint)?.state != "ACTIVE") return false
        dao.unpinSmimeCerts(accountId, box)
        return dao.pinSmimeCert(accountId, box, fingerprint, true) == 1
    }

    suspend fun unpin(accountId: Long, mailbox: String, fingerprint: String) { dao.pinSmimeCert(accountId, normalize(mailbox), fingerprint, false) }

    /** Forgets a certificate: it stops being a candidate and is not collected again from later signed mail. */
    suspend fun forget(accountId: Long, mailbox: String, fingerprint: String): Boolean = dao.ignoreSmimeCert(accountId, normalize(mailbox), fingerprint) == 1

    private fun SmimeRecipientCertRow.toStored() = StoredSmimeCert(
        accountId, mailbox, fingerprint, subject, issuer, notBefore, notAfter, firstSeen, lastSeen,
        runCatching { SmimeCertSource.valueOf(source) }.getOrDefault(SmimeCertSource.VERIFIED_SIGNATURE), pinned,
    )

    companion object {
        fun normalize(mailbox: String) = mailbox.trim().lowercase(Locale.ROOT)

        /** Lower-case hex SHA-256 of the DER encoding. */
        fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

        /**
         * The mailboxes a certificate names: its rfc822Name subject-alternative names, or, for older certificates without
         * any, the emailAddress attribute of the subject.
         */
        fun certificateMailboxes(certificate: X509Certificate): Set<String> {
            val san = runCatching { certificate.subjectAlternativeNames }.getOrNull().orEmpty()
                .filter { (it[0] as? Int) == 1 }.mapNotNull { (it[1] as? String)?.let(::normalize) }.toSet()
            if (san.isNotEmpty()) return san
            val subject = certificate.subjectX500Principal.getName(X500Principal.RFC2253, mapOf("1.2.840.113549.1.9.1" to "emailaddress"))
            return Regex("(?i)(?:^|,)emailaddress=([^,]+)").findAll(subject).map { normalize(it.groupValues[1]) }.toSet()
        }

        /** At most this many intermediates / bytes are kept per certificate. */
        const val MAX_CHAIN_CERTS = 8
        const val MAX_CHAIN_BYTES = 64 * 1024

        /** Length-prefixed DER of the usable intermediates (not the leaf, no duplicates), capped; null when there are none. */
        internal fun encodeChain(leaf: X509Certificate, intermediates: Collection<X509Certificate>): ByteArray? {
            val out = java.io.ByteArrayOutputStream(); val seen = HashSet<String>(); var count = 0
            seen += fingerprint(leaf)
            for (c in intermediates) {
                if (count >= MAX_CHAIN_CERTS) break
                val der = runCatching { c.encoded }.getOrNull() ?: continue
                if (!seen.add(fingerprint(c))) continue
                if (out.size() + 4 + der.size > MAX_CHAIN_BYTES) break
                java.io.DataOutputStream(out).writeInt(der.size); out.write(der); count++
            }
            return if (count == 0) null else out.toByteArray()
        }

        internal fun decodeChain(bytes: ByteArray?): List<X509Certificate> {
            if (bytes == null) return emptyList()
            val input = java.io.DataInputStream(ByteArrayInputStream(bytes)); val out = ArrayList<X509Certificate>()
            while (input.available() >= 4 && out.size < MAX_CHAIN_CERTS) {
                val n = runCatching { input.readInt() }.getOrNull() ?: break
                if (n <= 0 || n > input.available()) break
                val der = ByteArray(n).also { input.readFully(it) }
                parse(der)?.let(out::add)
            }
            return out
        }

        private fun parse(der: ByteArray): X509Certificate? =
            runCatching { CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate }.getOrNull()
    }
}
