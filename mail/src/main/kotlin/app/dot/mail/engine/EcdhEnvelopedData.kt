package app.dot.mail.engine

import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.cms.EnvelopedData
import org.bouncycastle.asn1.cms.IssuerAndSerialNumber
import org.bouncycastle.asn1.cms.KeyAgreeRecipientIdentifier
import org.bouncycastle.asn1.cms.KeyAgreeRecipientInfo
import org.bouncycastle.asn1.cms.RecipientEncryptedKey
import org.bouncycastle.asn1.cms.RecipientInfo
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Provider
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Receiving ECDH KeyAgreeRecipientInfo (RFC 5753: ephemeral-static ECDH + X9.63 KDF + AES key wrap) with the shared secret
 * computed by the *platform* `KeyAgreement`, not by Bouncy Castle. That is what lets a non-exportable Android KeyChain key
 * work (BC's own recipient classes need an extractable ECPrivateKey); Bouncy Castle only supplies AES key wrap and the content
 * cipher. Platform ECDH on AndroidKeyStore keys needs Android 12 (API 31); software keys work everywhere.
 */
internal object EcdhEnvelopedData {
    private class Kdf(val digest: String)

    // dhSinglePass-stdDH / cofactorDH with sha1..sha512 KDF; for the NIST curves the cofactor is 1, so both are the same operation.
    private val kdfs = mapOf(
        "1.3.133.16.840.63.0.2" to Kdf("SHA-1"), "1.3.133.16.840.63.0.3" to Kdf("SHA-1"),
        "1.3.132.1.11.0" to Kdf("SHA-224"), "1.3.132.1.11.1" to Kdf("SHA-256"), "1.3.132.1.11.2" to Kdf("SHA-384"), "1.3.132.1.11.3" to Kdf("SHA-512"),
        "1.3.132.1.14.0" to Kdf("SHA-224"), "1.3.132.1.14.1" to Kdf("SHA-256"), "1.3.132.1.14.2" to Kdf("SHA-384"), "1.3.132.1.14.3" to Kdf("SHA-512"),
    )
    private val wrapBits = mapOf(
        NISTObjectIdentifiers.id_aes128_wrap.id to 128, NISTObjectIdentifiers.id_aes192_wrap.id to 192, NISTObjectIdentifiers.id_aes256_wrap.id to 256,
    )
    private val cbcBits = mapOf(
        NISTObjectIdentifiers.id_aes128_CBC.id to 128, NISTObjectIdentifiers.id_aes192_CBC.id to 192, NISTObjectIdentifiers.id_aes256_CBC.id to 256,
    )

    /** True when [contentInfo] (EnvelopedData) has at least one KeyAgreeRecipientInfo. */
    fun hasKeyAgreement(contentInfo: ContentInfo): Boolean = recipients(contentInfo).isNotEmpty()

    private fun recipients(contentInfo: ContentInfo): List<KeyAgreeRecipientInfo> =
        EnvelopedData.getInstance(contentInfo.content).recipientInfos.toArray().map { RecipientInfo.getInstance(it).info }.filterIsInstance<KeyAgreeRecipientInfo>()

    /**
     * Decrypts with [identity]; the recipient entry must name [identity]'s certificate. Throws [IllegalArgumentException] when
     * no key-agreement entry does ("does not contain exactly one matching recipient"), or the algorithms are not supported.
     */
    fun decrypt(contentInfo: ContentInfo, identity: SmimeDecryptionIdentity, provider: Provider): ByteArray {
        val envelope = EnvelopedData.getInstance(contentInfo.content)
        val holder = JcaX509CertificateHolder(identity.certificate)
        val ski = holder.getExtension(Extension.subjectKeyIdentifier)?.let { SubjectKeyIdentifier.getInstance(it.parsedValue).keyIdentifier }
        val matches = recipients(contentInfo).flatMap { info ->
            info.recipientEncryptedKeys.toArray().map { RecipientEncryptedKey.getInstance(it) }
                .filter { matches(it.identifier, holder.issuer, holder.serialNumber, ski) }.map { info to it }
        }
        require(matches.size == 1) { "CMS message does not contain exactly one matching recipient" }
        val (info, entry) = matches.single()

        val keyEncAlg = info.keyEncryptionAlgorithm
        val kdf = requireNotNull(kdfs[keyEncAlg.algorithm.id]) { "Unsupported ECDH key agreement scheme: ${keyEncAlg.algorithm.id}" }
        val wrapAlg = AlgorithmIdentifier.getInstance(keyEncAlg.parameters)
        val kekBits = requireNotNull(wrapBits[wrapAlg.algorithm.id]) { "Unsupported key wrap algorithm: ${wrapAlg.algorithm.id}" }

        val originator = requireNotNull(info.originator.originatorKey) { "Only ephemeral originator keys (originatorKey) are supported" }
        val receiverInfo = SubjectPublicKeyInfo.getInstance(identity.certificate.publicKey.encoded)
        // An originator key without curve parameters inherits the recipient's curve.
        val parameters = originator.algorithm.parameters ?: receiverInfo.algorithm.parameters
        val senderKey: PublicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(SubjectPublicKeyInfo(AlgorithmIdentifier(originator.algorithm.algorithm, parameters), originator.publicKey.bytes).encoded),
        )

        // The shared secret comes from the platform provider, so a hardware-backed key never leaves its keystore.
        val z = KeyAgreement.getInstance("ECDH").run { init(identity.privateKey); doPhase(senderKey, true); generateSecret() }
        val kek = x963(kdf.digest, z, sharedInfo(wrapAlg, info.userKeyingMaterial, kekBits), kekBits / 8)

        val unwrap = Cipher.getInstance("AESWrap", provider).apply { init(Cipher.UNWRAP_MODE, SecretKeySpec(kek, "AES")) }
        val cek = unwrap.unwrap(entry.encryptedKey.octets, "AES", Cipher.SECRET_KEY)

        val content = envelope.encryptedContentInfo
        val cipherAlg = content.contentEncryptionAlgorithm
        require(cbcBits.containsKey(cipherAlg.algorithm.id)) { "Unsupported S/MIME EnvelopedData content algorithm: ${cipherAlg.algorithm.id}" }
        val iv = ASN1OctetString.getInstance(cipherAlg.parameters).octets
        val encrypted = requireNotNull(content.encryptedContent) { "CMS envelope has empty content" }.octets
        return Cipher.getInstance("AES/CBC/PKCS5Padding", provider).run {
            init(Cipher.DECRYPT_MODE, cek, IvParameterSpec(iv)); doFinal(encrypted)
        }
    }

    private fun matches(id: KeyAgreeRecipientIdentifier, issuer: org.bouncycastle.asn1.x500.X500Name, serial: java.math.BigInteger, ski: ByteArray?): Boolean {
        id.issuerAndSerialNumber?.let { return it.name == issuer && it.serialNumber.value == serial }
        val key = id.rKeyID?.subjectKeyIdentifier?.octets
        return key != null && ski != null && key.contentEquals(ski)
    }

    /** ECC-CMS-SharedInfo (RFC 5753 §7.2): the key-wrap algorithm, the optional user keying material, and the KEK length in bits. */
    private fun sharedInfo(wrapAlg: AlgorithmIdentifier, ukm: ASN1OctetString?, kekBits: Int): ByteArray {
        val v = ASN1EncodableVector()
        v.add(wrapAlg)
        if (ukm != null) v.add(DERTaggedObject(true, 0, ukm))
        v.add(DERTaggedObject(true, 2, DEROctetString(ByteBuffer.allocate(4).putInt(kekBits).array())))
        return DERSequence(v).encoded
    }

    /** ANSI X9.63 KDF: Hash(Z || counter || SharedInfo) with a 32-bit big-endian counter starting at 1. */
    private fun x963(digest: String, z: ByteArray, sharedInfo: ByteArray, length: Int): ByteArray {
        val md = MessageDigest.getInstance(digest)
        val out = java.io.ByteArrayOutputStream()
        var counter = 1
        while (out.size() < length) {
            md.update(z); md.update(ByteBuffer.allocate(4).putInt(counter++).array()); md.update(sharedInfo)
            out.write(md.digest())
        }
        return out.toByteArray().copyOf(length)
    }
}
