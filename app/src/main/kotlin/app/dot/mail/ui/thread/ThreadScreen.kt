package app.dot.mail.ui.thread

import androidx.compose.foundation.focusable

import androidx.compose.ui.focus.focusRequester

import androidx.compose.ui.input.key.isShiftPressed

import androidx.compose.ui.input.key.isMetaPressed

import androidx.compose.ui.input.key.isAltPressed

import androidx.compose.ui.input.key.isCtrlPressed

import androidx.compose.ui.input.key.onPreviewKeyEvent

import androidx.compose.ui.input.key.type

import androidx.compose.ui.input.key.key

import androidx.compose.ui.input.key.Key

import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.gestures.awaitEachGesture

import androidx.compose.ui.input.pointer.pointerInput

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
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Snooze
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.input.nestedscroll.nestedScroll
import app.dot.design.components.DotFloatingCapsule
import app.dot.design.components.glassBackdropSource
import app.dot.design.components.DotCapsuleButton
import app.dot.design.components.DotCapsuleDivider
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import app.dot.design.DotMotion
import app.dot.mail.R
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

class ThreadViewModel(
    private val graph: MailGraph,
    private val threadId: Long,
    private val remotePolicy: app.dot.mail.ui.settings.RemoteImagePolicy,
    /** Mailbox the conversation was opened from; moves/archives only touch its messages there. Null = search/notification. */
    private val source: app.dot.mail.data.model.Mailbox? = null,
    /** Application resources: messages built here follow the app language. */
    private val resources: android.content.res.Resources,
) : ViewModel() {
    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any) = resources.getString(id, *args)

    val messages: StateFlow<List<Message>?> =
        graph.mail.thread(threadId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Explicitly toggled messages; default expansion is computed from unread/last. */
    private val _toggled = MutableStateFlow<Map<Long, Boolean>>(emptMap())
    val toggled: StateFlow<Map<Long, Boolean>> = _toggled

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
                .onFailure { _events.send(ThreadEvent.Message(str(R.string.thread_error_download, it.message ?: "").trim())) }
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
            runCatching { @Suppress("DEPRECATION") graph.mail.apply(MessageAction.MARK_READ, listOf(threadId)) }
        }
    }

    /** RFC 8058: a single POST, no cookies, redirects not followed. The sender's list software does the rest. */
    fun unsubscribeOneClick(endpoint: String) {
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val url = java.net.URL(endpoint)
                    require(url.protocol.equals("https", true))
                    (url.openConnection() as java.net.HttpURLConnection).run {
                        requestMethod = "POST"
                        instanceFollowRedirects = false
                        useCaches = false
                        connectTimeout = 15_000; readTimeout = 15_000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        setRequestProperty("User-Agent", "DotMail")
                        outputStream.use { it.write("List-Unsubscribe=One-Click".toByteArray()) }
                        val code = responseCode
                        disconnect()
                        code in 200..299
                    }
                }.getOrDefault(false)
            }
            _events.send(ThreadEvent.Message(str(if (ok) R.string.thread_unsub_done else R.string.thread_unsub_failed)))
        }
    }

    fun move(folderId: Long) {
        viewModelScope.launch {
            runCatching { if (source != null) graph.mail.move(listOf(threadId), folderId, source) else @Suppress("DEPRECATION") graph.mail.move(listOf(threadId), folderId) }
                .onSuccess { _events.send(ThreadEvent.Close) }
                .onFailure { _events.send(ThreadEvent.Message(it.message ?: str(R.string.inbox_error_move_failed))) }
        }
    }

    // Remote-image permissions are persistent (app.dot.mail.ui.settings.RemoteImagePolicy), keyed by account + Message-ID.
    fun allowRemote(m: Message) = remotePolicy.approveMessage(m)
    fun alwaysAllowSender(m: Message) { remotePolicy.addRule(isDomain = false, raw = m.from.email, accountId = m.accountId) }
    fun alwaysAllowDomain(m: Message) { remotePolicy.addRule(isDomain = true, raw = app.dot.mail.ui.settings.RemoteImageMatching.domainOf(m.from.email), accountId = m.accountId) }
    fun stopRemote(m: Message, reason: app.dot.mail.ui.settings.LoadReason) = remotePolicy.stop(m, reason)

    fun setMuted(muted: Boolean) {
        viewModelScope.launch {
            runCatching { graph.mail.setMuted(listOf(threadId), muted) }
                .onSuccess { _events.send(ThreadEvent.Message(str(if (muted) R.string.inbox_muted_toast else R.string.inbox_unmuted_toast))) }
                .onFailure { _events.send(ThreadEvent.Message(it.message ?: str(R.string.inbox_error_mute_failed))) }
        }
    }

    fun snooze(untilMillis: Long) {
        viewModelScope.launch {
            runCatching { graph.mail.snooze(listOf(threadId), untilMillis) }
                .onSuccess { _events.send(ThreadEvent.Close) }
                .onFailure { _events.send(ThreadEvent.Message(it.message ?: str(R.string.inbox_error_snooze_failed))) }
        }
    }

    fun act(action: MessageAction) {
        viewModelScope.launch {
            runCatching {
                if (source != null && action in UNDOABLE) graph.mail.applyUndoable(action, listOf(threadId), source)
                else if (source != null) { graph.mail.apply(action, listOf(threadId), source); null }
                else { @Suppress("DEPRECATION") graph.mail.apply(action, listOf(threadId)); null }
            }
                .onSuccess { token ->
                    if (token != null && token.moves.isNotEmpty()) {
                        val text = when (action) {
                            MessageAction.ARCHIVE -> resources.getQuantityString(R.plurals.inbox_archived_toast, 1, 1)
                            MessageAction.TRASH -> resources.getQuantityString(R.plurals.inbox_trashed_toast, 1, 1)
                            else -> resources.getQuantityString(R.plurals.inbox_spam_toast, 1, 1)
                        }
                        _events.send(ThreadEvent.CloseWithUndo(text, token))
                        return@onSuccess
                    }
                    when (action) {
                        MessageAction.ARCHIVE, MessageAction.TRASH, MessageAction.SPAM, MessageAction.NOT_SPAM,
                        MessageAction.DELETE_FOREVER, MessageAction.MARK_UNREAD -> _events.send(ThreadEvent.Close)
                        else -> Unit
                    }
                }
                .onFailure { _events.send(ThreadEvent.Message(it.message ?: str(R.string.inbox_error_action_failed))) }
        }
    }

    fun openAttachment(a: Attachment, share: Boolean) {
        viewModelScope.launch {
            runCatching { graph.mail.openAttachment(a) }
                .onSuccess { _events.send(ThreadEvent.OpenUri(it, a.mimeType, a.fileName, share)) }
                .onFailure { _events.send(ThreadEvent.Message(str(R.string.thread_error_open_attachment, a.fileName))) }
        }
    }

    private fun emptMap() = emptyMap<Long, Boolean>()

    private companion object {
        val UNDOABLE = setOf(MessageAction.ARCHIVE, MessageAction.TRASH, MessageAction.SPAM)
    }
}

sealed interface ThreadEvent {
    data object Close : ThreadEvent
    /** Close after an undoable move: the inbox shows [text] with an Undo action when the list is what we return to. */
    data class CloseWithUndo(val text: String, val token: app.dot.mail.data.model.UndoToken) : ThreadEvent
    data class Message(val text: String) : ThreadEvent
    data class OpenUri(val uri: String, val mime: String, val name: String, val share: Boolean) : ThreadEvent
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    threadId: Long,
    onBack: () -> Unit,
    source: app.dot.mail.data.model.Mailbox? = null,
    onReply: (messageId: Long, mode: ReplyMode) -> Unit,
    onComposeTo: (String) -> Unit,
    onOpenAdjacent: (Long) -> Unit = {},
) {
    val graph = LocalMailGraph.current
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val vm: ThreadViewModel = viewModel(key = "thread-$threadId") { ThreadViewModel(graph, threadId, app.dot.mail.ui.settings.RemoteImagePolicy.get(appContext), source, appContext.resources) }
    val messages by vm.messages.collectAsStateWithLifecycle()
    val toggled by vm.toggled.collectAsStateWithLifecycle()
    val remotePolicy = remember { app.dot.mail.ui.settings.RemoteImagePolicy.get(appContext) }
    val approvals by remotePolicy.approvals.collectAsStateWithLifecycle()
    val remoteRules by remotePolicy.rules.collectAsStateWithLifecycle()
    val prefs by LocalSettings.current.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var pendingLink by remember { mutableStateOf<String?>(null) }
    // href → visible text of links whose text names another site (collected from each sanitised message).
    val suspiciousLinks = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var confirmUnsubscribe by remember { mutableStateOf<String?>(null) }
    var pdfPreview by remember { mutableStateOf<ThreadEvent.OpenUri?>(null) }
    var menu by remember { mutableStateOf(false) }
    var snoozing by remember { mutableStateOf(false) }
    val muted by remember(graph, threadId) { graph.mail.mutedThreads() }.collectAsStateWithLifecycle(emptySet()).let { s -> androidx.compose.runtime.derivedStateOf { threadId in s.value || (messages?.firstOrNull()?.threadId?.let { it in s.value } == true) } }
    var showMove by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is ThreadEvent.CloseWithUndo -> {
                    val target = when (prefs.afterAction) {
                        app.dot.mail.AfterAction.NEXT -> ThreadOrder.after(threadId)
                        app.dot.mail.AfterAction.PREVIOUS -> ThreadOrder.before(threadId)
                        app.dot.mail.AfterAction.LIST -> null
                    }
                    // Back to the list: it shows the same Undo snackbar as a swipe. Jumping to the next/previous
                    // conversation leaves no list on screen to carry it, so no undo is offered in that case.
                    if (target != null) onOpenAdjacent(target) else { app.dot.mail.ui.inbox.ReaderUndoBus.post(e.text, e.token); onBack() }
                }
                ThreadEvent.Close -> {
                    val target = when (prefs.afterAction) {
                        app.dot.mail.AfterAction.NEXT -> ThreadOrder.after(threadId)
                        app.dot.mail.AfterAction.PREVIOUS -> ThreadOrder.before(threadId)
                        app.dot.mail.AfterAction.LIST -> null
                    }
                    if (target != null) onOpenAdjacent(target) else onBack()
                }
                is ThreadEvent.Message -> snackbar.showSnackbar(e.text)
                is ThreadEvent.OpenUri -> if (!e.share && (e.mime.equals("application/pdf", true) || e.name.endsWith(".pdf", true))) {
                    pdfPreview = e
                } else {
                    val uri = Uri.parse(e.uri)
                    val intent = if (e.share) {
                        Intent(Intent.ACTION_SEND).setType(e.mime).putExtra(Intent.EXTRA_STREAM, uri)
                    } else {
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mime)
                    }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(Intent.createChooser(intent, e.name))
                    } catch (_: ActivityNotFoundException) {
                        scope.launch { snackbar.showSnackbar(context.getString(R.string.thread_no_app_to_open, e.name)) }
                    }
                }
            }
        }
    }
    LaunchedEffect(messages != null) { messages?.let { vm.onOpened(it, prefs.markRead) } }

    val list = messages
    val last = list?.lastOrNull()
    val c = Dot.colors

    // Floating chrome (Back, actions, reply capsules): hides while reading downwards, returns on any scroll up,
    // and is always shown at the very top and at the end of the mail.
    var chromeVisible by remember { mutableStateOf(true) }
    val chromeConnection = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y < -CHROME_SCROLL_THRESHOLD) chromeVisible = false else if (available.y > CHROME_SCROLL_THRESHOLD) chromeVisible = true
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val showChrome = chromeVisible || !listState.canScrollBackward || !listState.canScrollForward
    val reducedMotionChrome = app.dot.design.rememberReducedMotion()
    val chromeTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 60.dp
    val chromeBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 84.dp
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val dotHaptics = app.dot.design.rememberDotHaptics()
    val act: (MessageAction) -> Unit = { dotHaptics.confirm(); vm.act(it) }
    // Hardware keyboard shortcuts (tablets, Chromebooks, DeX), Gmail-style single keys.
    val keyFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keyFocus.requestFocus() } }
    // The message scrolls under the floating capsules; they blur exactly what passes behind them.
    val glass = app.dot.design.components.rememberGlassBackdrop()
    Box(
        Modifier.fillMaxSize().background(c.background)
            .focusRequester(keyFocus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return@onPreviewKeyEvent false
                val target = last
                when (e.key) {
                    Key.R -> { target?.let { onReply(it.id, ReplyMode.REPLY) }; true }
                    Key.A -> { target?.let { onReply(it.id, ReplyMode.REPLY_ALL) }; true }
                    Key.F -> { target?.let { onReply(it.id, ReplyMode.FORWARD) }; true }
                    Key.E -> { act(MessageAction.ARCHIVE); true }
                    Key.Three -> if (e.isShiftPressed) { act(MessageAction.TRASH); true } else false
                    Key.Delete -> { act(MessageAction.TRASH); true }
                    Key.U -> { act(MessageAction.MARK_UNREAD); true }
                    Key.S -> { act(if (list?.any { it.isStarred } == true) MessageAction.UNSTAR else MessageAction.STAR); true }
                    Key.J -> { ThreadOrder.after(threadId)?.let(onOpenAdjacent); true }
                    Key.K -> { ThreadOrder.before(threadId)?.let(onOpenAdjacent); true }
                    Key.Escape -> { onBack(); true }
                    else -> false
                }
            }
            .swipeBetweenConversations(enabled = prefs.swipeBetweenMessages) { forward ->
                val target = if (forward) ThreadOrder.after(threadId) else ThreadOrder.before(threadId)
                if (target != null) {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureEnd)
                    onOpenAdjacent(target)
                }
            },
    ) {
        Column(Modifier.fillMaxSize().nestedScroll(chromeConnection).glassBackdropSource(glass)) {
            when {
                list == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    app.dot.design.components.DotSpinner(size = 22.dp, color = c.textTertiary)
                }
                list.isEmpty() -> Box(Modifier.weight(1f)) {
                    EmptyState(Icons.Outlined.Description, stringResource(R.string.thread_unavailable_title), stringResource(R.string.thread_unavailable_body))
                }
                else -> androidx.compose.runtime.CompositionLocalProvider(LocalZoomScroll provides listState) { LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    // Room for the floating capsules: the message starts below them and its end is never covered.
                    contentPadding = PaddingValues(top = chromeTop, bottom = chromeBottom),
                ) {
                    item(key = "subject") {
                        Text(
                            list.first().subject.ifBlank { stringResource(R.string.inbox_no_subject) },
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
                            remoteReason = app.dot.mail.ui.settings.RemoteImageMatching.reason(m, prefs.loadRemoteImages, approvals, remoteRules),
                            onToggle = { vm.toggle(m, list) },
                            onAllowRemote = { vm.allowRemote(m) },
                            onAlwaysSender = { vm.alwaysAllowSender(m) },
                            onAlwaysDomain = { vm.alwaysAllowDomain(m) },
                            onStopRemote = { reason -> vm.stopRemote(m, reason) },
                            onSuspiciousLinks = { suspiciousLinks.putAll(it) },
                            onLink = { url ->
                                if (url.startsWith("mailto:", ignoreCase = true)) onComposeTo(url)
                                else if (prefs.confirmLinks) pendingLink = url
                                else openUrl(context, app.dot.mail.engine.text.Links.stripTracking(url))
                            },
                            onAttachment = vm::openAttachment,
                            onSender = { onComposeTo(it.email) },
                            readingScale = prefs.readingTextPercent / 100f,
                            readerFont = prefs.readerFont,
                            readerLineHeight = prefs.readerLineHeightPercent,
                            quotedExpanded = prefs.quotedExpanded,
                        )
                        Hairline(Modifier.padding(horizontal = DotSpacing.gutter))
                    }
                } }
            }
        }
        ReaderChrome(
            visible = showChrome, reducedMotion = reducedMotionChrome,
            topScrim = chromeTop, bottomScrim = chromeBottom,
            back = { DotFloatingCapsule(backdrop = glass) { DotCapsuleButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(app.dot.design.R.string.ds_back), onClick = onBack) } },
            actions = {
                DotFloatingCapsule(backdrop = glass) {
                    DotCapsuleButton(Icons.Outlined.Archive, stringResource(R.string.inbox_action_archive), onClick = { act(MessageAction.ARCHIVE) })
                    DotCapsuleDivider()
                    DotCapsuleButton(Icons.Outlined.Delete, stringResource(R.string.inbox_action_delete), onClick = { if (prefs.confirmDelete) confirmTrash = true else act(MessageAction.TRASH) })
                    DotCapsuleDivider()
                    DotCapsuleButton(Icons.Outlined.MarkEmailUnread, stringResource(R.string.inbox_action_mark_unread), onClick = { act(MessageAction.MARK_UNREAD) })
                    DotCapsuleDivider()
                    Box {
                        DotCapsuleButton(Icons.Outlined.MoreVert, stringResource(R.string.thread_more), onClick = { menu = true })
                        DotMenu(menu, { menu = false }) {
                            val starred = list?.any { it.isStarred } == true
                            DotMenuItem(stringResource(R.string.inbox_action_snooze_ellipsis), Icons.Outlined.Snooze) { menu = false; snoozing = true }
                            DotMenuItem(stringResource(if (muted) R.string.thread_unmute_conversation else R.string.thread_mute_conversation), if (muted) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff) { menu = false; vm.setMuted(!muted) }
                            DotMenuItem(stringResource(if (starred) R.string.thread_remove_star else R.string.thread_star), if (starred) Icons.Outlined.StarOutline else Icons.Outlined.Star) {
                                menu = false; act(if (starred) MessageAction.UNSTAR else MessageAction.STAR)
                            }
                            DotMenuItem(stringResource(R.string.inbox_action_report_spam), Icons.Outlined.Report) { menu = false; act(MessageAction.SPAM) }
                            DotMenuItem(stringResource(R.string.inbox_action_not_spam), Icons.Outlined.Inbox) { menu = false; act(MessageAction.NOT_SPAM) }
                            DotMenuItem(stringResource(R.string.inbox_action_move_ellipsis), Icons.AutoMirrored.Outlined.DriveFileMove) { menu = false; showMove = true }
                            DotMenuItem(stringResource(R.string.inbox_action_delete_forever), Icons.Outlined.DeleteForever, color = c.danger) { menu = false; confirmDelete = true }
                            val oneClick = list?.lastOrNull { it.unsubscribeOneClick != null }?.unsubscribeOneClick
                            val target = list?.lastOrNull { it.unsubscribe != null }?.unsubscribe
                            if (oneClick != null || target != null) {
                                DotMenuItem(stringResource(R.string.thread_unsubscribe), Icons.Outlined.Unsubscribe) {
                                    menu = false
                                    when {
                                        oneClick != null -> confirmUnsubscribe = oneClick
                                        target!!.startsWith("mailto:", ignoreCase = true) -> onComposeTo(target)
                                        else -> pendingLink = target
                                    }
                                }
                            }
                        }
                    }
                }
            },
            reply = if (last == null) null else ({
                val multiple = (last.to + last.cc).size > 1
                val allFirst = multiple && prefs.replyAllByDefault
                DotFloatingCapsule(backdrop = glass) {
                    if (allFirst) {
                        DotCapsuleButton(Icons.AutoMirrored.Outlined.ReplyAll, stringResource(R.string.compose_reply_all), onClick = { onReply(last.id, ReplyMode.REPLY_ALL) })
                        DotCapsuleDivider()
                    }
                    DotCapsuleButton(Icons.AutoMirrored.Outlined.Reply, stringResource(R.string.compose_reply), onClick = { onReply(last.id, ReplyMode.REPLY) })
                    if (multiple && !allFirst) {
                        DotCapsuleDivider()
                        DotCapsuleButton(Icons.AutoMirrored.Outlined.ReplyAll, stringResource(R.string.compose_reply_all), onClick = { onReply(last.id, ReplyMode.REPLY_ALL) })
                    }
                    DotCapsuleDivider()
                    DotCapsuleButton(Icons.AutoMirrored.Outlined.Forward, stringResource(R.string.compose_forward), onClick = { onReply(last.id, ReplyMode.FORWARD) })
                }
            }),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp)) { DotSnackbar(it) }
    }

    if (showMove && list != null && list.isNotEmpty()) {
        val folders by graph.mail.folders(list.first().accountId).collectAsStateWithLifecycle(emptyList())
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showMove = false }, containerColor = c.surface, shape = DotShapes.sheet, dragHandle = null,
        ) {
            Text(stringResource(R.string.inbox_move_to), style = Dot.type.title, color = c.textPrimary, modifier = Modifier.padding(DotSpacing.gutter))
            folders.filter { it.id != list.first().folderId }
                .sortedWith(compareBy({ Format.roleOrder.indexOf(it.role).let { i -> if (i < 0) 99 else i } }, { it.path }))
                .forEach { f ->
                    app.dot.design.components.ListRow(
                        title = Format.folderTitle(f.role, f.path),
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
            title = { Text(stringResource(R.string.thread_confirm_trash_title), style = Dot.type.title, color = c.textPrimary) },
            confirmButton = { DotButton(stringResource(R.string.inbox_delete), { confirmTrash = false; act(MessageAction.TRASH) }, style = ButtonStyle.Danger, compact = true) },
            dismissButton = { DotButton(stringResource(R.string.inbox_cancel), { confirmTrash = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, containerColor = c.surface, shape = DotShapes.large,
            title = { Text(stringResource(R.string.thread_confirm_delete_title), style = Dot.type.title, color = c.textPrimary) },
            text = { Text(stringResource(R.string.thread_confirm_delete_body), style = Dot.type.callout, color = c.textSecondary) },
            confirmButton = { DotButton(stringResource(R.string.inbox_delete), { confirmDelete = false; act(MessageAction.DELETE_FOREVER) }, style = ButtonStyle.Danger, compact = true) },
            dismissButton = { DotButton(stringResource(R.string.inbox_cancel), { confirmDelete = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
    if (snoozing) app.dot.mail.ui.snooze.SnoozeSheet(onDismiss = { snoozing = false }, onPick = { until -> snoozing = false; dotHaptics.confirm(); vm.snooze(until) })
    pdfPreview?.let { pdf ->
        fun launch(share: Boolean) {
            val uri = Uri.parse(pdf.uri)
            val intent = (if (share) Intent(Intent.ACTION_SEND).setType(pdf.mime).putExtra(Intent.EXTRA_STREAM, uri)
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, pdf.mime)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { context.startActivity(Intent.createChooser(intent, pdf.name)) }
            catch (_: ActivityNotFoundException) { scope.launch { snackbar.showSnackbar(context.getString(R.string.thread_no_app_to_open, pdf.name)) } }
        }
        app.dot.mail.ui.pdf.PdfViewerDialog(
            uri = pdf.uri, name = pdf.name,
            onDismiss = { pdfPreview = null },
            onShare = { launch(share = true) },
            onOpenExternally = { launch(share = false) },
        )
    }
    confirmUnsubscribe?.let { endpoint ->
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = null },
            containerColor = c.surface,
            shape = DotShapes.large,
            title = { Text(stringResource(R.string.thread_unsub_title), style = Dot.type.title, color = c.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.thread_unsub_body, Uri.parse(endpoint).host.orEmpty()),
                    style = Dot.type.callout, color = c.textSecondary,
                )
            },
            confirmButton = { app.dot.design.components.DotPrimaryButton(stringResource(R.string.thread_unsubscribe), { confirmUnsubscribe = null; vm.unsubscribeOneClick(endpoint) }, compact = true) },
            dismissButton = { DotButton(stringResource(R.string.inbox_cancel), { confirmUnsubscribe = null }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
    pendingLink?.let { url -> LinkDialog(url, suspiciousLinks[url] ?: suspiciousLinks[url.trimEnd('/')] ?: suspiciousLinks.entries.firstOrNull { it.key.trimEnd('/') == url.trimEnd('/') }?.value, onDismiss = { pendingLink = null }, onOpen = { target -> pendingLink = null; openUrl(context, target) }) }
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
private fun LinkDialog(url: String, shownText: String?, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val info = remember(url) { app.dot.mail.engine.text.Links.describe(url) }
    val cleaned = remember(url) { app.dot.mail.engine.text.Links.stripTracking(url) }
    val hasTracking = cleaned != url
    var strip by remember(url) { mutableStateOf(true) }
    val target = if (hasTracking && strip) cleaned else url
    val host = info?.displayHost ?: Uri.parse(url).host.orEmpty()
    val c = Dot.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        shape = DotShapes.large,
        title = { Text(stringResource(R.string.thread_link_title), style = Dot.type.title, color = c.textPrimary) },
        text = {
            Column {
                if (host.isNotEmpty()) {
                    Text(host, style = Dot.type.bodyStrong, color = if (info?.isIdnHomographRisk == true) c.danger else c.textPrimary)
                    Spacer(Modifier.height(DotSpacing.xs))
                }
                Text(target, style = Dot.type.caption, color = c.textSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (shownText != null) {
                    Spacer(Modifier.height(DotSpacing.s))
                    Text(stringResource(R.string.thread_link_mismatch, shownText, host), style = Dot.type.caption, color = c.danger)
                }
                if (info?.isIdnHomographRisk == true) {
                    Spacer(Modifier.height(DotSpacing.s))
                    Text(
                        stringResource(R.string.thread_link_homograph),
                        style = Dot.type.caption, color = c.danger,
                    )
                }
                if (info != null && !info.isHttps) {
                    Spacer(Modifier.height(DotSpacing.s))
                    Text(stringResource(R.string.thread_link_not_encrypted), style = Dot.type.caption, color = c.danger)
                }
                Spacer(Modifier.height(DotSpacing.s))
                Row(horizontalArrangement = Arrangement.spacedBy(DotSpacing.s)) {
                    DotButton(stringResource(R.string.thread_link_copy), {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(target))
                    }, style = ButtonStyle.Ghost, compact = true)
                    DotButton(stringResource(R.string.thread_link_share), {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, target)
                        runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, style = ButtonStyle.Ghost, compact = true)
                }
                if (hasTracking) {
                    Spacer(Modifier.height(DotSpacing.s))
                    Row(
                        Modifier.fillMaxWidth().clip(DotShapes.medium).clickable { strip = !strip }.padding(vertical = DotSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = strip, onCheckedChange = { strip = it },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.textTertiary),
                        )
                        Text(stringResource(R.string.thread_link_strip_tracking), style = Dot.type.callout, color = c.textPrimary)
                    }
                }
            }
        },
        confirmButton = { DotButton(stringResource(R.string.thread_link_open), { onOpen(target) }, compact = true) },
        dismissButton = { DotButton(stringResource(R.string.inbox_cancel), onDismiss, style = ButtonStyle.Ghost, compact = true) },
    )
}

@Composable
private fun MessageCard(
    message: Message,
    expanded: Boolean,
    remoteReason: app.dot.mail.ui.settings.LoadReason,
    onToggle: () -> Unit,
    onAllowRemote: () -> Unit,
    onAlwaysSender: () -> Unit,
    onAlwaysDomain: () -> Unit,
    onStopRemote: (app.dot.mail.ui.settings.LoadReason) -> Unit,
    onLink: (String) -> Unit,
    onSuspiciousLinks: (Map<String, String>) -> Unit = {},
    onAttachment: (Attachment, Boolean) -> Unit,
    onSender: (EmailAddress) -> Unit,
    readingScale: Float = 1f,
    readerFont: app.dot.mail.ReaderFont = app.dot.mail.ReaderFont.INTER,
    readerLineHeight: Int = 155,
    quotedExpanded: Boolean = false,
) {
    val allowRemote = remoteReason != app.dot.mail.ui.settings.LoadReason.NONE
    val c = Dot.colors
    var details by rememberSaveable(message.id) { mutableStateOf(false) }
    val reduceMotion = app.dot.design.rememberReducedMotion()
    Column(Modifier.fillMaxWidth().then(if (reduceMotion) Modifier else Modifier.animateContentSize(tween(220, easing = DotMotion.standard)))) {
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
                        // The name takes all free width so the date sits at the end (two weights split it in half).
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(DotSpacing.s))
                    if (message.isStarred) {
                        Icon(Icons.Outlined.Star, stringResource(R.string.thread_starred), Modifier.size(15.dp), tint = c.star)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(Format.listDate(message.sentAt), style = Dot.type.caption, color = c.textTertiary)
                }
                androidx.compose.animation.Crossfade(expanded, animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else tween(DotMotion.fast), label = "header-line") { open ->
                    if (open) {
                        Text(
                            stringResource(R.string.thread_to_recipients, recipientsSummary(message)),
                            style = Dot.type.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { details = !details },
                        )
                    } else {
                        Text(message.snippet, style = Dot.type.callout, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (!expanded) return@Column
        AnimatedVisibility(details) { HeaderDetails(message, onSender) }
        // OpenPGP: status line + in-memory plaintext from OpenKeychain replaces the ciphertext for display only.
        val pgp = app.dot.mail.ui.pgp.rememberPgpReader(message.id, message.body)
        message.body?.let { if (it.pgp != null) app.dot.mail.ui.pgp.PgpBar(it, pgp) }
        val body = pgp.decrypted ?: message.body?.let { b ->
            // An encrypted PGP/MIME message has no readable part until decrypted; show nothing instead of an empty frame.
            if (b.pgp is app.dot.mail.data.model.PgpContent.Encrypted) b.copy(plainText = b.plainText.orEmpty(), html = null) else b
        }
        if (body == null) {
            Row(Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.l), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.thread_downloading), style = Dot.type.callout, color = c.textTertiary)
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
            androidx.compose.runtime.LaunchedEffect(clean) { clean?.suspiciousLinks?.takeIf { it.isNotEmpty() }?.let(onSuspiciousLinks) }
            if (clean != null && clean.blockedRemoteResources > 0 && !allowRemote) {
                RemoteContentBanner(clean.blockedRemoteResources, message.from, onAllowRemote, onAlwaysSender, onAlwaysDomain)
            } else if (html != null && remoteReason.canStop) {
                RemoteImagesLoadedRow(remoteReason, message.from) { onStopRemote(remoteReason) }
            }
            if (clean != null && clean.trackersBlocked > 0) TrackerNote(clean.trackersBlocked)
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
                    font = readerFont,
                    lineHeightPercent = readerLineHeight,
                    layoutWidth = clean.layoutWidth,
                    responsive = clean.responsive,
                    // Designed (table/newsletter) mail gets the full width like other clients; plain prose keeps our margins.
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = if (clean.layoutWidth > 0 || clean.responsive) DotSpacing.s else DotSpacing.gutter,
                        vertical = DotSpacing.s,
                    ).pinchToZoom(),
                )
            } else {
                PlainTextBody(body.plainText.orEmpty(), onLink, textScale = readingScale, quotedExpanded = quotedExpanded, font = readerFont, lineHeightPercent = readerLineHeight)
            }
            if (body.isTruncated) {
                Text(
                    stringResource(R.string.thread_truncated),
                    style = Dot.type.caption, color = c.textTertiary,
                    modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs),
                )
            }
            val files = message.attachments.filter { !it.isInline || it.contentId == null }
            CalendarInvites(message)
            if (files.isNotEmpty()) AttachmentStrip(files, onAttachment)
        }
        Spacer(Modifier.height(DotSpacing.m))
    }
}

@Composable
private fun recipientsSummary(m: Message): String {
    val all = m.to + m.cc
    if (all.isEmpty()) return stringResource(R.string.thread_undisclosed_recipients)
    val first = all.take(2).joinToString(", ") { it.displayName }
    return if (all.size > 2) stringResource(R.string.thread_recipients_more, first, all.size - 2) else first
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
        line(stringResource(R.string.compose_from), fmt(m.from), onClick = { onSender(m.from) })
        if (m.replyTo.isNotEmpty() && m.replyTo != listOf(m.from)) line(stringResource(R.string.thread_header_reply_to), m.replyTo.joinToString(", ") { fmt(it) })
        if (m.to.isNotEmpty()) line(stringResource(R.string.compose_to), m.to.joinToString(", ") { fmt(it) })
        if (m.cc.isNotEmpty()) line(stringResource(R.string.compose_cc), m.cc.joinToString(", ") { fmt(it) })
        if (m.bcc.isNotEmpty()) line(stringResource(R.string.compose_bcc), m.bcc.joinToString(", ") { fmt(it) })
        line(stringResource(R.string.thread_header_date), Format.messageDate(m.sentAt))
    }
}

private fun fmt(a: EmailAddress) = if (a.name.isNullOrBlank()) a.email else "${a.name} <${a.email}>"

private val app.dot.mail.ui.settings.LoadReason.canStop: Boolean
    get() = this == app.dot.mail.ui.settings.LoadReason.MESSAGE || this == app.dot.mail.ui.settings.LoadReason.SENDER || this == app.dot.mail.ui.settings.LoadReason.DOMAIN

/** One quiet line: how many open-tracking pixels were removed from this message. */
@Composable
private fun TrackerNote(count: Int) {
    val c = Dot.colors
    Row(Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Shield, null, Modifier.size(14.dp), tint = c.textTertiary)
        Spacer(Modifier.width(DotSpacing.xs))
        Text(pluralStringResource(R.plurals.thread_trackers_blocked, count, count), style = Dot.type.caption, color = c.textTertiary)
    }
}

@Composable
private fun RemoteContentBanner(
    count: Int,
    sender: EmailAddress,
    onAllow: () -> Unit,
    onAlwaysSender: () -> Unit,
    onAlwaysDomain: () -> Unit,
) {
    val c = Dot.colors
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Boolean?>(null) } // true = whole domain, false = this address
    val domain = app.dot.mail.ui.settings.RemoteImageMatching.domainOf(sender.email)
    val offerDomain = domain.isNotEmpty() && !app.dot.mail.ui.avatar.FreeMail.isFreeMail(domain)
    Row(
        Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s).fillMaxWidth()
            .clip(DotShapes.medium).border(1.dp, c.border, DotShapes.medium)
            .padding(start = DotSpacing.m, end = DotSpacing.xs, top = DotSpacing.xs, bottom = DotSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Shield, null, Modifier.size(18.dp), tint = c.textSecondary)
        Spacer(Modifier.width(DotSpacing.s))
        Text(
            pluralStringResource(R.plurals.thread_remote_blocked, count, count),
            style = Dot.type.caption, color = c.textSecondary, modifier = Modifier.weight(1f),
        )
        DotButton(stringResource(R.string.thread_load_images), onAllow, style = ButtonStyle.Ghost, compact = true)
        Box {
            DotIconButton(Icons.Outlined.MoreVert, stringResource(R.string.thread_remote_more), onClick = { menu = true })
            DotMenu(menu, { menu = false }) {
                app.dot.design.components.DotMenuItem(stringResource(R.string.thread_always_for, sender.email), null) { menu = false; confirm = false }
                if (offerDomain) app.dot.design.components.DotMenuItem(stringResource(R.string.thread_always_for, "@$domain"), null) { menu = false; confirm = true }
            }
        }
    }
    confirm?.let { wholeDomain ->
        val target = if (wholeDomain) "@$domain" else sender.email
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = c.surface,
            shape = DotShapes.large,
            title = { Text(stringResource(R.string.thread_remote_confirm_title, target), style = Dot.type.title, color = c.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.thread_remote_confirm_body),
                    style = Dot.type.callout, color = c.textSecondary,
                )
            },
            confirmButton = {
                DotButton(stringResource(R.string.thread_remote_confirm_action), compact = true, onClick = { confirm = null; if (wholeDomain) onAlwaysDomain() else onAlwaysSender() })
            },
            dismissButton = { DotButton(stringResource(R.string.inbox_cancel), { confirm = null }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
}

/** Quiet note while remembered images are showing; "Stop" revokes the message approval or the sender / domain rule. */
@Composable
private fun RemoteImagesLoadedRow(reason: app.dot.mail.ui.settings.LoadReason, sender: EmailAddress, onStop: () -> Unit) {
    val c = Dot.colors
    val stopLabel = stringResource(R.string.thread_stop_label)
    val why = when (reason) {
        app.dot.mail.ui.settings.LoadReason.SENDER -> stringResource(R.string.thread_images_loaded_always_for, sender.email)
        app.dot.mail.ui.settings.LoadReason.DOMAIN -> stringResource(R.string.thread_images_loaded_always_for, "@" + app.dot.mail.ui.settings.RemoteImageMatching.domainOf(sender.email))
        else -> stringResource(R.string.thread_images_loaded_this_message)
    }
    Row(
        Modifier.padding(horizontal = DotSpacing.gutter).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.thread_images_loaded, why), style = Dot.type.caption, color = c.textTertiary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.defaultMinSize(minHeight = 48.dp).clip(DotShapes.small).clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = stopLabel) { onStop() }.padding(horizontal = DotSpacing.m), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.thread_stop), style = Dot.type.label, color = c.textSecondary)
        }
    }
}

private val URL = Regex("""(https?://[^\s<>"')\]]+)|([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})""")

@Composable
private fun PlainTextBody(text: String, onLink: (String) -> Unit, textScale: Float = 1f, quotedExpanded: Boolean = false, font: app.dot.mail.ReaderFont = app.dot.mail.ReaderFont.INTER, lineHeightPercent: Int = 155) {
    val c = Dot.colors
    val split = remember(text) { QuoteSplitter.split(text) }
    var showQuoted by rememberSaveable(text) { mutableStateOf(quotedExpanded) }
    val bodyStyle = readerTextStyle(Dot.type.body, textScale, font, lineHeightPercent)
    Column(Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s)) {
        SelectionContainer { Text(linkify(split.visible.trimEnd(), c.accent, onLink), style = bodyStyle, color = c.textPrimary) }
        val quoted = split.quoted
        if (!quoted.isNullOrBlank()) {
            Box(
                Modifier.padding(top = DotSpacing.s).minimumInteractiveComponentSize().clip(DotShapes.small).background(c.surface)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) { showQuoted = !showQuoted }.padding(horizontal = 10.dp, vertical = 2.dp),
            ) { Icon(Icons.Outlined.MoreHoriz, stringResource(if (showQuoted) R.string.compose_hide_quoted else R.string.compose_show_quoted), tint = c.textSecondary) }
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

/**
 * Calendar invitations carried as text/calendar parts (or .ics files): parsed locally and shown as a card with
 * "Add to calendar" (system Calendar insert intent, no permission) and, when one of my addresses is invited,
 * Accept / Maybe / Decline (iTIP REPLY to the organiser only, 5 s undo).
 */
@Composable
private fun CalendarInvites(message: Message) {
    val parts = remember(message.attachments) {
        message.attachments.filter { it.mimeType.startsWith("text/calendar", true) || it.fileName.endsWith(".ics", true) }
    }
    if (parts.isEmpty()) return
    val graph = LocalMailGraph.current
    val context = LocalContext.current
    val invites by androidx.compose.runtime.produceState(emptyList<app.dot.mail.engine.calendar.Ics.CalendarInvite>(), parts) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            parts.flatMap { a ->
                runCatching {
                    val uri = Uri.parse(graph.mail.openAttachment(a))
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(512 * 1024).toByteArray().decodeToString() }.orEmpty()
                    app.dot.mail.engine.calendar.Ics.parse(text)
                }.getOrDefault(emptyList())
            }.distinctBy { it.uid to it.sequence }
        }
    }
    val use24h = remember { android.text.format.DateFormat.is24HourFormat(context) }
    // Which of my addresses (account + aliases) the invitation is addressed to: only then can I answer it.
    val accounts by graph.accounts.accounts.collectAsStateWithLifecycle(emptyList())
    val mine = remember(accounts, message.accountId) {
        accounts.firstOrNull { it.id == message.accountId }?.let { a -> (a.identities.map { it.email } + a.email).map { it.lowercase() }.toSet() }.orEmpty()
    }
    val scope = rememberCoroutineScope()
    val replyFailed = stringResource(R.string.thread_error_reply_failed)
    val tooLate = stringResource(R.string.thread_reply_too_late)
    var responding by remember(message.id) { mutableStateOf(false) }
    var answered by remember(message.id) { mutableStateOf<app.dot.mail.engine.calendar.Ics.PartStat?>(null) }
    var undoable by remember(message.id) { mutableStateOf<Long?>(null) }
    var error by remember(message.id) { mutableStateOf<String?>(null) }
    invites.forEach { invite ->
        val myEmail = invite.attendees.firstOrNull { it.email.lowercase() in mine }?.email
        app.dot.mail.ui.calendar.InviteCard(
            invite = invite,
            myEmail = myEmail,
            responding = responding,
            myResponse = answered,
            onRespond = { partstat ->
                responding = true; error = null
                scope.launch {
                    runCatching { graph.compose.sendCalendarReply(message.id, partstat) }
                        .onSuccess { outboxId ->
                            answered = partstat; undoable = outboxId
                            kotlinx.coroutines.delay(5_000)
                            if (undoable == outboxId) undoable = null
                        }
                        .onFailure { error = it.message ?: replyFailed }
                    responding = false
                }
            },
            onAddToCalendar = {
                try { context.startActivity(app.dot.mail.ui.calendar.insertEventIntent(invite, context.getString(R.string.calendar_event_fallback))) }
                catch (_: ActivityNotFoundException) { }
            },
            use24h = use24h,
            modifier = Modifier.fillMaxWidth().padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
        )
    }
    val c = Dot.colors
    undoable?.let { outboxId ->
        Row(Modifier.padding(horizontal = DotSpacing.gutter), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.thread_reply_sending), style = Dot.type.caption, color = c.textTertiary, modifier = Modifier.weight(1f))
            DotButton(stringResource(R.string.thread_undo), {
                scope.launch { if (graph.compose.cancelSend(outboxId)) { answered = null } else error = tooLate; undoable = null }
            }, style = ButtonStyle.Ghost, compact = true)
        }
    }
    error?.let { Text(it, style = Dot.type.caption, color = c.danger, modifier = Modifier.padding(horizontal = DotSpacing.gutter)) }
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
                    .combinedClickable(onClickLabel = stringResource(R.string.thread_open), onLongClickLabel = stringResource(R.string.thread_share), onLongClick = { onOpen(a, true) }) { onOpen(a, false) }
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

/** Order of the list the user came from, for "after archive: open next/previous". */
/**
 * Optional sideways swipe to the next (leftwards) / previous (rightwards) conversation. It only *observes* touches
 * (Initial pass, nothing consumed) so scrolling, text selection, links and the HTML view behave as before. It ignores
 * gestures that start in the system Back edge zones, multi-touch (pinch), slow drags and anything mostly vertical.
 */
private fun Modifier.swipeBetweenConversations(enabled: Boolean, onSwipe: (forward: Boolean) -> Unit): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        val edge = 24.dp.toPx()
        val distance = 96.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            if (down.position.x < edge || down.position.x > size.width - edge) return@awaitEachGesture
            val start = down.position
            val startedAt = down.uptimeMillis
            var end = start
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (event.changes.size > 1) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                end = change.position
                if (!change.pressed) {
                    val dx = end.x - start.x
                    val dy = end.y - start.y
                    val quick = change.uptimeMillis - startedAt < 700
                    if (quick && kotlin.math.abs(dx) > distance && kotlin.math.abs(dx) > 2.5f * kotlin.math.abs(dy)) onSwipe(dx < 0)
                    return@awaitEachGesture
                }
            }
        }
    }

object ThreadOrder {
    @Volatile var ids: List<Long> = emptyList()
    fun after(id: Long): Long? = ids.indexOf(id).takeIf { it >= 0 }?.let { ids.getOrNull(it + 1) }
    fun before(id: Long): Long? = ids.indexOf(id).takeIf { it > 0 }?.let { ids.getOrNull(it - 1) }
}

private const val CHROME_SCROLL_THRESHOLD = 6f

/**
 * Soft frosted capsules floating over the message: Back (top left), one grouped action capsule (top right) and one
 * reply capsule (bottom centre). They fade and slide away while scrolling down and return on scroll up (reduced
 * motion: they just appear / disappear). Thin scrims keep status-bar icons and text legible under them.
 */
@Composable
private fun BoxScope.ReaderChrome(
    visible: Boolean,
    reducedMotion: Boolean,
    topScrim: androidx.compose.ui.unit.Dp,
    bottomScrim: androidx.compose.ui.unit.Dp,
    back: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    reply: (@Composable () -> Unit)?,
) {
    val c = Dot.colors
    val enter = { fromTop: Boolean ->
        if (reducedMotion) androidx.compose.animation.EnterTransition.None
        else androidx.compose.animation.fadeIn(tween(DotMotion.fast)) +
            androidx.compose.animation.slideInVertically(tween(DotMotion.medium, easing = DotMotion.decelerate)) { if (fromTop) -it / 2 else it / 2 }
    }
    val exit = { toTop: Boolean ->
        if (reducedMotion) androidx.compose.animation.ExitTransition.None
        else androidx.compose.animation.fadeOut(tween(DotMotion.fast)) +
            androidx.compose.animation.slideOutVertically(tween(DotMotion.fast, easing = DotMotion.accelerate)) { if (toTop) -it / 2 else it / 2 }
    }
    // Always: the status bar area never shows message content underneath (clock and icons stay readable over images),
    // even while the capsules are hidden. Solid up to the bar's bottom edge, then a short fade.
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val fade = 10.dp
    Box(
        Modifier.align(Alignment.TopCenter).fillMaxWidth().height(statusBar + fade).background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to c.background, (statusBar / (statusBar + fade)).coerceIn(0f, 1f) to c.background, 1f to c.background.copy(alpha = 0f),
            ),
        ),
    )
    // Scrims only make sense while the capsules are there; they are plain gradients of the page colour.
    AnimatedVisibility(visible, Modifier.align(Alignment.TopCenter), enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
        Box(Modifier.fillMaxWidth().height(topScrim - 8.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.background, c.background.copy(alpha = 0f)))))
    }
    AnimatedVisibility(visible, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = DotSpacing.s, top = DotSpacing.xs), enter = enter(true), exit = exit(true)) { back() }
    AnimatedVisibility(visible, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = DotSpacing.s, top = DotSpacing.xs), enter = enter(true), exit = exit(true)) { actions() }
    if (reply != null) {
        AnimatedVisibility(visible, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = DotSpacing.m), enter = enter(false), exit = exit(false)) { reply() }
    }
}

/** The reader's plain-text style: size scale, typeface and line height from Settings. */
internal fun readerTextStyle(base: androidx.compose.ui.text.TextStyle, scale: Float, font: app.dot.mail.ReaderFont, lineHeightPercent: Int): androidx.compose.ui.text.TextStyle {
    val size = base.fontSize * scale
    return base.copy(
        fontSize = size,
        lineHeight = size * (lineHeightPercent.coerceIn(120, 200) / 100f),
        fontFamily = when (font) {
            app.dot.mail.ReaderFont.INTER -> base.fontFamily
            app.dot.mail.ReaderFont.SYSTEM -> androidx.compose.ui.text.font.FontFamily.Default
            app.dot.mail.ReaderFont.SERIF -> androidx.compose.ui.text.font.FontFamily.Serif
        },
    )
}
