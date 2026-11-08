package app.dot.mail.engine

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.util.SharedFileInputStream
import java.io.Closeable
import java.io.File
import java.util.Properties

/** Header edits and SMTP/APPEND read the body from disk; close promptly instead of awaiting stream finalizers. */
class StoredMimeMessage private constructor(private val source: SharedFileInputStream) : MimeMessage(Session.getInstance(Properties()), source), Closeable {
    constructor(file: File) : this(SharedFileInputStream(file))
    override fun close() { try { contentStream?.close() } finally { source.close() } }
}
