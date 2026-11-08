package app.dot.mail.data.model

/** Coarse type filters of the attachment browser. [OTHER] is everything the other three don't claim. */
enum class AttachmentKind {
    IMAGES, PDFS, DOCUMENTS, OTHER;

    companion object {
        /** Which bucket a MIME type falls into. The SQL used by the browser is derived from the same rules. */
        fun of(mime: String): AttachmentKind {
            val m = mime.trim().lowercase()
            return when {
                m.startsWith("image/") -> IMAGES
                m == "application/pdf" -> PDFS
                isDocument(m) -> DOCUMENTS
                else -> OTHER
            }
        }

        private fun isDocument(m: String) =
            (m.startsWith("text/") && m != "text/calendar") || m.startsWith("application/msword") ||
                m.startsWith("application/vnd.openxmlformats") || m.startsWith("application/vnd.ms-") ||
                m.startsWith("application/vnd.oasis.opendocument") || m == "application/rtf"
    }
}

/** One attachment in the cross-account browser, with enough of its message to show and open it. */
data class AttachmentItem(
    val attachment: Attachment,
    val accountId: Long,
    val from: EmailAddress,
    val subject: String,
    val sentAt: Long,
) {
    /** True once the file is on the device: only then a thumbnail may be drawn (nothing is fetched just to preview). */
    val isDownloaded: Boolean get() = attachment.localUri != null
}
