package app.dot.mail.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The buttons a new-mail notification can carry. Exactly two are shown. */
enum class NotificationAction { ARCHIVE, DELETE, MARK_READ, REPLY }

/**
 * Who may notify: muted conversations, per-folder "notify for new mail" switches and the two notification actions.
 * Kept in SharedPreferences (no database schema involved), readable synchronously from the sync engine.
 */
class NotificationRules private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("dotmail_notification_rules", Context.MODE_PRIVATE)
    private val _muted = MutableStateFlow(decodeIds(prefs.getString("muted", null)))
    private val _folders = MutableStateFlow(decodeFolders(prefs.getString("folders", null)))
    private val _actions = MutableStateFlow(decodeActions(prefs.getString("actions", null)))

    /** Real thread ids whose new mail never notifies. */
    val muted: StateFlow<Set<Long>> = _muted.asStateFlow()

    /** Explicit per-folder choices (folder id → on/off). Folders without an entry follow [defaultNotify]. */
    val folderOverrides: StateFlow<Map<Long, Boolean>> = _folders.asStateFlow()

    val actions: StateFlow<List<NotificationAction>> = _actions.asStateFlow()

    fun isMuted(threadId: Long) = threadId in _muted.value

    @Synchronized fun setMuted(threadIds: Collection<Long>, muted: Boolean) {
        val next = LinkedHashSet(_muted.value)
        if (muted) next.addAll(threadIds.filter { it > 0 }) else next.removeAll(threadIds.toSet())
        while (next.size > MAX_MUTED) next.remove(next.first())
        _muted.value = next
        prefs.edit { putString("muted", next.joinToString(",")) }
    }

    fun notifyForFolder(folderId: Long, role: String): Boolean = effective(_folders.value, folderId, role)

    @Synchronized fun setFolderNotify(folderId: Long, role: String, on: Boolean) {
        // Store only deviations from the default, so a role's default can evolve without stale entries.
        val next = HashMap(_folders.value)
        if (on == defaultNotify(role)) next.remove(folderId) else next[folderId] = on
        _folders.value = next
        prefs.edit { putString("folders", next.entries.joinToString(",") { "${it.key}:${if (it.value) 1 else 0}" }) }
    }

    @Synchronized fun forgetFolder(folderId: Long) {
        if (folderId !in _folders.value) return
        val next = _folders.value - folderId
        _folders.value = next
        prefs.edit { putString("folders", next.entries.joinToString(",") { "${it.key}:${if (it.value) 1 else 0}" }) }
    }

    /** Picks the two notification buttons. They must differ; the second is replaced if equal to the first. */
    @Synchronized fun setActions(first: NotificationAction, second: NotificationAction) {
        val pair = listOf(first, if (second == first) NotificationAction.entries.first { it != first } else second)
        _actions.value = pair
        prefs.edit { putString("actions", pair.joinToString(",") { it.name }) }
    }

    /** Restores everything from a backup. */
    @Synchronized fun restore(muted: Set<Long>, folders: Map<Long, Boolean>, actions: List<NotificationAction>) {
        _muted.value = muted; _folders.value = folders; _actions.value = actions.takeIf { it.size == 2 && it[0] != it[1] } ?: DEFAULT_ACTIONS
        prefs.edit {
            putString("muted", muted.joinToString(",")); putString("folders", folders.entries.joinToString(",") { "${it.key}:${if (it.value) 1 else 0}" })
            putString("actions", _actions.value.joinToString(",") { it.name })
        }
    }

    companion object {
        const val MAX_MUTED = 5000
        val DEFAULT_ACTIONS = listOf(NotificationAction.MARK_READ, NotificationAction.ARCHIVE)

        /** Inbox notifies by default, every other folder is silent until switched on. */
        fun defaultNotify(role: String) = role == "INBOX"

        fun effective(overrides: Map<Long, Boolean>, folderId: Long, role: String) = overrides[folderId] ?: defaultNotify(role)

        internal fun decodeIds(raw: String?): Set<Long> = raw.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toCollection(LinkedHashSet())

        internal fun decodeFolders(raw: String?): Map<Long, Boolean> = raw.orEmpty().split(',').mapNotNull { part ->
            val p = part.split(':')
            val id = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val on = when (p.getOrNull(1)) { "1" -> true; "0" -> false; else -> return@mapNotNull null }
            id to on
        }.toMap()

        internal fun decodeActions(raw: String?): List<NotificationAction> {
            val list = raw.orEmpty().split(',').mapNotNull { n -> NotificationAction.entries.firstOrNull { it.name == n } }
            return if (list.size == 2 && list[0] != list[1]) list else DEFAULT_ACTIONS
        }

        @Volatile private var instance: NotificationRules? = null
        fun get(context: Context): NotificationRules =
            instance ?: synchronized(this) { instance ?: NotificationRules(context.applicationContext).also { instance = it } }

        /** For tests. */
        internal fun resetForTest() { instance = null }
    }
}
