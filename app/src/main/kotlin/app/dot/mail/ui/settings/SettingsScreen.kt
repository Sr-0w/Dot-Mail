package app.dot.mail.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material.icons.outlined.ReplyAll
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ShieldMoon
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SwipeLeft
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.SwipeRight
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.ThemeMode
import app.dot.design.components.Avatar
import app.dot.design.components.BackButton
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotTopBar
import app.dot.design.components.Hairline
import app.dot.design.components.ListRow
import app.dot.design.components.SectionLabel
import app.dot.design.components.dotSurfaceEnter
import app.dot.mail.AfterAction
import app.dot.mail.BuildConfig
import app.dot.mail.ComposeFormat
import app.dot.mail.Density
import app.dot.mail.LocalMailGraph
import app.dot.mail.LocalSettings
import app.dot.mail.MarkRead
import app.dot.mail.SwipeAction
import app.dot.mail.data.model.Account
import app.dot.mail.data.model.SyncState
import app.dot.mail.ui.avatar.AvatarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val SOURCE_URL = "https://github.com/Sr-0w/Dot-Mail"

@Composable
fun SettingsScreen(onBack: () -> Unit, onAddAccount: () -> Unit, onAllAccountsRemoved: () -> Unit) {
    val settings = LocalSettings.current
    val graph = LocalMailGraph.current
    val prefs by settings.ui.collectAsStateWithLifecycle()
    val accounts by graph.accounts.accounts.collectAsStateWithLifecycle(emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var removing by remember { mutableStateOf<Account?>(null) }
    var about by remember { mutableStateOf(false) }
    var licences by remember { mutableStateOf(false) }
    var cacheVersion by remember { mutableIntStateOf(0) }
    // Sub-pages: 0 = none. identityPage: -1 = account page, 0 = new identity, else editing that identity.
    var accountPage by rememberSaveable { mutableLongStateOf(0L) }
    var identityPage by rememberSaveable { mutableLongStateOf(-1L) }
    var remoteImagesPage by rememberSaveable { mutableStateOf(false) }
    val pageAccount = accounts.firstOrNull { it.id == accountPage }
    BackHandler(enabled = accountPage != 0L || remoteImagesPage) { if (remoteImagesPage) remoteImagesPage = false else if (identityPage >= 0) identityPage = -1 else accountPage = 0 }
    val c = Dot.colors
    val canLock = remember {
        BiometricManager.from(context).canAuthenticate(
            if (Build.VERSION.SDK_INT >= 30) BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            else BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }
    val hasContacts = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
    var contactsGranted by remember { mutableStateOf(hasContacts()) }
    var contactsDenied by remember { mutableStateOf(false) }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        contactsGranted = ok
        contactsDenied = !ok
        if (ok) settings.update { it.copy(contactPhotos = true) }
    }
    // Permission revoked in system settings while the toggle was on: show it off.
    LaunchedEffect(Unit) { contactsGranted = hasContacts() }

    if (remoteImagesPage) {
        RemoteImagesPage(accounts, onBack = { remoteImagesPage = false })
    } else if (pageAccount != null && identityPage >= 0) {
        IdentityEditorPage(pageAccount, identityPage, onBack = { identityPage = -1 })
    } else if (pageAccount != null) {
        AccountPage(pageAccount, onBack = { accountPage = 0 }, onEditIdentity = { identityPage = it }, onRemove = { removing = pageAccount })
    } else Column(Modifier.fillMaxSize().background(c.background)) {
        DotTopBar(title = "Settings", elevated = scroll.value > 0, navigation = { BackButton(onBack) })
        Column(Modifier.verticalScroll(scroll)) {
            // ---- Accounts
            SectionLabel("Accounts", Modifier.padding(top = DotSpacing.s))
            accounts.forEach { a ->
                val err = a.syncState as? SyncState.Error
                ListRow(
                    title = a.displayName,
                    subtitle = if (err != null) "${a.email}\n${err.message}" else a.email,
                    subtitleColor = if (err != null) c.danger else c.textSecondary,
                    onClick = { accountPage = a.id; identityPage = -1 },
                    trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.textTertiary) },
                )
            }
            ListRow("Add account", icon = Icons.Outlined.Add, onClick = onAddAccount)
            Divider()

            // ---- General
            SectionLabel("General")
            if (accounts.size > 1) {
                ChoiceRow(
                    "Default account for new mail", Icons.Outlined.AccountCircle,
                    value = accounts.firstOrNull { it.id == prefs.defaultAccountId }?.id ?: accounts.first().id,
                    options = accounts.map { it.id to it.email },
                ) { id -> settings.update { it.copy(defaultAccountId = id) } }
            }
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            ChoiceRow(
                if (rtl) "Swipe left" else "Swipe right", if (rtl) Icons.Outlined.SwipeLeft else Icons.Outlined.SwipeRight,
                prefs.swipeStart, SwipeAction.entries.map { it to swipeName(it) },
            ) { a -> settings.update { it.copy(swipeStart = a) } }
            ChoiceRow(
                if (rtl) "Swipe right" else "Swipe left", if (rtl) Icons.Outlined.SwipeRight else Icons.Outlined.SwipeLeft,
                prefs.swipeEnd, SwipeAction.entries.map { it to swipeName(it) },
            ) { a -> settings.update { it.copy(swipeEnd = a) } }
            ToggleRow("Confirm before deleting", "Ask before moving conversations to Trash.", Icons.Outlined.Delete, prefs.confirmDelete) { v ->
                settings.update { it.copy(confirmDelete = v) }
            }
            ChoiceRow(
                "After archive or delete", Icons.Outlined.Archive, prefs.afterAction,
                listOf(AfterAction.LIST to "Back to the list", AfterAction.NEXT to "Open the next conversation", AfterAction.PREVIOUS to "Open the previous conversation"),
            ) { v -> settings.update { it.copy(afterAction = v) } }
            ChoiceRow(
                "Mark as read", Icons.Outlined.MarkEmailRead, prefs.markRead,
                listOf(MarkRead.IMMEDIATELY to "When opened", MarkRead.AFTER_2S to "After 2 seconds", MarkRead.MANUALLY to "Only manually"),
            ) { v -> settings.update { it.copy(markRead = v) } }
            Divider()

            // ---- Appearance
            SectionLabel("Appearance")
            SegmentedSetting("Theme", listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"), prefs.theme) { m ->
                settings.update { it.copy(theme = m) }
            }
            ToggleRow("Group into conversations", "Show replies together in one row. Off: every message is listed on its own.", Icons.Outlined.Forum, prefs.conversationView) { v ->
                settings.update { it.copy(conversationView = v) }
            }
            SegmentedSetting("List density", listOf(Density.COMFORTABLE to "Comfortable", Density.COMPACT to "Compact"), prefs.density) { d ->
                settings.update { it.copy(density = d) }
            }
            PercentSlider("Reading text size", prefs.readingTextPercent, 90..130, 10) { v -> settings.update { it.copy(readingTextPercent = v) } }
            ToggleRow("Coloured avatars", "Give every sender a colour. Off: one neutral grey.", Icons.Outlined.Palette, prefs.avatarColors) { v ->
                settings.update { it.copy(avatarColors = v) }
            }
            SectionLabel("Sender pictures")
            Text(
                "Initials are used unless you turn a source on. Every source below except contacts asks another server, which learns that you received mail from that sender.",
                style = Dot.type.caption, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs),
            )
            ToggleRow(
                "Contact photos",
                if (contactsDenied) "Contacts permission was denied. Allow it in system settings to use this." else "Photos from your address book. Stays on this device.",
                Icons.Outlined.ContactPage, prefs.contactPhotos && contactsGranted,
            ) { v ->
                when {
                    !v -> settings.update { it.copy(contactPhotos = false) }
                    contactsGranted -> settings.update { it.copy(contactPhotos = true) }
                    else -> contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
                }
            }
            ToggleRow("Gravatar", "Sends a hash of the sender's address to gravatar.com.", Icons.Outlined.Public, prefs.gravatar) { v ->
                settings.update { it.copy(gravatar = v) }
            }
            ToggleRow("Brand logos", "Looks up the sender's domain in DNS and downloads its BIMI logo from the sender's host.", Icons.Outlined.VerifiedUser, prefs.brandLogos) { v ->
                settings.update { it.copy(brandLogos = v) }
            }
            ToggleRow("Website icons", "Downloads the icon of the sender's website (not for Gmail, Outlook and similar).", Icons.Outlined.Language, prefs.websiteIcons) { v ->
                settings.update { it.copy(websiteIcons = v) }
            }
            Divider()

            // ---- Reading
            SectionLabel("Reading")
            ListRow(
                "Tracking pixels are always blocked", subtitle = "Invisible 1-pixel images that report you opened a message never load.",
                icon = Icons.Outlined.Block,
            )
            ToggleRow("Confirm links", "Show the real address before opening a link.", Icons.Outlined.Link, prefs.confirmLinks) { v ->
                settings.update { it.copy(confirmLinks = v) }
            }
            ToggleRow("Show quoted text", "Expand earlier messages quoted in a reply.", Icons.Outlined.FormatQuote, prefs.quotedExpanded) { v ->
                settings.update { it.copy(quotedExpanded = v) }
            }
            ToggleRow("Swipe between conversations", "Swipe left for the next conversation, right for the previous one.", Icons.Outlined.SwipeLeft, prefs.swipeBetweenMessages) { v ->
                settings.update { it.copy(swipeBetweenMessages = v) }
            }
            Divider()

            // ---- Composing
            SectionLabel("Composing")
            ChoiceRow(
                "Default format", Icons.Outlined.TextFields, prefs.defaultFormat,
                listOf(ComposeFormat.RICH to "Rich text", ComposeFormat.PLAIN to "Plain text"),
            ) { f -> settings.update { it.copy(defaultFormat = f) } }
            UndoSetting(prefs.undoSendSeconds) { s -> settings.update { it.copy(undoSendSeconds = s) } }
            ToggleRow("Reply to all by default", "Tapping Reply addresses everyone on the message.", Icons.Outlined.ReplyAll, prefs.replyAllByDefault) { v ->
                settings.update { it.copy(replyAllByDefault = v) }
            }
            ToggleRow("Include original message", "Quote the message you are replying to.", Icons.Outlined.Reply, prefs.includeOriginal) { v ->
                settings.update { it.copy(includeOriginal = v) }
            }
            Divider()

            // ---- Notifications
            SectionLabel("Notifications")
            ListRow(
                "System notification settings", subtitle = "Sound, vibration and channels per account", icon = Icons.Outlined.Notifications,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
            )
            val instant by graph.mail.instantNotifications.collectAsStateWithLifecycle(false)
            ToggleRow(
                "Instant notifications",
                "Keeps a direct connection to your inbox open so new mail arrives within seconds, with no Google push service. " +
                    "Uses more battery and shows a permanent notification. Off: checks follow the sync interval.",
                Icons.Outlined.Notifications, instant,
            ) { v ->
                scope.launch {
                    if (!graph.mail.setInstantNotificationsEnabled(v) && v) {
                        android.widget.Toast.makeText(context, "Instant notifications couldn't be started on this device.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            val rules = remember { app.dot.mail.data.NotificationRules.get(context.applicationContext) }
            val buttons by rules.actions.collectAsStateWithLifecycle()
            val actionOptions = listOf(
                app.dot.mail.data.NotificationAction.MARK_READ to "Mark as read", app.dot.mail.data.NotificationAction.ARCHIVE to "Archive",
                app.dot.mail.data.NotificationAction.DELETE to "Delete", app.dot.mail.data.NotificationAction.REPLY to "Reply",
            )
            ChoiceRow("First notification button", Icons.Outlined.Notifications, buttons[0], actionOptions) { rules.setActions(it, buttons[1]) }
            ChoiceRow("Second notification button", Icons.Outlined.Notifications, buttons[1], actionOptions) { rules.setActions(buttons[0], it) }
            Divider()

            // ---- Privacy & security
            SectionLabel("Privacy & security")
            val imageRules by remember { RemoteImagePolicy.get(context.applicationContext).rules }.collectAsStateWithLifecycle()
            ListRow(
                "Remote images",
                subtitle = (if (prefs.loadRemoteImages) "Always loaded" else "Blocked by default") + if (imageRules.isEmpty()) "" else " · ${imageRules.size} allowed " + if (imageRules.size == 1) "sender" else "senders",
                icon = Icons.Outlined.Image, onClick = { remoteImagesPage = true },
                trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Dot.colors.textTertiary) },
            )
            ToggleRow(
                "App lock", if (canLock) "Require fingerprint, face or screen lock to open." else "Set up a screen lock on this device to use this.",
                Icons.Outlined.Fingerprint, prefs.appLock, enabled = canLock,
            ) { v -> settings.update { it.copy(appLock = v) } }
            ChoiceRow(
                "Lock after", Icons.Outlined.Lock, prefs.lockAfterMinutes,
                listOf(0 to "Immediately", 1 to "1 minute", 5 to "5 minutes", 15 to "15 minutes"), enabled = prefs.appLock && canLock,
            ) { m -> settings.update { it.copy(lockAfterMinutes = m) } }
            ToggleRow("Hide content in Recents", "Also blocks screenshots.", Icons.Outlined.VisibilityOff, prefs.secureScreen) { v ->
                settings.update { it.copy(secureScreen = v) }
            }
            Divider()

            // ---- Storage
            SectionLabel("Storage")
            val cacheBytes by produceState(0L, cacheVersion) {
                value = withContext(Dispatchers.IO) { context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            }
            ListRow(
                "Clear cache", subtitle = "${formatSize(cacheBytes)} of temporary files. Mail stays on your account and this device.",
                icon = Icons.Outlined.DeleteSweep,
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            AvatarRepository.get(context).clearCache()
                            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                        }
                        cacheVersion++
                    }
                },
            )
            Divider()

            BackupSettings()
            Divider()

            // ---- About
            SectionLabel("About")
            ListRow("About Dot Mail", subtitle = "Version ${BuildConfig.VERSION_NAME} · GPL-3.0-or-later", icon = Icons.Outlined.Info, onClick = { about = true })
            ListRow(
                "Source code", subtitle = SOURCE_URL.removePrefix("https://"), icon = Icons.Outlined.Code,
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) } },
            )
            ListRow("Open-source licences", icon = Icons.Outlined.Gavel, onClick = { licences = true })
            ListRow(
                "No telemetry", subtitle = "No ads, analytics, crash reporting or account with us. Mail goes only between this device and your provider.",
                icon = Icons.Outlined.ShieldMoon,
            )
            if (BuildConfig.DEBUG) {
                ToggleRow("Demo mode", "Sample mail, no network. Restart the app to apply.", Icons.Outlined.Palette, settings.demoMode) { v ->
                    settings.demoMode = v
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            Spacer(Modifier.height(DotSpacing.xl))
        }
    }

    removing?.let { a ->
        AlertDialog(
            onDismissRequest = { removing = null },
            modifier = Modifier.dotSurfaceEnter(),
            containerColor = c.surface,
            shape = DotShapes.large,
            icon = { Avatar(a.displayName, a.email) },
            title = { Text(a.email, style = Dot.type.title, color = c.textPrimary) },
            text = { Text("Remove this account from Dot Mail? Mail stays on your server; the local copy and saved password are deleted.", style = Dot.type.callout, color = c.textSecondary) },
            confirmButton = {
                DotButton("Remove", style = ButtonStyle.Danger, compact = true, onClick = {
                    removing = null
                    scope.launch {
                        accountPage = 0
                        graph.accounts.removeAccount(a.id)
                        if (graph.accounts.accounts.first().isEmpty()) onAllAccountsRemoved()
                    }
                })
            },
            dismissButton = { DotButton("Cancel", { removing = null }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            modifier = Modifier.dotSurfaceEnter(),
            containerColor = c.surface,
            shape = DotShapes.large,
            title = { Text("Dot Mail ${BuildConfig.VERSION_NAME}", style = Dot.type.title, color = c.textPrimary) },
            text = {
                Text(
                    "Free software under the GNU GPL v3 or later. No ads, no analytics, no tracking, no account with us. " +
                        "Your mail goes only between this device and your mail provider.",
                    style = Dot.type.callout, color = c.textSecondary,
                )
            },
            confirmButton = { DotButton("Close", { about = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
    if (licences) {
        AlertDialog(
            onDismissRequest = { licences = false },
            modifier = Modifier.dotSurfaceEnter(),
            containerColor = c.surface,
            shape = DotShapes.large,
            title = { Text("Open-source licences", style = Dot.type.title, color = c.textPrimary) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LICENCES.forEach { (name, licence) ->
                        Text(name, style = Dot.type.bodyStrong, color = c.textPrimary)
                        Text(licence, style = Dot.type.caption, color = c.textSecondary, modifier = Modifier.padding(bottom = DotSpacing.m))
                    }
                }
            },
            confirmButton = { DotButton("Close", { licences = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
}

private val LICENCES = listOf(
    "Dot Mail" to "GNU General Public License v3.0 or later",
    "Inter typeface" to "SIL Open Font License 1.1",
    "AndroidX, Jetpack Compose, Glance, Room, WorkManager, Paging" to "Apache License 2.0",
    "Kotlin and kotlinx libraries" to "Apache License 2.0",
    "Jakarta Mail (Eclipse Angus)" to "EPL 2.0 / GPL 2.0 with Classpath Exception",
    "jsoup" to "MIT License",
    "AndroidSVG" to "Apache License 2.0",
)

@Composable
private fun Divider() = Hairline(Modifier.padding(vertical = DotSpacing.s))

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1048576.0)
}

private fun swipeName(a: SwipeAction) = when (a) {
    SwipeAction.ARCHIVE -> "Archive"
    SwipeAction.TRASH -> "Delete"
    SwipeAction.READ_TOGGLE -> "Mark read / unread"
    SwipeAction.STAR_TOGGLE -> "Star / unstar"
    SwipeAction.SNOOZE -> "Snooze"
    SwipeAction.NONE -> "Nothing"
}

@Composable
private fun UndoSetting(seconds: Int, onChange: (Int) -> Unit) {
    ChoiceRow(
        "Undo send", Icons.Outlined.Schedule, seconds,
        listOf(0, 5, 10, 20, 30).map { it to if (it == 0) "Off" else "$it seconds" }, onChange = onChange,
    )
}
