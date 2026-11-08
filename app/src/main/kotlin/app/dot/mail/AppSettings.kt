package app.dot.mail

import android.content.Context
import androidx.core.content.edit
import app.dot.design.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SwipeAction { ARCHIVE, TRASH, READ_TOGGLE, STAR_TOGGLE, NONE }
enum class Density { COMFORTABLE, COMPACT }

/** App-wide UI preferences. Small and synchronous on purpose (SharedPreferences, read once). */
data class UiPrefs(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val density: Density = Density.COMFORTABLE,
    val swipeStart: SwipeAction = SwipeAction.ARCHIVE,
    val swipeEnd: SwipeAction = SwipeAction.TRASH,
    /** Remote images are never loaded unless this is on (or allowed per message). */
    val loadRemoteImages: Boolean = false,
    val appLock: Boolean = false,
    /** FLAG_SECURE: hide content in Recents and block screenshots. */
    val secureScreen: Boolean = false,
    val undoSendSeconds: Int = 5,
    val confirmLinks: Boolean = true,
)

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private val _ui = MutableStateFlow(read())
    val ui: StateFlow<UiPrefs> = _ui.asStateFlow()

    var demoMode: Boolean
        get() = prefs.getBoolean("demo", false)
        set(value) = prefs.edit { putBoolean("demo", value) }

    fun update(transform: (UiPrefs) -> UiPrefs) {
        val next = transform(_ui.value)
        _ui.value = next
        prefs.edit {
            putString("theme", next.theme.name)
            putString("density", next.density.name)
            putString("swipeStart", next.swipeStart.name)
            putString("swipeEnd", next.swipeEnd.name)
            putBoolean("remoteImages", next.loadRemoteImages)
            putBoolean("appLock", next.appLock)
            putBoolean("secure", next.secureScreen)
            putInt("undoSend", next.undoSendSeconds)
            putBoolean("confirmLinks", next.confirmLinks)
        }
    }

    private fun read(): UiPrefs {
        val d = UiPrefs()
        return UiPrefs(
            theme = enumOr(prefs.getString("theme", null), d.theme),
            density = enumOr(prefs.getString("density", null), d.density),
            swipeStart = enumOr(prefs.getString("swipeStart", null), d.swipeStart),
            swipeEnd = enumOr(prefs.getString("swipeEnd", null), d.swipeEnd),
            loadRemoteImages = prefs.getBoolean("remoteImages", d.loadRemoteImages),
            appLock = prefs.getBoolean("appLock", d.appLock),
            secureScreen = prefs.getBoolean("secure", d.secureScreen),
            undoSendSeconds = prefs.getInt("undoSend", d.undoSendSeconds),
            confirmLinks = prefs.getBoolean("confirmLinks", d.confirmLinks),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
}
