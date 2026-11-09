package app.dot.mail.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Organisations such as the European Commission sign with one certificate and receive encrypted mail with another; both
 * travel in a signed message. Fixtures: Split Test CA → carol sign-only, carol encrypt-only, mallory encrypt-only.
 */
class SmimeSplitKeysTest {
    private fun cert(name: String) = javaClass.getResourceAsStream("/smime/$name.pem")!!.use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    private val ca = cert("split-ca"); private val sign = cert("split-sign"); private val enc = cert("split-enc"); private val other = cert("split-other")

    @Test fun `the sender's encryption certificate is kept next to the signer`() =
        assertEquals(listOf(enc), encryptionCompanions(sign, listOf(sign, enc, other, ca), "Carol@Example.org"))

    @Test fun `CA certificates, other mailboxes and sign-only certificates are not companions`() {
        assertEquals(emptyList<X509Certificate>(), encryptionCompanions(sign, listOf(sign, ca, other), "carol@example.org"))
        assertEquals(emptyList<X509Certificate>(), encryptionCompanions(enc, listOf(enc, sign), "carol@example.org"))
    }
}
