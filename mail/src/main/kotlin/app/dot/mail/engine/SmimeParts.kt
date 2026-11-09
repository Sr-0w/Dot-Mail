package app.dot.mail.engine

/** Files written next to a cached body for an S/MIME signed message (see [Mime.cache]). */
object SmimeParts {
    /** The signed MIME entity, byte for byte as transmitted (headers + body). */
    const val SIGNED_FILE = "smime-signed.mime"
    /** The detached CMS SignedData (DER, transfer encoding removed). */
    const val SIGNATURE_FILE = "smime-signature.p7s"
    /** The CMS EnvelopedData/AuthEnvelopedData of an encrypted message (DER). Plaintext is never written to disk. */
    const val ENCRYPTED_FILE = "smime-encrypted.p7m"

    /** The CMS SignedData of an opaque-signed message (`application/pkcs7-mime; smime-type=signed-data`), kept for verification. */
    const val OPAQUE_FILE = "smime-opaque.p7m"

    /** Opaque CMS is read into memory to extract its content; larger ones stay ordinary attachments. */
    const val MAX_OPAQUE_BYTES = 8 * 1024 * 1024

    private val pkcs7MimeTypes = setOf("application/pkcs7-mime", "application/x-pkcs7-mime")

    /** True for an opaque-signed message part: pkcs7-mime with smime-type=signed-data (what Outlook sends with "opaque signing"). */
    fun isOpaqueSigned(contentType: String?): Boolean = runCatching {
        val type = jakarta.mail.internet.ContentType(contentType ?: return false)
        type.baseType.lowercase() in pkcs7MimeTypes && type.getParameter("smime-type").equals("signed-data", ignoreCase = true)
    }.getOrDefault(false)

    /**
     * The signed content inside opaque CMS SignedData, WITHOUT verifying anything (verification happens in the reader, against
     * the same CMS bytes). Null when it is not encapsulated SignedData. The content is only signed, not secret.
     */
    fun opaqueContent(cms: ByteArray): ByteArray? = runCatching {
        val signed = org.bouncycastle.cms.CMSSignedData(cms)
        if (signed.isDetachedSignature) null else signed.signedContent?.content as? ByteArray
    }.getOrNull()

    private val signatureTypes = setOf("application/pkcs7-signature", "application/x-pkcs7-signature")

    /** True for the detached-signature part of an S/MIME message: it is verification data, never a user attachment. */
    fun isSignaturePart(mime: String?): Boolean = mime?.substringBefore(';')?.trim()?.lowercase() in signatureTypes

    internal fun isSignedProtocol(protocol: String?): Boolean = protocol?.lowercase() in signatureTypes
}
