package app.dot.mail.ui.settings

import android.Manifest
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
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
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FilterList
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
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShieldMoon
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowRight
import androidx.compose.material.icons.outlined.SwipeLeft
import androidx.compose.material.icons.outlined.TextFields
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.dot.mail.R
import app.dot.mail.BuildConfig
import app.dot.mail.ComposeFormat
import app.dot.mail.Density
import app.dot.mail.ReaderFont
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
    var categoriesPage by rememberSaveable { mutableStateOf(false) }
    var smartPage by rememberSaveable { mutableStateOf(false) }
    val pageAccount = accounts.firstOrNull { it.id == accountPage }
    // Settings search: a field on top of the page; while it has text, matching settings replace the sections.
    var query by rememberSaveable { mutableStateOf("") }
    var pendingSection by remember { mutableStateOf<SettingsSection?>(null) }
    val anchors = remember { androidx.compose.runtime.mutableStateMapOf<SettingsSection, Int>() }
    fun Modifier.anchor(section: SettingsSection) = onGloballyPositioned { anchors[section] = it.positionInParent().y.toInt() }
    LaunchedEffect(pendingSection) {
        val section = pendingSection ?: return@LaunchedEffect
        val y = androidx.compose.runtime.snapshotFlow { anchors[section] }.filterNotNull().first()
        scroll.animateScrollTo((y - 8).coerceAtLeast(0))
        pendingSection = null
    }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    fun go(target: SettingsTarget) {
        query = ""
        focusManager.clearFocus()
        when (target) {
            is SettingsTarget.Section -> pendingSection = target.section
            is SettingsTarget.Account -> { accountPage = target.accountId; identityPage = -1 }
            SettingsTarget.SmartFolders -> smartPage = true
            SettingsTarget.Categories -> categoriesPage = true
            SettingsTarget.RemoteImages -> remoteImagesPage = true
        }
    }
    BackHandler(enabled = accountPage != 0L || remoteImagesPage || categoriesPage || smartPage) { if (smartPage) smartPage = false else if (categoriesPage) categoriesPage = false else if (remoteImagesPage) remoteImagesPage = false else if (identityPage >= 0) identityPage = -1 else accountPage = 0 }
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
        scope.launch { graph.mail.refreshKnownContacts() }
        if (ok) settings.update { it.copy(contactPhotos = true) }
    }
    // Permission revoked in system settings while the toggle was on: show it off.
    LaunchedEffect(Unit) { contactsGranted = hasContacts() }

    if (smartPage) {
        SmartFoldersPage(onBack = { smartPage = false })
    } else if (categoriesPage) {
        CategoriesPage(onBack = { categoriesPage = false })
    } else if (remoteImagesPage) {
        RemoteImagesPage(accounts, onBack = { remoteImagesPage = false })
    } else if (pageAccount != null && identityPage >= 0) {
        IdentityEditorPage(pageAccount, identityPage, onBack = { identityPage = -1 })
    } else if (pageAccount != null) {
        AccountPage(pageAccount, onBack = { accountPage = 0 }, onEditIdentity = { identityPage = it }, onRemove = { removing = pageAccount })
    } else Column(Modifier.fillMaxSize().background(c.background)) {
        DotTopBar(title = stringResource(R.string.settings_title), elevated = scroll.value > 0, navigation = { BackButton(onBack) })
        val edgeState = app.dot.design.components.rememberGlassBackdrop()
        Box(Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().hazeSource(edgeState).verticalScroll(scroll)) {
            app.dot.design.components.SearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs),
                placeholder = stringResource(R.string.settings_search_placeholder),
                onSearch = { focusManager.clearFocus() },
            )
            if (query.isNotBlank()) {
                SettingsSearchResults(query, accounts, onOpen = ::go)
            } else {
            // ---- Accounts
            SectionLabel(stringResource(R.string.settings_section_accounts), Modifier.padding(top = DotSpacing.s).anchor(SettingsSection.ACCOUNTS))
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
            ListRow(stringResource(R.string.settings_add_account), icon = Icons.Outlined.Add, onClick = onAddAccount)
            Divider()

            // ---- General
            SectionLabel(stringResource(R.string.settings_section_general), Modifier.anchor(SettingsSection.GENERAL))
            if (accounts.size > 1) {
                ChoiceRow(
                    stringResource(R.string.settings_default_account), Icons.Outlined.AccountCircle,
                    value = accounts.firstOrNull { it.id == prefs.defaultAccountId }?.id ?: accounts.first().id,
                    options = accounts.map { it.id to it.email },
                ) { id -> settings.update { it.copy(defaultAccountId = id) } }
            }
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            ChoiceRow(
                stringResource(if (rtl) R.string.settings_swipe_left else R.string.settings_swipe_right), if (rtl) Icons.Outlined.KeyboardDoubleArrowLeft else Icons.Outlined.KeyboardDoubleArrowRight,
                prefs.swipeStart, SwipeAction.entries.map { it to swipeName(it) },
            ) { a -> settings.update { it.copy(swipeStart = a) } }
            ChoiceRow(
                stringResource(if (rtl) R.string.settings_swipe_right else R.string.settings_swipe_left), if (rtl) Icons.Outlined.KeyboardDoubleArrowRight else Icons.Outlined.KeyboardDoubleArrowLeft,
                prefs.swipeEnd, SwipeAction.entries.map { it to swipeName(it) },
            ) { a -> settings.update { it.copy(swipeEnd = a) } }
            ToggleRow(stringResource(R.string.settings_confirm_delete), stringResource(R.string.settings_confirm_delete_sub), Icons.Outlined.Delete, prefs.confirmDelete) { v ->
                settings.update { it.copy(confirmDelete = v) }
            }
            ChoiceRow(
                stringResource(R.string.settings_after_action), Icons.Outlined.Archive, prefs.afterAction,
                listOf(AfterAction.LIST to stringResource(R.string.settings_after_list), AfterAction.NEXT to stringResource(R.string.settings_after_next), AfterAction.PREVIOUS to stringResource(R.string.settings_after_previous)),
            ) { v -> settings.update { it.copy(afterAction = v) } }
            ChoiceRow(
                stringResource(R.string.settings_mark_read), Icons.Outlined.MarkEmailRead, prefs.markRead,
                listOf(MarkRead.IMMEDIATELY to stringResource(R.string.settings_mark_read_opened), MarkRead.AFTER_2S to stringResource(R.string.settings_mark_read_2s), MarkRead.MANUALLY to stringResource(R.string.settings_mark_read_manual)),
            ) { v -> settings.update { it.copy(markRead = v) } }
            Divider()

            // ---- Appearance
            SectionLabel(stringResource(R.string.settings_section_appearance), Modifier.anchor(SettingsSection.APPEARANCE))
            SegmentedSetting(stringResource(R.string.settings_theme), listOf(ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system), ThemeMode.LIGHT to stringResource(R.string.settings_theme_light), ThemeMode.DARK to stringResource(R.string.settings_theme_dark), ThemeMode.OLED to stringResource(R.string.settings_theme_oled)), prefs.theme) { m ->
                settings.update { it.copy(theme = m) }
            }
            ToggleRow(stringResource(R.string.settings_group_conversations), stringResource(R.string.settings_group_conversations_sub), Icons.Outlined.Forum, prefs.conversationView) { v ->
                settings.update { it.copy(conversationView = v) }
            }
            SegmentedSetting(stringResource(R.string.settings_density), listOf(Density.COMPACT to stringResource(R.string.settings_density_compact), Density.COMFORTABLE to stringResource(R.string.settings_density_comfortable), Density.SPACIOUS to stringResource(R.string.settings_density_spacious)), prefs.density) { d ->
                settings.update { it.copy(density = d) }
            }
            PercentSlider(stringResource(R.string.settings_reading_size), prefs.readingTextPercent, 90..130, 10) { v -> settings.update { it.copy(readingTextPercent = v) } }
            ChoiceRow(
                stringResource(R.string.settings_reader_font), null, prefs.readerFont,
                listOf(ReaderFont.INTER to stringResource(R.string.settings_reader_font_inter), ReaderFont.SYSTEM to stringResource(R.string.settings_reader_font_system), ReaderFont.SERIF to stringResource(R.string.settings_reader_font_serif)),
            ) { f -> settings.update { it.copy(readerFont = f) } }
            PercentSlider(stringResource(R.string.settings_reader_line_height), prefs.readerLineHeightPercent, 120..200, 5) { v -> settings.update { it.copy(readerLineHeightPercent = v) } }
            ToggleRow(stringResource(R.string.settings_avatar_colors), stringResource(R.string.settings_avatar_colors_sub), Icons.Outlined.Palette, prefs.avatarColors) { v ->
                settings.update { it.copy(avatarColors = v) }
            }
            SectionLabel(stringResource(R.string.settings_section_sender_pictures), Modifier.anchor(SettingsSection.SENDER_PICTURES))
            Text(
                stringResource(R.string.settings_sender_pictures_intro),
                style = Dot.type.caption, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs),
            )
            ToggleRow(
                stringResource(R.string.settings_contact_photos),
                stringResource(if (contactsDenied) R.string.settings_contact_photos_denied else R.string.settings_contact_photos_sub),
                Icons.Outlined.ContactPage, prefs.contactPhotos && contactsGranted,
            ) { v ->
                when {
                    !v -> settings.update { it.copy(contactPhotos = false) }
                    contactsGranted -> settings.update { it.copy(contactPhotos = true) }
                    else -> contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
                }
            }
            ToggleRow(stringResource(R.string.settings_gravatar), stringResource(R.string.settings_gravatar_sub), Icons.Outlined.Public, prefs.gravatar) { v ->
                settings.update { it.copy(gravatar = v) }
            }
            ToggleRow(stringResource(R.string.settings_libravatar), stringResource(R.string.settings_libravatar_sub), Icons.Outlined.Public, prefs.libravatar) { v ->
                settings.update { it.copy(libravatar = v) }
            }
            ToggleRow(stringResource(R.string.settings_brand_logos), stringResource(R.string.settings_brand_logos_sub), Icons.Outlined.VerifiedUser, prefs.brandLogos) { v ->
                settings.update { it.copy(brandLogos = v) }
            }
            ToggleRow(stringResource(R.string.settings_website_icons), stringResource(R.string.settings_website_icons_sub), Icons.Outlined.Language, prefs.websiteIcons) { v ->
                settings.update { it.copy(websiteIcons = v) }
            }
            Divider()

            // ---- Reading
            SectionLabel(stringResource(R.string.settings_section_reading), Modifier.anchor(SettingsSection.READING))
            ListRow(
                stringResource(R.string.settings_tracking_blocked), subtitle = stringResource(R.string.settings_tracking_blocked_sub),
                icon = Icons.Outlined.Block,
            )
            ToggleRow(stringResource(R.string.settings_confirm_links), stringResource(R.string.settings_confirm_links_sub), Icons.Outlined.Link, prefs.confirmLinks) { v ->
                settings.update { it.copy(confirmLinks = v) }
            }
            ToggleRow(stringResource(R.string.settings_show_quoted), stringResource(R.string.settings_show_quoted_sub), Icons.Outlined.FormatQuote, prefs.quotedExpanded) { v ->
                settings.update { it.copy(quotedExpanded = v) }
            }
            ToggleRow(stringResource(R.string.settings_swipe_between), stringResource(R.string.settings_swipe_between_sub), Icons.Outlined.SwipeLeft, prefs.swipeBetweenMessages) { v ->
                settings.update { it.copy(swipeBetweenMessages = v) }
            }
            Divider()

            // ---- Composing
            SectionLabel(stringResource(R.string.settings_section_composing), Modifier.anchor(SettingsSection.COMPOSING))
            ChoiceRow(
                stringResource(R.string.settings_default_format), Icons.Outlined.TextFields, prefs.defaultFormat,
                listOf(ComposeFormat.RICH to stringResource(R.string.settings_format_rich), ComposeFormat.PLAIN to stringResource(R.string.settings_format_plain)),
            ) { f -> settings.update { it.copy(defaultFormat = f) } }
            UndoSetting(prefs.undoSendSeconds) { s -> settings.update { it.copy(undoSendSeconds = s) } }
            ToggleRow(stringResource(R.string.settings_reply_all), stringResource(R.string.settings_reply_all_sub), Icons.AutoMirrored.Outlined.ReplyAll, prefs.replyAllByDefault) { v ->
                settings.update { it.copy(replyAllByDefault = v) }
            }
            ToggleRow(stringResource(R.string.settings_include_original), stringResource(R.string.settings_include_original_sub), Icons.AutoMirrored.Outlined.Reply, prefs.includeOriginal) { v ->
                settings.update { it.copy(includeOriginal = v) }
            }
            Divider()

            // ---- Notifications
            SectionLabel(stringResource(R.string.settings_section_notifications), Modifier.anchor(SettingsSection.NOTIFICATIONS))
            ListRow(
                stringResource(R.string.settings_system_notifications), subtitle = stringResource(R.string.settings_system_notifications_sub), icon = Icons.Outlined.Notifications,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
            )
            val instant by graph.mail.instantNotifications.collectAsStateWithLifecycle(false)
            ToggleRow(
                stringResource(R.string.settings_instant_title),
                stringResource(R.string.settings_instant_body),
                Icons.Outlined.Notifications, instant,
            ) { v ->
                scope.launch {
                    if (!graph.mail.setInstantNotificationsEnabled(v) && v) {
                        android.widget.Toast.makeText(context, context.getString(R.string.settings_instant_failed), android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            val rules = remember { app.dot.mail.data.NotificationRules.get(context.applicationContext) }
            val buttons by rules.actions.collectAsStateWithLifecycle()
            val actionOptions = listOf(
                app.dot.mail.data.NotificationAction.MARK_READ to stringResource(R.string.settings_action_mark_read), app.dot.mail.data.NotificationAction.ARCHIVE to stringResource(R.string.settings_action_archive),
                app.dot.mail.data.NotificationAction.DELETE to stringResource(R.string.settings_action_delete), app.dot.mail.data.NotificationAction.REPLY to stringResource(R.string.settings_action_reply),
            )
            ChoiceRow(stringResource(R.string.settings_notif_button_first), Icons.Outlined.Notifications, buttons[0], actionOptions) { rules.setActions(it, buttons[1]) }
            ChoiceRow(stringResource(R.string.settings_notif_button_second), Icons.Outlined.Notifications, buttons[1], actionOptions) { rules.setActions(buttons[0], it) }
            Divider()

            ListRow(
                stringResource(R.string.settings_smart_folders), subtitle = stringResource(R.string.settings_smart_folders_sub),
                icon = Icons.Outlined.FilterList, onClick = { smartPage = true },
                trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Dot.colors.textTertiary) },
            )
            ListRow(
                stringResource(R.string.settings_categories), subtitle = stringResource(R.string.settings_categories_sub),
                icon = Icons.Outlined.Email, onClick = { categoriesPage = true },
                trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Dot.colors.textTertiary) },
            )
            Divider()

            // ---- Privacy & security
            SectionLabel(stringResource(R.string.settings_section_privacy), Modifier.anchor(SettingsSection.PRIVACY))
            val imageRules by remember { RemoteImagePolicy.get(context.applicationContext).rules }.collectAsStateWithLifecycle()
            ListRow(
                stringResource(R.string.settings_remote_images),
                subtitle = stringResource(if (prefs.loadRemoteImages) R.string.settings_remote_images_always else R.string.settings_remote_images_blocked) +
                    if (imageRules.isEmpty()) "" else " · " + pluralStringResource(R.plurals.settings_remote_images_rules, imageRules.size, imageRules.size),
                icon = Icons.Outlined.Image, onClick = { remoteImagesPage = true },
                trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Dot.colors.textTertiary) },
            )
            ToggleRow(stringResource(R.string.settings_block_pixels), stringResource(R.string.settings_block_pixels_sub), Icons.Outlined.Shield, prefs.blockTrackingPixels) { v ->
                settings.update { it.copy(blockTrackingPixels = v) }
            }
            ToggleRow(
                stringResource(R.string.settings_app_lock), stringResource(if (canLock) R.string.settings_app_lock_sub else R.string.settings_app_lock_unavailable),
                Icons.Outlined.Fingerprint, prefs.appLock, enabled = canLock,
            ) { v -> settings.update { it.copy(appLock = v) } }
            ChoiceRow(
                stringResource(R.string.settings_lock_after), Icons.Outlined.Lock, prefs.lockAfterMinutes,
                listOf(0 to stringResource(R.string.settings_lock_immediately)) + listOf(1, 5, 15).map { it to pluralStringResource(R.plurals.settings_lock_minutes, it, it) }, enabled = prefs.appLock && canLock,
            ) { m -> settings.update { it.copy(lockAfterMinutes = m) } }
            ToggleRow(stringResource(R.string.settings_hide_recents), stringResource(R.string.settings_hide_recents_sub), Icons.Outlined.VisibilityOff, prefs.secureScreen) { v ->
                settings.update { it.copy(secureScreen = v) }
            }
            Divider()

            // ---- Storage
            SectionLabel(stringResource(R.string.settings_section_storage), Modifier.anchor(SettingsSection.STORAGE))
            val cacheBytes by produceState(0L, cacheVersion) {
                value = withContext(Dispatchers.IO) { context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
            }
            ListRow(
                stringResource(R.string.settings_clear_cache), subtitle = stringResource(R.string.settings_clear_cache_sub, app.dot.mail.ui.common.Format.size(cacheBytes)),
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

            Box(Modifier.anchor(SettingsSection.BACKUP)) { BackupSettings() }
            Divider()

            // ---- About
            SectionLabel(stringResource(R.string.settings_section_about), Modifier.anchor(SettingsSection.ABOUT))
            ListRow(stringResource(R.string.settings_about), subtitle = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME), icon = Icons.Outlined.Info, onClick = { about = true })
            ListRow(
                stringResource(R.string.settings_source_code), subtitle = SOURCE_URL.removePrefix("https://"), icon = Icons.Outlined.Code,
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) } },
            )
            ListRow(stringResource(R.string.settings_licences), icon = Icons.Outlined.Gavel, onClick = { licences = true })
            ListRow(
                stringResource(R.string.settings_no_telemetry), subtitle = stringResource(R.string.settings_no_telemetry_sub),
                icon = Icons.Outlined.ShieldMoon,
            )
            if (BuildConfig.DEBUG) {
                ToggleRow(stringResource(R.string.settings_demo_mode), stringResource(R.string.settings_demo_mode_sub), Icons.Outlined.Palette, settings.demoMode) { v ->
                    settings.demoMode = v
                }
            }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            Spacer(Modifier.height(DotSpacing.xl))
        }
            app.dot.design.components.TopEdgeFade(edgeState, app.dot.design.components.rememberTopEdgeProgress(scroll), Modifier.align(androidx.compose.ui.Alignment.TopCenter))
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
            text = { Text(stringResource(R.string.settings_remove_account_text), style = Dot.type.callout, color = c.textSecondary) },
            confirmButton = {
                DotButton(stringResource(R.string.settings_remove), style = ButtonStyle.Danger, compact = true, onClick = {
                    removing = null
                    scope.launch {
                        accountPage = 0
                        graph.accounts.removeAccount(a.id)
                        if (graph.accounts.accounts.first().isEmpty()) onAllAccountsRemoved()
                    }
                })
            },
            dismissButton = { DotButton(stringResource(R.string.settings_cancel), { removing = null }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            modifier = Modifier.dotSurfaceEnter(),
            containerColor = c.surface,
            shape = DotShapes.large,
            title = { Text(stringResource(R.string.settings_about_title, BuildConfig.VERSION_NAME), style = Dot.type.title, color = c.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.settings_about_text),
                    style = Dot.type.callout, color = c.textSecondary,
                )
            },
            confirmButton = { DotButton(stringResource(R.string.settings_close), { about = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
    if (licences) {
        AlertDialog(
            onDismissRequest = { licences = false },
            modifier = Modifier.dotSurfaceEnter(),
            containerColor = c.surface,
            shape = DotShapes.large,
            title = { Text(stringResource(R.string.settings_licences_title), style = Dot.type.title, color = c.textPrimary) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LICENCES.forEach { (name, licence) ->
                        Text(name, style = Dot.type.bodyStrong, color = c.textPrimary)
                        Text(licence, style = Dot.type.caption, color = c.textSecondary, modifier = Modifier.padding(bottom = DotSpacing.m))
                    }
                }
            },
            confirmButton = { DotButton(stringResource(R.string.settings_close), { licences = false }, style = ButtonStyle.Ghost, compact = true) },
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
    "Haze (backdrop blur)" to "Apache License 2.0",
)

@Composable
private fun Divider() = Hairline(Modifier.padding(vertical = DotSpacing.s))


@Composable
private fun swipeName(a: SwipeAction) = stringResource(when (a) {
    SwipeAction.ARCHIVE -> R.string.settings_swipe_name_archive
    SwipeAction.TRASH -> R.string.settings_swipe_name_delete
    SwipeAction.READ_TOGGLE -> R.string.settings_swipe_name_read
    SwipeAction.STAR_TOGGLE -> R.string.settings_swipe_name_star
    SwipeAction.SNOOZE -> R.string.settings_swipe_name_snooze
    SwipeAction.NONE -> R.string.settings_swipe_name_none
})

@Composable
private fun UndoSetting(seconds: Int, onChange: (Int) -> Unit) {
    ChoiceRow(
        stringResource(R.string.settings_undo_send), Icons.Outlined.Schedule, seconds,
        listOf(0, 5, 10, 20, 30).map { it to if (it == 0) stringResource(R.string.settings_undo_off) else stringResource(R.string.settings_undo_seconds, it) }, onChange = onChange,
    )
}

@Composable
private fun SettingsSearchResults(query: String, accounts: List<Account>, onOpen: (SettingsTarget) -> Unit) {
    val accountsLabel = stringResource(R.string.settings_section_accounts)
    val hits = SettingsIndex.entries.map { e ->
        SettingsHit(
            title = stringResource(e.title),
            subtitle = e.subtitle?.let { stringResource(it) },
            breadcrumb = stringResource(e.section.label),
            keywords = e.keywords?.let { stringResource(it) }.orEmpty(),
            target = e.target,
        )
    } + accounts.map { a -> SettingsHit(a.displayName, a.email, accountsLabel, a.email, SettingsTarget.Account(a.id)) }
    val results = remember(query, hits) { SettingsMatcher.match(query, hits) }
    if (results.isEmpty()) {
        Text(
            stringResource(R.string.settings_search_empty, query.trim()),
            style = Dot.type.callout, color = Dot.colors.textTertiary,
            modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.l),
        )
        return
    }
    results.forEach { h ->
        ListRow(
            title = h.title,
            // Breadcrumb first ("Appearance › …"), so the user learns where the setting lives.
            subtitle = h.breadcrumb + (h.subtitle?.let { " · $it" } ?: ""),
            onClick = { onOpen(h.target) },
            trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Dot.colors.textTertiary) },
        )
    }
}
