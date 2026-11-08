package app.dot.mail.ui.compose

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.Avatar
import app.dot.design.components.DotIconButton
import app.dot.design.components.DotTopBar
import app.dot.design.components.Hairline
import app.dot.mail.LocalMailGraph
import app.dot.mail.LocalSettings
import app.dot.mail.data.model.EmailAddress
import app.dot.mail.ui.ComposeRoute
import app.dot.mail.ui.common.Format
import app.dot.mail.ui.inbox.DotMenu
import app.dot.mail.ui.inbox.MenuItem
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

@Composable
fun ComposeScreen(route: ComposeRoute, onClose: () -> Unit) {
    val graph = LocalMailGraph.current
    val settings = LocalSettings.current
    val vm: ComposeViewModel = viewModel { ComposeViewModel(graph, route) { settings.ui.value.undoSendSeconds } }
    val state by vm.state.collectAsStateWithLifecycle()
    val closed by vm.closed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = Dot.colors
    var menu by remember { mutableStateOf(false) }
    var scheduleMenu by remember { mutableStateOf(false) }
    var senderMenu by remember { mutableStateOf(false) }
    val bodyFocus = remember { FocusRequester() }
    val toFocus = remember { FocusRequester() }

    LaunchedEffect(closed) { if (closed) onClose() }
    BackHandler { vm.close() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            // Keep read access across restarts until the data layer has copied the file.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        vm.addAttachments(uris.map { it.toString() })
    }

    val draft = state.draft
    Column(Modifier.fillMaxSize().background(c.background).imePadding()) {
        DotTopBar(
            navigation = { DotIconButton(Icons.Outlined.Close, "Close", onClick = { vm.close() }) },
            title = if (route.replyMode != null) replyTitle(route.replyMode) else "New message",
            actions = {
                DotIconButton(Icons.Outlined.AttachFile, "Attach files", onClick = { picker.launch(arrayOf("*/*")) })
                Box {
                    DotIconButton(Icons.Outlined.MoreVert, "More", onClick = { menu = true })
                    DotMenu(menu, { menu = false }) {
                        MenuItem("Schedule send", Icons.Outlined.Schedule) { menu = false; scheduleMenu = true }
                        MenuItem("Discard", Icons.Outlined.DeleteOutline, color = c.danger) { menu = false; vm.discard() }
                    }
                    DotMenu(scheduleMenu, { scheduleMenu = false }) {
                        scheduleOptions().forEach { (label, at) ->
                            MenuItem(label, null) { scheduleMenu = false; vm.send(scheduledAt = at) }
                        }
                    }
                }
                SendButton(enabled = state.loaded && !state.sending && state.hasRecipients, onClick = { vm.send() })
            },
        )
        if (draft == null) return@Column
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // From
            if (state.senders.size > 1) {
                Box {
                    FieldRow("From", onClick = { senderMenu = true }) {
                        Text(state.sender?.email.orEmpty(), style = Dot.type.callout, color = c.textPrimary, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.ExpandMore, "Choose sender", Modifier.size(18.dp), tint = c.textTertiary)
                    }
                    DotMenu(senderMenu, { senderMenu = false }) {
                        state.senders.forEach { s ->
                            MenuItem("${s.name} <${s.email}>", null) { senderMenu = false; vm.setSender(s) }
                        }
                    }
                }
                Hairline()
            }
            RecipientRow(
                label = "To", field = RecipientField.TO, list = draft.to, vm = vm, state = state,
                focusRequester = toFocus,
                trailing = if (!state.showCcBcc) ({
                    Text("Cc Bcc", style = Dot.type.label, color = c.textTertiary,
                        modifier = Modifier.clip(DotShapes.small).clickable { vm.showCcBcc() }.padding(8.dp))
                }) else null,
            )
            Hairline()
            if (state.showCcBcc) {
                RecipientRow("Cc", RecipientField.CC, draft.cc, vm, state)
                Hairline()
                RecipientRow("Bcc", RecipientField.BCC, draft.bcc, vm, state)
                Hairline()
            }
            Row(Modifier.padding(horizontal = DotSpacing.gutter, vertical = 14.dp)) {
                Plain(
                    value = draft.subject,
                    onValue = { v -> vm.edit { it.copy(subject = v) } },
                    placeholder = "Subject",
                    style = Dot.type.headline,
                    singleLine = true,
                    imeAction = ImeAction.Next,
                    onIme = { bodyFocus.requestFocus() },
                    modifier = Modifier.weight(1f),
                )
            }
            Hairline()
            state.error?.let {
                Text(it, style = Dot.type.caption, color = c.danger, modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s))
            }
            if (draft.attachmentUris.isNotEmpty()) {
                AttachmentChips(draft.attachmentUris, onRemove = vm::removeAttachment)
            }
            Plain(
                value = draft.bodyText,
                onValue = { v -> vm.edit { it.copy(bodyText = v) } },
                placeholder = "Write your message",
                style = Dot.type.body,
                singleLine = false,
                capitalization = KeyboardCapitalization.Sentences,
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp)
                    .padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.l)
                    .focusRequester(bodyFocus),
            )
        }
        if (state.savedOnce) {
            Text(
                "Draft saved", style = Dot.type.caption, color = c.textTertiary,
                modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
            )
        }
    }
    LaunchedEffect(state.loaded) {
        if (state.loaded && draft != null) {
            if (draft.to.isEmpty()) toFocus.requestFocus() else bodyFocus.requestFocus()
        }
    }
}

private fun replyTitle(mode: String) = when (mode) {
    "REPLY_ALL" -> "Reply all"
    "FORWARD" -> "Forward"
    else -> "Reply"
}

private fun scheduleOptions(): List<Pair<String, Long>> {
    val zone = ZoneId.systemDefault()
    val now = java.time.ZonedDateTime.now(zone)
    val out = mutableListOf<Pair<String, Long>>()
    if (now.hour < 17) out += "This evening, 18:00" to now.toLocalDate().atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
    out += "Tomorrow morning, 08:00" to LocalDate.now(zone).plusDays(1).atTime(LocalTime.of(8, 0)).atZone(zone).toInstant().toEpochMilli()
    val monday = LocalDate.now(zone).with(TemporalAdjusters.next(DayOfWeek.MONDAY))
    out += "Monday morning, 08:00" to monday.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
    return out
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val c = Dot.colors
    Box(
        Modifier.padding(start = 4.dp, end = 8.dp).size(40.dp).clip(DotShapes.medium)
            .background(if (enabled) c.inverse else c.surfaceRaised)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Outlined.Send, "Send", Modifier.size(19.dp), tint = if (enabled) c.onInverse else c.textTertiary)
    }
}

@Composable
private fun FieldRow(label: String, onClick: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = DotSpacing.gutter, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Dot.type.callout, color = Dot.colors.textTertiary, modifier = Modifier.width(44.dp))
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipientRow(
    label: String,
    field: RecipientField,
    list: List<EmailAddress>,
    vm: ComposeViewModel,
    state: ComposeState,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Dot.colors
    var input by rememberSaveable(field) { mutableStateOf("") }
    val pendingInVm = state.pending[field].orEmpty()
    LaunchedEffect(pendingInVm) { if (pendingInVm.isEmpty() && input.isNotEmpty() && state.pending.containsKey(field).not()) input = "" }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = DotSpacing.gutter, end = DotSpacing.s, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = Dot.type.callout, color = c.textTertiary, modifier = Modifier.width(44.dp))
            FlowRow(
                Modifier.weight(1f).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                list.forEach { a -> RecipientChip(a, onRemove = { vm.removeRecipient(field, a) }) }
                Plain(
                    value = input,
                    onValue = { v ->
                        if (v.endsWith(",") || v.endsWith(";") || (v.endsWith(" ") && v.trim().contains('@'))) {
                            if (vm.addRecipient(field, v.trimEnd(',', ';', ' '))) input = "" else input = v.trimEnd(',', ';', ' ')
                        } else {
                            input = v
                            vm.query(field, v.trim())
                        }
                        vm.setPending(field, input)
                    },
                    placeholder = if (list.isEmpty()) "" else null,
                    style = Dot.type.callout,
                    singleLine = true,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                    onIme = { if (vm.addRecipient(field, input)) { input = ""; vm.setPending(field, "") } },
                    modifier = Modifier.widthIn(min = 120.dp).heightIn(min = 30.dp)
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .onFocusChanged { if (!it.isFocused && input.isNotBlank()) { if (vm.addRecipient(field, input)) { input = ""; vm.setPending(field, "") } } }
                        .onPreviewKeyEvent { e ->
                            if (e.key == Key.Backspace && input.isEmpty() && list.isNotEmpty()) { vm.removeRecipient(field, list.last()); true } else false
                        },
                )
            }
            trailing?.invoke()
        }
        if (state.suggestFor == field && state.suggestions.isNotEmpty()) {
            Column(Modifier.padding(start = 64.dp, end = DotSpacing.gutter, bottom = DotSpacing.s).clip(DotShapes.medium).background(c.surface)) {
                state.suggestions.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.addSuggestion(field, s); input = ""; vm.setPending(field, "") }.padding(horizontal = DotSpacing.m, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(s.displayName, s.email, size = 28.dp)
                        Spacer(Modifier.width(DotSpacing.m))
                        Column {
                            Text(s.displayName, style = Dot.type.label, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (s.name != null) Text(s.email, style = Dot.type.caption, color = c.textTertiary, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipientChip(a: EmailAddress, onRemove: () -> Unit) {
    val c = Dot.colors
    val valid = ComposeViewModel.isValidEmail(a.email)
    Row(
        Modifier.clip(DotShapes.small).background(c.surfaceRaised)
            .border(1.dp, if (valid) c.surfaceRaised else c.danger, DotShapes.small)
            .clickable(onClickLabel = "Remove ${a.displayName}", onClick = onRemove)
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(a.displayName, style = Dot.type.label, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
        Icon(Icons.Outlined.Close, null, Modifier.padding(start = 2.dp).size(14.dp), tint = c.textTertiary)
    }
}

@Composable
private fun AttachmentChips(uris: List<String>, onRemove: (String) -> Unit) {
    val context = LocalContext.current
    val c = Dot.colors
    Column(Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s)) {
        uris.forEach { u ->
            val meta by produceState<Pair<String, Long?>>(Uri.parse(u).lastPathSegment.orEmpty() to null, u) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { describe(context, Uri.parse(u)) }
            }
            val (name, size) = meta
            Row(
                Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(DotShapes.medium).border(1.dp, c.border, DotShapes.medium)
                    .padding(start = DotSpacing.m, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Description, null, Modifier.size(18.dp), tint = c.textSecondary)
                Spacer(Modifier.width(DotSpacing.s))
                Column(Modifier.weight(1f)) {
                    Text(name, style = Dot.type.label, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (size != null) Text(Format.size(size), style = Dot.type.caption, color = c.textTertiary)
                }
                DotIconButton(Icons.Outlined.Close, "Remove $name", onClick = { onRemove(u) }, tint = c.textTertiary)
            }
        }
    }
}

private fun describe(context: android.content.Context, uri: Uri): Pair<String, Long?> = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
        if (cur.moveToFirst()) {
            val name = cur.getString(0) ?: uri.lastPathSegment.orEmpty()
            val size = if (cur.isNull(1)) null else cur.getLong(1)
            name to size
        } else null
    }
}.getOrNull() ?: ((uri.lastPathSegment ?: "Attachment") to null)

/** Borderless text input used inside compose rows. */
@Composable
private fun Plain(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String?,
    style: androidx.compose.ui.text.TextStyle,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    imeAction: ImeAction = ImeAction.Default,
    onIme: () -> Unit = {},
) {
    val c = Dot.colors
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty() && placeholder != null) Text(placeholder, style = style, color = c.textTertiary)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = singleLine,
            textStyle = style.copy(color = c.textPrimary),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                capitalization = if (keyboardType == KeyboardType.Email) KeyboardCapitalization.None else capitalization,
                autoCorrectEnabled = keyboardType != KeyboardType.Email,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(onAny = { onIme() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
