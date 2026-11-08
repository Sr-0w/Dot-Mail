package app.dot.mail.engine

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * Builds OpenPGP/MIME (RFC 3156) messages from an [OutgoingMail]. The engine never does cryptography: it produces the
 * canonical MIME entity to sign/encrypt ([entity]) and wraps what OpenKeychain returns ([assembleSigned],
 * [assembleEncrypted]). Everything is byte-oriented with CRLF line endings, because signatures are over exact bytes.
 *
 * Protected headers (subject inside the encrypted part) are not implemented: the outer Subject stays visible.
 */
object PgpMime {
    const val MICALG = "pgp-sha256"
    private val CRLF = "\r\n"

    /** Headers of the plain message that belong on the outer message of a PGP/MIME wrapper (everything but Content-*). */
    private fun outerHeaders(mail: OutgoingMail): String {
        val headerOnly = Mime.build(mail.copy(text = "", html = null, attachments = emptyList(), calendarReply = null))
        return headerLines(headerOnly).filterNot { it.startsWith("Content-", ignoreCase = true) }.joinToString("") { it + CRLF }
    }

    /** The unfolded-as-written header lines of [message] (continuation lines stay attached to their header). */
    private fun headerLines(message: jakarta.mail.internet.MimeMessage): List<String> {
        val out = ArrayList<String>()
        val e = message.allHeaderLines
        while (e.hasMoreElements()) out += e.nextElement() as String
        return out
    }

    /**
     * The MIME entity of [mail]'s content (its Content-Type/Content-Transfer-Encoding headers, a blank line and the body),
     * canonical CRLF, **without** the final line break that belongs to the following boundary. This is exactly what gets
     * signed (PGP/MIME signed) or encrypted (PGP/MIME encrypted).
     */
    fun entity(mail: OutgoingMail): ByteArray {
        val message = Mime.build(mail)
        val keep = headerLines(message).map { it.substringBefore(':') }.filterNot { it.startsWith("Content-", ignoreCase = true) }.distinct()
        val out = ByteArrayOutputStream()
        message.writeTo(out, keep.toTypedArray())
        var bytes = out.toByteArray()
        if (bytes.size >= 2 && bytes[bytes.size - 2] == '\r'.code.toByte() && bytes.last() == '\n'.code.toByte()) bytes = bytes.copyOf(bytes.size - 2)
        return bytes
    }

    /** multipart/signed: [entity] verbatim + the detached ASCII-armoured [signature]. */
    fun assembleSigned(mail: OutgoingMail, entity: ByteArray, signature: ByteArray, micalg: String = MICALG): ByteArray {
        val boundary = boundary(entity)
        val out = ByteArrayOutputStream()
        out.write((outerHeaders(mail) +
            "Content-Type: multipart/signed; micalg=$micalg; protocol=\"application/pgp-signature\"; boundary=\"$boundary\"$CRLF$CRLF" +
            "This is an OpenPGP/MIME signed message (RFC 4880 and 3156)$CRLF$CRLF" +
            "--$boundary$CRLF").toByteArray(Charsets.US_ASCII))
        out.write(entity)
        out.write((CRLF + "--$boundary$CRLF" +
            "Content-Type: application/pgp-signature; name=\"signature.asc\"$CRLF" +
            "Content-Description: OpenPGP digital signature$CRLF" +
            "Content-Disposition: attachment; filename=\"signature.asc\"$CRLF$CRLF").toByteArray(Charsets.US_ASCII))
        out.write(crlf(signature))
        out.write((CRLF + "--$boundary--$CRLF").toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    /** multipart/encrypted: the version part and the ASCII-armoured [ciphertext] as encrypted.asc. */
    fun assembleEncrypted(mail: OutgoingMail, ciphertext: ByteArray): ByteArray {
        val boundary = boundary(ciphertext)
        val out = ByteArrayOutputStream()
        out.write((outerHeaders(mail) +
            "Content-Type: multipart/encrypted; protocol=\"application/pgp-encrypted\"; boundary=\"$boundary\"$CRLF$CRLF" +
            "This is an OpenPGP/MIME encrypted message (RFC 4880 and 3156)$CRLF$CRLF" +
            "--$boundary$CRLF" +
            "Content-Type: application/pgp-encrypted$CRLF" +
            "Content-Description: PGP/MIME version identification$CRLF$CRLF" +
            "Version: 1$CRLF$CRLF" +
            "--$boundary$CRLF" +
            "Content-Type: application/octet-stream; name=\"encrypted.asc\"$CRLF" +
            "Content-Description: OpenPGP encrypted message$CRLF" +
            "Content-Disposition: inline; filename=\"encrypted.asc\"$CRLF$CRLF").toByteArray(Charsets.US_ASCII))
        out.write(crlf(ciphertext))
        out.write((CRLF + "--$boundary--$CRLF").toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    /** Normalises line endings to CRLF and drops trailing line breaks (the delimiter supplies them). */
    private fun crlf(armor: ByteArray): ByteArray =
        String(armor, Charsets.ISO_8859_1).replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n').replace("\n", CRLF).toByteArray(Charsets.ISO_8859_1)

    private val random = SecureRandom()

    /** A boundary that provably does not occur in [content]. */
    private fun boundary(content: ByteArray): String {
        val text = String(content, Charsets.ISO_8859_1)
        while (true) {
            val candidate = "=_dotmail-" + ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            if (candidate !in text) return candidate
        }
    }
}
