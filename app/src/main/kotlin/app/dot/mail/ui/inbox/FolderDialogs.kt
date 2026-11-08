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
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
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
    val scope = rememberCoroutineScope()
    val c = Dot.colors
    var error by remember(dialog) { mutableStateOf<String?>(null) }
    var busy by remember(dialog) { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { block(); onDismiss() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Couldn't change the folder." }
            finally { busy = false }
        }
    }

    when (dialog) {
        is FolderDialog.Options -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = c.surface,
            shape = DotShapes.large,
            title = { Text(dialog.folder.name, style = Dot.type.title, color = c.textPrimary) },
            text = {
                Column {
                    ListRow("Rename", icon = Icons.Outlined.Edit, onClick = { onChange(FolderDialog.Rename(dialog.folder)) })
                    ListRow("Move into…", icon = Icons.Outlined.DriveFileMove, onClick = { onChange(FolderDialog.Move(dialog.folder)) })
                    ListRow("Delete", icon = Icons.Outlined.DeleteOutline, titleColor = c.danger, onClick = { onChange(FolderDialog.Delete(dialog.folder)) })
                }
            },
            confirmButton = { DotButton("Close", onDismiss, style = ButtonStyle.Ghost, compact = true) },
        )

        FolderDialog.New -> {
            var name by remember { mutableStateOf("") }
            var parent by remember { mutableStateOf<Long?>(null) }
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = c.surface,
                shape = DotShapes.large,
                title = { Text("New folder", style = Dot.type.title, color = c.textPrimary) },
                text = {
                    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DotSpacing.m)) {
                        DotTextField(name, { name = it; error = null }, label = "Name", isError = error != null, supporting = error)
                        ParentPicker(folders, exclude = null, selected = parent, onSelect = { parent = it })
                    }
                },
                confirmButton = {
                    DotButton("Create", enabled = name.isNotBlank() && !busy, loading = busy, compact = true, onClick = {
                        run { graph.mail.createFolder(accountId, parent, name) }
                    })
                },
                dismissButton = { DotButton("Cancel", onDismiss, style = ButtonStyle.Ghost, compact = true) },
            )
        }

        is FolderDialog.Rename -> {
            var name by remember { mutableStateOf(dialog.folder.name) }
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = c.surface,
                shape = DotShapes.large,
                title = { Text("Rename folder", style = Dot.type.title, color = c.textPrimary) },
                text = { DotTextField(name, { name = it; error = null }, label = "Name", isError = error != null, supporting = error) },
                confirmButton = {
                    DotButton("Rename", enabled = name.isNotBlank() && name.trim() != dialog.folder.name && !busy, loading = busy, compact = true, onClick = {
                        run { graph.mail.renameFolder(dialog.folder.id, name) }
                    })
                },
                dismissButton = { DotButton("Cancel", onDismiss, style = ButtonStyle.Ghost, compact = true) },
            )
        }

        is FolderDialog.Move -> {
            var parent by remember { mutableStateOf<Long?>(null) }
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = c.surface,
                shape = DotShapes.large,
                title = { Text("Move ${dialog.folder.name}", style = Dot.type.title, color = c.textPrimary) },
                text = {
                    Column {
                        ParentPicker(folders, exclude = dialog.folder, selected = parent, onSelect = { parent = it }, label = "Move into")
                        error?.let { Text(it, style = Dot.type.caption, color = c.danger, modifier = Modifier.padding(top = DotSpacing.s)) }
                    }
                },
                confirmButton = {
                    DotButton("Move", enabled = !busy, loading = busy, compact = true, onClick = { run { graph.mail.moveFolder(dialog.folder.id, parent) } })
                },
                dismissButton = { DotButton("Cancel", onDismiss, style = ButtonStyle.Ghost, compact = true) },
            )
        }

        is FolderDialog.Delete -> {
            val trash = folders.firstOrNull { it.role == FolderRole.TRASH }
            val hasMail = dialog.folder.totalCount > 0
            AlertDialog(
                onDismissRequest = onDismiss,
                containerColor = c.surface,
                shape = DotShapes.large,
                title = { Text("Delete ${dialog.folder.name}?", style = Dot.type.title, color = c.textPrimary) },
                text = {
                    Column {
                        Text(
                            when {
                                !hasMail -> "The folder is empty. It will also be deleted on your mail server."
                                trash != null -> "Its ${dialog.folder.totalCount} messages move to Trash first, then the folder is deleted on your mail server."
                                else -> "This folder still contains messages and there is no Trash folder to move them to."
                            },
                            style = Dot.type.callout, color = c.textSecondary,
                        )
                        error?.let { Text(it, style = Dot.type.caption, color = c.danger, modifier = Modifier.padding(top = DotSpacing.s)) }
                    }
                },
                confirmButton = {
                    DotButton(
                        if (hasMail) "Move to Trash and delete" else "Delete", style = ButtonStyle.Danger,
                        enabled = !busy && (!hasMail || trash != null), loading = busy, compact = true,
                        onClick = { run { graph.mail.deleteFolder(dialog.folder.id, if (hasMail) trash?.id else null); onDeleted(dialog.folder.id) } },
                    )
                },
                dismissButton = { DotButton("Cancel", onDismiss, style = ButtonStyle.Ghost, compact = true) },
            )
        }
    }
}

/** "Top level" plus every custom folder (indented by depth) except [exclude] and its subfolders. */
@Composable
private fun ParentPicker(folders: List<Folder>, exclude: Folder?, selected: Long?, onSelect: (Long?) -> Unit, label: String = "Inside") {
    var open by remember { mutableStateOf(false) }
    val options = folders.filter { it.role == FolderRole.CUSTOM && !isSameOrDescendant(it, exclude) }.sortedBy { it.path.lowercase() }
    val current = options.firstOrNull { it.id == selected }
    Box {
        ListRow(
            label, subtitle = current?.path ?: "Top level", onClick = { open = true },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        )
        DotMenu(open, { open = false }) {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                DotMenuItem("Top level", null) { open = false; onSelect(null) }
                options.forEach { f -> DotMenuItem(f.path, null) { open = false; onSelect(f.id) } }
            }
        }
    }
}

private fun isSameOrDescendant(f: Folder, root: Folder?): Boolean =
    root != null && (f.id == root.id || (f.path.length > root.path.length && f.path.startsWith(root.path) && !f.path[root.path.length].isLetterOrDigit()))
