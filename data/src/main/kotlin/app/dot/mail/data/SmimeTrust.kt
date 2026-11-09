package app.dot.mail.data

import app.dot.mail.engine.SmimeCertificatePolicy
import app.dot.mail.engine.SmimeCertificateUse
import app.dot.mail.engine.SmimeCertificateVerdict
import java.security.KeyStore
import java.security.cert.CertPathBuilder
import java.security.cert.CertStore
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.PKIXCertPathBuilderResult
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Decides whether an S/MIME certificate may be trusted for [mailbox], the same way for sending (recipient and own
 * certificates) and for showing a received signature.
 *
 * A certificate is [SmimeCertificateVerdict.Trusted] when all of these hold:
 * - it names [mailbox] (rfc822Name subject-alternative name, or the subject's emailAddress for older certificates);
 * - it is inside its validity period;
 * - its key usage allows the [use], and an extended key usage, if present, includes emailProtection;
 * - a PKIX path leads from it to a trust anchor of the device: Android's system CAs **and** CAs the user installed
 *   (`AndroidCAStore`). The other certificates supplied with it (intermediates carried by a signed message or stored with
 *   a recipient certificate) are only used to build that path; they are never anchors themselves.
 *
 * **Revocation is not checked.** Dot Mail makes no OCSP/CRL requests: they would tell the certificate authority whom the
 * user writes to or reads from. So "not checked" is not [SmimeCertificateVerdict.RevocationUnknown] (which would block
 * every encrypted send); certificate details say "revocation not checked" instead. RevocationUnknown stays reserved for a
 * check that was attempted and was inconclusive, should an opt-in check ever exist.
 */
class SmimeTrustPolicy(
    private val anchors: () -> Set<TrustAnchor> = ::deviceTrustAnchors,
    private val now: () -> Date = ::Date,
) : SmimeCertificatePolicy {

    override fun validate(certificate: X509Certificate, mailbox: String, use: SmimeCertificateUse): SmimeCertificateVerdict =
        validateChain(listOf(certificate), mailbox, use)

    override fun validateChain(certificates: Collection<X509Certificate>, mailbox: String, use: SmimeCertificateUse): SmimeCertificateVerdict {
        val leaf = certificates.firstOrNull() ?: return SmimeCertificateVerdict.Rejected("No certificate")
        val at = now()
        if (SmimeCertStore.normalize(mailbox) !in SmimeCertStore.certificateMailboxes(leaf)) {
            return SmimeCertificateVerdict.Rejected("The certificate is not issued for $mailbox")
        }
        if (at.before(leaf.notBefore)) return SmimeCertificateVerdict.Rejected("The certificate is not valid yet")
        if (at.after(leaf.notAfter)) return SmimeCertificateVerdict.Rejected("The certificate has expired")
        if (!permits(leaf, use)) return SmimeCertificateVerdict.Rejected("The certificate is not meant for e-mail ${if (use == SmimeCertificateUse.SIGNING) "signing" else "encryption"}")
        val trust = runCatching { anchors() }.getOrNull().orEmpty()
        if (trust.isEmpty()) return SmimeCertificateVerdict.Rejected("No trusted certificate authorities are available")
        return try {
            val params = PKIXBuilderParameters(trust, X509CertSelector().apply { certificate = leaf }).apply {
                isRevocationEnabled = false
                date = at
                maxPathLength = MAX_INTERMEDIATES
                addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(certificates.toList())))
            }
            CertPathBuilder.getInstance("PKIX").build(params) as PKIXCertPathBuilderResult
            SmimeCertificateVerdict.Trusted
        } catch (e: Exception) {
            SmimeCertificateVerdict.Rejected("The certificate is not issued by an authority this device trusts")
        }
    }

    private fun permits(certificate: X509Certificate, use: SmimeCertificateUse): Boolean {
        val usage = certificate.keyUsage
        val keyOk = usage == null || when (use) {
            SmimeCertificateUse.SIGNING -> usage.getOrNull(0) == true || usage.getOrNull(1) == true
            SmimeCertificateUse.ENCRYPTION -> usage.getOrNull(2) == true || usage.getOrNull(4) == true
        }
        val eku = runCatching { certificate.extendedKeyUsage }.getOrNull()
        return keyOk && (eku == null || EMAIL_PROTECTION in eku || ANY_EKU in eku)
    }

    companion object {
        private const val EMAIL_PROTECTION = "1.3.6.1.5.5.7.3.4"
        private const val ANY_EKU = "2.5.29.37.0"
        private const val MAX_INTERMEDIATES = 6

        /** System and user-installed CAs, as Android's own TLS stack sees them. Empty off-device. */
        fun deviceTrustAnchors(): Set<TrustAnchor> {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            return store.aliases().toList().mapNotNull { alias -> (store.getCertificate(alias) as? X509Certificate)?.let { TrustAnchor(it, null) } }.toSet()
        }
    }
}
