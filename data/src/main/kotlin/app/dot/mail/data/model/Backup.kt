package app.dot.mail.data.model

/**
 * What an import would do (preview) or did (result), in plain sentences for the user.
 * [error] set = the file can't be used at all (wrong app, newer format, not JSON...).
 */
data class BackupPreview(
    val lines: List<String>,
    /** Accounts that will be (or were) re-created and need their password / browser sign-in. */
    val accountsNeedingSignIn: List<String> = emptyList(),
    val error: String? = null,
    /** The same information, structured, so the UI can phrase it in the user's language ([lines] is English). */
    val items: List<BackupItem> = emptyList(),
    /** Entries of the file that were skipped as invalid (English, rare). */
    val warnings: List<String> = emptyList(),
)

data class BackupItem(val email: String, val host: String, val added: Boolean, val oauth: Boolean)
