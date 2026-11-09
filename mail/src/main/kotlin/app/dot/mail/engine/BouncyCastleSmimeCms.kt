package app.dot.mail.engine

import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cms.CMSAlgorithm
import org.bouncycastle.cms.CMSAuthEnvelopedData
import org.bouncycastle.cms.CMSAuthEnvelopedDataGenerator
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
import org.bouncycastle.cms.jcajce.JceKeyTransAuthEnvelopedRecipient
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientId
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers
import org.bouncycastle.asn1.cms.ContentInfo
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
        return verifySigners(signed, certificateCandidates)
    }

    override fun verifyEncapsulated(cms: ByteArray, certificateCandidates: Collection<X509Certificate>): SmimeCmsEncapsulated {
        val signed = CMSSignedData(cms)
        require(!signed.isDetachedSignature) { "Expected CMS SignedData with encapsulated content" }
        val content = signed.signedContent?.content as? ByteArray ?: throw SmimeProtectionException(SmimeFailure.INVALID_CMS, "The CMS object has no signed content")
        // BouncyCastle digests the encapsulated content itself when each signer is verified below.
        return SmimeCmsEncapsulated(content, verifySigners(signed, certificateCandidates))
    }

    private fun verifySigners(signed: CMSSignedData, certificateCandidates: Collection<X509Certificate>): List<SmimeCmsSignerResult> {
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
        val distinct = recipients.distinctBy { it.serialNumber to it.issuerX500Principal }
        val (ec, rsa) = distinct.partition { it.publicKey.algorithm.equals("EC", true) || it.publicKey.algorithm.equals("ECDSA", true) }
        rsa.forEach { certificate -> generator.addRecipientInfoGenerator(JceKeyTransRecipientInfoGenerator(certificate).setProvider(provider)) }
        // EC recipients get ephemeral-static ECDH (RFC 5753) with the SHA-256 KDF and AES-256 key wrap; one ephemeral key per curve.
        ec.groupBy { curveName(it) }.forEach { (curve, certificates) ->
            val pair = java.security.KeyPairGenerator.getInstance("EC", provider).apply { initialize(java.security.spec.ECGenParameterSpec(curve)) }.generateKeyPair()
            val agreement = org.bouncycastle.cms.jcajce.JceKeyAgreeRecipientInfoGenerator(CMSAlgorithm.ECDH_SHA256KDF, pair.private, pair.public, CMSAlgorithm.AES256_WRAP).setProvider(provider)
            certificates.forEach { agreement.addRecipient(it) }
            generator.addRecipientInfoGenerator(agreement)
        }
        // AES-CBC EnvelopedData is the interoperable S/MIME baseline. GCM is only used
        // for AuthEnvelopedData when receiving messages that explicitly carry that type.
        val encryptor = JceCMSContentEncryptorBuilder(CMSAlgorithm.AES256_CBC).setProvider(provider).build()
        return generator.generate(CMSProcessableByteArray(entity), encryptor).encoded
    }

    private fun curveName(certificate: X509Certificate): String = when ((certificate.publicKey as java.security.interfaces.ECKey).params.curve.field.fieldSize) {
        256 -> "secp256r1"
        384 -> "secp384r1"
        521 -> "secp521r1"
        else -> throw SmimeProtectionException(SmimeFailure.UNSUPPORTED_CERTIFICATE_KEY, "Unsupported EC curve for S/MIME encryption")
    }

    override fun decrypt(envelopedData: ByteArray, identity: SmimeDecryptionIdentity): ByteArray {
        val contentInfo = ContentInfo.getInstance(ASN1Primitive.fromByteArray(envelopedData))
        return when (contentInfo.contentType) {
            CMSObjectIdentifiers.envelopedData -> {
                val envelope = CMSEnvelopedData(contentInfo)
                val oid = envelope.encryptionAlgOID
                rejectWeakEncryption(oid)
                require(oid in setOf(CMSAlgorithm.AES128_CBC.id, CMSAlgorithm.AES192_CBC.id, CMSAlgorithm.AES256_CBC.id)) {
                    "Unsupported S/MIME EnvelopedData content algorithm: $oid"
                }
                if (identity.certificate.publicKey.algorithm.equals("EC", true) || identity.certificate.publicKey.algorithm.equals("ECDSA", true)) {
                    // ECDH key agreement: the shared secret is computed by the platform provider (see EcdhEnvelopedData).
                    return EcdhEnvelopedData.decrypt(contentInfo, identity, provider).also { require(it.isNotEmpty()) { "CMS envelope has empty content" } }
                }
                val recipient = matchingRecipient(envelope.recipientInfos, identity)
                // Keep key recovery on the platform JCA provider for non-exportable Android KeyChain keys.
                recipient.getContent(JceKeyTransEnvelopedRecipient(identity.privateKey).setContentProvider(provider))
            }
            CMSObjectIdentifiers.authEnvelopedData -> {
                val envelope = CMSAuthEnvelopedData(contentInfo)
                val oid = envelope.encryptionAlgOID
                rejectWeakEncryption(oid)
                require(oid in setOf(CMSAlgorithm.AES128_GCM.id, CMSAlgorithm.AES192_GCM.id, CMSAlgorithm.AES256_GCM.id)) {
                    "Unsupported S/MIME AuthEnvelopedData content algorithm: $oid"
                }
                val recipient = matchingRecipient(envelope.recipientInfos, identity)
                recipient.getContent(JceKeyTransAuthEnvelopedRecipient(identity.privateKey).setContentProvider(provider))
            }
            else -> error("Expected CMS EnvelopedData or AuthEnvelopedData")
        }.also { require(it.isNotEmpty()) { "CMS envelope has empty content" } }
    }

    private fun matchingRecipient(
        recipients: org.bouncycastle.cms.RecipientInformationStore,
        identity: SmimeDecryptionIdentity,
    ): org.bouncycastle.cms.RecipientInformation {
        val matches = recipients.getRecipients(JceKeyTransRecipientId(identity.certificate))
        require(matches.size == 1) { "CMS message does not contain exactly one matching recipient" }
        return matches.single()
    }

    private fun rejectWeakEncryption(oid: String) {
        if (oid in setOf(CMSAlgorithm.DES_CBC.id, CMSAlgorithm.DES_EDE3_CBC.id, CMSAlgorithm.RC2_CBC.id)) {
            throw SmimeProtectionException(
                SmimeFailure.WEAK_CONTENT_ENCRYPTION,
                "S/MIME content encryption is too weak to decrypt safely",
            )
        }
    }
}
