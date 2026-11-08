package app.dot.mail.engine

import jakarta.mail.Part

/** OpenPGP message shapes Dot Mail recognises. Crypto itself is done by OpenKeychain; the engine only keeps the bytes. */
object Pgp {
    /** Files written next to a cached body (see [Mime.cache]). */
    const val ENCRYPTED_FILE = "pgp-encrypted.asc"
    const val SIGNED_FILE = "pgp-signed.mime"
    const val SIGNATURE_FILE = "pgp-signature.asc"

    internal fun protocol(part: Part): String? = runCatching {
        jakarta.mail.internet.ContentType(part.contentType).getParameter("protocol")?.trim()?.lowercase()
    }.getOrNull()

    enum class Inline { ENCRYPTED, SIGNED }

    /** Inline (non-MIME) OpenPGP in a plain-text body, by its armour header at the start of a line. */
    fun inline(text: String?): Inline? {
        if (text == null) return null
        val lines = text.lineSequence().map { it.trim() }
        for (line in lines) {
            if (line == "-----BEGIN PGP MESSAGE-----") return Inline.ENCRYPTED
            if (line == "-----BEGIN PGP SIGNED MESSAGE-----") return Inline.SIGNED
        }
        return null
    }

    /**
     * Turns OpenKeychain's output for a PGP/MIME message (a MIME entity) into displayable text/HTML. If it isn't MIME
     * (inline PGP), the bytes are returned as plain text.
     */
    fun parseDecrypted(bytes: ByteArray, mime: Boolean): ParsedBody {
        if (!mime) return ParsedBody(bytes.toString(Charsets.UTF_8), null, emptyList())
        return runCatching { Mime.parse(jakarta.mail.internet.MimeBodyPart(bytes.inputStream())) }
            .getOrElse { ParsedBody(bytes.toString(Charsets.UTF_8), null, emptyList()) }
    }
}
