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
    /** Only mail newer than this many days is kept offline; 0 = everything. UI offers 30 / 90 / 365 / 0. */
    val syncWindowDays: Int = 90,
    val notificationsEnabled: Boolean = true,
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
    val sync: StateFlow<SyncSettings> = _sync.asStateFlow()

    private fun read(id: Long): AccountSettings {
        val d = AccountSettings()
        return AccountSettings(
            signature = prefs.getString("$id.signature", d.signature) ?: d.signature,
            syncIntervalMinutes = prefs.getInt("$id.interval", d.syncIntervalMinutes),
            syncWindowDays = prefs.getInt("$id.window", d.syncWindowDays),
            notificationsEnabled = prefs.getBoolean("$id.notify", d.notificationsEnabled),
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

    /** Drop everything stored for a removed account. */
    @Synchronized
    fun forget(accountId: Long) {
        flows.remove(accountId)
        prefs.edit { prefs.all.keys.filter { it.startsWith("$accountId.") }.forEach(::remove) }
    }

    companion object {
        @Volatile private var instance: AccountSettingsStore? = null
        fun get(context: Context): AccountSettingsStore =
            instance ?: synchronized(this) { instance ?: AccountSettingsStore(context.applicationContext).also { instance = it } }
    }
}
