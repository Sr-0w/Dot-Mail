package app.dot.mail.data.backup

import app.dot.mail.data.AccountSettings
import app.dot.mail.data.NotificationAction
import app.dot.mail.data.SyncSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * The account-related part of a settings backup. Pure data + JSON, so it is unit-tested on the JVM.
 * It never contains passwords, tokens or anything derived from them: only what is needed to set an account up again
 * (address, server names, ports, security, login name) plus identities, signatures and preferences.
 */

data class BackupServer(val host: String, val port: Int, val security: String, val username: String)

data class BackupIdentity(
    val name: String, val email: String, val replyTo: String?, val signature: String?, val signatureHtml: String?, val isDefault: Boolean,
)

data class BackupAccount(
    val email: String,
    val displayName: String,
    val imap: BackupServer,
    val smtp: BackupServer,
    /** "PASSWORD" or "OAUTH2". OAuth accounts come back needing browser sign-in. */
    val authType: String,
    val identities: List<BackupIdentity>,
    val settings: AccountSettings,
    /** Explicit "notify for new mail" choices by folder path. */
    val folderNotify: Map<String, Boolean>,
)

data class AccountsBackup(
    val accounts: List<BackupAccount>,
    val sync: SyncSettings,
    val notificationActions: List<NotificationAction>,
)

class DecodedAccounts(val backup: AccountsBackup, val warnings: List<String>)

object AccountsBackupCodec {
    const val MAX_ACCOUNTS = 50
    const val MAX_IDENTITIES = 50
    private const val MAX_TEXT = 200
    private const val MAX_SIGNATURE = 20_000
    private val EMAIL = Regex("^[^\\s@<>\",;]+@[^\\s@<>\",;]+\\.[^\\s@<>\",;]{2,}$")
    private val SECURITY = setOf("SSL_TLS", "STARTTLS", "NONE")

    fun encode(b: AccountsBackup): JsonObject = buildJsonObject {
        put("accounts", buildJsonArray {
            b.accounts.forEach { a ->
                add(buildJsonObject {
                    put("email", a.email); put("displayName", a.displayName); put("authType", a.authType)
                    put("imap", server(a.imap)); put("smtp", server(a.smtp))
                    put("identities", buildJsonArray {
                        a.identities.forEach { i ->
                            add(buildJsonObject {
                                put("name", i.name); put("email", i.email); put("replyTo", i.replyTo); put("signature", i.signature)
                                put("signatureHtml", i.signatureHtml); put("isDefault", i.isDefault)
                            })
                        }
                    })
                    put("settings", buildJsonObject {
                        put("syncIntervalMinutes", a.settings.syncIntervalMinutes); put("syncWindowDays", a.settings.syncWindowDays)
                        put("notificationsEnabled", a.settings.notificationsEnabled)
                    })
                    put("folderNotify", buildJsonObject { a.folderNotify.forEach { (path, on) -> put(path, on) } })
                })
            }
        })
        put("sync", buildJsonObject { put("wifiOnly", b.sync.wifiOnly); put("instantNotifications", b.sync.instantNotifications) })
        put("notificationActions", buildJsonArray { b.notificationActions.forEach { add(JsonPrimitive(it.name)) } })
    }

    private fun server(s: BackupServer) = buildJsonObject { put("host", s.host); put("port", s.port); put("security", s.security); put("username", s.username) }

    /** Lenient on purpose: bad items are dropped with a warning instead of failing the whole import. */
    fun decode(root: JsonObject): DecodedAccounts {
        val warnings = mutableListOf<String>()
        val accounts = (root["accounts"] as? JsonArray).orEmpty().take(MAX_ACCOUNTS).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val email = o.str("email")?.trim()
            if (email == null || !EMAIL.matches(email)) { warnings += "Skipped an account with an invalid address"; return@mapNotNull null }
            val imap = o.server("imap"); val smtp = o.server("smtp")
            if (imap == null || smtp == null) { warnings += "Skipped $email: incomplete server settings"; return@mapNotNull null }
            val authType = o.str("authType")?.takeIf { it == "PASSWORD" || it == "OAUTH2" } ?: "PASSWORD"
            val identities = (o["identities"] as? JsonArray).orEmpty().take(MAX_IDENTITIES).mapNotNull { ie ->
                val io = ie as? JsonObject ?: return@mapNotNull null
                val ie2 = io.str("email")?.trim()
                if (ie2 == null || !EMAIL.matches(ie2)) return@mapNotNull null
                BackupIdentity(
                    name = io.str("name").orEmpty().take(MAX_TEXT), email = ie2,
                    replyTo = io.str("replyTo")?.trim()?.takeIf { EMAIL.matches(it) },
                    signature = io.str("signature")?.take(MAX_SIGNATURE)?.ifEmpty { null },
                    signatureHtml = io.str("signatureHtml")?.take(MAX_SIGNATURE)?.ifEmpty { null },
                    isDefault = (io["isDefault"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
            val st = o["settings"] as? JsonObject
            val d = AccountSettings()
            val settings = AccountSettings(
                signature = "",
                syncIntervalMinutes = st?.int("syncIntervalMinutes")?.takeIf { it in setOf(0, 15, 30, 60) } ?: d.syncIntervalMinutes,
                syncWindowDays = st?.int("syncWindowDays")?.takeIf { it in setOf(0, 14, 30, 90) } ?: d.syncWindowDays,
                notificationsEnabled = (st?.get("notificationsEnabled") as? JsonPrimitive)?.booleanOrNull ?: d.notificationsEnabled,
            )
            val folders = (o["folderNotify"] as? JsonObject).orEmpty().mapNotNull { (path, v) ->
                (v as? JsonPrimitive)?.booleanOrNull?.let { path.take(MAX_TEXT) to it }
            }.toMap()
            BackupAccount(email, o.str("displayName").orEmpty().take(MAX_TEXT).ifBlank { email }, imap, smtp, authType, identities, settings, folders)
        }.distinctBy { it.email.lowercase() }
        val sync = (root["sync"] as? JsonObject)?.let {
            SyncSettings((it["wifiOnly"] as? JsonPrimitive)?.booleanOrNull == true, (it["instantNotifications"] as? JsonPrimitive)?.booleanOrNull == true)
        } ?: SyncSettings()
        val actions = (root["notificationActions"] as? JsonArray).orEmpty()
            .mapNotNull { e -> NotificationAction.entries.firstOrNull { it.name == (e as? JsonPrimitive)?.contentOrNull } }
            .let { if (it.size == 2 && it[0] != it[1]) it else emptyList() }
        return DecodedAccounts(AccountsBackup(accounts, sync, actions), warnings)
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.server(key: String): BackupServer? {
        val o = this[key] as? JsonObject ?: return null
        val host = o.str("host")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 253 && it.none { c -> c.isWhitespace() } } ?: return null
        val port = o.int("port")?.takeIf { it in 1..65535 } ?: return null
        val security = o.str("security")?.takeIf { it in SECURITY } ?: return null
        return BackupServer(host, port, security, o.str("username").orEmpty().take(MAX_TEXT))
    }

}

/** Defence in depth: a backup must never carry anything that looks like a credential. Throws if one is found. */
object BackupGuard {
    private val FORBIDDEN_KEYS = Regex("(?i)(pass(word|wd)?|secret|token|credential|api[-_]?key|private[-_]?key|oauth)")

    fun assertNoSecrets(el: JsonElement, path: String = "$") {
        when (el) {
            is JsonObject -> el.forEach { (k, v) ->
                require(!FORBIDDEN_KEYS.containsMatchIn(k)) { "Backup would contain a credential field at $path.$k" }
                assertNoSecrets(v, "$path.$k")
            }
            is JsonArray -> el.forEachIndexed { i, v -> assertNoSecrets(v, "$path[$i]") }
            else -> Unit
        }
    }

    val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
}
