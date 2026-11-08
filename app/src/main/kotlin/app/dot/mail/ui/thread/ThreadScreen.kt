package app.dot.mail.ui.thread

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Unsubscribe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.Avatar
import app.dot.design.components.BackButton
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotIconButton
import app.dot.design.components.DotMenu
import app.dot.design.components.DotMenuItem
import app.dot.design.components.DotSnackbar
import app.dot.design.components.DotTopBar
import app.dot.design.components.EmptyState
import app.dot.design.components.Hairline
import app.dot.mail.LocalMailGraph
import app.dot.mail.LocalSettings
import app.dot.mail.data.MailGraph
import app.dot.mail.data.ReplyMode
import app.dot.mail.data.model.Attachment
import app.dot.mail.data.model.EmailAddress
import app.dot.mail.data.model.Message
import app.dot.mail.data.model.MessageAction
import app.dot.mail.engine.html.HtmlSanitizer
import app.dot.mail.engine.text.QuoteSplitter
import app.dot.mail.ui.common.Format
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ThreadViewModel(private val graph: MailGraph, private val threadId: Long) : ViewModel() {
    val messages: StateFlow<List<Message>?> =
        graph.mail.thread(threadId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Explicitly toggled messages; default expansion is computed from unread/last. */
    private val _toggled = MutableStateFlow<Map<Long, Boolean>>(emptMap())
    val toggled: StateFlow<Map<Long, Boolean>> = _toggled

    private val _remoteAllowed = MutableStateFlow<Set<Long>>(emptySet())
    val remoteAllowed: StateFlow<Set<Long>> = _remoteAllowed

    private val loading = mutableSetOf<Long>()
    private var markedRead = false

    private val _events = Channel<ThreadEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun isExpanded(m: Message, all: List<Message>): Boolean =
        _toggled.value[m.id] ?: (m.isUnread || m == all.lastOrNull() || all.size == 1)

    fun toggle(m: Message, all: List<Message>) {
        val now = !isExpanded(m, all)
        _toggled.update { it + (m.id to now) }
        if (now) ensureBody(m)
    }

    fun ensureBody(m: Message) {
        if (m.body != null || !loading.add(m.id)) return
        viewModelScope.launch {
            runCatching { graph.mail.loadBody(m.id) }
                .onFailure { _events.send(ThreadEvent.Message("Couldn't download this message. ${it.message ?: ""}".trim())) }
            loading.remove(m.id)
        }
    }

    /** Latch which messages start expanded *before* marking read, or unread ones would collapse a frame later. */
    fun onOpened(initial: List<Message>, mode: app.dot.mail.MarkRead = app.dot.mail.MarkRead.IMMEDIATELY) {
        if (markedRead) return
        markedRead = true
        _toggled.update { current ->
            initial.associate { m -> m.id to (m.isUnread || m == initial.lastOrNull() || initial.size == 1) } + current
        }
        if (initial.any { it.isUnread } && mode != app.dot.mail.MarkRead.MANUALLY) viewModelScope.launch {
            if (mode == app.dot.mail.MarkRead.AFTER_2S) kotlinx.coroutines.delay(2_000) // cancelled if the user leaves sooner
            runCatching { graph.mail.apply(MessageAction.MARK_READ, listOf(threadId)) }
        }
    }

    fun move(folderId: Long) {
        viewModelScope.launch {
            runCatching { graph.mail.move(listOf(threadId), folderId) }
                .onSuccess { _events.send(ThreadEvent.Close) }
                .onFailure { _events.send(ThreadEvent.Message(it.message ?: "Couldn't move")) }
        }
    }

    fun allowRemote(messageId: Long) = _remoteAllowed.update { it + messageId }

    fun act(action: MessageAction) {
        viewModelScope.launch {
            runCatching { graph.mail.apply(action, listOf(threadId)) }
                .onSuccess {
                    when (action) {
                        MessageAction.ARCHIVE, MessageAction.TRASH, MessageAction.SPAM, MessageAction.NOT_SPAM,
                        MessageAction.DELETE_FOREVER, MessageAction.MARK_UNREAD -> _events.send(ThreadEvent.Close)
                        else -> Unit
                    }
                }
                .onFailure { _events.send(ThreadEvent.Message(it.message ?: "That action couldn't be completed")) }
        }
    }

    fun openAttachment(a: Attachment, share: Boolean) {
        viewModelScope.launch {
            runCatching { graph.mail.openAttachment(a) }
                .onSuccess { _events.send(ThreadEvent.OpenUri(it, a.mimeType, a.fileName, share)) }
                .onFailure { _events.send(ThreadEvent.Message("Couldn't open ${a.fileName}")) }
        }
    }

    private fun emptMap() = emptyMap<Long, Boolean>()
}

sealed interface ThreadEvent {
    data object Close : ThreadEvent
    data class Message(val text: String) : ThreadEvent
    data class OpenUri(val uri: String, val mime: String, val name: String, val share: Boolean) : ThreadEvent
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    threadId: Long,
    onBack: () -> Unit,
    onReply: (messageId: Long, mode: ReplyMode) -> Unit,
    onComposeTo: (String) -> Unit,
    onOpenAdjacent: (Long) -> Unit = {},
) {
    val graph = LocalMailGraph.current
    val vm: ThreadViewModel = viewModel(key = "thread-$threadId") { ThreadViewModel(graph, threadId) }
    val messages by vm.messages.collectAsStateWithLifecycle()
    val toggled by vm.toggled.collectAsStateWithLifecycle()
    val remoteAllowed by vm.remoteAllowed.collectAsStateWithLifecycle()
    val prefs by LocalSettings.current.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var pendingLink by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                ThreadEvent.Close -> {
                    val target = when (prefs.afterAction) {
                        app.dot.mail.AfterAction.NEXT -> ThreadOrder.after(threadId)
                        app.dot.mail.AfterAction.PREVIOUS -> ThreadOrder.before(threadId)
                        app.dot.mail.AfterAction.LIST -> null
                    }
                    if (target != null) onOpenAdjacent(target) else onBack()
                }
                is ThreadEvent.Message -> snackbar.showSnackbar(e.text)
                is ThreadEvent.OpenUri -> {
                    val uri = Uri.parse(e.uri)
                    val intent = if (e.share) {
                        Intent(Intent.ACTION_SEND).setType(e.mime).putExtra(Intent.EXTRA_STREAM, uri)
                    } else {
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mime)
                    }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(Intent.createChooser(intent, e.name))
                    } catch (_: ActivityNotFoundException) {
                        scope.launch { snackbar.showSnackbar("No app can open ${e.name}") }
                    }
                }
            }
        }
    }
    LaunchedEffect(messages != null) { messages?.let { vm.onOpened(it, prefs.markRead) } }

    val list = messages
    val last = list?.lastOrNull()
    val c = Dot.colors

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize()) {
            DotTopBar(
                elevated = listState.canScrollBackward,
                navigation = { BackButton(onBack) },
                actions = {
                    DotIconButton(Icons.Outlined.Archive, "Archive", onClick = { vm.act(MessageAction.ARCHIVE) })
                    DotIconButton(Icons.Outlined.Delete, "Delete", onClick = { if (prefs.confirmDelete) confirmTrash = true else vm.act(MessageAction.TRASH) })
                    DotIconButton(Icons.Outlined.MarkEmailUnread, "Mark as unread", onClick = { vm.act(MessageAction.MARK_UNREAD) })
                    Box {
                        DotIconButton(Icons.Outlined.MoreVert, "More", onClick = { menu = true })
                        DotMenu(menu, { menu = false }) {
                            val starred = list?.any { it.isStarred } == true
                            DotMenuItem(if (starred) "Remove star" else "Star", if (starred) Icons.Outlined.StarOutline else Icons.Outlined.Star) {
                                menu = false; vm.act(if (starred) MessageAction.UNSTAR else MessageAction.STAR)
                            }
                            DotMenuItem("Report spam", Icons.Outlined.Report) { menu = false; vm.act(MessageAction.SPAM) }
                            DotMenuItem("Not spam", Icons.Outlined.Inbox) { menu = false; vm.act(MessageAction.NOT_SPAM) }
                            DotMenuItem("Move to…", Icons.Outlined.DriveFileMove) { menu = false; showMove = true }
                            DotMenuItem("Delete forever", Icons.Outlined.DeleteForever, color = c.danger) { menu = false; confirmDelete = true }
                            last?.unsubscribe?.let { target ->
                                DotMenuItem("Unsubscribe", Icons.Outlined.Unsubscribe) {
                                    menu = false
                                    if (target.startsWith("mailto:", ignoreCase = true)) onComposeTo(target) else pendingLink = target
                                }
                            }
                        }
                    }
                },
            )
            when {
                list == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    app.dot.design.components.DotSpinner(size = 22.dp, color = c.textTertiary)
                }
                list.isEmpty() -> Box(Modifier.weight(1f)) {
                    EmptyState(Icons.Outlined.Description, "Conversation unavailable", "It may have been moved or deleted.")
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = DotSpacing.xl),
                ) {
                    item(key = "subject") {
                        Text(
                            list.first().subject.ifBlank { "(no subject)" },
                            style = Dot.type.display,
                            color = c.textPrimary,
                            modifier = Modifier
                                .padding(horizontal = DotSpacing.gutter)
                                .padding(top = DotSpacing.s, bottom = DotSpacing.l)
                                .semantics { heading() },
                        )
                    }
                    items(list, key = { it.id }) { m ->
                        val expanded = toggled[m.id] ?: (m.isUnread || m == last || list.size == 1)
                        LaunchedEffect(expanded, m.body == null) { if (expanded) vm.ensureBody(m) }
                        MessageCard(
                            message = m,
                            expanded = expanded,
                            allowRemote = prefs.loadRemoteImages || m.id in remoteAllowed,
                            onToggle = { vm.toggle(m, list) },
                            onAllowRemote = { vm.allowRemote(m.id) },
                            onLink = { url ->
                                if (url.startsWith("mailto:", ignoreCase = true)) onComposeTo(url)
                                else if (prefs.confirmLinks) pendingLink = url
                                else openUrl(context, url)
                            },
                            onAttachment = vm::openAttachment,
                            onSender = { onComposeTo(it.email) },
                            readingScale = prefs.readingTextPercent / 100f,
                            quotedExpanded = prefs.quotedExpanded,
                        )
                        Hairline(Modifier.padding(horizontal = DotSpacing.gutter))
                    }
                }
            }
            if (last != null) ReplyBar(last, onReply, replyAllByDefault = prefs.replyAllByDefault)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp)) { DotSnackbar(it) }
    }

    if (showMove && list != null && list.isNotEmpty()) {
        val folders by graph.mail.folders(list.first().accountId).collectAsStateWithLifecycle(emptyList())
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showMove = false }, containerColor = c.surface, shape = DotShapes.sheet, dragHandle = null,
        ) {
            Text("Move to", style = Dot.type.title, color = c.textPrimary, modifier = Modifier.padding(DotSpacing.gutter))
            folders.filter { it.id != list.first().folderId }
                .sortedWith(compareBy({ Format.roleOrder.indexOf(it.role).let { i -> if (i < 0) 99 else i } }, { it.path }))
                .forEach { f ->
                    app.dot.design.components.ListRow(
                        title = if (f.role == app.dot.mail.data.model.FolderRole.CUSTOM) f.path else Format.roleName(f.role),
                        icon = Format.roleIcon(f.role),
                        onClick = { showMove = false; vm.move(f.id) },
                    )
                }
            Spacer(Modifier.navigationBarsPadding().height(DotSpacing.l))
        }
    }
    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false }, containerColor = c.surface, shape = DotShapes.large,
            title = { Text("Move to trash?", style = Dot.type.title, color = c.textPrimary) },
            confirmButton = { DotButton("Delete", { confirmTrash = false; vm.act(MessageAction.TRASH) }, style = ButtonStyle.Danger, compact = true) },
            dismissButton = { DotButton("Cancel", { confirmTrash = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, containerColor = c.surface, shape = DotShapes.large,
            title = { Text("Delete forever?", style = Dot.type.title, color = c.textPrimary) },
            text = { Text("This conversation will be permanently removed from the server.", style = Dot.type.callout, color = c.textSecondary) },
            confirmButton = { DotButton("Delete", { confirmDelete = false; vm.act(MessageAction.DELETE_FOREVER) }, style = ButtonStyle.Danger, compact = true) },
            dismissButton = { DotButton("Cancel", { confirmDelete = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
    pendingLink?.let { url -> LinkDialog(url, onDismiss = { pendingLink = null }, onOpen = { pendingLink = null; openUrl(context, url) }) }
}

private fun openUrl(context: android.content.Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme?.lowercase() !in setOf("http", "https", "mailto", "tel")) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (_: ActivityNotFoundException) { }
}

/** Shows the real destination before leaving the app: the main defence against disguised phishing links. */
@Composable
private fun LinkDialog(url: String, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val uri = remember(url) { Uri.parse(url) }
    val host = uri.host.orEmpty()
    val c = Dot.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = DotShapes.large,
        title = { Text("Open link?", style = Dot.type.title, color = c.textPrimary) },
        text = {
            Column {
                if (host.isNotEmpty()) {
                    Text(host, style = Dot.type.bodyStrong, color = c.textPrimary)
                    Spacer(Modifier.height(DotSpacing.xs))
                }
                Text(url, style = Dot.type.caption, color = c.textSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (uri.scheme?.lowercase() == "http") {
                    Spacer(Modifier.height(DotSpacing.s))
                    Text("This link is not encrypted.", style = Dot.type.caption, color = c.danger)
                }
            }
        },
        confirmButton = { DotButton("Open", onOpen, compact = true) },
        dismissButton = { DotButton("Cancel", onDismiss, style = ButtonStyle.Ghost, compact = true) },
    )
}

@Composable
private fun MessageCard(
    message: Message,
    expanded: Boolean,
    allowRemote: Boolean,
    onToggle: () -> Unit,
    onAllowRemote: () -> Unit,
    onLink: (String) -> Unit,
    onAttachment: (Attachment, Boolean) -> Unit,
    onSender: (EmailAddress) -> Unit,
    readingScale: Float = 1f,
    quotedExpanded: Boolean = false,
) {
    val c = Dot.colors
    var details by rememberSaveable(message.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(tween(220))) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle)
                .padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            app.dot.mail.ui.avatar.SenderAvatar(message.from, size = 36.dp)
            Spacer(Modifier.width(DotSpacing.m))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message.from.displayName,
                        style = Dot.type.headline.copy(fontWeight = if (message.isUnread) FontWeight.SemiBold else FontWeight.Medium),
                        color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.weight(1f).width(DotSpacing.s))
                    if (message.isStarred) {
                        Icon(Icons.Outlined.Star, "Starred", Modifier.size(15.dp), tint = c.star)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(Format.listDate(message.sentAt), style = Dot.type.caption, color = c.textTertiary)
                }
                if (expanded) {
                    Text(
                        "to " + recipientsSummary(message),
                        style = Dot.type.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { details = !details },
                    )
                } else {
                    Text(message.snippet, style = Dot.type.callout, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (!expanded) return@Column
        AnimatedVisibility(details) { HeaderDetails(message, onSender) }
        val body = message.body
        if (body == null) {
            Row(Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.l), verticalAlignment = Alignment.CenterVertically) {
                Text("Downloading", style = Dot.type.callout, color = c.textTertiary)
                Spacer(Modifier.width(DotSpacing.s))
                app.dot.design.components.DotDotsLoader(color = c.textTertiary)
            }
        } else {
            val cids = remember(message.attachments) {
                message.attachments.mapNotNull { a -> a.contentId?.let { id -> a.localUri?.let { id.trim('<', '>') to it } } }.toMap()
            }
            val html = body.html
            val sanitized by androidx.compose.runtime.produceState<app.dot.mail.engine.html.SanitizedHtml?>(null, html, allowRemote, cids) {
                value = html?.let {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        HtmlSanitizer.sanitize(it, allowRemoteImages = allowRemote, cidUris = cids)
                    }
                }
            }
            val clean = sanitized
            if (clean != null && clean.blockedRemoteResources > 0 && !allowRemote) {
                RemoteContentBanner(clean.blockedRemoteResources, onAllowRemote)
            }
            if (html != null && clean == null) {
                Box(Modifier.fillMaxWidth().height(120.dp))
            } else if (clean != null) {
                HtmlMessageView(
                    html = clean.html,
                    inlineUris = cids.values.toSet(),
                    dark = c.isDark,
                    allowRemote = allowRemote,
                    textColor = c.textPrimary.toArgb(),
                    linkColor = c.accent.toArgb(),
                    onLink = onLink,
                    textZoom = (readingScale * 100).toInt(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
                )
            } else {
                PlainTextBody(body.plainText.orEmpty(), onLink, textScale = readingScale, quotedExpanded = quotedExpanded)
            }
            if (body.isTruncated) {
                Text(
                    "This message is very long — showing the beginning.",
                    style = Dot.type.caption, color = c.textTertiary,
                    modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs),
                )
            }
            val files = message.attachments.filter { !it.isInline || it.contentId == null }
            if (files.isNotEmpty()) AttachmentStrip(files, onAttachment)
        }
        Spacer(Modifier.height(DotSpacing.m))
    }
}

private fun recipientsSummary(m: Message): String {
    val all = m.to + m.cc
    if (all.isEmpty()) return "undisclosed recipients"
    val first = all.take(2).joinToString(", ") { it.displayName }
    return if (all.size > 2) "$first +${all.size - 2}" else first
}

@Composable
private fun HeaderDetails(m: Message, onSender: (EmailAddress) -> Unit) {
    val c = Dot.colors
    Column(
        Modifier.padding(horizontal = DotSpacing.gutter).padding(bottom = DotSpacing.m).fillMaxWidth()
            .clip(DotShapes.medium).background(c.surface).padding(DotSpacing.m),
    ) {
        @Composable
        fun line(label: String, value: String, onClick: (() -> Unit)? = null) {
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(label, style = Dot.type.caption, color = c.textTertiary, modifier = Modifier.width(52.dp))
                SelectionContainer {
                    Text(
                        value, style = Dot.type.caption, color = c.textPrimary,
                        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
                    )
                }
            }
        }
        line("From", fmt(m.from), onClick = { onSender(m.from) })
        if (m.replyTo.isNotEmpty() && m.replyTo != listOf(m.from)) line("Reply to", m.replyTo.joinToString(", ") { fmt(it) })
        if (m.to.isNotEmpty()) line("To", m.to.joinToString(", ") { fmt(it) })
        if (m.cc.isNotEmpty()) line("Cc", m.cc.joinToString(", ") { fmt(it) })
        if (m.bcc.isNotEmpty()) line("Bcc", m.bcc.joinToString(", ") { fmt(it) })
        line("Date", Format.messageDate(m.sentAt))
    }
}

private fun fmt(a: EmailAddress) = if (a.name.isNullOrBlank()) a.email else "${a.name} <${a.email}>"

@Composable
private fun RemoteContentBanner(count: Int, onAllow: () -> Unit) {
    val c = Dot.colors
    Row(
        Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s).fillMaxWidth()
            .clip(DotShapes.medium).border(1.dp, c.border, DotShapes.medium)
            .padding(start = DotSpacing.m, end = DotSpacing.xs, top = DotSpacing.xs, bottom = DotSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Shield, null, Modifier.size(18.dp), tint = c.textSecondary)
        Spacer(Modifier.width(DotSpacing.s))
        Text(
            if (count == 1) "1 remote image blocked to protect your privacy" else "$count remote images blocked to protect your privacy",
            style = Dot.type.caption, color = c.textSecondary, modifier = Modifier.weight(1f),
        )
        DotButton("Load", onAllow, style = ButtonStyle.Ghost, compact = true)
    }
}

private val URL = Regex("""(https?://[^\s<>"')\]]+)|([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})""")

@Composable
private fun PlainTextBody(text: String, onLink: (String) -> Unit, textScale: Float = 1f, quotedExpanded: Boolean = false) {
    val c = Dot.colors
    val split = remember(text) { QuoteSplitter.split(text) }
    var showQuoted by rememberSaveable(text) { mutableStateOf(quotedExpanded) }
    val bodyStyle = Dot.type.body.let { it.copy(fontSize = it.fontSize * textScale, lineHeight = it.lineHeight * textScale) }
    Column(Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s)) {
        SelectionContainer { Text(linkify(split.visible.trimEnd(), c.accent, onLink), style = bodyStyle, color = c.textPrimary) }
        val quoted = split.quoted
        if (!quoted.isNullOrBlank()) {
            Box(
                Modifier.padding(top = DotSpacing.s).clip(DotShapes.small).background(c.surface)
                    .clickable { showQuoted = !showQuoted }.padding(horizontal = 10.dp, vertical = 2.dp),
            ) { Icon(Icons.Outlined.MoreHoriz, if (showQuoted) "Hide quoted text" else "Show quoted text", tint = c.textSecondary) }
            AnimatedVisibility(showQuoted) {
                SelectionContainer {
                    Text(
                        linkify(quoted.trimEnd(), c.accent, onLink), style = Dot.type.callout, color = c.textSecondary,
                        modifier = Modifier.padding(top = DotSpacing.s),
                    )
                }
            }
        }
    }
}

private fun linkify(text: String, color: androidx.compose.ui.graphics.Color, onLink: (String) -> Unit) = buildAnnotatedString {
    var last = 0
    for (m in URL.findAll(text)) {
        append(text, last, m.range.first)
        val raw = m.value.trimEnd('.', ',', ';', ':', '!', '?')
        val target = if (m.groupValues[2].isNotEmpty()) "mailto:$raw" else raw
        withLink(LinkAnnotation.Clickable(target, TextLinkStyles(SpanStyle(color = color))) { onLink(target) }) { append(raw) }
        last = m.range.first + raw.length
    }
    append(text, last, text.length)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AttachmentStrip(files: List<Attachment>, onOpen: (Attachment, Boolean) -> Unit) {
    val c = Dot.colors
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(DotSpacing.s),
    ) {
        files.forEach { a ->
            Row(
                Modifier.widthIn(max = 240.dp).clip(DotShapes.medium).border(1.dp, c.border, DotShapes.medium)
                    .combinedClickable(onClickLabel = "Open", onLongClickLabel = "Share", onLongClick = { onOpen(a, true) }) { onOpen(a, false) }
                    .padding(horizontal = DotSpacing.m, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (a.mimeType.startsWith("image/")) Icons.Outlined.Image else Icons.Outlined.Description,
                    null, Modifier.size(20.dp), tint = c.textSecondary,
                )
                Spacer(Modifier.width(DotSpacing.s))
                Column {
                    Text(a.fileName, style = Dot.type.label, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Format.size(a.sizeBytes), style = Dot.type.caption, color = c.textTertiary)
                }
            }
        }
    }
}

/** Quiet reply affordance, like a chat composer: a soft field that opens the full composer. */
@Composable
private fun ReplyBar(last: Message, onReply: (Long, ReplyMode) -> Unit, replyAllByDefault: Boolean = false) {
    val c = Dot.colors
    val multiple = (last.to + last.cc).size > 1
    val primaryAll = multiple && replyAllByDefault
    Column(Modifier.background(c.background).navigationBarsPadding()) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DotSpacing.m, vertical = DotSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).clip(DotShapes.large).background(c.surface)
                    .clickable { onReply(last.id, if (primaryAll) ReplyMode.REPLY_ALL else ReplyMode.REPLY) }
                    .padding(horizontal = DotSpacing.l, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Reply, null, Modifier.size(18.dp), tint = c.textTertiary)
                Spacer(Modifier.width(DotSpacing.s))
                Text(
                    if (primaryAll) "Reply to all" else "Reply to ${last.from.displayName.substringBefore(' ')}",
                    style = Dot.type.callout, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (multiple && primaryAll) DotIconButton(Icons.AutoMirrored.Outlined.Reply, "Reply", onClick = { onReply(last.id, ReplyMode.REPLY) })
            else if (multiple) DotIconButton(Icons.AutoMirrored.Outlined.ReplyAll, "Reply all", onClick = { onReply(last.id, ReplyMode.REPLY_ALL) })
            DotIconButton(Icons.AutoMirrored.Outlined.Forward, "Forward", onClick = { onReply(last.id, ReplyMode.FORWARD) })
        }
    }
}

/** Order of the list the user came from, for "after archive: open next/previous". */
object ThreadOrder {
    @Volatile var ids: List<Long> = emptyList()
    fun after(id: Long): Long? = ids.indexOf(id).takeIf { it >= 0 }?.let { ids.getOrNull(it + 1) }
    fun before(id: Long): Long? = ids.indexOf(id).takeIf { it > 0 }?.let { ids.getOrNull(it - 1) }
}
