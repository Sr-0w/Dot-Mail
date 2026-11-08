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
            val store = remember { AccountSettingsStore.get(context.applicationContext) }
            val cfg by store.settings(account.id).collectAsStateWithLifecycle()
            var renaming by remember { mutableStateOf(false) }
            var signingIn by remember { mutableStateOf(false) }

            SectionLabel("Account", Modifier.padding(top = DotSpacing.s))
            ListRow("Name", subtitle = account.displayName, icon = Icons.Outlined.Edit, onClick = { renaming = true })
            ListRow("Update password", subtitle = "Sign in again with a new password or app password", icon = Icons.Outlined.Key, onClick = { signingIn = true })
            ListRow(
                "Signature", subtitle = account.identities.firstOrNull { it.id == account.id }?.signature?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "None",
                icon = Icons.Outlined.Edit, onClick = { onEditIdentity(account.id) },
            )

            SectionLabel("Sync")
            ChoiceRow(
                "Check for new mail", Icons.Outlined.Schedule, cfg.syncIntervalMinutes,
                listOf(15 to "Every 15 minutes", 30 to "Every 30 minutes", 60 to "Every hour", 0 to "Only when I refresh"),
            ) { v -> store.update(account.id) { it.copy(syncIntervalMinutes = v) } }
            Text(
                if (cfg.syncIntervalMinutes == 0) "Background checks are off for this account. Opening the app or pulling to refresh still syncs." else "Used for background checks. Opening the app always syncs.",
                style = Dot.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs),
            )
            ChoiceRow(
                "Download mail from", Icons.Outlined.History, cfg.syncWindowDays,
                listOf(14 to "The last 2 weeks", 30 to "The last month", 90 to "The last 3 months", 0 to "All mail"),
            ) { v -> store.update(account.id) { it.copy(syncWindowDays = v) } }
            Text(
                "Older mail stays on the server and is found by search. Changing this downloads message lists again on the next sync. \"All mail\" is capped at the newest 5000 messages per folder.",
                style = Dot.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs),
            )

            SectionLabel("Notifications")
            ToggleRow("Notify about new mail", "For ${account.email}", Icons.Outlined.Notifications, cfg.notificationsEnabled) { v ->
                store.update(account.id) { it.copy(notificationsEnabled = v) }
            }
            ListRow(
                "Sound and vibration", subtitle = "Opens this account's notification channel in system settings", icon = Icons.Outlined.NotificationsActive,
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
            SectionLabel("Sender identities", Modifier.padding(top = DotSpacing.m))
            Text(
                "Addresses you can send from. Replies use the identity the original message was sent to.",
                style = Dot.type.caption, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs),
            )
            account.identities.forEach { i ->
                ListRow(
                    title = i.name.ifBlank { i.email },
                    subtitle = buildString {
                        append(i.email)
                        if (i.isDefault) append(" · Default")
                        if (i.replyTo != null) append("\nReplies to ${i.replyTo}")
                    },
                    onClick = { onEditIdentity(i.id) },
                    trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.textTertiary) },
                )
            }
            ListRow("Add identity", icon = Icons.Outlined.Add, onClick = { onEditIdentity(0) })
            Hairline(Modifier.padding(vertical = DotSpacing.s))
            ListRow("Remove account", subtitle = "Mail stays on your server; the local copy and saved password are deleted.",
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

    val emailOk = isPrimary || IdentityStore.isValidAddress(email)
    val replyToOk = replyTo.isBlank() || IdentityStore.isValidAddress(replyTo)
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize().background(c.background).imePadding()) {
        DotTopBar(
            title = if (existing == null) "New identity" else "Edit identity",
            elevated = scroll.value > 0,
            navigation = { BackButton(onBack) },
        )
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = DotSpacing.gutter), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DotSpacing.l)) {
            DotTextField(name, { name = it }, label = "Name", placeholder = "Shown to recipients")
            DotTextField(
                email, { email = it }, label = "Address", keyboardType = KeyboardType.Email,
                enabled = !isPrimary,
                isError = email.isNotBlank() && !emailOk,
                supporting = when {
                    isPrimary -> "This is the address you sign in with; it can't be changed."
                    email.isNotBlank() && !emailOk -> "Enter a valid address."
                    else -> "An alias your provider accepts for this account. Mail is sent through ${account.email}."
                },
            )
            DotTextField(
                replyTo, { replyTo = it }, label = "Reply-to (optional)", keyboardType = KeyboardType.Email,
                isError = !replyToOk,
                supporting = if (!replyToOk) "Enter a valid address." else "Replies to messages sent from this identity go here instead.",
            )
            DotTextField(signature, { signature = it }, label = "Signature", singleLine = false, placeholder = "Added to new messages")
            ToggleRow(
                "Default identity", "Used for new messages.", null, makeDefault,
                enabled = existing?.isDefault != true, // choose another one to change the default
            ) { makeDefault = it }
            error?.let { Text(it, style = Dot.type.callout, color = c.danger) }
            if (existing != null && !isPrimary) {
                DotButton("Delete identity", { confirmDelete = true }, style = ButtonStyle.Danger, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(DotSpacing.l))
        }
        DotButton(
            text = "Save",
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
                        error = e.message ?: "Couldn't save this identity."
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
            title = { Text("Delete ${existing?.email}?", style = Dot.type.title, color = c.textPrimary) },
            text = { Text("Drafts using it will be sent from your default identity instead.", style = Dot.type.callout, color = c.textSecondary) },
            confirmButton = {
                DotButton("Delete", style = ButtonStyle.Danger, compact = true, onClick = {
                    confirmDelete = false
                    scope.launch { graph.accounts.deleteIdentity(account.id, identityId); onBack() }
                })
            },
            dismissButton = { DotButton("Cancel", { confirmDelete = false }, style = ButtonStyle.Ghost, compact = true) },
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
        title = { Text("Account name", style = Dot.type.title, color = c.textPrimary) },
        text = { DotTextField(name, { name = it }, label = "Name", supporting = "Shown in the account list and as your sender name.") },
        confirmButton = {
            DotButton("Save", enabled = name.isNotBlank() && name.trim() != account.displayName, compact = true, onClick = {
                scope.launch { graph.accounts.renameAccount(account.id, name); onDismiss() }
            })
        },
        dismissButton = { DotButton("Cancel", onDismiss, style = ButtonStyle.Ghost, compact = true) },
    )
}
