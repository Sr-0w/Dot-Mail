package app.dot.mail.ui.compose

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatClear
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotIconButton
import app.dot.design.components.DotMenu
import app.dot.design.components.DotMenuItem
import app.dot.design.components.DotTextField
import app.dot.design.components.DotTopBar
import app.dot.design.components.Hairline
import app.dot.mail.LocalMailGraph
import app.dot.mail.LocalSettings
import app.dot.mail.data.model.EmailAddress
import app.dot.mail.ui.ComposeRoute
import app.dot.mail.ui.avatar.SenderAvatar
import app.dot.mail.ui.common.Format
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val QuoteIndent = 16.sp
private val QuoteParagraphStyle = ParagraphStyle(textIndent = TextIndent(firstLine = QuoteIndent, restLine = QuoteIndent))

@Composable
fun ComposeScreen(route: ComposeRoute, onClose: () -> Unit) {
    val graph = LocalMailGraph.current
    val settings = LocalSettings.current
    // TODO(Sonnet): read settings.ui.value.defaultFormat when UiPrefs.defaultFormat lands in AppSettings.kt
    val vm: ComposeViewModel = viewModel {
        ComposeViewModel(
            graph = graph,
            route = route,
            undoSeconds = { settings.ui.value.undoSendSeconds },
            defaultRichText = true,
            defaultAccountId = { settings.ui.value.defaultAccountId },
            includeOriginal = { settings.ui.value.includeOriginal },
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val closed by vm.closed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = Dot.colors
    var menu by remember { mutableStateOf(false) }
    var scheduleMenu by remember { mutableStateOf(false) }
    var senderMenu by remember { mutableStateOf(false) }
    var confirmPlainDialog by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var bodyFocused by remember { mutableStateOf(false) }
    val bodyFocus = remember { FocusRequester() }
    val toFocus = remember { FocusRequester() }

    val richTextState = rememberRichTextState()
    LaunchedEffect(c.accent, c.textPrimary, c.surfaceRaised, c.border) {
        richTextState.config.linkColor = c.accent
        richTextState.config.linkTextDecoration = TextDecoration.Underline
        richTextState.config.codeSpanColor = c.textPrimary
        richTextState.config.codeSpanBackgroundColor = c.surfaceRaised
        richTextState.config.codeSpanStrokeColor = c.border
    }

    var lastEmittedHtml by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.loaded, state.isRichText, state.bodyHtml) {
        if (state.loaded && state.isRichText && state.bodyHtml != lastEmittedHtml) {
            lastEmittedHtml = state.bodyHtml
            if (state.bodyHtml.isBlank()) {
                richTextState.clear()
            } else {
                richTextState.setHtml(emailHtmlToEditorHtml(state.bodyHtml))
            }
        }
    }

    LaunchedEffect(state.loaded, state.isRichText) {
        if (!state.loaded || !state.isRichText) return@LaunchedEffect
        snapshotFlow {
            val text = richTextState.annotatedString.text
            val isList = richTextState.isList
            val pStyle = richTextState.currentParagraphStyle
            if (text.isEmpty() && !isList && pStyle.textIndent == null) {
                ""
            } else {
                editorHtmlToEmailHtml(richTextState.toHtml())
            }
        }.collect { html ->
            if (html != lastEmittedHtml) {
                lastEmittedHtml = html
                vm.onRichBodyChanged(html)
            }
        }
    }

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
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding(),
    ) {
        DotTopBar(
            navigation = { DotIconButton(Icons.Outlined.Close, "Close", onClick = { vm.close() }) },
            title = if (route.replyMode != null) replyTitle(route.replyMode) else "New message",
            actions = {
                DotIconButton(Icons.Outlined.AttachFile, "Attach files", onClick = { picker.launch(arrayOf("*/*")) })
                Box {
                    DotIconButton(Icons.Outlined.MoreVert, "More", onClick = { menu = true })
                    DotMenu(menu, { menu = false }) {
                        if (state.isRichText) {
                            DotMenuItem("Plain text", Icons.AutoMirrored.Outlined.Subject) {
                                menu = false
                                confirmPlainDialog = true
                            }
                        } else {
                            DotMenuItem("Formatted text", Icons.Outlined.TextFormat) {
                                menu = false
                                vm.switchToRichText()
                            }
                        }
                        DotMenuItem("Schedule send", Icons.Outlined.Schedule) { menu = false; scheduleMenu = true }
                        DotMenuItem("Discard", Icons.Outlined.DeleteOutline, color = c.danger) { menu = false; vm.discard() }
                    }
                    DotMenu(scheduleMenu, { scheduleMenu = false }) {
                        scheduleOptions().forEach { (label, at) ->
                            DotMenuItem(label, null) { scheduleMenu = false; vm.send(scheduledAt = at) }
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
                            DotMenuItem("${s.name} <${s.email}>", null) { senderMenu = false; vm.setSender(s) }
                        }
                    }
                }
                Hairline()
            }
            RecipientRow(
                label = "To", field = RecipientField.TO, list = draft.to, vm = vm, state = state,
                focusRequester = toFocus,
                trailing = if (!state.showCcBcc) ({
                    Box(
                        Modifier
                            .heightIn(min = 32.dp)
                            .clip(DotShapes.small)
                            .clickable(role = Role.Button) { vm.showCcBcc() }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Cc Bcc", style = Dot.type.label, color = c.textTertiary)
                    }
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
            if (state.isRichText) {
                RichBodyEditor(
                    richTextState = richTextState,
                    focusRequester = bodyFocus,
                    onFocusChanged = { bodyFocused = it },
                )
            } else {
                Plain(
                    value = state.editableText,
                    onValue = vm::onPlainBodyChanged,
                    placeholder = "Write your message",
                    style = Dot.type.body,
                    singleLine = false,
                    capitalization = KeyboardCapitalization.Sentences,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp)
                        .padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.l)
                        .focusRequester(bodyFocus)
                        .onFocusChanged { bodyFocused = it.isFocused },
                )
            }
            state.quotedText?.takeIf { it.isNotBlank() }?.let { quoted ->
                QuotedReplySection(
                    quoted = quoted,
                    expanded = state.quoteExpanded,
                    onToggle = vm::toggleQuoteExpanded,
                )
            }
        }
        if (state.savedOnce) {
            Text(
                "Draft saved", style = Dot.type.caption, color = c.textTertiary,
                modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
            )
        }
        if (state.isRichText && bodyFocused) {
            Hairline()
            FormattingToolbar(
                richTextState = richTextState,
                onLinkClick = { showLinkDialog = true },
            )
        }
    }

    if (confirmPlainDialog) {
        ConfirmPlainTextDialog(
            onDismiss = { confirmPlainDialog = false },
            onConfirm = {
                confirmPlainDialog = false
                vm.switchToPlainText()
            },
        )
    }

    if (showLinkDialog) {
        val initialText = when {
            richTextState.isLink -> richTextState.selectedLinkText.orEmpty()
            !richTextState.selection.collapsed -> {
                val text = richTextState.annotatedString.text
                val min = richTextState.selection.min.coerceIn(0, text.length)
                val max = richTextState.selection.max.coerceIn(min, text.length)
                text.substring(min, max)
            }
            else -> ""
        }
        val initialUrl = if (richTextState.isLink) richTextState.selectedLinkUrl.orEmpty() else ""
        InsertLinkDialog(
            initialText = initialText,
            initialUrl = initialUrl,
            isEditing = richTextState.isLink,
            onDismiss = { showLinkDialog = false },
            onRemove = {
                showLinkDialog = false
                richTextState.removeLink()
            },
            onConfirm = { linkText, rawUrl ->
                showLinkDialog = false
                val normalizedUrl = normalizeLinkUrl(rawUrl)
                if (normalizedUrl.isNotEmpty()) {
                    val display = linkText.ifBlank { normalizedUrl }
                    when {
                        richTextState.isLink && display == richTextState.selectedLinkText -> {
                            richTextState.updateLink(normalizedUrl)
                        }
                        !richTextState.selection.collapsed && display == initialText -> {
                            richTextState.addLinkToSelection(normalizedUrl)
                        }
                        else -> {
                            if (richTextState.isLink) richTextState.removeLink()
                            if (!richTextState.selection.collapsed) richTextState.removeSelectedText()
                            richTextState.addLink(text = display, url = normalizedUrl)
                        }
                    }
                }
            },
        )
    }

    LaunchedEffect(state.loaded) {
        if (state.loaded && draft != null) {
            if (draft.to.isEmpty()) toFocus.requestFocus() else bodyFocus.requestFocus()
        }
    }
}

@Composable
private fun RichBodyEditor(
    richTextState: RichTextState,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
) {
    val c = Dot.colors
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val quoteBarColor = c.borderStrong
    BasicRichTextEditor(
        state = richTextState,
        textStyle = Dot.type.body.copy(color = c.textPrimary),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            autoCorrectEnabled = true,
        ),
        onTextLayout = { layoutResult = it },
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.TopStart) {
                if (richTextState.annotatedString.text.isEmpty() && !richTextState.isList) {
                    Text("Write your message", style = Dot.type.body, color = c.textTertiary)
                }
                innerTextField()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp)
            .padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.l)
            .drawBehind {
                val layout = layoutResult ?: return@drawBehind
                val textLen = layout.layoutInput.text.length
                richTextState.annotatedString.paragraphStyles.forEach { range ->
                    if (range.item.textIndent?.firstLine == QuoteIndent && range.start <= textLen) {
                        val startLine = layout.getLineForOffset(range.start.coerceIn(0, textLen))
                        val endOffset = (range.end - 1).coerceIn(range.start.coerceAtMost(textLen), textLen)
                        val endLine = layout.getLineForOffset(endOffset)
                        val top = layout.getLineTop(startLine)
                        val bottom = layout.getLineBottom(endLine)
                        drawLine(
                            color = quoteBarColor,
                            start = Offset(4.dp.toPx(), top + 2.dp.toPx()),
                            end = Offset(4.dp.toPx(), bottom - 2.dp.toPx()),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                }
            }
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) },
    )
}

@Composable
private fun FormattingToolbar(
    richTextState: RichTextState,
    onLinkClick: () -> Unit,
) {
    val c = Dot.colors
    val span = richTextState.currentSpanStyle
    val paragraph = richTextState.currentParagraphStyle
    val isBold = span.fontWeight == FontWeight.Bold
    val isItalic = span.fontStyle == FontStyle.Italic
    val isUnderline = span.textDecoration?.contains(TextDecoration.Underline) == true
    val isStrike = span.textDecoration?.contains(TextDecoration.LineThrough) == true
    val isLink = richTextState.isLink
    val isBulleted = richTextState.isUnorderedList
    val isNumbered = richTextState.isOrderedList
    val isQuote = paragraph.textIndent?.firstLine == QuoteIndent

    Row(
        Modifier
            .fillMaxWidth()
            .background(c.background)
            .focusProperties { canFocus = false }
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = DotSpacing.s, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FormatButton(Icons.Outlined.FormatBold, "Bold", active = isBold) {
            richTextState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        }
        FormatButton(Icons.Outlined.FormatItalic, "Italic", active = isItalic) {
            richTextState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
        }
        FormatButton(Icons.Outlined.FormatUnderlined, "Underline", active = isUnderline) {
            richTextState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
        }
        FormatButton(Icons.Outlined.FormatStrikethrough, "Strikethrough", active = isStrike) {
            richTextState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
        }
        FormatButton(Icons.Outlined.Link, "Insert link", active = isLink, onClick = onLinkClick)
        FormatButton(Icons.AutoMirrored.Outlined.FormatListBulleted, "Bulleted list", active = isBulleted) {
            richTextState.toggleUnorderedList()
        }
        FormatButton(Icons.Outlined.FormatListNumbered, "Numbered list", active = isNumbered) {
            richTextState.toggleOrderedList()
        }
        FormatButton(Icons.Outlined.FormatQuote, "Quote", active = isQuote) {
            richTextState.toggleParagraphStyle(QuoteParagraphStyle)
        }
        FormatButton(Icons.Outlined.FormatClear, "Clear formatting", active = false) {
            if (!richTextState.selection.collapsed) {
                val range = richTextState.selection
                richTextState.removeSpanStyle(SpanStyle(fontWeight = FontWeight.Bold), range)
                richTextState.removeSpanStyle(SpanStyle(fontStyle = FontStyle.Italic), range)
                richTextState.removeSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline), range)
                richTextState.removeSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), range)
                richTextState.clearSpanStyles(range)
                richTextState.clearRichSpans(range)
            } else {
                richTextState.clearSpanStyles()
                richTextState.clearRichSpans()
            }
            if (richTextState.isLink) richTextState.removeLink()
            if (richTextState.isUnorderedList) richTextState.removeUnorderedList()
            if (richTextState.isOrderedList) richTextState.removeOrderedList()
            if (richTextState.currentParagraphStyle.textIndent != null) {
                richTextState.removeParagraphStyle(QuoteParagraphStyle)
            }
        }
    }
}

@Composable
private fun FormatButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val c = Dot.colors
    DotIconButton(
        icon = icon,
        contentDescription = label,
        onClick = onClick,
        tint = if (active) c.textPrimary else c.textSecondary,
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(DotShapes.small)
            .background(if (active) c.surfaceRaised else Color.Transparent)
            .semantics { selected = active },
    )
}

@Composable
private fun QuotedReplySection(
    quoted: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val c = Dot.colors
    Column(Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s)) {
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .clip(DotShapes.small)
                .background(c.surfaceRaised)
                .border(DotSpacing.hairline * 2, c.border, DotShapes.small)
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "Hide quoted text" else "Show quoted text",
                    onClick = onToggle,
                )
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics { contentDescription = if (expanded) "Hide quoted text" else "Show quoted text" },
            contentAlignment = Alignment.Center,
        ) {
            Text("•••", style = Dot.type.label, color = c.textSecondary)
        }
        AnimatedVisibility(expanded) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = DotSpacing.s)
                    .height(IntrinsicSize.Min),
            ) {
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(c.borderStrong),
                )
                Spacer(Modifier.width(DotSpacing.m))
                Text(
                    quoted.trimEnd(),
                    style = Dot.type.callout,
                    color = c.textSecondary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ConfirmPlainTextDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = Dot.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = DotShapes.large,
        title = { Text("Plain text", style = Dot.type.title, color = c.textPrimary) },
        text = { Text("Formatting will be removed", style = Dot.type.callout, color = c.textSecondary) },
        confirmButton = { DotButton("Convert", onClick = onConfirm, compact = true) },
        dismissButton = { DotButton("Cancel", onClick = onDismiss, style = ButtonStyle.Ghost, compact = true) },
    )
}

@Composable
private fun InsertLinkDialog(
    initialText: String,
    initialUrl: String,
    isEditing: Boolean,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onConfirm: (text: String, url: String) -> Unit,
) {
    val c = Dot.colors
    var text by rememberSaveable { mutableStateOf(initialText) }
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    val urlFocus = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = DotShapes.large,
        title = { Text(if (isEditing) "Edit link" else "Insert link", style = Dot.type.title, color = c.textPrimary) },
        text = {
            Column {
                DotTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "Text",
                    placeholder = "Text to display",
                    singleLine = true,
                    imeAction = ImeAction.Next,
                    onImeAction = { urlFocus.requestFocus() },
                )
                Spacer(Modifier.height(DotSpacing.m))
                DotTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = "URL",
                    placeholder = "https://example.com",
                    singleLine = true,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    onImeAction = { if (url.isNotBlank()) onConfirm(text.trim(), url.trim()) },
                    focusRequester = urlFocus,
                )
            }
        },
        confirmButton = {
            DotButton(
                text = if (isEditing) "Save" else "Insert",
                onClick = { onConfirm(text.trim(), url.trim()) },
                enabled = url.isNotBlank(),
                compact = true,
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(DotSpacing.xs)) {
                if (isEditing) {
                    DotButton("Remove", onClick = onRemove, style = ButtonStyle.Danger, compact = true)
                }
                DotButton("Cancel", onClick = onDismiss, style = ButtonStyle.Ghost, compact = true)
            }
        },
    )
}

private fun normalizeLinkUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val lower = trimmed.lowercase()
    return when {
        lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:") || lower.startsWith("tel:") -> trimmed
        ComposeViewModel.isValidEmail(trimmed) -> "mailto:$trimmed"
        else -> "https://$trimmed"
    }
}

private val INDENTED_P = Regex(
    """<p\s+style="([^"]*?\btext-indent:\s*[^;"]+;?\s*[^"]*)"\s*>(.*?)</p>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val TEXT_INDENT_DECL = Regex("""\btext-indent:\s*[^;"]+;?\s*""", RegexOption.IGNORE_CASE)
private val BLOCKQUOTE_TAG = Regex(
    """<blockquote[^>]*>(.*?)</blockquote>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val P_OPEN_TAG = Regex("""<p(\s+style="([^"]*)")?\s*>""", RegexOption.IGNORE_CASE)

/**
 * Converts paragraphs styled with [QuoteParagraphStyle] (`text-indent`) in the editor's HTML
 * into standard `<blockquote><p>...</p></blockquote>` elements for [app.dot.mail.engine.text.EmailHtml].
 */
internal fun editorHtmlToEmailHtml(editorHtml: String): String {
    if (!editorHtml.contains("text-indent", ignoreCase = true)) return editorHtml
    val converted = INDENTED_P.replace(editorHtml) { match ->
        val rawStyle = match.groupValues[1]
        val content = match.groupValues[2]
        val remainingStyle = TEXT_INDENT_DECL.replace(rawStyle, "").trim()
        val pOpen = if (remainingStyle.isEmpty()) "<p>" else "<p style=\"$remainingStyle\">"
        "<blockquote>$pOpen$content</p></blockquote>"
    }
    return converted.replace("</blockquote><blockquote>", "")
}

/**
 * Converts `<blockquote>` blocks from [app.dot.mail.engine.text.EmailHtml] into `text-indent`
 * paragraphs understood by [RichTextState].
 */
internal fun emailHtmlToEditorHtml(emailHtml: String): String {
    if (!emailHtml.contains("<blockquote", ignoreCase = true)) return emailHtml
    return BLOCKQUOTE_TAG.replace(emailHtml) { match ->
        val inner = match.groupValues[1].trim()
        if (P_OPEN_TAG.containsMatchIn(inner)) {
            P_OPEN_TAG.replace(inner) { pMatch ->
                val existingStyle = pMatch.groupValues[2].trim()
                val mergedStyle = if (existingStyle.isEmpty()) "text-indent: 16px;" else "text-indent: 16px; $existingStyle"
                "<p style=\"$mergedStyle\">"
            }
        } else {
            "<p style=\"text-indent: 16px;\">$inner</p>"
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
        Modifier
            .padding(start = 4.dp, end = 8.dp)
            .size(40.dp)
            .clip(DotShapes.medium)
            .background(if (enabled) c.inverse else c.surfaceRaised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Outlined.Send, "Send", Modifier.size(19.dp), tint = if (enabled) c.onInverse else c.textTertiary)
    }
}

@Composable
private fun FieldRow(label: String, onClick: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
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
            Modifier
                .fillMaxWidth()
                .padding(start = DotSpacing.gutter, end = DotSpacing.s, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .width(44.dp)
                    .heightIn(min = 32.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(label, style = Dot.type.callout, color = c.textTertiary)
            }
            FlowRow(
                Modifier.weight(1f),
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
                    modifier = Modifier
                        .widthIn(min = 120.dp)
                        .heightIn(min = 32.dp)
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
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.addSuggestion(field, s); input = ""; vm.setPending(field, "") }
                            .padding(horizontal = DotSpacing.m, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SenderAvatar(s, size = 28.dp)
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
        Modifier
            .heightIn(min = 32.dp)
            .clip(DotShapes.small)
            .background(c.surfaceRaised)
            .border(1.dp, if (valid) c.surfaceRaised else c.danger, DotShapes.small)
            .clickable(onClickLabel = "Remove ${a.displayName}", role = Role.Button, onClick = onRemove)
            .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(a.displayName, style = Dot.type.label, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
        Icon(Icons.Outlined.Close, null, Modifier.padding(start = 4.dp).size(14.dp), tint = c.textTertiary)
    }
}

@Composable
private fun AttachmentChips(uris: List<String>, onRemove: (String) -> Unit) {
    val context = LocalContext.current
    val c = Dot.colors
    Column(Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s)) {
        uris.forEach { u ->
            var meta by remember(u) { mutableStateOf(Uri.parse(u).lastPathSegment.orEmpty() to (null as Long?)) }
            LaunchedEffect(u) {
                meta = withContext(Dispatchers.IO) { describe(context, Uri.parse(u)) }
            }
            val (name, size) = meta
            Row(
                Modifier
                    .padding(vertical = 3.dp)
                    .fillMaxWidth()
                    .clip(DotShapes.medium)
                    .border(1.dp, c.border, DotShapes.medium)
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
    Box(modifier, contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
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
