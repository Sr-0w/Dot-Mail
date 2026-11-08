package app.dot.mail.data

import app.dot.mail.data.local.MessageRow
import app.dot.mail.engine.text.Category

internal fun messageCategory(message: MessageRow): Category? = Codec.field(message.header,"category")?.let { value -> Category.entries.firstOrNull { it.name==value } }
internal fun isNewsletter(message: MessageRow, role: String?): Boolean = messageCategory(message)==Category.NEWSLETTER && role!=null && role !in setOf("TRASH","SPAM","DRAFTS","SENT")

/** JSON is emitted canonically by JSONObject. Quoted subject text is escaped, so it cannot match this field marker.
 * Avoid SQLite JSON1, which is not consistently available on our oldest supported Android versions. */
internal fun newsletterPredicate(message: String, folder: String): String =
    "$message.header LIKE '%\"category\":\"NEWSLETTER\"%' AND $folder.role NOT IN ('TRASH','SPAM','DRAFTS','SENT')"
