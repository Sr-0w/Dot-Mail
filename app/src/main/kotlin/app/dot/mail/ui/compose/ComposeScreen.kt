package app.dot.mail.ui.compose

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import app.dot.design.components.backdropSource
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
import androidx.compose.material.icons.outlined.PersonAddAlt
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import app.dot.design.components.DotPrimaryButton
import app.dot.design.components.DotTextField
import app.dot.design.components.DotTopBar
import app.dot.design.components.Hairline
import app.dot.design.components.dotSurfaceEnter
import app.dot.design.rememberReducedMotion
import app.dot.mail.ComposeFormat
import app.dot.mail.LocalMailGraph
import app.dot.mail.LocalSettings
import app.dot.mail.R
import app.dot.mail.data.model.EmailAddress
import app.dot.mail.data.model.Identity
import app.dot.mail.data.aliases.AndroidAliasRepository
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private val QuoteIndent = 16.sp
private val QuoteParagraphStyle = ParagraphStyle(textIndent = TextIndent(firstLine = QuoteIndent, restLine = QuoteIndent))

@Composable
fun ComposeScreen(route: ComposeRoute, onClose: () -> Unit) {
    val graph = LocalMailGraph.current
    val settings = LocalSettings.current
    val context = LocalContext.current
    val appContext = context.applicationContext
    val vm: ComposeViewModel = viewModel {
        ComposeViewModel(
            graph = graph,
            route = route,
            undoSeconds = { settings.ui.value.undoSendSeconds },
            defaultRichText = settings.ui.value.defaultFormat == ComposeFormat.RICH,
            defaultAccountId = { settings.ui.value.defaultAccountId },
            includeOriginal = { settings.ui.value.includeOriginal },
            strings = { id, args -> appContext.getString(id, *args) },
            pgp = app.dot.mail.ui.pgp.OpenKeychainService(appContext),
            pgpDefaults = { id -> app.dot.mail.data.AccountSettingsStore.get(appContext).current(id).let { it.pgpSignDefault to it.pgpEncryptDefault } },
            aliasRepository = AndroidAliasRepository(appContext),
        )
    }
    // OpenKeychain's own screens (passphrase, key choice) during an encrypted/signed send.
    val pgpPrompt by vm.pgpPrompt.collectAsStateWithLifecycle()
    val pgpLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        vm.pgpResult(if (res.resultCode == android.app.Activity.RESULT_OK) res.data ?: Intent() else null)
    }
    LaunchedEffect(pgpPrompt) { pgpPrompt?.let { pgpLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(it.intentSender).build()) } }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val closed by vm.closed.collectAsStateWithLifecycle()
    val c = Dot.colors
    var menu by remember { mutableStateOf(false) }
    var scheduleMenu by remember { mutableStateOf(false) }
    var senderMenu by remember { mutableStateOf(false) }
    var confirmPlainDialog by remember { mutableStateOf(false) }
    var confirmCloseDialog by remember { mutableStateOf(false) }
    val requestClose: () -> Unit = { if (vm.needsCloseChoice()) confirmCloseDialog = true else vm.close() }
    var showLinkDialog by remember { mutableStateOf(false) }
    var bodyFocused by remember { mutableStateOf(false) }
    val bodyFocus = remember { FocusRequester() }
    val toFocus = remember { FocusRequester() }
    val ccFocus = remember { FocusRequester() }
    var focusCcOnReveal by remember { mutableStateOf(false) }
    val reduceMotion = rememberReducedMotion()

    LaunchedEffect(vm, context) {
        vm.aliasOutcomes.collect { outcome ->
            when (outcome) {
                is AliasOutcome.Selected -> Unit
                is AliasOutcome.CopiedNeedsReplyTo -> {
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("email alias", outcome.email))
                    Toast.makeText(context, R.string.aliases_reply_to_guidance, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val richTextState = rememberRichTextState()
    var templatesOpen by remember { mutableStateOf(false) }
    if (templatesOpen) TemplateSheet(state, onInsert = { templatesOpen = false; vm.insertTemplate(it) }, onDismiss = { templatesOpen = false })
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
    BackHandler { requestClose() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            // Keep read access across restarts until the data layer has copied the file.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        vm.addAttachments(uris.map { it.toString() })
    }

    val draft = state.draft
    val edgeState=app.dot.design.components.rememberGlassBackdrop()
    val bodyScroll=rememberScrollState()
    app.dot.design.components.FrostedHeaderLayout(
        backdrop=edgeState,modifier=Modifier
            .fillMaxSize()
            .background(c.background)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding(),
        header={ DotTopBar(
            navigation = { DotIconButton(Icons.Outlined.Close, stringResource(R.string.compose_close), onClick = requestClose) },
            title = if (route.replyMode != null) replyTitle(route.replyMode) else stringResource(R.string.compose_new_message),
            actions = {
                DotIconButton(Icons.Outlined.AttachFile, stringResource(R.string.compose_attach_files), onClick = { picker.launch(arrayOf("*/*")) })
                Box {
                    DotIconButton(Icons.Outlined.MoreVert, stringResource(R.string.compose_more), onClick = { menu = true })
                    DotMenu(menu, { menu = false }) {
                        if (state.isRichText) {
                            DotMenuItem(stringResource(R.string.compose_plain_text), Icons.AutoMirrored.Outlined.Subject) {
                                menu = false
                                confirmPlainDialog = true
                            }
                        } else {
                            DotMenuItem(stringResource(R.string.compose_formatted_text), Icons.Outlined.TextFormat) {
                                menu = false
                                vm.switchToRichText()
                            }
                        }
                        DotMenuItem("Templates…", Icons.Outlined.Description) { menu = false; templatesOpen = true }
                        DotMenuItem(stringResource(R.string.compose_schedule_send), Icons.Outlined.Schedule) { menu = false; scheduleMenu = true }
                        DotMenuItem(stringResource(R.string.compose_discard), Icons.Outlined.DeleteOutline, color = c.danger) { menu = false; vm.discard() }
                    }
                    if (scheduleMenu) app.dot.mail.ui.schedule.ScheduleSheet(
                        title = stringResource(R.string.compose_schedule_send),
                        onDismiss = { scheduleMenu = false },
                        onPick = { at -> scheduleMenu = false; vm.scheduleSend(at) },
                    )
                }
                SendButton(enabled = state.loaded && !state.sending && state.hasRecipients, onClick = { vm.send() })
            },
        ) },
    ) { headerPadding ->
        if(draft!=null) {
        Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().backdropSource(edgeState).verticalScroll(bodyScroll)) { Spacer(Modifier.height(headerPadding.calculateTopPadding()))

            // From
            if (state.senders.isNotEmpty()) {
                Box {
                    FieldRow(stringResource(R.string.compose_from), onClick = { senderMenu = true }) {
                        Text(state.sender?.email.orEmpty(), style = Dot.type.callout, color = c.textPrimary, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.ExpandMore, stringResource(R.string.compose_choose_sender), Modifier.size(18.dp), tint = c.textTertiary)
                    }
                    DotMenu(senderMenu, { senderMenu = false }) {
                        state.senders.forEach { s ->
                            DotMenuItem("${s.name} <${s.email}>", null) { senderMenu = false; vm.setSender(s) }
                        }
                        DotMenuItem(stringResource(R.string.compose_use_alias), null) { senderMenu = false; vm.loadAliases() }
                    }
                }
                Hairline()
            }
            val canToggleCcBcc = !state.showCcBcc || state.canCollapseCcBcc
            val ccBccToggleLabel = stringResource(if (state.showCcBcc) R.string.compose_hide_cc_bcc else R.string.compose_show_cc_bcc)
            RecipientRow(
                label = stringResource(R.string.compose_to), field = RecipientField.TO, list = draft.to, vm = vm, state = state,
                focusRequester = toFocus,
                trailing = if (canToggleCcBcc) ({
                    Box(
                        Modifier
                            .minimumInteractiveComponentSize()
                            .heightIn(min = 32.dp)
                            .clip(DotShapes.pill)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = ccBccToggleLabel,
                            ) {
                                if (!state.showCcBcc) {
                                    focusCcOnReveal = true
                                    vm.showCcBcc()
                                } else if (state.canCollapseCcBcc) {
                                    focusCcOnReveal = false
                                    vm.hideCcBcc()
                                    runCatching { toFocus.requestFocus() }
                                }
                            }
                            .padding(horizontal = 8.dp)
                            .semantics {
                                contentDescription = ccBccToggleLabel
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.compose_cc_bcc),
                            style = Dot.type.label,
                            color = if (state.showCcBcc) c.textSecondary else c.textTertiary,
                        )
                    }
                }) else null,
            )
            Hairline()
            val ccBccEnter = if (reduceMotion) {
                EnterTransition.None
            } else {
                expandVertically(
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Top,
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                )
            }
            val ccBccExit = if (reduceMotion) {
                ExitTransition.None
            } else {
                shrinkVertically(
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                )
            }
            AnimatedVisibility(
                visible = state.showCcBcc,
                enter = ccBccEnter,
                exit = ccBccExit,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    RecipientRow(stringResource(R.string.compose_cc), RecipientField.CC, draft.cc, vm, state, focusRequester = ccFocus)
                    Hairline()
                    RecipientRow(stringResource(R.string.compose_bcc), RecipientField.BCC, draft.bcc, vm, state)
                    Hairline()
                }
            }
            Row(Modifier.padding(horizontal = DotSpacing.gutter, vertical = 14.dp)) {
                Plain(
                    value = draft.subject,
                    onValue = { v -> vm.edit { it.copy(subject = v) } },
                    placeholder = stringResource(R.string.compose_subject),
                    style = Dot.type.headline,
                    singleLine = true,
                    imeAction = ImeAction.Next,
                    onIme = { bodyFocus.requestFocus() },
                    modifier = Modifier.weight(1f),
                )
            }
            Hairline()
            if (state.pgpAvailable) PgpRow(state, vm)
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
                    placeholder = stringResource(R.string.compose_body_placeholder),
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
            SignatureRow(state, vm)
            state.quotedText?.takeIf { it.isNotBlank() }?.let { quoted ->
                QuotedReplySection(
                    quoted = quoted,
                    expanded = state.quoteExpanded,
                    onToggle = vm::toggleQuoteExpanded,
                )
            }

        }
        state.aliasChoices?.let { choices ->
            AlertDialog(
                onDismissRequest = vm::dismissAliasPicker,
                modifier = Modifier.dotSurfaceEnter(),
                containerColor = c.surface,
                shape = DotShapes.dialog,
                title = { Text(stringResource(R.string.compose_use_alias), style = Dot.type.title, color = c.textPrimary) },
                text = {
                    Column {
                        if (state.aliasLoading) {
                            Row(Modifier.fillMaxWidth().padding(vertical = DotSpacing.m), horizontalArrangement = Arrangement.Center) {
                                app.dot.design.components.DotSpinner(size = 22.dp, color = c.textTertiary)
                            }
                        }
                        if (state.aliasProviderErrors.isNotEmpty()) {
                            Text(stringResource(R.string.aliases_error), style = Dot.type.callout, color = c.danger, modifier = Modifier.padding(vertical = DotSpacing.s))
                        }
                        if (!state.aliasLoading && choices.isEmpty() && state.aliasProviderErrors.isEmpty()) {
                            Text(stringResource(R.string.compose_aliases_empty), style = Dot.type.callout, color = c.textSecondary)
                        }
                        choices.forEach { alias ->
                            app.dot.design.components.ListRow(
                                title = alias.email,
                                onClick = if (state.aliasUsing == null) ({ vm.useAlias(alias) }) else null,
                                trailing = if (state.aliasUsing == alias.email) ({
                                    app.dot.design.components.DotSpinner(size = 18.dp, color = c.textTertiary)
                                }) else null,
                            )
                        }
                        if (state.aliasUseError) {
                            Text(stringResource(R.string.aliases_error), style = Dot.type.callout, color = c.danger, modifier = Modifier.padding(vertical = DotSpacing.s))
                        }
                    }
                },
                confirmButton = { DotButton(stringResource(R.string.compose_close), onClick = vm::dismissAliasPicker, style = ButtonStyle.Ghost, compact = true) },
            )
        }
        if (state.savedOnce) {
            Text(
                stringResource(R.string.compose_draft_saved), style = Dot.type.caption, color = c.textTertiary,
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
        }
    }

    if (confirmCloseDialog) {
        ConfirmCloseDialog(
            onDismiss = { confirmCloseDialog = false },
            onDiscard = { confirmCloseDialog = false; vm.discard() },
            onSave = { confirmCloseDialog = false; vm.close() },
        )
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

    if (showLinkDialog) RichLinkDialog(richTextState) { showLinkDialog = false }

    LaunchedEffect(state.loaded) {
        if (state.loaded && draft != null) {
            if (draft.to.isEmpty()) toFocus.requestFocus() else bodyFocus.requestFocus()
        }
    }

    LaunchedEffect(state.showCcBcc, focusCcOnReveal) {
        if (state.showCcBcc && focusCcOnReveal) {
            focusCcOnReveal = false
            runCatching { ccFocus.requestFocus() }
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
                    Text(stringResource(R.string.compose_body_placeholder), style = Dot.type.body, color = c.textTertiary)
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

/** Insert / edit / remove a link on the current selection of [richTextState]. */
@Composable
internal fun RichLinkDialog(richTextState: RichTextState, onDismiss: () -> Unit) {
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
        onDismiss = onDismiss,
        onRemove = {
            onDismiss()
            richTextState.removeLink()
        },
        onConfirm = { linkText, rawUrl ->
            onDismiss()
            val normalizedUrl = normalizeLinkUrl(rawUrl)
            if (normalizedUrl.isNotEmpty()) {
                val display = linkText.ifBlank { normalizedUrl }
                when {
                    richTextState.isLink && display == richTextState.selectedLinkText -> richTextState.updateLink(normalizedUrl)
                    !richTextState.selection.collapsed && display == initialText -> richTextState.addLinkToSelection(normalizedUrl)
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

/** [wrap] lays the buttons out on several lines instead of scrolling sideways (used where width is tight). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FormattingToolbar(
    richTextState: RichTextState,
    wrap: Boolean = false,
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

    val buttons: @Composable () -> Unit = {
        FormatButton(Icons.Outlined.FormatBold, stringResource(R.string.compose_format_bold), active = isBold) {
            richTextState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        }
        FormatButton(Icons.Outlined.FormatItalic, stringResource(R.string.compose_format_italic), active = isItalic) {
            richTextState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
        }
        FormatButton(Icons.Outlined.FormatUnderlined, stringResource(R.string.compose_format_underline), active = isUnderline) {
            richTextState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
        }
        FormatButton(Icons.Outlined.FormatStrikethrough, stringResource(R.string.compose_format_strikethrough), active = isStrike) {
            richTextState.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
        }
        FormatButton(Icons.Outlined.Link, stringResource(R.string.compose_format_link), active = isLink, onClick = onLinkClick)
        FormatButton(Icons.AutoMirrored.Outlined.FormatListBulleted, stringResource(R.string.compose_format_bulleted_list), active = isBulleted) {
            richTextState.toggleUnorderedList()
        }
        FormatButton(Icons.Outlined.FormatListNumbered, stringResource(R.string.compose_format_numbered_list), active = isNumbered) {
            richTextState.toggleOrderedList()
        }
        FormatButton(Icons.Outlined.FormatQuote, stringResource(R.string.compose_format_quote), active = isQuote) {
            richTextState.toggleParagraphStyle(QuoteParagraphStyle)
        }
        FormatButton(Icons.Outlined.FormatClear, stringResource(R.string.compose_format_clear), active = false) {
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
    val modifier = Modifier
        .fillMaxWidth()
        .background(c.background)
        .focusProperties { canFocus = false }
    if (wrap) {
        androidx.compose.foundation.layout.FlowRow(
            modifier.padding(horizontal = DotSpacing.s, vertical = 2.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) { buttons() }
    } else {
        Row(
            modifier.horizontalScroll(rememberScrollState()).padding(horizontal = DotSpacing.s, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { buttons() }
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
            .clip(DotShapes.pill)
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
    val reduceMotion = rememberReducedMotion()
    val quoteToggleLabel = stringResource(if (expanded) R.string.compose_hide_quoted else R.string.compose_show_quoted)
    Column(Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s)) {
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .clip(DotShapes.pill)
                .background(c.surfaceRaised)
                .border(DotSpacing.hairline * 2, c.border, DotShapes.pill)
                .clickable(
                    role = Role.Button,
                    onClickLabel = quoteToggleLabel,
                    onClick = onToggle,
                )
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .semantics { contentDescription = quoteToggleLabel },
            contentAlignment = Alignment.Center,
        ) {
            Text("•••", style = Dot.type.label, color = c.textSecondary)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduceMotion) EnterTransition.None else expandVertically(tween(200, easing = FastOutSlowInEasing)) + fadeIn(tween(200, easing = FastOutSlowInEasing)),
            exit = if (reduceMotion) ExitTransition.None else shrinkVertically(tween(200, easing = FastOutSlowInEasing)) + fadeOut(tween(150, easing = FastOutSlowInEasing)),
        ) {
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

/** Leaving the composer: keep it as a draft (the default), throw it away, or go back to writing. */
@Composable
private fun ConfirmCloseDialog(onDismiss: () -> Unit, onDiscard: () -> Unit, onSave: () -> Unit) {
    val c = Dot.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.dotSurfaceEnter(),
        containerColor = c.surface,
        shape = DotShapes.dialog,
        title = { Text(stringResource(R.string.compose_close_title), style = Dot.type.title, color = c.textPrimary) },
        text = { Text(stringResource(R.string.compose_close_body), style = Dot.type.callout, color = c.textSecondary) },
        confirmButton = { DotPrimaryButton(stringResource(R.string.compose_save_draft), onClick = onSave, compact = true) },
        dismissButton = {
            Row {
                DotButton(stringResource(R.string.compose_discard_short), onClick = onDiscard, style = ButtonStyle.Ghost, compact = true)
                DotButton(stringResource(R.string.compose_keep_editing), onClick = onDismiss, style = ButtonStyle.Ghost, compact = true)
            }
        },
    )
}

@Composable
private fun ConfirmPlainTextDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = Dot.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.dotSurfaceEnter(),
        containerColor = c.surface,
        shape = DotShapes.dialog,
        title = { Text(stringResource(R.string.compose_plain_text), style = Dot.type.title, color = c.textPrimary) },
        text = { Text(stringResource(R.string.compose_plain_text_confirm_body), style = Dot.type.callout, color = c.textSecondary) },
        confirmButton = { DotPrimaryButton(stringResource(R.string.compose_convert), onClick = onConfirm, compact = true) },
        dismissButton = { DotButton(stringResource(R.string.compose_cancel), onClick = onDismiss, style = ButtonStyle.Ghost, compact = true) },
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
        modifier = Modifier.dotSurfaceEnter(),
        containerColor = c.surface,
        shape = DotShapes.dialog,
        title = {
            Text(
                stringResource(if (isEditing) R.string.compose_edit_link else R.string.compose_insert_link),
                style = Dot.type.title,
                color = c.textPrimary,
            )
        },
        text = {
            Column {
                DotTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = stringResource(R.string.compose_link_text_label),
                    placeholder = stringResource(R.string.compose_link_text_placeholder),
                    singleLine = true,
                    imeAction = ImeAction.Next,
                    onImeAction = { urlFocus.requestFocus() },
                )
                Spacer(Modifier.height(DotSpacing.m))
                DotTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = stringResource(R.string.compose_link_url_label),
                    placeholder = stringResource(R.string.compose_link_url_placeholder),
                    singleLine = true,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    onImeAction = { if (url.isNotBlank()) onConfirm(text.trim(), url.trim()) },
                    focusRequester = urlFocus,
                )
            }
        },
        confirmButton = {
            DotPrimaryButton(
                text = stringResource(if (isEditing) R.string.compose_save else R.string.compose_insert),
                onClick = { onConfirm(text.trim(), url.trim()) },
                enabled = url.isNotBlank(),
                compact = true,
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(DotSpacing.xs)) {
                if (isEditing) {
                    DotButton(stringResource(R.string.compose_remove), onClick = onRemove, style = ButtonStyle.Danger, compact = true)
                }
                DotButton(stringResource(R.string.compose_cancel), onClick = onDismiss, style = ButtonStyle.Ghost, compact = true)
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

@Composable
private fun replyTitle(mode: String) = when (mode) {
    "REPLY_ALL" -> stringResource(R.string.compose_reply_all)
    "FORWARD" -> stringResource(R.string.compose_forward)
    else -> stringResource(R.string.compose_reply)
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val haptics = app.dot.design.rememberDotHaptics()
    DotPrimaryButton(
        icon = Icons.AutoMirrored.Outlined.Send,
        contentDescription = stringResource(R.string.compose_send),
        enabled = enabled,
        onClick = { haptics.confirm(); onClick() },
        compact = true,
        modifier = Modifier.padding(start = 4.dp, end = 8.dp),
    )
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
                .padding(start = DotSpacing.gutter, end = DotSpacing.s),
            verticalAlignment = Alignment.Top,
        ) {
            // The row is 48dp tall (the contact and Cc/Bcc buttons are 48dp touch targets): the label is centred in that
            // height and the first line of the field (32dp) sits 8dp below the top, so label, caret and icons share one centre line.
            Box(
                Modifier
                    .width(44.dp)
                    .heightIn(min = 48.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(label, style = Dot.type.callout, color = c.textTertiary)
            }
            FlowRow(
                Modifier.weight(1f).padding(vertical = 8.dp),
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
            // System contact picker: Android grants access to the one picked address only — no contacts permission.
            val context = androidx.compose.ui.platform.LocalContext.current
            val pickContact = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                val uri = result.data?.data ?: return@rememberLauncherForActivityResult
                runCatching {
                    context.contentResolver.query(
                        uri,
                        arrayOf(android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS, android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                        null, null, null,
                    )?.use { cur ->
                        if (cur.moveToFirst()) {
                            val address = cur.getString(0)?.trim().orEmpty()
                            val name = cur.getString(1)?.takeIf { it.isNotBlank() && it != address }
                            if (address.contains('@')) vm.addSuggestion(field, EmailAddress(address, name))
                        }
                    }
                }
            }
            app.dot.design.components.DotIconButton(
                androidx.compose.material.icons.Icons.Outlined.PersonAddAlt, "Choose from contacts",
                onClick = {
                    runCatching {
                        pickContact.launch(android.content.Intent(android.content.Intent.ACTION_PICK, android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI))
                    }
                },
            )
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
    val removeLabel = stringResource(R.string.compose_remove_recipient, a.displayName)
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .clip(DotShapes.pill)
            .background(c.surfaceRaised)
            .border(1.dp, if (valid) c.surfaceRaised else c.danger, DotShapes.pill)
            .clickable(onClickLabel = removeLabel, role = Role.Button, onClick = onRemove)
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
    val summary = pluralStringResource(R.plurals.compose_attachments_count, uris.size, uris.size)
    Column(
        Modifier
            .padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s)
            .semantics { contentDescription = summary },
    ) {
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
                DotIconButton(Icons.Outlined.Close, stringResource(R.string.compose_remove_attachment, name), onClick = { onRemove(u) }, tint = c.textTertiary)
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
}.getOrNull() ?: ((uri.lastPathSegment ?: context.getString(R.string.compose_attachment_fallback)) to null)

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

/** The signature that will be appended to this message, kept apart from the editor so it is never mixed with user text. */
@Composable
private fun SignatureRow(state: ComposeState, vm: ComposeViewModel) {
    val c = Dot.colors
    val active = state.signatureSender
    val sender = state.sender
    var menu by remember { mutableStateOf(false) }
    val others = state.senders.filter { it.hasSignature && it.key != active?.key }
    if (active == null && sender?.hasSignature != true && others.isEmpty()) return
    Box(Modifier.fillMaxWidth().padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(R.string.compose_signature_options)) { menu = true },
            verticalAlignment = Alignment.Top,
        ) {
            if (active != null) {
                app.dot.mail.ui.compose.SignaturePreview(
                    ComposeViewModel.signatureBodyHtml(active).orEmpty(), active.signatureLogo, Modifier.weight(1f),
                )
            } else {
                Text(stringResource(R.string.compose_signature_add), style = Dot.type.callout, color = c.accent, modifier = Modifier.weight(1f))
            }
            Icon(Icons.Outlined.ExpandMore, stringResource(R.string.compose_signature_options), Modifier.size(18.dp), tint = c.textTertiary)
        }
        DotMenu(menu, { menu = false }) {
            if (active != null) DotMenuItem(stringResource(R.string.compose_signature_remove), null) { menu = false; vm.removeSignature() }
            if (active == null && sender?.hasSignature == true) DotMenuItem(stringResource(R.string.compose_signature_add), null) { menu = false; vm.useSignatureOf(sender) }
            others.forEach { o ->
                DotMenuItem(stringResource(R.string.compose_signature_use, o.email), null) { menu = false; vm.useSignatureOf(o) }
            }
        }
    }
}

/** Sign / Encrypt for this message (shown only when OpenKeychain is installed), with the recipient-key warnings inline. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PgpRow(state: ComposeState, vm: ComposeViewModel) {
    val c = Dot.colors
    val draft = state.draft ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = DotSpacing.gutter)) {
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DotSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            app.dot.design.components.DotFilterChip(stringResource(R.string.compose_pgp_sign), draft.pgpSign, { vm.setPgp(sign = !draft.pgpSign) })
            app.dot.design.components.DotFilterChip(stringResource(R.string.compose_pgp_encrypt), draft.pgpEncrypt, { vm.setPgp(encrypt = !draft.pgpEncrypt) })
        }
        if (draft.pgpEncrypt) {
            val own = state.sender?.email.orEmpty()
            when {
                state.pgpMissing.isNotEmpty() -> {
                    Text(
                        stringResource(R.string.compose_pgp_missing, state.pgpMissing.joinToString(", ")),
                        style = Dot.type.caption, color = c.danger, modifier = Modifier.padding(bottom = DotSpacing.xs),
                    )
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DotSpacing.s)) {
                        DotButton(stringResource(R.string.compose_pgp_send_unencrypted), { vm.sendUnencrypted() }, style = ButtonStyle.Ghost, compact = true)
                        DotButton(stringResource(R.string.compose_pgp_remove_recipients), { vm.removeRecipientsWithoutKey() }, style = ButtonStyle.Ghost, compact = true)
                    }
                }
                state.pgpChecking -> Text(stringResource(R.string.compose_pgp_checking), style = Dot.type.caption, color = c.textTertiary)
            }
            if (state.pgpOwnKeyMissing && own.isNotEmpty()) {
                Text(stringResource(R.string.compose_pgp_no_own_key, own), style = Dot.type.caption, color = c.textTertiary)
            }
            Text(stringResource(R.string.compose_pgp_draft_local), style = Dot.type.caption, color = c.textTertiary, modifier = Modifier.padding(bottom = DotSpacing.xs))
        }
    }
}
