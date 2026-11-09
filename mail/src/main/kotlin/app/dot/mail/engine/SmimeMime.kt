package app.dot.mail.engine

import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.ContentType
import jakarta.mail.Session
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties

/** S/MIME MIME wrappers; all cryptographic inputs are the exact serialized inner MIME entity bytes. */
internal object SmimeMime {
    private val crlf = "\r\n"
    private val random = SecureRandom()

    fun signedMessage(mail: OutgoingMail, signedEntity: ByteArray): ByteArray =
        message(mail, signedEntity)

    fun envelopedMessage(mail: OutgoingMail, cms: ByteArray, authenticated: Boolean = false): ByteArray {
        val smimeType = if (authenticated) "auth-enveloped-data" else "enveloped-data"
        val out = ByteArrayOutputStream()
        out.write(outerHeaders(mail))
        out.write((
            "Content-Type: application/pkcs7-mime; smime-type=$smimeType; name=\"smime.p7m\"$crlf" +
                "Content-Transfer-Encoding: base64$crlf" +
                "Content-Disposition: attachment; filename=\"smime.p7m\"$crlf$crlf"
            ).toByteArray(Charsets.US_ASCII))
        out.write(Base64.getMimeEncoder(64, crlf.toByteArray(Charsets.US_ASCII)).encode(cms))
        out.write(crlf.toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    /** Extracts the signed entity as transmitted; parsing and reserializing it would invalidate valid signatures. */
    fun detachedParts(messageBytes: ByteArray): Pair<ByteArray, ByteArray> {
        val message = MimeMessage(Session.getInstance(Properties()), messageBytes.inputStream())
        val contentType = ContentType(message.contentType)
        require(contentType.baseType.equals("multipart/signed", ignoreCase = true)) { "Expected multipart/signed" }
        require(contentType.getParameter("protocol").equals("application/pkcs7-signature", ignoreCase = true) ||
            contentType.getParameter("protocol").equals("application/x-pkcs7-signature", ignoreCase = true)) {
            "Expected an S/MIME detached signature"
        }
        val boundary = contentType.getParameter("boundary")?.takeIf { it.isNotEmpty() }
            ?: error("multipart/signed has no boundary")
        val raw = String(messageBytes, Charsets.ISO_8859_1)
        val headersEnd = raw.indexOf("\r\n\r\n")
        require(headersEnd >= 0) { "MIME message has no header/body separator" }
        val bodyStart = headersEnd + 4
        val first = findBoundary(raw, boundary, bodyStart) ?: error("First signed MIME boundary is missing")
        require(!first.closing) { "First signed MIME boundary is the closing delimiter" }
        val entityStart = first.afterLine
        val second = findBoundary(raw, boundary, entityStart) ?: error("Detached signature MIME part is missing")
        require(!second.closing) { "Detached signature MIME part is missing" }
        val signaturePartStart = second.afterLine
        val closing = findBoundary(raw, boundary, signaturePartStart) ?: error("Closing signed MIME boundary is missing")
        require(closing.closing) { "Expected exactly two multipart/signed parts" }

        val entity = raw.substring(entityStart, second.contentEnd).toByteArray(Charsets.ISO_8859_1)
        val signaturePart = raw.substring(signaturePartStart, closing.contentEnd).toByteArray(Charsets.ISO_8859_1)
        val parsedSignature = MimeBodyPart(ByteArrayInputStream(signaturePart))
        val signatureType = ContentType(parsedSignature.contentType).baseType
        require(signatureType.equals("application/pkcs7-signature", ignoreCase = true) ||
            signatureType.equals("application/x-pkcs7-signature", ignoreCase = true)) {
            "Second signed MIME part is not a PKCS#7 signature"
        }
        return entity to parsedSignature.inputStream.use { it.readBytes() }
    }

    private data class BoundaryLine(val contentEnd: Int, val afterLine: Int, val closing: Boolean)

    /** RFC 2046 transport padding allows spaces/tabs after the boundary marker. */
    private fun findBoundary(raw: String, boundary: String, from: Int): BoundaryLine? {
        val pattern = Regex("(?:^|\\r\\n)--${Regex.escape(boundary)}(--)?[ \\t]*(?:\\r\\n|$)")
        val match = pattern.find(raw, from) ?: return null
        return BoundaryLine(match.range.first, match.range.last + 1, match.groupValues[1] == "--")
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
