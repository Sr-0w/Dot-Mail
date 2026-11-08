package app.dot.mail

import android.content.Context
import androidx.core.content.edit
import app.dot.design.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SwipeAction { ARCHIVE, TRASH, READ_TOGGLE, STAR_TOGGLE, SNOOZE, NONE }
enum class Density { COMFORTABLE, COMPACT, SPACIOUS }

/** Typeface of message text in the reader (plain text, and the default for HTML mail that sets none). */
enum class ReaderFont { INTER, SYSTEM, SERIF }

/** Format of newly started messages. */
enum class ComposeFormat { RICH, PLAIN }

/** Where to go after archiving / deleting the open conversation. */
enum class AfterAction { LIST, NEXT, PREVIOUS }

/** When opening a conversation marks it as read. */
enum class MarkRead { IMMEDIATELY, AFTER_2S, MANUALLY }

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

    // --- General
    /** Account used for new messages; 0 = the first account. */
    val defaultAccountId: Long = 0,
    val confirmDelete: Boolean = false,
    val afterAction: AfterAction = AfterAction.LIST,
    val markRead: MarkRead = MarkRead.IMMEDIATELY,
    /** Group messages into conversations (off: every message is its own row). */
    val conversationView: Boolean = true,

    // --- Appearance
    /** Per-sender hues on avatars; off = one neutral grey. */
    val avatarColors: Boolean = true,
    /** Sender pictures, each an independent opt-in (see ui/avatar). */
    val contactPhotos: Boolean = false,
    val gravatar: Boolean = false,
    val libravatar: Boolean = false,
    val brandLogos: Boolean = false,
    val websiteIcons: Boolean = false,
    /** Reading text size in percent, 90..130. */
    val readingTextPercent: Int = 100,
    val readerFont: ReaderFont = ReaderFont.INTER,
    /** Reader line height as a percentage of the font size, 120..200. */
    val readerLineHeightPercent: Int = 155,

    // --- Reading
    val quotedExpanded: Boolean = false,

    // --- Composing
    val replyAllByDefault: Boolean = false,
    /** Swipe sideways in an open conversation to go to the next / previous one. */
    val swipeBetweenMessages: Boolean = false,
    val includeOriginal: Boolean = true,
    val defaultFormat: ComposeFormat = ComposeFormat.RICH,

    // --- Privacy
    /** Minutes in the background before the app locks again; 0 = as soon as it leaves the screen. */
    val lockAfterMinutes: Int = 0,
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
            putLong("defaultAccountId", next.defaultAccountId)
            putBoolean("confirmDelete", next.confirmDelete)
            putString("afterAction", next.afterAction.name)
            putString("markRead", next.markRead.name)
            putBoolean("conversationView", next.conversationView)
            putBoolean("avatarColors", next.avatarColors)
            putBoolean("contactPhotos", next.contactPhotos)
            putBoolean("gravatar", next.gravatar)
            putBoolean("libravatar", next.libravatar)
            putBoolean("brandLogos", next.brandLogos)
            putBoolean("websiteIcons", next.websiteIcons)
            putInt("readingTextPercent", next.readingTextPercent)
            putString("readerFont", next.readerFont.name)
            putInt("readerLineHeight", next.readerLineHeightPercent)
            putBoolean("quotedExpanded", next.quotedExpanded)
            putBoolean("replyAllByDefault", next.replyAllByDefault)
            putBoolean("swipeBetweenMessages", next.swipeBetweenMessages)
            putBoolean("includeOriginal", next.includeOriginal)
            putString("defaultFormat", next.defaultFormat.name)
            putInt("lockAfterMinutes", next.lockAfterMinutes)
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
            defaultAccountId = prefs.getLong("defaultAccountId", d.defaultAccountId),
            confirmDelete = prefs.getBoolean("confirmDelete", d.confirmDelete),
            afterAction = enumOr(prefs.getString("afterAction", null), d.afterAction),
            markRead = enumOr(prefs.getString("markRead", null), d.markRead),
            conversationView = prefs.getBoolean("conversationView", d.conversationView),
            avatarColors = prefs.getBoolean("avatarColors", d.avatarColors),
            contactPhotos = prefs.getBoolean("contactPhotos", d.contactPhotos),
            gravatar = prefs.getBoolean("gravatar", d.gravatar),
            libravatar = prefs.getBoolean("libravatar", d.libravatar),
            brandLogos = prefs.getBoolean("brandLogos", d.brandLogos),
            websiteIcons = prefs.getBoolean("websiteIcons", d.websiteIcons),
            readingTextPercent = prefs.getInt("readingTextPercent", d.readingTextPercent).coerceIn(90, 130),
            readerFont = enumOr(prefs.getString("readerFont", null), d.readerFont),
            readerLineHeightPercent = prefs.getInt("readerLineHeight", d.readerLineHeightPercent).coerceIn(120, 200),
            quotedExpanded = prefs.getBoolean("quotedExpanded", d.quotedExpanded),
            replyAllByDefault = prefs.getBoolean("replyAllByDefault", d.replyAllByDefault),
            swipeBetweenMessages = prefs.getBoolean("swipeBetweenMessages", d.swipeBetweenMessages),
            includeOriginal = prefs.getBoolean("includeOriginal", d.includeOriginal),
            defaultFormat = enumOr(prefs.getString("defaultFormat", null), d.defaultFormat),
            lockAfterMinutes = prefs.getInt("lockAfterMinutes", d.lockAfterMinutes),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
}
