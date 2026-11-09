package app.dot.mail.engine

import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cms.CMSAlgorithm
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSEnvelopedData
import org.bouncycastle.cms.CMSEnvelopedDataGenerator
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder
import org.bouncycastle.cms.jcajce.JceKeyTransEnvelopedRecipient
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientId
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.security.cert.X509Certificate

/** Bouncy Castle is kept behind [SmimeCms]; no global Provider registration or mutable JCA configuration. */
internal class BouncyCastleSmimeCms : SmimeCms {
    private val provider = BouncyCastleProvider()

    override fun signDetached(entity: ByteArray, identity: SmimeSigningIdentity): ByteArray {
        val algorithm = when (identity.certificate.publicKey.algorithm.uppercase()) {
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            else -> throw SmimeProtectionException(SmimeFailure.UNSUPPORTED_CERTIFICATE_KEY, "Unsupported S/MIME signing key type")
        }
        val signer = JcaContentSignerBuilder(algorithm).build(identity.privateKey)
        val digestProvider = JcaDigestCalculatorProviderBuilder().setProvider(provider).build()
        require(identity.certificateChain.firstOrNull() == identity.certificate) { "Signing certificate chain must start with the selected certificate" }
        val generator = CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(digestProvider).build(signer, identity.certificate))
            addCertificates(JcaCertStore(identity.certificateChain.distinctBy { it.issuerX500Principal to it.serialNumber }))
        }
        return generator.generate(CMSProcessableByteArray(entity), false).encoded
    }

    override fun verifyDetached(
        entity: ByteArray,
        signature: ByteArray,
        certificateCandidates: Collection<X509Certificate>,
    ): List<SmimeCmsSignerResult> {
        val signed = CMSSignedData(CMSProcessableByteArray(entity), signature)
        require(signed.isDetachedSignature) { "Expected detached CMS SignedData" }
        val embedded = signed.certificates.getMatches(null).toList()
        val external = certificateCandidates.map(::JcaX509CertificateHolder)
        val available = (embedded + external).distinctBy { it.issuer.toString() to it.serialNumber }
        val converter = JcaX509CertificateConverter().setProvider(provider)
        val allCertificates = available.map(converter::getCertificate)
        return signed.signerInfos.signers.map { signer: SignerInformation ->
            val matches = available.filter { signer.sid.match(it) }
            val matching = matches.firstOrNull()
                ?: throw SmimeProtectionException(SmimeFailure.SIGNATURE_CERTIFICATE_MISSING, "No certificate matches this CMS signer")
            val valid = runCatching {
                signer.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(provider).build(matching))
            }.getOrDefault(false)
            SmimeCmsSignerResult(converter.getCertificate(matching), allCertificates, valid)
        }
    }

    override fun encrypt(entity: ByteArray, recipients: Collection<X509Certificate>): ByteArray {
        require(recipients.isNotEmpty()) { "CMS EnvelopedData requires at least one recipient" }
        val generator = CMSEnvelopedDataGenerator()
        recipients.distinctBy { it.serialNumber to it.issuerX500Principal }.forEach { certificate ->
            generator.addRecipientInfoGenerator(JceKeyTransRecipientInfoGenerator(certificate).setProvider(provider))
        }
        val encryptor = JceCMSContentEncryptorBuilder(CMSAlgorithm.AES256_GCM).setProvider(provider).build()
        return generator.generate(CMSProcessableByteArray(entity), encryptor).encoded
    }

    override fun decrypt(envelopedData: ByteArray, identity: SmimeDecryptionIdentity): ByteArray {
        val envelope = CMSEnvelopedData(envelopedData)
        require(envelope.encryptionAlgOID == CMSAlgorithm.AES256_GCM.id) { "Unsupported S/MIME content-encryption algorithm" }
        val recipientId = JceKeyTransRecipientId(identity.certificate)
        val recipients = envelope.recipientInfos.getRecipients(recipientId)
        require(recipients.size == 1) { "CMS message does not contain exactly one matching recipient" }
        val recipient = recipients.single()
        // Keep key recovery on the platform JCA provider for non-exportable Android KeyChain keys;
        // use BC only for CMS content decryption (including AES-GCM AlgorithmParameters).
        val content = recipient.getContent(JceKeyTransEnvelopedRecipient(identity.privateKey).setContentProvider(provider))
        require(content.isNotEmpty()) { "CMS envelope has empty content" }
        return content
    }
}
