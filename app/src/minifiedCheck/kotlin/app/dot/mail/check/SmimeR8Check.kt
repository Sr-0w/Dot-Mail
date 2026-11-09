package app.dot.mail.check

import app.dot.mail.engine.Address
import app.dot.mail.engine.OutgoingMail
import app.dot.mail.engine.SmimeCertificatePolicy
import app.dot.mail.engine.SmimeCertificateUse
import app.dot.mail.engine.SmimeCertificateVerdict
import app.dot.mail.engine.SmimeDecryptionIdentity
import app.dot.mail.engine.SmimeEngine
import app.dot.mail.engine.SmimeMode
import app.dot.mail.engine.SmimeProtectionException
import app.dot.mail.engine.SmimeRecipientCertificate
import app.dot.mail.engine.SmimeSigningIdentity
import org.bouncycastle.cms.CMSAlgorithm
import org.bouncycastle.cms.CMSAuthEnvelopedDataGenerator
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.system.exitProcess

/**
 * Runs the S/MIME engine inside the shrunk minifiedCheck APK on real ART, without installing it:
 *   adb push app-minifiedCheck-unsigned.apk /data/local/tmp/r8check.apk
 *   adb shell CLASSPATH=/data/local/tmp/r8check.apk app_process / app.dot.mail.check.SmimeR8Check
 * The R8 configuration is the release one plus this entry point, so a class R8 strips or renames from Bouncy Castle's
 * reflective provider tables fails here instead of on a user's message. Test fixtures only; no app data is touched.
 */
object SmimeR8Check {
    private fun res(name: String) = SmimeR8Check::class.java.classLoader!!.getResourceAsStream("r8check/$name")!!.readBytes()
    private fun cert(name: String) = CertificateFactory.getInstance("X.509").generateCertificate(res("$name.crt.pem").inputStream()) as X509Certificate
    private fun key(name: String, algorithm: String = "RSA"): PrivateKey = KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(
        Base64.getMimeDecoder().decode(String(res("$name.key.pem")).lines().filterNot { it.startsWith("-----") }.joinToString(""))))
    private val trusted = object : SmimeCertificatePolicy {
        override fun validate(certificate: X509Certificate, mailbox: String, use: SmimeCertificateUse) = SmimeCertificateVerdict.Trusted
        override fun validateChain(certificates: Collection<X509Certificate>, mailbox: String, use: SmimeCertificateUse) = SmimeCertificateVerdict.Trusted
    }

    private var failures = 0
    private fun check(name: String, block: () -> Boolean) {
        val ok = runCatching(block).onFailure { it.printStackTrace() }.getOrDefault(false)
        if (!ok) failures++
        println((if (ok) "PASS " else "FAIL ") + name)
    }

    @JvmStatic fun main(args: Array<String>) {
        val engine = SmimeEngine(certificatePolicy = trusted)
        val alice = cert("id-alice"); val bob = cert("id-bob")
        val mail = OutgoingMail(from = Address("alice@example.org"), to = listOf(Address("bob@example.org")), subject = "R8", text = "minified round trip\r\n")
        val bobId = SmimeDecryptionIdentity(bob, key("id-bob"))

        var signed = ByteArray(0)
        check("sign + AES-256-CBC encrypt (engine build)") {
            val variant = engine.build(mail, SmimeMode.SIGN_AND_ENCRYPT, SmimeSigningIdentity(alice, key("id-alice")),
                listOf(SmimeRecipientCertificate("bob@example.org", bob)), alice).single()
            signed = engine.decryptMime(variant.mimeBytes, bobId); String(signed, Charsets.ISO_8859_1).contains("minified round trip")
        }
        check("detached signature verifies") { engine.verifySignedMime(signed, "alice@example.org").allTrustedAndValid }
        check("tampered detached signature is rejected") {
            require(signed.isNotEmpty())
            val tampered = String(signed, Charsets.ISO_8859_1).replace("minified round trip", "minified round trap").toByteArray(Charsets.ISO_8859_1)
            try { !engine.verifySignedMime(tampered, "alice@example.org").allCryptographicallyValid } catch (e: SmimeProtectionException) { true }
        }
        check("AES-GCM AuthEnvelopedData decrypts") {
            val provider = BouncyCastleProvider()
            val generator = CMSAuthEnvelopedDataGenerator().apply { addRecipientInfoGenerator(JceKeyTransRecipientInfoGenerator(bob).setProvider(provider)) }
            val gcm = generator.generate(CMSProcessableByteArray("gcm body".toByteArray()),
                JceCMSContentEncryptorBuilder(CMSAlgorithm.AES256_GCM).setProvider(provider).build() as org.bouncycastle.operator.OutputAEADEncryptor).encoded
            String(engine.decrypt(gcm, bobId)) == "gcm body"
        }
        check("opaque signed-data verifies") {
            val result = engine.verifyOpaque(res("opaque-valid.der"), "alice@example.org")
            result.verification.allCryptographicallyValid && String(result.content, Charsets.ISO_8859_1).contains("opaque signed body")
        }
        // ECDH key agreement (RFC 5753) with a P-256 software key: our own ephemeral-static send, then a message made by OpenSSL.
        val carol = cert("carol-p256"); val carolId = SmimeDecryptionIdentity(carol, key("carol-p256", "EC"))
        check("ECDH P-256: our encrypted message decrypts with the recipient's EC key") {
            val variant = engine.build(mail.copy(to = listOf(Address("carol-p256@example.test"))), SmimeMode.ENCRYPT, null,
                listOf(SmimeRecipientCertificate("carol-p256@example.test", carol)), alice).single()
            String(engine.decryptMime(variant.mimeBytes, carolId), Charsets.ISO_8859_1).contains("minified round trip")
        }
        check("ECDH P-256: an OpenSSL-made message decrypts (X9.63 KDF + AES key wrap)") {
            String(engine.decrypt(res("ecdh-openssl.der"), carolId), Charsets.ISO_8859_1).contains("interop body")
        }
        println(if (failures == 0) "SMIME_R8_CHECK OK" else "SMIME_R8_CHECK FAILED $failures")
        exitProcess(if (failures == 0) 0 else 1)
    }
}
