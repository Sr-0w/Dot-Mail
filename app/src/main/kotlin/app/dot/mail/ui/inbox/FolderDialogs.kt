package app.dot.mail.ui.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Notifications
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotMenu
import app.dot.design.components.DotMenuItem
import app.dot.design.components.DotTextField
import app.dot.design.components.ListRow
import app.dot.design.components.dotSurfaceEnter
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.dot.mail.R
import app.dot.mail.LocalMailGraph
import app.dot.mail.data.model.Folder
import app.dot.mail.data.model.FolderRole
import kotlinx.coroutines.launch

/** What the folder UI is currently asking the user. */
sealed interface FolderDialog {
    data object New : FolderDialog
    data class Options(val folder: Folder) : FolderDialog
    data class Rename(val folder: Folder) : FolderDialog
    data class Move(val folder: Folder) : FolderDialog
    data class Delete(val folder: Folder) : FolderDialog
}

/**
 * Dialogs for creating, renaming, moving and deleting custom folders. Every call goes through the repository, which
 * validates and queues the server command; its refusals (FolderException, MailActionException) are shown inline.
 * [onDeleted] lets the caller leave a folder that no longer exists.
 */
@Composable
fun FolderDialogs(
    dialog: FolderDialog?,
    accountId: Long?,
    folders: List<Folder>,
    onDismiss: () -> Unit,
    onChange: (FolderDialog) -> Unit,
    onDeleted: (Long) -> Unit,
) {
    if (dialog == null || accountId == null) return
    val graph = LocalMailGraph.current
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val c = Dot.colors
    var error by remember(dialog) { mutableStateOf<String?>(null) }
    var busy by remember(dialog) { mutableStateOf(false) }
    val genericError = stringResource(R.string.drawer_folder_error_generic)

    fun run(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { block(); onDismiss() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: genericError }
            finally { busy = false }
        }
    }

    when (dialog) {
        is FolderDialog.Options -> AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.dotSurfaceEnter(),
            containerColor = c.surface,
            shape = DotShapes.large,
            title = { Text(dialog.folder.name, style = Dot.type.title, color = c.textPrimary) },
            text = {
                Column {
                    val rules = remember { app.dot.mail.data.NotificationRules.get(appContext) }
                    val overrides by rules.folderOverrides.collectAsStateWithLifecycle()
                    val notify = app.dot.mail.data.NotificationRules.effective(overrides, dialog.folder.id, dialog.folder.role.name)
                    app.dot.mail.ui.settings.ToggleRow(
                        stringResource(R.string.drawer_folder_notify_title), stringResource(if (dialog.folder.role == FolderRole.INBOX) R.string.drawer_folder_notify_inbox_default else R.string.drawer_folder_notify_other_default),
                        Icons.Outlined.Notifications, notify,
                    ) { on -> rules.setFolderNotify(dialog.folder.id, dialog.folder.role.name, on) }
                    if (dialog.folder.role == FolderRole.CUSTOM) {
                        ListRow(stringResource(R.string.drawer_folder_rename), icon = Icons.Outlined.Edit, onClick = { onChange(FolderDialog.Rename(dialog.folder)) })
                        ListRow(stringResource(R.string.drawer_folder_move_into_ellipsis), icon = Icons.AutoMirrored.Outlined.DriveFileMove, onClick = { onChange(FolderDialog.Move(dialog.folder)) })
                        ListRow(stringResource(R.string.drawer_folder_delete), icon = Icons.Outlined.DeleteOutline, titleColor = c.danger, onClick = { onChange(FolderDialog.Delete(dialog.folder)) })
                    }
                }
            },
            confirmButton = { DotButton(stringResource(R.string.drawer_folder_close), onDismiss, style = ButtonStyle.Ghost, compact = true) },
        )

        FolderDialog.New -> {
            var name by remember { mutableStateOf("") }
            var parent by remember { mutableStateOf<Long?>(null) }
            AlertDialog(
                onDismissRequest = onDismiss,
                modifier = Modifier.dotSurfaceEnter(),
                containerColor = c.surface,
                shape = DotShapes.large,
                title = { Text(stringResource(R.string.drawer_new_folder), style = Dot.type.title, color = c.textPrimary) },
                text = {
                    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DotSpacing.m)) {
                        DotTextField(name, { name = it; error = null }, label = stringResource(R.string.drawer_folder_name_label), isError = error != null, supporting = error)
                        ParentPicker(folders, exclude = null, selected = parent, onSelect = { parent = it })
                    }
                },
                confirmButton = {
                    DotButton(stringResource(R.string.drawer_folder_create), enabled = name.isNotBlank() && !busy, loading = busy, compact = true, onClick = {
                        run { graph.mail.createFolder(accountId, parent, name) }
                    })
                },
                dismissButton = { DotButton(stringResource(R.string.drawer_folder_cancel), onDismiss, style = ButtonStyle.Ghost, compact = true) },
            )
        }

        is FolderDialog.Rename -> {
            var name by remember { mutableStateOf(dialog.folder.name) }
            AlertDialog(
                onDismissRequest = onDismiss,
                modifier = Modifier.dotSurfaceEnter(),
                containerColor = c.surface,
                shape = DotShapes.large,
                title = { Text(stringResource(R.string.drawer_folder_rename_title), style = Dot.type.title, color = c.textPrimary) },
                text = { DotTextField(name, { name = it; error = null }, label = stringResource(R.string.drawer_folder_name_label), isError = error != null, supporting = error) },
                confirmButton = {
                    DotButton(stringResource(R.string.drawer_folder_rename), enabled = name.isNotBlank() && name.trim() != dialog.folder.name && !busy, loading = busy, compact = true, onClick = {
                        run { graph.mail.renameFolder(dialog.folder.id, name) }
                    })
                },
                dismissButton = { DotButton(stringResource(R.string.drawer_folder_cancel), onDismiss, style = ButtonStyle.Ghost, compact = true) },
            )
        }

        is FolderDialog.Move -> {
            var parent by remember { mutableStateOf<Long?>(null) }
            AlertDialog(
                onDismissRequest = onDismiss,
                modifier = Modifier.dotSurfaceEnter(),
                containerColor = c.surface,
                shape = DotShapes.large,
                title = { Text(stringResource(R.string.drawer_folder_move_title, dialog.folder.name), style = Dot.type.title, color = c.textPrimary) },
                text = {
                    Column {
                        ParentPicker(folders, exclude = dialog.folder, selected = parent, onSelect = { parent = it }, label = stringResource(R.string.drawer_folder_move_into_label))
                        error?.let { Text(it, style = Dot.type.caption, color = c.danger, modifier = Modifier.padding(top = DotSpacing.s)) }
                    }
                },
                confirmButton = {
                    DotButton(stringResource(R.string.drawer_folder_move_action), enabled = !busy, loading = busy, compact = true, onClick = { run { graph.mail.moveFolder(dialog.folder.id, parent) } })
                },
                dismissButton = { DotButton(stringResource(R.string.drawer_folder_cancel), onDismiss, style = ButtonStyle.Ghost, compact = true) },
            )
        }

        is FolderDialog.Delete -> {
            val trash = folders.firstOrNull { it.role == FolderRole.TRASH }
            val hasMail = dialog.folder.totalCount > 0
            AlertDialog(
                onDismissRequest = onDismiss,
                modifier = Modifier.dotSurfaceEnter(),
                containerColor = c.surface,
                shape = DotShapes.large,
                title = { Text(stringResource(R.string.drawer_folder_delete_title, dialog.folder.name), style = Dot.type.title, color = c.textPrimary) },
                text = {
                    Column {
                        Text(
                            when {
                                !hasMail -> stringResource(R.string.drawer_folder_delete_empty_body)
                                trash != null -> pluralStringResource(R.plurals.drawer_folder_delete_move_trash_body, dialog.folder.totalCount, dialog.folder.totalCount)
                                else -> stringResource(R.string.drawer_folder_delete_no_trash_body)
                            },
                            style = Dot.type.callout, color = c.textSecondary,
                        )
                        error?.let { Text(it, style = Dot.type.caption, color = c.danger, modifier = Modifier.padding(top = DotSpacing.s)) }
                    }
                },
                confirmButton = {
                    DotButton(
                        stringResource(if (hasMail) R.string.drawer_folder_move_trash_and_delete else R.string.drawer_folder_delete), style = ButtonStyle.Danger,
                        enabled = !busy && (!hasMail || trash != null), loading = busy, compact = true,
                        onClick = { run { graph.mail.deleteFolder(dialog.folder.id, if (hasMail) trash?.id else null); onDeleted(dialog.folder.id) } },
                    )
                },
                dismissButton = { DotButton(stringResource(R.string.drawer_folder_cancel), onDismiss, style = ButtonStyle.Ghost, compact = true) },
            )
        }
    }
}

/** "Top level" plus every custom folder (indented by depth) except [exclude] and its subfolders. */
@Composable
private fun ParentPicker(folders: List<Folder>, exclude: Folder?, selected: Long?, onSelect: (Long?) -> Unit, label: String = stringResource(R.string.drawer_folder_inside_label)) {
    var open by remember { mutableStateOf(false) }
    val options = folders.filter { it.role == FolderRole.CUSTOM && !isSameOrDescendant(it, exclude) }.sortedBy { it.path.lowercase() }
    val current = options.firstOrNull { it.id == selected }
    Box {
        ListRow(
            label, subtitle = current?.path ?: stringResource(R.string.drawer_folder_top_level), onClick = { open = true },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        )
        DotMenu(open, { open = false }) {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                DotMenuItem(stringResource(R.string.drawer_folder_top_level), null) { open = false; onSelect(null) }
                options.forEach { f -> DotMenuItem(f.path, null) { open = false; onSelect(f.id) } }
            }
        }
    }
}

private fun isSameOrDescendant(f: Folder, root: Folder?): Boolean =
    root != null && (f.id == root.id || (f.path.length > root.path.length && f.path.startsWith(root.path) && !f.path[root.path.length].isLetterOrDigit()))
