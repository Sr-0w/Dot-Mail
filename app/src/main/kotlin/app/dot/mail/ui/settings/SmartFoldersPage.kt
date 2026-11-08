package app.dot.mail.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.BackButton
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotIconButton
import app.dot.design.components.DotTextField
import app.dot.design.components.DotTopBar
import app.dot.design.components.Hairline
import app.dot.design.components.ListRow
import app.dot.design.components.SectionLabel
import app.dot.design.components.dotSurfaceEnter
import app.dot.mail.LocalMailGraph
import app.dot.mail.R
import app.dot.mail.data.SmartFolderStore
import app.dot.mail.data.model.FolderRole
import app.dot.mail.data.model.SmartFolder
import app.dot.mail.engine.text.Category
import app.dot.mail.ui.common.Format
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/** Roles a smart folder can search (Trash/Spam only when asked for explicitly). */
private val searchableRoles = listOf(FolderRole.INBOX, FolderRole.ARCHIVE, FolderRole.SENT, FolderRole.ALL_MAIL, FolderRole.SPAM, FolderRole.TRASH)

@Composable
internal fun SmartFoldersPage(onBack: () -> Unit) {
    val c = Dot.colors
    val appContext = LocalContext.current.applicationContext
    val store = remember { SmartFolderStore.get(appContext) }
    val folders by store.folders.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SmartFolder?>(null) }
    editing?.let { draft ->
        SmartFolderEditor(draft, isNew = store.get(draft.id) == null, onBack = { editing = null }, store = store)
        return
    }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().background(c.background)) {
        DotTopBar(title = stringResource(R.string.smart_title), elevated = scroll.value > 0, navigation = { BackButton(onBack) })
        Column(Modifier.verticalScroll(scroll).navigationBarsPadding()) {
            Text(
                stringResource(R.string.smart_intro), style = Dot.type.callout, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
            )
            if (folders.isEmpty()) {
                Text(
                    stringResource(R.string.smart_empty), style = Dot.type.caption, color = c.textTertiary,
                    modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
                )
            }
            folders.forEachIndexed { index, folder ->
                ListRow(
                    title = folder.name, icon = Icons.Outlined.FilterList, onClick = { editing = folder },
                    trailing = {
                        Row {
                            DotIconButton(Icons.Outlined.ArrowUpward, stringResource(R.string.smart_move_up), enabled = index > 0, onClick = { store.move(folder.id, -1) })
                            DotIconButton(Icons.Outlined.ArrowDownward, stringResource(R.string.smart_move_down), enabled = index < folders.lastIndex, onClick = { store.move(folder.id, 1) })
                        }
                    },
                )
            }
            ListRow(stringResource(R.string.smart_add), icon = Icons.Outlined.Add, onClick = { editing = SmartFolder(UUID.randomUUID().toString(), "") })
            Spacer(Modifier.height(DotSpacing.xl))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SmartFolderEditor(initial: SmartFolder, isNew: Boolean, onBack: () -> Unit, store: SmartFolderStore) {
    val c = Dot.colors
    val accounts by LocalMailGraph.current.accounts.accounts.collectAsStateWithLifecycle(emptyList())
    var name by remember { mutableStateOf(initial.name) }
    var accountIds by remember { mutableStateOf(initial.accountIds) }
    var roles by remember { mutableStateOf(initial.roles) }
    var from by remember { mutableStateOf(initial.from) }
    var to by remember { mutableStateOf(initial.to) }
    var subject by remember { mutableStateOf(initial.subject) }
    var category by remember { mutableStateOf(initial.category) }
    var unread by remember { mutableStateOf(initial.unreadOnly) }
    var starred by remember { mutableStateOf(initial.starredOnly) }
    var attachment by remember { mutableStateOf(initial.hasAttachment) }
    var after by remember { mutableStateOf(initial.afterMillis) }
    var before by remember { mutableStateOf(initial.beforeMillis) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = from date, false = until date
    var confirmDelete by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    fun label(millis: Long?) = millis?.let { dateFormat.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()) } ?: ""

    Column(Modifier.fillMaxSize().background(c.background).imePadding()) {
        DotTopBar(title = stringResource(if (isNew) R.string.smart_add else R.string.smart_edit), elevated = scroll.value > 0, navigation = { BackButton(onBack) })
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            Column(Modifier.padding(horizontal = DotSpacing.gutter), verticalArrangement = Arrangement.spacedBy(DotSpacing.m)) {
                DotTextField(name, { name = it }, label = stringResource(R.string.smart_name))
                DotTextField(from, { from = it }, label = stringResource(R.string.smart_from))
                DotTextField(to, { to = it }, label = stringResource(R.string.smart_to))
                DotTextField(subject, { subject = it }, label = stringResource(R.string.smart_subject))
            }
            Hairline(Modifier.padding(vertical = DotSpacing.s))
            ChoiceRow(
                stringResource(R.string.smart_category), null, category,
                listOf<Pair<Category?, String>>(null to stringResource(R.string.smart_any_category)) + Category.entries.map { it to categoryLabel(it) },
            ) { category = it }
            ToggleRow(stringResource(R.string.smart_unread_only), null, null, unread) { unread = it }
            ToggleRow(stringResource(R.string.smart_starred_only), null, null, starred) { starred = it }
            ToggleRow(stringResource(R.string.smart_has_attachment), null, null, attachment) { attachment = it }
            ListRow(stringResource(R.string.smart_after), subtitle = label(after).ifEmpty { stringResource(R.string.smart_date_any) }, onClick = { picking = true })
            ListRow(stringResource(R.string.smart_before), subtitle = label(before).ifEmpty { stringResource(R.string.smart_date_any) }, onClick = { picking = false })
            Hairline(Modifier.padding(vertical = DotSpacing.s))
            SectionLabel(stringResource(R.string.smart_accounts))
            if (accountIds.isEmpty()) Text(stringResource(R.string.smart_all_accounts), style = Dot.type.caption, color = c.textTertiary, modifier = Modifier.padding(horizontal = DotSpacing.gutter))
            accounts.forEach { a ->
                ToggleRow(a.email, null, null, a.id in accountIds) { on -> accountIds = if (on) accountIds + a.id else accountIds - a.id }
            }
            SectionLabel(stringResource(R.string.smart_roles))
            if (roles.isEmpty()) Text(stringResource(R.string.smart_all_folders), style = Dot.type.caption, color = c.textTertiary, modifier = Modifier.padding(horizontal = DotSpacing.gutter))
            searchableRoles.forEach { role ->
                ToggleRow(Format.roleName(role), null, null, role in roles) { on -> roles = if (on) roles + role else roles - role }
            }
            if (!isNew) {
                Spacer(Modifier.height(DotSpacing.m))
                DotButton(stringResource(R.string.smart_delete), { confirmDelete = true }, style = ButtonStyle.Danger, modifier = Modifier.fillMaxWidth().padding(horizontal = DotSpacing.gutter))
            }
            Spacer(Modifier.height(DotSpacing.l))
        }
        DotButton(
            text = stringResource(R.string.smart_save), enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(DotSpacing.gutter),
            onClick = {
                store.save(SmartFolder(initial.id, name.trim(), accountIds, roles, from.trim(), to.trim(), subject.trim(), category, unread, starred, attachment, after, before))
                onBack()
            },
        )
    }

    picking?.let { isFrom ->
        val state = rememberDatePickerState(initialSelectedDateMillis = if (isFrom) after else before)
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                DotButton(stringResource(R.string.smart_date_ok), compact = true, onClick = {
                    // The picker returns UTC midnight; use local midnight so "until" excludes the picked day's end correctly.
                    val picked = state.selectedDateMillis?.let { utc ->
                        LocalDate.ofEpochDay(Math.floorDiv(utc, 86_400_000L)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }
                    if (isFrom) after = picked else before = picked?.plus(86_400_000L)
                    picking = null
                })
            },
            dismissButton = { DotButton(stringResource(R.string.smart_date_any), { if (isFrom) after = null else before = null; picking = null }, style = ButtonStyle.Ghost, compact = true) },
        ) { DatePicker(state) }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            modifier = Modifier.dotSurfaceEnter(), containerColor = c.surface, shape = DotShapes.large,
            title = { Text(stringResource(R.string.smart_delete_title), style = Dot.type.title, color = c.textPrimary) },
            text = { Text(stringResource(R.string.smart_delete_body), style = Dot.type.callout, color = c.textSecondary) },
            confirmButton = { DotButton(stringResource(R.string.smart_delete), { confirmDelete = false; store.delete(initial.id); onBack() }, style = ButtonStyle.Danger, compact = true) },
            dismissButton = { DotButton(stringResource(R.string.identity_cancel), { confirmDelete = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
}
