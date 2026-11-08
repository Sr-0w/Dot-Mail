package app.dot.mail.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SwipeLeft
import androidx.compose.material.icons.outlined.SwipeRight
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.ThemeMode
import app.dot.design.components.Avatar
import app.dot.design.components.BackButton
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotSwitch
import app.dot.design.components.DotTopBar
import app.dot.design.components.Hairline
import app.dot.design.components.ListRow
import app.dot.design.components.SectionLabel
import app.dot.mail.BuildConfig
import app.dot.mail.Density
import app.dot.mail.LocalMailGraph
import app.dot.mail.LocalSettings
import app.dot.mail.SwipeAction
import app.dot.mail.data.model.Account
import app.dot.mail.ui.account.Segmented
import app.dot.design.components.DotMenu
import app.dot.design.components.DotMenuItem
import app.dot.mail.data.model.SyncState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    val c = Dot.colors
    val canLock = remember {
        BiometricManager.from(context).canAuthenticate(
            if (Build.VERSION.SDK_INT >= 30) BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            else BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        DotTopBar(title = "Settings", elevated = scroll.value > 0, navigation = { BackButton(onBack) })
        Column(Modifier.verticalScroll(scroll)) {
            SectionLabel("Accounts", Modifier.padding(top = DotSpacing.s))
            accounts.forEach { a ->
                val err = a.syncState as? SyncState.Error
                ListRow(
                    title = a.displayName,
                    subtitle = if (err != null) "${a.email}\n${err.message}" else a.email,
                    subtitleColor = if (err != null) c.danger else c.textSecondary,
                    onClick = { removing = a },
                    trailing = { Text("Remove", style = Dot.type.label, color = c.textSecondary) },
                )
            }
            ListRow("Add account", icon = Icons.Outlined.Add, onClick = onAddAccount)
            Hairline(Modifier.padding(vertical = DotSpacing.s))

            SectionLabel("Appearance")
            Column(Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s)) {
                Text("Theme", style = Dot.type.bodyStrong, color = c.textPrimary)
                Spacer(Modifier.height(DotSpacing.s))
                Segmented(
                    listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"),
                    prefs.theme, { m -> settings.update { it.copy(theme = m) } },
                )
                Spacer(Modifier.height(DotSpacing.l))
                Text("List density", style = Dot.type.bodyStrong, color = c.textPrimary)
                Spacer(Modifier.height(DotSpacing.s))
                Segmented(
                    listOf(Density.COMFORTABLE to "Comfortable", Density.COMPACT to "Compact"),
                    prefs.density, { d -> settings.update { it.copy(density = d) } },
                )
            }
            Spacer(Modifier.height(DotSpacing.s))
            // swipeStart = start→end: physically rightwards in LTR, leftwards in RTL.
            val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
            SwipeSetting(if (rtl) "Swipe left" else "Swipe right", if (rtl) Icons.Outlined.SwipeLeft else Icons.Outlined.SwipeRight, prefs.swipeStart) { a -> settings.update { it.copy(swipeStart = a) } }
            SwipeSetting(if (rtl) "Swipe right" else "Swipe left", if (rtl) Icons.Outlined.SwipeRight else Icons.Outlined.SwipeLeft, prefs.swipeEnd) { a -> settings.update { it.copy(swipeEnd = a) } }
            Hairline(Modifier.padding(vertical = DotSpacing.s))

            SectionLabel("Privacy & security")
            ToggleRow("Load remote images", "Off by default: remote images can reveal when and where you read mail.", Icons.Outlined.Image, prefs.loadRemoteImages) { v ->
                settings.update { it.copy(loadRemoteImages = v) }
            }
            ToggleRow("Confirm links", "Show the real address before opening a link.", Icons.Outlined.Link, prefs.confirmLinks) { v ->
                settings.update { it.copy(confirmLinks = v) }
            }
            ToggleRow(
                "App lock", if (canLock) "Require fingerprint, face or screen lock to open." else "Set up a screen lock on this device to use this.",
                Icons.Outlined.Fingerprint, prefs.appLock, enabled = canLock,
            ) { v -> settings.update { it.copy(appLock = v) } }
            ToggleRow("Hide content in Recents", "Also blocks screenshots.", Icons.Outlined.VisibilityOff, prefs.secureScreen) { v ->
                settings.update { it.copy(secureScreen = v) }
            }
            Hairline(Modifier.padding(vertical = DotSpacing.s))

            SectionLabel("Sending")
            UndoSetting(prefs.undoSendSeconds) { s -> settings.update { it.copy(undoSendSeconds = s) } }
            ListRow(
                "Notifications", subtitle = "Per-account channels, sound and vibration", icon = Icons.Outlined.Notifications,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
            )
            Hairline(Modifier.padding(vertical = DotSpacing.s))
            ListRow("About Dot Mail", subtitle = "Version ${BuildConfig.VERSION_NAME} · GPL-3.0-or-later", icon = Icons.Outlined.Info, onClick = { about = true })
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
            containerColor = c.surface,
            shape = DotShapes.large,
            icon = { Avatar(a.displayName, a.email) },
            title = { Text(a.email, style = Dot.type.title, color = c.textPrimary) },
            text = { Text("Remove this account from Dot Mail? Mail stays on your server; the local copy and saved password are deleted.", style = Dot.type.callout, color = c.textSecondary) },
            confirmButton = {
                DotButton("Remove", style = ButtonStyle.Danger, compact = true, onClick = {
                    removing = null
                    scope.launch {
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
            containerColor = c.surface,
            shape = DotShapes.large,
            title = { Text("Dot Mail ${BuildConfig.VERSION_NAME}", style = Dot.type.title, color = c.textPrimary) },
            text = {
                Text(
                    "Free software under the GNU GPL v3 or later. No ads, no analytics, no tracking, no account with us. " +
                        "Your mail goes only between this device and your mail provider.\n\nTypeface: Inter (SIL Open Font License).",
                    style = Dot.type.callout, color = c.textSecondary,
                )
            },
            confirmButton = { DotButton("Close", { about = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
}

@Composable
private fun ToggleRow(
    title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, checked: Boolean,
    enabled: Boolean = true, onChange: (Boolean) -> Unit,
) {
    // The row is the single toggle target (merged semantics: "Title, switch, on/off"); the switch is visual only.
    ListRow(
        title = title, subtitle = subtitle, icon = icon,
        titleColor = if (enabled) Dot.colors.textPrimary else Dot.colors.textTertiary,
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        trailing = { DotSwitch(checked, onCheckedChange = null, enabled = enabled) },
    )
}

@Composable
private fun SwipeSetting(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, value: SwipeAction, onChange: (SwipeAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListRow(title, subtitle = swipeName(value), icon = icon, onClick = { open = true })
        DotMenu(open, { open = false }) {
            SwipeAction.entries.forEach { a -> DotMenuItem(swipeName(a), null) { open = false; onChange(a) } }
        }
    }
}

private fun swipeName(a: SwipeAction) = when (a) {
    SwipeAction.ARCHIVE -> "Archive"
    SwipeAction.TRASH -> "Delete"
    SwipeAction.READ_TOGGLE -> "Mark read / unread"
    SwipeAction.STAR_TOGGLE -> "Star / unstar"
    SwipeAction.NONE -> "Nothing"
}

@Composable
private fun UndoSetting(seconds: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListRow("Undo send", subtitle = if (seconds == 0) "Off" else "$seconds seconds", icon = Icons.Outlined.Schedule, onClick = { open = true })
        DotMenu(open, { open = false }) {
            listOf(0, 5, 10, 20, 30).forEach { s -> DotMenuItem(if (s == 0) "Off" else "$s seconds", null) { open = false; onChange(s) } }
        }
    }
}

