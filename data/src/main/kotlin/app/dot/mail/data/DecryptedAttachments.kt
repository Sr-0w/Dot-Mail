package app.dot.mail.data

import app.dot.mail.data.model.Attachment
import app.dot.mail.data.model.DecryptedBytes
import app.dot.mail.engine.MimeAttachment
import app.dot.mail.engine.ParsedBody
import app.dot.mail.engine.SmimeParts
import java.io.File
import java.util.UUID

/**
 * The attachments found inside a decrypted message, for the reader's attachment strip. They carry their plaintext in memory
 * ([Attachment.memory]) and have negative ids (never a database id); nothing is written anywhere until the user opens or
 * shares one (see [DecryptedFiles]) or an inline image has to be rendered.
 */
fun ParsedBody.toDecryptedAttachments(messageId: Long): List<Attachment> = attachments
    // The detached signature of a sign-then-encrypt message is verification data (shown by the signature line), not a file.
    .filterNot { SmimeParts.isSignaturePart(it.mimeType) || it.mimeType.substringBefore(';').trim().equals("application/pgp-signature", ignoreCase = true) }
    .mapIndexed { index, a ->
    Attachment(
        id = -(index + 1L), messageId = messageId, fileName = a.name.ifBlank { "attachment" }, mimeType = a.mimeType,
        sizeBytes = a.bytes.size.toLong(), contentId = a.contentId?.trim('<', '>'), isInline = a.inline, localUri = null,
        memory = DecryptedBytes(a.bytes),
    )
}

/**
 * Short-lived app-private files for decrypted attachments, the only place their plaintext touches storage: written when an
 * attachment is opened/shared or an inline image is shown, under `attachments/decrypted-open/<token>/` (served by the
 * existing FileProvider), and deleted when the reader that asked for them closes ([clear]), and at every app start.
 * [token] is per reader, so closing one conversation never removes another's files.
 */
internal class DecryptedFiles(private val filesDir: File, private val toUri: (File) -> String) {
    private val root get() = File(filesDir, ROOT)

    fun write(token: String, attachment: Attachment, safeName: (String) -> String): String {
        val memory = requireNotNull(attachment.memory) { "Not a decrypted attachment" }
        val dir = File(root, "${safeToken(token)}/${UUID.randomUUID()}").also { it.mkdirs() }
        val file = File(dir, safeName(attachment.fileName))
        file.writeBytes(memory.bytes)
        return toUri(file)
    }

    fun clear(token: String) { File(root, safeToken(token)).deleteRecursively() }

    /** Stale files from a process that died while a reader was open. */
    fun clearAll() { root.deleteRecursively() }

    private fun safeToken(token: String) = token.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "x" }

    companion object { const val ROOT = "attachments/decrypted-open" }
}
