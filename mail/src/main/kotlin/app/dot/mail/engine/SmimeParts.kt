package app.dot.mail.engine

/** Files written next to a cached body for an S/MIME signed message (see [Mime.cache]). */
object SmimeParts {
    /** The signed MIME entity, byte for byte as transmitted (headers + body). */
    const val SIGNED_FILE = "smime-signed.mime"
    /** The detached CMS SignedData (DER, transfer encoding removed). */
    const val SIGNATURE_FILE = "smime-signature.p7s"
    /** The CMS EnvelopedData/AuthEnvelopedData of an encrypted message (DER). Plaintext is never written to disk. */
    const val ENCRYPTED_FILE = "smime-encrypted.p7m"

    private val signatureTypes = setOf("application/pkcs7-signature", "application/x-pkcs7-signature")

    /** True for the detached-signature part of an S/MIME message: it is verification data, never a user attachment. */
    fun isSignaturePart(mime: String?): Boolean = mime?.substringBefore(';')?.trim()?.lowercase() in signatureTypes

    internal fun isSignedProtocol(protocol: String?): Boolean = protocol?.lowercase() in signatureTypes
}
