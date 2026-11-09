package app.dot.mail.engine

import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties

/** S/MIME MIME wrappers; all cryptographic inputs are the exact serialized inner MIME entity bytes. */
internal object SmimeMime {
    private val crlf = "\r\n"
    private val random = SecureRandom()

    fun signedMessage(mail: OutgoingMail, signedEntity: ByteArray): ByteArray =
        message(mail, signedEntity)

    fun envelopedMessage(mail: OutgoingMail, cms: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(outerHeaders(mail))
        out.write((
            "Content-Type: application/pkcs7-mime; smime-type=enveloped-data; name=\"smime.p7m\"$crlf" +
                "Content-Transfer-Encoding: base64$crlf" +
                "Content-Disposition: attachment; filename=\"smime.p7m\"$crlf$crlf"
            ).toByteArray(Charsets.US_ASCII))
        out.write(Base64.getMimeEncoder(64, crlf.toByteArray(Charsets.US_ASCII)).encode(cms))
        out.write(crlf.toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    /** Returns a complete multipart/signed MIME entity, suitable for nesting inside EnvelopedData. */
    fun signedEntity(entity: ByteArray, detachedSignature: ByteArray): ByteArray {
        val boundary = boundary(entity, detachedSignature)
        val out = ByteArrayOutputStream()
        out.write((
            "Content-Type: multipart/signed; protocol=\"application/pkcs7-signature\"; micalg=sha-256; boundary=\"$boundary\"$crlf$crlf" +
                "This is an S/MIME signed message.$crlf$crlf" +
                "--$boundary$crlf"
            ).toByteArray(Charsets.US_ASCII))
        out.write(stripFinalCrlf(entity))
        out.write((
            "$crlf--$boundary$crlf" +
                "Content-Type: application/pkcs7-signature; name=\"smime.p7s\"$crlf" +
                "Content-Transfer-Encoding: base64$crlf" +
                "Content-Disposition: attachment; filename=\"smime.p7s\"$crlf$crlf"
            ).toByteArray(Charsets.US_ASCII))
        out.write(Base64.getMimeEncoder(64, crlf.toByteArray(Charsets.US_ASCII)).encode(detachedSignature))
        out.write(("$crlf--$boundary--$crlf").toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    private fun message(mail: OutgoingMail, entity: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(outerHeaders(mail))
        out.write(stripFinalCrlf(entity))
        return out.toByteArray()
    }

    private fun outerHeaders(mail: OutgoingMail): ByteArray {
        // Bcc is intentionally cleared before this point. Ignore body headers and emit one wrapper Content-Type.
        val headerMessage = Mime.build(mail.copy(text = "", html = null, attachments = emptyList(), calendarReply = null, bcc = emptyList()))
        val headers = headerLines(headerMessage)
            .filterNot { line ->
                val name = line.substringBefore(':')
                name.startsWith("Content-", ignoreCase = true) ||
                    name.equals("Bcc", ignoreCase = true) ||
                    name.equals("MIME-Version", ignoreCase = true)
            }
        return (headers.joinToString(crlf, postfix = "$crlf") + "MIME-Version: 1.0$crlf")
            .toByteArray(Charsets.ISO_8859_1)
    }

    private fun headerLines(message: MimeMessage): List<String> {
        val output = ArrayList<String>()
        val headers = message.allHeaderLines
        while (headers.hasMoreElements()) output += headers.nextElement() as String
        return output
    }

    private fun stripFinalCrlf(bytes: ByteArray): ByteArray =
        if (bytes.size >= 2 && bytes[bytes.lastIndex - 1] == '\r'.code.toByte() && bytes.last() == '\n'.code.toByte()) {
            bytes.copyOf(bytes.size - 2)
        } else bytes

    private fun boundary(vararg content: ByteArray): String {
        while (true) {
            val candidate = "=_dotmail-smime-" + ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            if (content.none { String(it, Charsets.ISO_8859_1).contains(candidate) }) return candidate
        }
    }
}
