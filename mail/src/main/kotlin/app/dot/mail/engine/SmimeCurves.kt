package app.dot.mail.engine

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.AlgorithmParameters
import java.security.cert.X509Certificate
import java.security.interfaces.ECKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec

/**
 * The named elliptic curves S/MIME key agreement is supported (and tested) on. A curve is identified by its full parameters, never
 * by its field size: brainpoolP256r1 and secp256k1 also have a 256-bit field, and an ephemeral key generated on the wrong curve
 * would produce mail the recipient can never open.
 */
internal object SmimeCurves {
    /** Curve names as Bouncy Castle spells them; each has a fixture or test of its own. */
    val supported = listOf("secp256r1", "secp384r1", "secp521r1", "brainpoolP256r1", "brainpoolP384r1")

    private val provider = BouncyCastleProvider()
    private val specs: Map<String, ECParameterSpec> by lazy {
        supported.associateWith { name ->
            AlgorithmParameters.getInstance("EC", provider).apply { init(ECGenParameterSpec(name)) }.getParameterSpec(ECParameterSpec::class.java)
        }
    }

    /** The supported curve [params] describe, or null (unknown, explicit or unsupported parameters). */
    fun name(params: ECParameterSpec?): String? = params?.let { p ->
        specs.entries.firstOrNull { (_, s) -> s.curve == p.curve && s.generator == p.generator && s.order == p.order && s.cofactor == p.cofactor }?.key
    }

    fun name(certificate: X509Certificate): String? = (certificate.publicKey as? ECKey)?.let { name(it.params) }
}
