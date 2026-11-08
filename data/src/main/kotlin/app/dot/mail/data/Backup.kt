package app.dot.mail.data

import app.dot.mail.data.backup.AccountsBackup
import app.dot.mail.data.backup.AccountsBackupCodec
import app.dot.mail.data.backup.BackupAccount
import app.dot.mail.data.backup.BackupGuard
import app.dot.mail.data.backup.BackupIdentity
import app.dot.mail.data.backup.BackupServer
import app.dot.mail.data.model.AccountConfig
import app.dot.mail.data.model.AuthType
import app.dot.mail.data.model.BackupPreview
import app.dot.mail.data.model.Identity
import app.dot.mail.data.model.Security
import app.dot.mail.data.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Reads the account-related state into a credential-free [AccountsBackup]. */
internal suspend fun RealMailGraph.exportAccountsBackup(): String = withContext(Dispatchers.IO) {
    val settings = AccountSettingsStore.get(context)
    val rules = NotificationRules.get(context)
    val accounts = dao.accounts().map { row ->
        val config = Codec.config(row.config)
        val folderNotify = dao.folders(row.id).mapNotNull { f -> rules.folderOverrides.value[f.id]?.let { f.path to it } }.toMap()
        val s = settings.current(row.id)
        BackupAccount(
            email = row.email, displayName = row.name,
            imap = BackupServer(config.imap.host, config.imap.port, config.imap.security.name, config.imap.username),
            smtp = BackupServer(config.smtp.host, config.smtp.port, config.smtp.security.name, config.smtp.username),
            authType = config.authType.name,
            identities = identityStore.resolve(row.id, row.name, row.email).map {
                BackupIdentity(it.name, it.email, it.replyTo, it.signature, it.signatureHtml, it.isDefault)
            },
            settings = s, folderNotify = folderNotify,
        )
    }
    val json = AccountsBackupCodec.encode(AccountsBackup(accounts, settings.sync.value, rules.actions.value))
    BackupGuard.assertNoSecrets(json)
    json.toString()
}

private fun parse(json: String): Pair<app.dot.mail.data.backup.DecodedAccounts?, String?> = try {
    val root = Json.parseToJsonElement(json) as? JsonObject
    if (root == null) null to "This file isn't a Dot Mail backup." else AccountsBackupCodec.decode(root) to null
} catch (e: Exception) {
    null to "This file isn't a Dot Mail backup."
}

internal suspend fun RealMailGraph.previewAccountsBackup(json: String): BackupPreview = withContext(Dispatchers.IO) {
    val (decoded, error) = parse(json)
    if (decoded == null) return@withContext BackupPreview(emptyList(), error = error)
    val existing = dao.accounts().associateBy { it.email.lowercase() }
    val lines = mutableListOf<String>()
    val needSignIn = mutableListOf<String>()
    decoded.backup.accounts.forEach { a ->
        if (existing[a.email.lowercase()] != null) {
            lines += "${a.email}: update identities (${a.identities.size}), signatures and preferences. Servers and password stay as they are."
        } else {
            lines += "${a.email}: add the account (${a.imap.host}). " + if (a.authType == "OAUTH2") "You will sign in again in the browser." else "You will enter its password."
            needSignIn += a.email
        }
    }
    decoded.warnings.forEach { lines += "Skipped: $it" }
    BackupPreview(lines, needSignIn)
}

internal suspend fun RealMailGraph.applyAccountsBackup(json: String): BackupPreview = withContext(Dispatchers.IO) {
    val (decoded, error) = parse(json)
    if (decoded == null) return@withContext BackupPreview(emptyList(), error = error)
    val lines = mutableListOf<String>()
    val needSignIn = mutableListOf<String>()
    val settingsStore = AccountSettingsStore.get(context)
    val rules = NotificationRules.get(context)
    for (a in decoded.backup.accounts) {
        var row = dao.accounts().firstOrNull { it.email.equals(a.email, ignoreCase = true) }
        if (row == null) {
            val config = AccountConfig(
                a.email, a.displayName,
                ServerConfig(a.imap.host, a.imap.port, Security.valueOf(a.imap.security), a.imap.username),
                ServerConfig(a.smtp.host, a.smtp.port, Security.valueOf(a.smtp.security), a.smtp.username),
                AuthType.valueOf(a.authType),
            )
            // An empty secret: the account exists but can't log in until the user signs in again (the normal sign-in-again flow).
            val id = addAccount(config, "")
            dao.syncError(id, "Sign in to finish restoring this account", true)
            row = dao.account(id)
            needSignIn += a.email
            lines += "Added ${a.email}; sign in to start syncing."
        } else lines += "Updated ${a.email}."
        val account = requireNotNull(row)
        settingsStore.update(account.id) { it.copy(syncIntervalMinutes = a.settings.syncIntervalMinutes, syncWindowDays = a.settings.syncWindowDays, notificationsEnabled = a.settings.notificationsEnabled) }
        val current = identityStore.resolve(account.id, account.name, account.email)
        var defaultId: Long? = null
        for (i in a.identities) {
            val primary = i.email.equals(account.email, ignoreCase = true)
            val match = if (primary) current.firstOrNull { it.id == account.id } else current.firstOrNull { it.email.equals(i.email, ignoreCase = true) }
            val id = identityStore.save(
                Identity(match?.id ?: if (primary) account.id else 0L, account.id, i.name, i.email, i.signature, false, i.replyTo, i.signatureHtml),
                account.email,
            )
            if (i.isDefault) defaultId = id
        }
        defaultId?.let { identityStore.setDefault(account.id, it) }
        val folders = dao.folders(account.id).associateBy { it.path }
        a.folderNotify.forEach { (path, on) -> folders[path]?.let { rules.setFolderNotify(it.id, it.role, on) } }
    }
    settingsStore.updateSync { decoded.backup.sync }
    if (decoded.backup.notificationActions.size == 2) rules.setActions(decoded.backup.notificationActions[0], decoded.backup.notificationActions[1])
    decoded.warnings.forEach { lines += "Skipped: $it" }
    BackupPreview(lines, needSignIn)
}
