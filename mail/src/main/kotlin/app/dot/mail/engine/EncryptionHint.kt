package app.dot.mail.engine

import jakarta.mail.internet.ContentType

/** Encryption classification based only on an explicit MIME encryption container. */
enum class EncryptionHint { NONE, OPENPGP, SMIME, UNSUPPORTED }

fun encryptionHintFromMime(rawContentType: String?): EncryptionHint {
    if (rawContentType.isNullOrBlank()) return EncryptionHint.NONE
    val type = runCatching { ContentType(rawContentType) }.getOrNull() ?: return EncryptionHint.NONE
    return when (type.baseType.lowercase()) {
        "multipart/encrypted" -> if (type.getParameter("protocol").equals("application/pgp-encrypted", true)) {
            EncryptionHint.OPENPGP
        } else EncryptionHint.UNSUPPORTED
        "application/pkcs7-mime", "application/x-pkcs7-mime" ->
            when (type.getParameter("smime-type")?.lowercase()) {
                "enveloped-data", "authenveloped-data" -> EncryptionHint.SMIME
                else -> EncryptionHint.NONE
            }
        else -> EncryptionHint.NONE
    }
}
