package app.dot.mail.data

import app.dot.mail.engine.SmimeCertificateUse
import app.dot.mail.engine.SmimeCertificateVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Date

/** Fixtures: Dot Test Root → Dot Test Intermediate → leaf for alice@example.org (emailProtection) / same with serverAuth only. */
class SmimeTrustPolicyTest {
    private fun cert(name: String) = javaClass.getResourceAsStream("/smime/$name.pem")!!.use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    private val root = cert("policy-root")
    private val intermediate = cert("policy-intermediate")
    private val leaf = cert("policy-leaf")
    private val serverOnly = cert("policy-server")
    private val untrustedRoot = cert("policy-untrusted-root")
    private val inside = Date(leaf.notBefore.time + 86_400_000L)

    private fun policy(vararg anchors: X509Certificate, at: Date = inside) =
        SmimeTrustPolicy(anchors = { anchors.map { TrustAnchor(it, null) }.toSet() }, now = { at })

    private fun rejected(v: SmimeCertificateVerdict) = assertTrue("expected Rejected, was $v", v is SmimeCertificateVerdict.Rejected)

    @Test fun `leaf with its intermediate chains to a device anchor`() {
        assertEquals(SmimeCertificateVerdict.Trusted, policy(root).validateChain(listOf(leaf, intermediate), "alice@example.org", SmimeCertificateUse.ENCRYPTION))
        assertEquals(SmimeCertificateVerdict.Trusted, policy(root).validateChain(listOf(leaf, intermediate), " Alice@Example.org ", SmimeCertificateUse.SIGNING))
    }

    @Test fun `missing intermediate cannot build a path`() =
        rejected(policy(root).validateChain(listOf(leaf), "alice@example.org", SmimeCertificateUse.ENCRYPTION))

    @Test fun `supplied certificates are evidence, never anchors`() {
        // The message carries the whole chain up to its own root, but the device does not trust that root.
        rejected(policy(untrustedRoot).validateChain(listOf(leaf, intermediate, root), "alice@example.org", SmimeCertificateUse.ENCRYPTION))
    }

    @Test fun `certificate for another mailbox is rejected`() =
        rejected(policy(root).validateChain(listOf(leaf, intermediate), "mallory@example.org", SmimeCertificateUse.ENCRYPTION))

    @Test fun `expired and not-yet-valid are rejected`() {
        rejected(policy(root, at = Date(leaf.notAfter.time + 1_000)).validateChain(listOf(leaf, intermediate), "alice@example.org", SmimeCertificateUse.ENCRYPTION))
        rejected(policy(root, at = Date(leaf.notBefore.time - 1_000)).validateChain(listOf(leaf, intermediate), "alice@example.org", SmimeCertificateUse.ENCRYPTION))
    }

    @Test fun `extended key usage without emailProtection is rejected`() =
        rejected(policy(root).validateChain(listOf(serverOnly, intermediate), "alice@example.org", SmimeCertificateUse.ENCRYPTION))

    @Test fun `revocation is not checked so it never blocks a valid chain`() {
        // No OCSP/CRL is reachable in the fixtures; a revocation-checking validator would fail here.
        val verdict = policy(root).validateChain(listOf(leaf, intermediate), "alice@example.org", SmimeCertificateUse.SIGNING)
        assertEquals(SmimeCertificateVerdict.Trusted, verdict)
    }

    @Test fun `no anchors available means rejected, not trusted`() =
        rejected(policy().validateChain(listOf(leaf, intermediate), "alice@example.org", SmimeCertificateUse.ENCRYPTION))
}
