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
)
