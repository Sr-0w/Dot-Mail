package app.dot.mail.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Per-account preferences the sync engine and the UI both read. Stored apart from Room (no schema migration). */
data class AccountSettings(
    /** Plain-text signature appended to new messages. */
    val signature: String = "",
    /** Minutes between background syncs of this account; 0 = manual only. UI offers 15 / 30 / 60 / 0. */
    val syncIntervalMinutes: Int = 15,
    /** Only mail from the last N days is downloaded when a folder is (re)synced; 0 = everything (up to 5000 per folder). UI offers 14 / 30 / 90 / 0. */
    val syncWindowDays: Int = 30,
    val notificationsEnabled: Boolean = true,
    /** OpenPGP defaults for new messages from this account (used only when OpenKeychain is installed). */
    val pgpSignDefault: Boolean = false,
    val pgpEncryptDefault: Boolean = false,
)

/** Settings that apply to syncing as a whole. */
data class SyncSettings(
    val wifiOnly: Boolean = false,
    /** Keep a connection open for instant notifications (foreground-service mode). */
    val instantNotifications: Boolean = false,
)

/**
 * SharedPreferences-backed store, readable synchronously from workers ([current]) and observable from the UI
 * ([settings], [sync]). One process-wide instance per application: [get].
 */
class AccountSettingsStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("dotmail_account_settings", Context.MODE_PRIVATE)
    private val flows = HashMap<Long, MutableStateFlow<AccountSettings>>()
    private val _sync = MutableStateFlow(readSync())
    private val _lastSynced = MutableStateFlow(
        prefs.all.mapNotNull { (k, v) -> if (k.endsWith(".lastSync") && v is Long) k.substringBefore('.').toLongOrNull()?.let { it to v } else null }.toMap()
    )
    /** Last successful sync per account id, observable for the inbox's "Last synced" label. */
    val lastSyncedAll: StateFlow<Map<Long, Long>> = _lastSynced.asStateFlow()
    val sync: StateFlow<SyncSettings> = _sync.asStateFlow()

    private fun read(id: Long): AccountSettings {
        val d = AccountSettings()
        return AccountSettings(
            signature = prefs.getString("$id.signature", d.signature) ?: d.signature,
            syncIntervalMinutes = prefs.getInt("$id.interval", d.syncIntervalMinutes),
            syncWindowDays = prefs.getInt("$id.window", d.syncWindowDays),
            notificationsEnabled = prefs.getBoolean("$id.notify", d.notificationsEnabled),
            pgpSignDefault = prefs.getBoolean("$id.pgpSign", d.pgpSignDefault),
            pgpEncryptDefault = prefs.getBoolean("$id.pgpEncrypt", d.pgpEncryptDefault),
        )
    }

    private fun readSync() = SyncSettings(prefs.getBoolean("sync.wifiOnly", false), prefs.getBoolean("sync.instant", false))

    @Synchronized
    private fun flow(id: Long) = flows.getOrPut(id) { MutableStateFlow(read(id)) }

    fun settings(accountId: Long): StateFlow<AccountSettings> = flow(accountId).asStateFlow()

    fun current(accountId: Long): AccountSettings = flow(accountId).value

    @Synchronized
    fun update(accountId: Long, transform: (AccountSettings) -> AccountSettings) {
        val f = flow(accountId)
        val next = transform(f.value)
        f.value = next
        prefs.edit {
            putString("$accountId.signature", next.signature)
            putInt("$accountId.interval", next.syncIntervalMinutes)
            putInt("$accountId.window", next.syncWindowDays)
            putBoolean("$accountId.notify", next.notificationsEnabled)
            putBoolean("$accountId.pgpSign", next.pgpSignDefault)
            putBoolean("$accountId.pgpEncrypt", next.pgpEncryptDefault)
        }
    }

    @Synchronized
    fun updateSync(transform: (SyncSettings) -> SyncSettings) {
        val next = transform(_sync.value)
        _sync.value = next
        prefs.edit {
            putBoolean("sync.wifiOnly", next.wifiOnly)
            putBoolean("sync.instant", next.instantNotifications)
        }
    }

    internal fun snippetRevision(accountId:Long):Int=prefs.getInt("$accountId.snippetRevision",0)
    internal fun markSnippetRevision(accountId:Long,revision:Int) = prefs.edit { putInt("$accountId.snippetRevision",revision) }

    // ---- bookkeeping for the sync engine (not user settings) ----
    fun lastSynced(accountId: Long): Long = prefs.getLong("$accountId.lastSync", 0L)
    @Synchronized
    fun markSynced(accountId: Long, at: Long = System.currentTimeMillis()) {
        prefs.edit { putLong("$accountId.lastSync", at) }
        _lastSynced.value = _lastSynced.value + (accountId to at)
    }

    /** The window the folders were last downloaded with; null if never recorded (then the current one is assumed). */
    fun appliedWindow(accountId: Long): Int? = if (prefs.contains("$accountId.appliedWindow")) prefs.getInt("$accountId.appliedWindow", 0) else null
    fun markWindowApplied(accountId: Long, days: Int) = prefs.edit { putInt("$accountId.appliedWindow", days) }

    /** Drop everything stored for a removed account. */
    @Synchronized
    fun forget(accountId: Long) {
        flows.remove(accountId)
        _lastSynced.value = _lastSynced.value - accountId
        prefs.edit { prefs.all.keys.filter { it.startsWith("$accountId.") }.forEach(::remove) }
    }

    companion object {
        @Volatile private var instance: AccountSettingsStore? = null
        fun get(context: Context): AccountSettingsStore =
            instance ?: synchronized(this) { instance ?: AccountSettingsStore(context.applicationContext).also { instance = it } }
    }
}
