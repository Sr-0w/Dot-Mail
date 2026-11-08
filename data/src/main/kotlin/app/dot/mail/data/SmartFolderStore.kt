package app.dot.mail.data

import android.content.Context
import androidx.core.content.edit
import app.dot.mail.data.model.FolderRole
import app.dot.mail.data.model.SmartFolder
import app.dot.mail.engine.text.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The user's smart folders, kept (in order) as one JSON array in SharedPreferences. */
class SmartFolderStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("dotmail_smart_folders", Context.MODE_PRIVATE)
    private val _folders = MutableStateFlow(decode(prefs.getString("list", null)))
    val folders: StateFlow<List<SmartFolder>> = _folders.asStateFlow()

    fun get(id: String): SmartFolder? = _folders.value.firstOrNull { it.id == id }

    /** Adds or replaces (by id). */
    @Synchronized fun save(folder: SmartFolder) {
        val list = _folders.value
        set(if (list.any { it.id == folder.id }) list.map { if (it.id == folder.id) folder else it } else list + folder)
    }

    @Synchronized fun delete(id: String) = set(_folders.value.filter { it.id != id })

    /** Moves the folder one step; [delta] is -1 (up) or +1 (down). */
    @Synchronized fun move(id: String, delta: Int) {
        val list = _folders.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        set(list)
    }

    /** Replaces everything (settings restore). */
    @Synchronized fun replaceAll(folders: List<SmartFolder>) = set(folders)

    @Synchronized fun forgetAccount(accountId: Long) =
        set(_folders.value.map { f -> if (accountId in f.accountIds) f.copy(accountIds = f.accountIds - accountId) else f })

    private fun set(list: List<SmartFolder>) {
        prefs.edit { putString("list", encode(list)) }
        _folders.value = list
    }

    companion object {
        @Volatile private var instance: SmartFolderStore? = null
        fun get(context: Context): SmartFolderStore =
            instance ?: synchronized(this) { instance ?: SmartFolderStore(context.applicationContext).also { instance = it } }
        internal fun forTest(context: Context) = SmartFolderStore(context)

        fun encode(list: List<SmartFolder>): String = JSONArray().also { arr -> list.forEach { arr.put(toJson(it)) } }.toString()

        fun decode(raw: String?): List<SmartFolder> = runCatching {
            val arr = JSONArray(raw ?: return emptyList())
            (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        fun toJson(f: SmartFolder): JSONObject = JSONObject()
            .put("id", f.id).put("name", f.name)
            .put("accounts", JSONArray(f.accountIds.toList())).put("roles", JSONArray(f.roles.map { it.name }))
            .put("from", f.from).put("to", f.to).put("subject", f.subject).put("category", f.category?.name ?: "")
            .put("unread", f.unreadOnly).put("starred", f.starredOnly).put("attachment", f.hasAttachment)
            .put("after", f.afterMillis ?: JSONObject.NULL).put("before", f.beforeMillis ?: JSONObject.NULL)

        fun fromJson(o: JSONObject): SmartFolder? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return null
            fun longs(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() }.orEmpty()
            val roles = o.optJSONArray("roles")?.let { a -> (0 until a.length()).mapNotNull { i -> FolderRole.entries.firstOrNull { it.name == a.getString(i) } }.toSet() }.orEmpty()
            return SmartFolder(
                id, name, longs("accounts"), roles, o.optString("from"), o.optString("to"), o.optString("subject"),
                Category.entries.firstOrNull { it.name == o.optString("category") },
                o.optBoolean("unread"), o.optBoolean("starred"), o.optBoolean("attachment"),
                if (o.isNull("after")) null else o.optLong("after"), if (o.isNull("before")) null else o.optLong("before"),
            )
        }
    }
}

/** The conversation-list sort choice, remembered across launches. */
class ListPrefs private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("dotmail_list", Context.MODE_PRIVATE)
    private val _sort = MutableStateFlow(
        app.dot.mail.data.model.InboxSort.entries.firstOrNull { it.name == prefs.getString("sort", null) } ?: app.dot.mail.data.model.InboxSort.DATE,
    )
    val sort: StateFlow<app.dot.mail.data.model.InboxSort> = _sort.asStateFlow()
    fun setSort(sort: app.dot.mail.data.model.InboxSort) { prefs.edit { putString("sort", sort.name) }; _sort.value = sort }

    companion object {
        @Volatile private var instance: ListPrefs? = null
        fun get(context: Context): ListPrefs =
            instance ?: synchronized(this) { instance ?: ListPrefs(context.applicationContext).also { instance = it } }
    }
}
