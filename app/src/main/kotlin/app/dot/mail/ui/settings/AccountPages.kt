package app.dot.mail.ui.settings

import app.dot.design.components.backdropSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.History
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dot.mail.data.AccountSettingsStore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.Avatar
import app.dot.design.components.BackButton
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotTextField
import app.dot.design.components.DotTopBar
import app.dot.design.components.Hairline
import app.dot.design.components.ListRow
import app.dot.design.components.SectionLabel
import app.dot.design.components.dotSurfaceEnter
import androidx.compose.ui.res.stringResource
import app.dot.mail.R
import app.dot.mail.LocalMailGraph
import app.dot.mail.data.IdentityStore
import app.dot.mail.data.model.Account
import app.dot.mail.data.model.Identity
import app.dot.mail.data.model.SyncState
import kotlinx.coroutines.launch

/** One account: its status, its sender identities, and removal. */
@Composable
internal fun AccountPage(account: Account, onBack: () -> Unit, onEditIdentity: (Long) -> Unit, onRemove: () -> Unit) {
    val c = Dot.colors
    val scroll = rememberScrollState()
    val frostedSource=app.dot.design.components.rememberGlassBackdrop()
    app.dot.design.components.FrostedHeaderLayout(
        backdrop=frostedSource,modifier=Modifier.fillMaxSize().background(c.background),
        header={ DotTopBar(title = account.displayName, subtitle = account.email, elevated = scroll.value > 0, navigation = { BackButton(onBack) }) },
    ) { topPadding ->
        Column(Modifier.fillMaxSize().backdropSource(frostedSource).verticalScroll(scroll).navigationBarsPadding()) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(topPadding.calculateTopPadding()))

            (account.syncState as? SyncState.Error)?.let { err ->
                Text(app.dot.mail.ui.common.ErrorText.syncError(err), style = Dot.type.callout, color = c.danger, modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.m))
            }
            val context = LocalContext.current
            val graph = LocalMailGraph.current
            val store = remember { AccountSettingsStore.get(context.applicationContext) }
            val cfg by store.settings(account.id).collectAsStateWithLifecycle()
            var renaming by remember { mutableStateOf(false) }
            var signingIn by remember { mutableStateOf(false) }

            SectionLabel(stringResource(R.string.account_section_account), Modifier.padding(top = DotSpacing.s))
            ListRow(stringResource(R.string.account_name), subtitle = account.displayName, icon = Icons.Outlined.Edit, onClick = { renaming = true })
            ListRow(stringResource(R.string.account_update_password), subtitle = stringResource(R.string.account_update_password_sub), icon = Icons.Outlined.Key, onClick = { signingIn = true })
            ListRow(
                stringResource(R.string.account_signature), subtitle = account.identities.firstOrNull { it.id == account.id }?.signature?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: stringResource(R.string.account_signature_none),
                icon = Icons.Outlined.Edit, onClick = { onEditIdentity(account.id) },
            )

            SectionLabel(stringResource(R.string.account_section_sync))
            ChoiceRow(
                stringResource(R.string.account_check_mail), Icons.Outlined.Schedule, cfg.syncIntervalMinutes,
                listOf(15 to stringResource(R.string.account_check_15), 30 to stringResource(R.string.account_check_30), 60 to stringResource(R.string.account_check_60), 0 to stringResource(R.string.account_check_manual)),
            ) { v -> store.update(account.id) { it.copy(syncIntervalMinutes = v) } }
            Text(
                stringResource(if (cfg.syncIntervalMinutes == 0) R.string.account_check_off_note else R.string.account_check_on_note),
                style = Dot.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs),
            )
            ChoiceRow(
                stringResource(R.string.account_window), Icons.Outlined.History, cfg.syncWindowDays,
                listOf(14 to stringResource(R.string.account_window_14), 30 to stringResource(R.string.account_window_30), 90 to stringResource(R.string.account_window_90), 0 to stringResource(R.string.account_window_all)),
            ) { v -> store.update(account.id) { it.copy(syncWindowDays = v) } }
            Text(
                stringResource(R.string.account_window_note),
                style = Dot.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs),
            )

            if (remember { app.dot.mail.ui.pgp.OpenKeychain.installed(context) }) {
                SectionLabel(stringResource(R.string.account_section_pgp))
                ToggleRow(stringResource(R.string.account_pgp_sign), stringResource(R.string.account_pgp_sign_sub), Icons.Outlined.Key, cfg.pgpSignDefault) { v -> store.update(account.id) { it.copy(pgpSignDefault = v) } }
                ToggleRow(stringResource(R.string.account_pgp_encrypt), stringResource(R.string.account_pgp_encrypt_sub), Icons.Outlined.Key, cfg.pgpEncryptDefault) { v -> store.update(account.id) { it.copy(pgpEncryptDefault = v) } }
            }

            SectionLabel(stringResource(R.string.account_section_notifications))
            ToggleRow(stringResource(R.string.account_notify), stringResource(R.string.account_notify_for, account.email), Icons.Outlined.Notifications, cfg.notificationsEnabled) { v ->
                store.update(account.id) { it.copy(notificationsEnabled = v) }
            }
            if (cfg.notificationsEnabled) {
                val folders by remember(account.id) { graph.mail.folders(account.id) }.collectAsStateWithLifecycle(emptyList())
                val rules = remember { app.dot.mail.data.NotificationRules.get(context.applicationContext) }
                val overrides by rules.folderOverrides.collectAsStateWithLifecycle()
                Text(
                    stringResource(R.string.account_notify_folders_note),
                    style = Dot.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs),
                )
                folders.filter { it.role != app.dot.mail.data.model.FolderRole.ALL_MAIL && it.role != app.dot.mail.data.model.FolderRole.STARRED }
                    .sortedWith(compareBy({ app.dot.mail.ui.common.Format.roleOrder.indexOf(it.role).let { i -> if (i < 0) 99 else i } }, { it.path.lowercase() }))
                    .forEach { f ->
                        ToggleRow(
                            app.dot.mail.ui.common.Format.folderTitle(f.role, f.path), null, null,
                            app.dot.mail.data.NotificationRules.effective(overrides, f.id, f.role.name),
                        ) { on -> rules.setFolderNotify(f.id, f.role.name, on) }
                    }
            }
            ListRow(
                stringResource(R.string.account_sound_vibration), subtitle = stringResource(R.string.account_sound_vibration_sub), icon = Icons.Outlined.NotificationsActive,
                onClick = {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, "dotmail.account.${account.id}"),
                    )
                },
            )
            if (renaming) RenameAccountDialog(account) { renaming = false }
            if (signingIn) app.dot.mail.ui.account.UpdatePasswordDialog(account, onDismiss = { signingIn = false }, onSuccess = {})
            SectionLabel(stringResource(R.string.account_section_identities), Modifier.padding(top = DotSpacing.m))
            Text(
                stringResource(R.string.account_identities_note),
                style = Dot.type.caption, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs),
            )
            val defaultSuffix = stringResource(R.string.account_identity_default_suffix)
            val repliesToTemplate = stringResource(R.string.account_identity_replies_to, "\u0001")
            fun repliesTo(a: String) = repliesToTemplate.replace("\u0001", a)
            account.identities.forEach { i ->
                ListRow(
                    title = i.name.ifBlank { i.email },
                    subtitle = buildString {
                        append(i.email)
                        if (i.isDefault) append(defaultSuffix)
                        if (i.replyTo != null) append("\n").append(repliesTo(i.replyTo!!))
                    },
                    onClick = { onEditIdentity(i.id) },
                    trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.textTertiary) },
                )
            }
            ListRow(stringResource(R.string.account_add_identity), icon = Icons.Outlined.Add, onClick = { onEditIdentity(0) })
            Hairline(Modifier.padding(vertical = DotSpacing.s))
            ListRow(stringResource(R.string.account_remove), subtitle = stringResource(R.string.account_remove_sub),
                icon = Icons.Outlined.DeleteOutline, titleColor = c.danger, onClick = onRemove)
            Spacer(Modifier.height(DotSpacing.xl))

        }
    }
}

/** Create or edit one identity. [identityId] 0 = new. */
@Composable
internal fun IdentityEditorPage(account: Account, identityId: Long, onBack: () -> Unit) {
    val c = Dot.colors
    val graph = LocalMailGraph.current
    val scope = rememberCoroutineScope()
    val existing = account.identities.firstOrNull { it.id == identityId }
    val isPrimary = identityId == account.id
    var name by remember { mutableStateOf(existing?.name ?: account.displayName) }
    var email by remember { mutableStateOf(existing?.email.orEmpty()) }
    var replyTo by remember { mutableStateOf(existing?.replyTo.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val sigState = com.mohamedrejeb.richeditor.model.rememberRichTextState()
    var logo by remember { mutableStateOf(existing?.signatureLogo) }
    val createdLogos = remember { mutableListOf<String>() }
    var logoSaved by remember { mutableStateOf(false) }
    var sigOnNew by remember { mutableStateOf(existing?.signatureOnNew ?: true) }
    var sigOnReply by remember { mutableStateOf(existing?.signatureOnReply ?: true) }
    var baselineHtml by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var htmlPicked by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val logoFailed = stringResource(R.string.identity_signature_logo_failed)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val initial = existing?.signatureHtml
            ?: existing?.signature?.let { app.dot.mail.ui.compose.ComposeViewModel.signatureBodyHtml(app.dot.mail.ui.compose.Sender(0, null, "", "", signature = it)) }.orEmpty()
        sigState.loadSignature(initial)
        baselineHtml = signatureHtmlOf(sigState)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (!logoSaved) createdLogos.forEach { app.dot.mail.data.SignatureLogos.delete(context, it) } }
    }
    val pickLogo = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val name = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.takeIf { it.size <= 8 * 1024 * 1024 }
                    bytes?.let { app.dot.mail.data.SignatureLogos.save(context, it, "${account.id}-$identityId") }
                }.getOrNull()
            }
            if (name != null) { createdLogos += name; logo = name } else error = logoFailed
        }
    }
    val pickHtml = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val callback = htmlPicked
        if (uri != null && callback != null) scope.launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.bufferedReader(Charsets.UTF_8).use { r -> CharArray(1_000_000).let { buf -> String(buf, 0, r.read(buf).coerceAtLeast(0)) } } } }.getOrNull()
            }
            if (text != null) callback(text)
        }
    }
    var makeDefault by remember { mutableStateOf(existing?.isDefault == true) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val saveFailed = stringResource(R.string.identity_save_failed)

    val emailOk = isPrimary || IdentityStore.isValidAddress(email)
    val replyToOk = replyTo.isBlank() || IdentityStore.isValidAddress(replyTo)
    val scroll = rememberScrollState()
    val dirty = baselineHtml?.let { base ->
        name != (existing?.name ?: account.displayName) || email != existing?.email.orEmpty() || replyTo != existing?.replyTo.orEmpty() ||
            logo != existing?.signatureLogo || sigOnNew != (existing?.signatureOnNew ?: true) || sigOnReply != (existing?.signatureOnReply ?: true) ||
            makeDefault != (existing?.isDefault == true) || signatureHtmlOf(sigState) != base
    } == true
    val leave: () -> Unit = { if (dirty && !busy) confirmDiscard = true else onBack() }
    androidx.activity.compose.BackHandler(enabled = dirty && !busy) { confirmDiscard = true }

    val frostedSource=app.dot.design.components.rememberGlassBackdrop()
    app.dot.design.components.FrostedHeaderLayout(
        backdrop=frostedSource,modifier=Modifier.fillMaxSize().background(c.background).imePadding(),
        header={ DotTopBar(
            title = stringResource(if (existing == null) R.string.identity_new else R.string.identity_edit),
            elevated = scroll.value > 0,
            navigation = { BackButton(leave) },
        ) },
    ) { topPadding ->
        Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().weight(1f).backdropSource(frostedSource).verticalScroll(scroll).padding(horizontal = DotSpacing.gutter), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DotSpacing.l)) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(topPadding.calculateTopPadding()))

            DotTextField(name, { name = it }, label = stringResource(R.string.identity_name), placeholder = stringResource(R.string.identity_name_placeholder))
            DotTextField(
                email, { email = it }, label = stringResource(R.string.identity_address), keyboardType = KeyboardType.Email,
                enabled = !isPrimary,
                isError = email.isNotBlank() && !emailOk,
                supporting = when {
                    isPrimary -> stringResource(R.string.identity_address_primary)
                    email.isNotBlank() && !emailOk -> stringResource(R.string.identity_address_invalid)
                    else -> stringResource(R.string.identity_address_alias, account.email)
                },
            )
            DotTextField(
                replyTo, { replyTo = it }, label = stringResource(R.string.identity_reply_to), keyboardType = KeyboardType.Email,
                isError = !replyToOk,
                supporting = stringResource(if (!replyToOk) R.string.identity_address_invalid else R.string.identity_reply_to_note),
            )
            SignatureSection(
                state = sigState, logo = logo,
                onPickLogo = { pickLogo.launch("image/*") },
                onRemoveLogo = { logo = null },
                onPickHtmlFile = { cb -> htmlPicked = cb; pickHtml.launch(arrayOf("text/html", "text/plain")) },
            )
            ToggleRow(stringResource(R.string.identity_signature_new), null, null, sigOnNew) { sigOnNew = it }
            ToggleRow(stringResource(R.string.identity_signature_reply), null, null, sigOnReply) { sigOnReply = it }
            ToggleRow(
                stringResource(R.string.identity_default), stringResource(R.string.identity_default_sub), null, makeDefault,
                enabled = existing?.isDefault != true, // choose another one to change the default
            ) { makeDefault = it }
            error?.let { Text(it, style = Dot.type.callout, color = c.danger) }
            if (existing != null && !isPrimary) {
                DotButton(stringResource(R.string.identity_delete), { confirmDelete = true }, style = ButtonStyle.Danger, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(DotSpacing.l))

        }

        DotButton(
            text = stringResource(R.string.identity_save),
            enabled = emailOk && replyToOk && (isPrimary || email.isNotBlank()) && !busy,
            loading = busy,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(DotSpacing.gutter),
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val html = signatureHtmlOf(sigState)
                        val id = graph.accounts.saveIdentity(
                            Identity(
                                id = identityId, accountId = account.id, name = name, email = email,
                                signature = html.takeIf { it.isNotBlank() }?.let { app.dot.mail.engine.text.EmailHtml.toPlainText(it) },
                                replyTo = replyTo.ifBlank { null },
                                signatureHtml = html.ifBlank { null },
                                signatureLogo = logo, signatureOnNew = sigOnNew, signatureOnReply = sigOnReply,
                            ),
                        )
                        logoSaved = true
                        // Drop every logo file that is no longer referenced (replaced or removed).
                        (createdLogos + listOfNotNull(existing?.signatureLogo)).filter { it != logo }.forEach { app.dot.mail.data.SignatureLogos.delete(context, it) }
                        if (makeDefault && existing?.isDefault != true) graph.accounts.setDefaultIdentity(account.id, id)
                        onBack()
                    } catch (e: IllegalArgumentException) {
                        error = e.message ?: saveFailed
                    } finally {
                        busy = false
                    }
                }
            },
        )

        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            modifier = Modifier.dotSurfaceEnter(),
            containerColor = c.surface,
            shape = DotShapes.dialog,
            title = { Text(stringResource(R.string.identity_discard_title), style = Dot.type.title, color = c.textPrimary) },
            text = { Text(stringResource(R.string.identity_discard_body), style = Dot.type.callout, color = c.textSecondary) },
            confirmButton = { DotButton(stringResource(R.string.identity_keep_editing), { confirmDiscard = false }, compact = true) },
            dismissButton = { DotButton(stringResource(R.string.identity_discard), { confirmDiscard = false; onBack() }, style = ButtonStyle.Danger, compact = true) },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            modifier = Modifier.dotSurfaceEnter(),
            containerColor = c.surface,
            shape = DotShapes.dialog,
            title = { Text(stringResource(R.string.identity_delete_title, existing?.email.orEmpty()), style = Dot.type.title, color = c.textPrimary) },
            text = { Text(stringResource(R.string.identity_delete_body), style = Dot.type.callout, color = c.textSecondary) },
            confirmButton = {
                DotButton(stringResource(R.string.identity_delete_confirm), style = ButtonStyle.Danger, compact = true, onClick = {
                    confirmDelete = false
                    scope.launch { graph.accounts.deleteIdentity(account.id, identityId); onBack() }
                })
            },
            dismissButton = { DotButton(stringResource(R.string.identity_cancel), { confirmDelete = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
}

@Composable
private fun RenameAccountDialog(account: Account, onDismiss: () -> Unit) {
    val c = Dot.colors
    val graph = LocalMailGraph.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(account.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface, shape = DotShapes.dialog,
        title = { Text(stringResource(R.string.account_name_title), style = Dot.type.title, color = c.textPrimary) },
        text = { DotTextField(name, { name = it }, label = stringResource(R.string.identity_name), supporting = stringResource(R.string.account_name_note)) },
        confirmButton = {
            DotButton(stringResource(R.string.identity_save), enabled = name.isNotBlank() && name.trim() != account.displayName, compact = true, onClick = {
                scope.launch { graph.accounts.renameAccount(account.id, name); onDismiss() }
            })
        },
        dismissButton = { DotButton(stringResource(R.string.identity_cancel), onDismiss, style = ButtonStyle.Ghost, compact = true) },
    )
}
