package app.dot.mail.ui.settings

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
    Column(Modifier.fillMaxSize().background(c.background)) {
        DotTopBar(title = account.displayName, subtitle = account.email, elevated = scroll.value > 0, navigation = { BackButton(onBack) })
        Column(Modifier.verticalScroll(scroll).navigationBarsPadding()) {
            (account.syncState as? SyncState.Error)?.let { err ->
                Text(err.message, style = Dot.type.callout, color = c.danger, modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.m))
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
    var signature by remember { mutableStateOf(existing?.signature.orEmpty()) }
    var makeDefault by remember { mutableStateOf(existing?.isDefault == true) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val saveFailed = stringResource(R.string.identity_save_failed)

    val emailOk = isPrimary || IdentityStore.isValidAddress(email)
    val replyToOk = replyTo.isBlank() || IdentityStore.isValidAddress(replyTo)
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize().background(c.background).imePadding()) {
        DotTopBar(
            title = stringResource(if (existing == null) R.string.identity_new else R.string.identity_edit),
            elevated = scroll.value > 0,
            navigation = { BackButton(onBack) },
        )
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = DotSpacing.gutter), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DotSpacing.l)) {
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
            DotTextField(signature, { signature = it }, label = stringResource(R.string.identity_signature), singleLine = false, placeholder = stringResource(R.string.identity_signature_placeholder))
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
                        // Editing the plain text invalidates a stored HTML signature; keep it only if untouched.
                        val htmlStillValid = existing?.signature.orEmpty() == signature
                        val id = graph.accounts.saveIdentity(
                            Identity(
                                id = identityId, accountId = account.id, name = name, email = email,
                                signature = signature.ifBlank { null }, replyTo = replyTo.ifBlank { null },
                                signatureHtml = if (htmlStillValid) existing?.signatureHtml else null,
                            ),
                        )
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

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            modifier = Modifier.dotSurfaceEnter(),
            containerColor = c.surface,
            shape = DotShapes.large,
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
        containerColor = c.surface, shape = DotShapes.large,
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
