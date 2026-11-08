package app.dot.mail.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material.icons.outlined.FilterList
import app.dot.mail.data.model.category
import app.dot.mail.data.model.viewRole
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AllInbox
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.res.stringResource
import app.dot.mail.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.Avatar
import app.dot.design.components.Hairline
import app.dot.design.components.SectionLabel
import app.dot.mail.data.model.FolderRole
import app.dot.mail.data.model.Mailbox
import app.dot.mail.data.model.SyncState
import app.dot.mail.ui.common.Format

@Composable
fun MailDrawer(
    state: DrawerState,
    current: Mailbox,
    onOpen: (Mailbox) -> Unit,
    onFocusAccount: (Long) -> Unit,
    onAddAccount: () -> Unit,
    onSettings: () -> Unit,
    outboxCount: Int = 0,
    outboxNeedsAttention: Boolean = false,
    onOutbox: () -> Unit = {},
    onAttachments: () -> Unit = {},
) {
    val c = Dot.colors
    val graph = app.dot.mail.LocalMailGraph.current
    var folderDialog by remember { mutableStateOf<FolderDialog?>(null) }
    FolderDialogs(
        dialog = folderDialog, accountId = state.focusedAccountId, folders = state.folders,
        onDismiss = { folderDialog = null }, onChange = { folderDialog = it },
        onDeleted = { id -> if (current == Mailbox.InFolder(id)) onOpen(Mailbox.Unified(FolderRole.INBOX)) },
    )
    ModalDrawerSheet(
        modifier = Modifier.widthIn(max = 320.dp).fillMaxHeight(),
        drawerContainerColor = c.background,
        drawerShape = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
        windowInsets = WindowInsets(0),
    ) {
        Column(Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.l), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(c.accent))
                Spacer(Modifier.width(10.dp))
                Text("Dot Mail", style = Dot.type.title, color = c.textPrimary)
            }
            LazyColumn(Modifier.weight(1f)) {
                if (state.accounts.size > 1) {
                    item {
                        DrawerItem(
                            label = stringResource(R.string.drawer_all_inboxes),
                            icon = Icons.Outlined.AllInbox,
                            count = null,
                            selected = current == Mailbox.Unified(FolderRole.INBOX),
                            onClick = { onOpen(Mailbox.Unified(FolderRole.INBOX)) },
                        )
                    }
                }
                if (outboxCount > 0) {
                    item {
                        DrawerItem(
                            label = stringResource(if (outboxNeedsAttention) R.string.drawer_outbox_needs_attention else R.string.drawer_outbox),
                            icon = Icons.Outlined.Outbox,
                            count = outboxCount,
                            selected = false,
                            navigation = false,
                            onClick = onOutbox,
                        )
                    }
                }
                item {
                    val snoozed by graph.mail.snoozedCount().collectAsStateWithLifecycle(0)
                    val isCurrent = current == Mailbox.Unified(FolderRole.SNOOZED)
                    if (snoozed > 0 || isCurrent) {
                        DrawerItem(stringResource(R.string.drawer_snoozed), Format.roleIcon(FolderRole.SNOOZED), snoozed.takeIf { it > 0 }, selected = isCurrent, onClick = { onOpen(Mailbox.Unified(FolderRole.SNOOZED)) })
                    }
                }
                item {
                    // Optional category views (Settings → Categories). Mail stays in the Inbox either way.
                    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
                    val categories = remember { app.dot.mail.data.CategorySettings.get(appContext) }
                    val on by categories.enabled.collectAsStateWithLifecycle()
                    Column {
                        app.dot.mail.engine.text.Category.entries.mapNotNull { it.viewRole() }.filter { role -> role.category() in on || current == Mailbox.Unified(role) }.forEach { role ->
                            val box = Mailbox.Unified(role)
                            DrawerItem(Format.roleName(role), Format.roleIcon(role), null, selected = current == box, onClick = { onOpen(box) })
                        }
                    }
                }
                item {
                    val appContext2 = androidx.compose.ui.platform.LocalContext.current.applicationContext
                    val smartStore = remember { app.dot.mail.data.SmartFolderStore.get(appContext2) }
                    val smart by smartStore.folders.collectAsStateWithLifecycle()
                    if (smart.isNotEmpty()) Column {
                        SectionLabel(stringResource(R.string.drawer_smart_folders), Modifier.padding(top = DotSpacing.m))
                        smart.forEach { sf ->
                            val box = Mailbox.Smart(sf.id)
                            DrawerItem(sf.name, androidx.compose.material.icons.Icons.Outlined.FilterList, null, selected = current == box, onClick = { onOpen(box) })
                        }
                    }
                }
                val focused = state.accounts.firstOrNull { it.id == state.focusedAccountId }
                if (focused != null) {
                    item { SectionLabel(focused.email, Modifier.padding(top = DotSpacing.m)) }
                    val system = state.folders.filter { it.role != FolderRole.CUSTOM }
                        .sortedBy { Format.roleOrder.indexOf(it.role).let { i -> if (i < 0) 99 else i } }
                    items(system, key = { "f" + it.id }) { f ->
                        DrawerItem(
                            label = Format.roleName(f.role),
                            icon = Format.roleIcon(f.role),
                            count = f.unreadCount.takeIf { it > 0 && f.role in setOf(FolderRole.INBOX, FolderRole.SPAM, FolderRole.CUSTOM) },
                            selected = current == Mailbox.InFolder(f.id) ||
                                (state.accounts.size == 1 && f.role == FolderRole.INBOX && current == Mailbox.Unified(FolderRole.INBOX)),
                            onLongClick = { folderDialog = FolderDialog.Options(f) },
                            onClick = { onOpen(Mailbox.InFolder(f.id)) },
                        )
                    }
                    val custom = state.folders.filter { it.role == FolderRole.CUSTOM }.sortedBy { it.path.lowercase() }
                    item { SectionLabel(stringResource(R.string.drawer_folders_section), Modifier.padding(top = DotSpacing.m)) }
                    run {
                        items(custom, key = { "c" + it.id }) { f ->
                            val depth = f.path.count { it == '/' || it == '.' }.coerceAtMost(3)
                            DrawerItem(
                                label = f.name,
                                icon = Format.roleIcon(f.role),
                                count = f.unreadCount.takeIf { it > 0 },
                                selected = current == Mailbox.InFolder(f.id),
                                indent = depth * 14,
                                onLongClick = { folderDialog = FolderDialog.Options(f) },
                                onClick = { onOpen(Mailbox.InFolder(f.id)) },
                            )
                        }
                    }
                    item { DrawerItem(stringResource(R.string.drawer_new_folder), Icons.Outlined.CreateNewFolder, null, selected = false, navigation = false, onClick = { folderDialog = FolderDialog.New }) }
                }
                item { DrawerItem(stringResource(R.string.drawer_attachments), Icons.Outlined.AttachFile, null, selected = false, navigation = false, onClick = onAttachments) }
                item {
                    Spacer(Modifier.height(DotSpacing.l))
                    Hairline(Modifier.padding(horizontal = DotSpacing.gutter))
                    SectionLabel(stringResource(R.string.drawer_accounts_section), Modifier.padding(top = DotSpacing.m))
                }
                items(state.accounts, key = { "a" + it.id }) { a ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(DotShapes.medium)
                            .background(if (a.id == state.focusedAccountId) c.surface else Color.Transparent)
                            .clickable(role = Role.Tab) { onFocusAccount(a.id) }
                            .semantics { this.selected = a.id == state.focusedAccountId }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(a.displayName, a.email, size = 30.dp)
                        Spacer(Modifier.width(DotSpacing.m))
                        Column(Modifier.weight(1f)) {
                            Text(a.displayName, style = Dot.type.label, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(a.email, style = Dot.type.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (a.syncState is SyncState.Error) {
                            Icon(Icons.Outlined.ErrorOutline, stringResource(R.string.drawer_sync_problem, (a.syncState as SyncState.Error).message), Modifier.size(18.dp), tint = c.danger)
                        }
                    }
                }
                item {
                    DrawerItem(stringResource(R.string.drawer_add_account), Icons.Outlined.Add, null, selected = false, navigation = false, onClick = onAddAccount)
                }
            }
            Hairline()
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(vertical = DotSpacing.s)) {
                DrawerItem(stringResource(R.string.drawer_settings), Icons.Outlined.Settings, null, selected = false, navigation = false, onClick = onSettings)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerItem(
    label: String,
    icon: ImageVector,
    count: Int?,
    selected: Boolean,
    indent: Int = 0,
    /** True for mailbox destinations (tab, selectable); false for plain actions like Settings. */
    navigation: Boolean = true,
    /** Long-press menu (accessible as the "Folder options" custom action). */
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = Dot.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 1.dp)
            .clip(DotShapes.medium)
            .background(if (selected) c.surfaceRaised else Color.Transparent)
            .combinedClickable(role = if (navigation) Role.Tab else Role.Button, onLongClickLabel = stringResource(R.string.drawer_folder_options), onLongClick = onLongClick, onClick = onClick)
            .then(if (navigation) Modifier.semantics { this.selected = selected } else Modifier)
            .heightIn(min = 48.dp)
            .padding(start = (10 + indent).dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (selected) c.textPrimary else c.textSecondary)
        Spacer(Modifier.width(14.dp))
        Text(
            label, style = Dot.type.callout,
            color = if (selected) c.textPrimary else c.textSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (count != null) Text(if (count > 999) "999+" else count.toString(), style = Dot.type.caption, color = c.textSecondary)
    }
}
