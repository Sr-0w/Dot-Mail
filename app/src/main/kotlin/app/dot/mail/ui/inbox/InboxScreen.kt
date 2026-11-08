package app.dot.mail.ui.inbox

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.Avatar
import app.dot.design.components.DotIconButton
import app.dot.design.components.DotSnackbar
import app.dot.design.components.DotTopBar
import app.dot.design.components.EmptyState
import app.dot.design.components.ListRow
import app.dot.design.components.UnreadDot
import app.dot.mail.Density
import app.dot.mail.LocalMailGraph
import app.dot.mail.LocalSettings
import app.dot.mail.SwipeAction
import app.dot.mail.data.model.FolderRole
import app.dot.mail.data.model.MessageAction
import app.dot.mail.data.model.ThreadSummary
import app.dot.mail.ui.common.Format
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpenThread: (Long) -> Unit,
    onCompose: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onAddAccount: () -> Unit,
    onOpenDraft: (Long) -> Unit,
    onOutbox: () -> Unit,
) {
    val graph = LocalMailGraph.current
    val vm: InboxViewModel = viewModel { InboxViewModel(graph, createSavedStateHandle()) }
    val prefs by LocalSettings.current.ui.collectAsStateWithLifecycle()
    val mailbox by vm.mailbox.collectAsStateWithLifecycle()
    val header by vm.header.collectAsStateWithLifecycle()
    val drawer by vm.drawer.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val threads = vm.threads.collectAsLazyPagingItems()
    val outbox by graph.compose.outbox.collectAsStateWithLifecycle(emptyList())
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var showMove by remember { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()

    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
    // Back closes the selection first.
    androidx.activity.compose.BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = selection.isEmpty(),
        scrimColor = Dot.colors.scrim,
        drawerContent = {
            MailDrawer(
                state = drawer,
                current = mailbox,
                onOpen = { box -> vm.open(box); scope.launch { drawerState.close(); listState.scrollToItem(0) } },
                onFocusAccount = vm::focusAccount,
                onAddAccount = { scope.launch { drawerState.close() }; onAddAccount() },
                onSettings = { scope.launch { drawerState.close() }; onSettings() },
                outboxCount = outbox.size,
                outboxNeedsAttention = outbox.any { it.state == app.dot.mail.data.model.OutboxState.NEEDS_REVIEW || it.error != null },
                onOutbox = { scope.launch { drawerState.close() }; onOutbox() },
            )
        },
    ) {
        Box(Modifier.fillMaxSize().background(Dot.colors.background)) {
            Column(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = selection.isNotEmpty(),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "topbar",
                ) { selecting ->
                    if (selecting) {
                        SelectionBar(
                            count = selection.size,
                            role = header.role,
                            onClose = vm::clearSelection,
                            onAction = { vm.act(it) },
                            onMove = { showMove = true },
                        )
                    } else {
                        DotTopBar(
                            title = header.title,
                            subtitle = header.subtitle,
                            elevated = listState.canScrollBackward,
                            navigation = { DotIconButton(Icons.Outlined.Menu, "Open mailboxes", onClick = { scope.launch { drawerState.open() } }) },
                            actions = { DotIconButton(Icons.Outlined.Search, "Search", onClick = onSearch) },
                        )
                    }
                }
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = vm::refresh,
                    modifier = Modifier.weight(1f),
                    state = pullState,
                    indicator = {
                        PullToRefreshDefaults.Indicator(
                            state = pullState, isRefreshing = refreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                            containerColor = Dot.colors.surfaceRaised, color = Dot.colors.textPrimary,
                        )
                    },
                ) {
                    ThreadList(
                        threads = threads,
                        listState = listState,
                        selection = selection,
                        density = prefs.density,
                        swipeStart = prefs.swipeStart,
                        swipeEnd = prefs.swipeEnd,
                        role = header.role,
                        onOpen = { t ->
                            when {
                                selection.isNotEmpty() -> vm.toggleSelection(t.threadId)
                                header.role == FolderRole.DRAFTS -> onOpenDraft(t.threadId)
                                else -> onOpenThread(t.threadId)
                            }
                        },
                        onToggleSelect = vm::toggleSelection,
                        onSwipe = { action, t -> vm.act(action, listOf(t.threadId)) },
                    )
                }
            }
            ComposeButton(
                expanded = !listState.canScrollBackward,
                onClick = onCompose,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(DotSpacing.xl),
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 76.dp)) { DotSnackbar(it) }
        }
    }

    if (showMove) {
        ModalBottomSheet(
            onDismissRequest = { showMove = false },
            containerColor = Dot.colors.surface,
            shape = DotShapes.sheet,
            dragHandle = null,
        ) {
            Text("Move to", style = Dot.type.title, color = Dot.colors.textPrimary, modifier = Modifier.padding(DotSpacing.gutter))
            drawer.folders.sortedWith(compareBy({ Format.roleOrder.indexOf(it.role).let { i -> if (i < 0) 99 else i } }, { it.path })).forEach { f ->
                ListRow(
                    title = if (f.role == FolderRole.CUSTOM) f.path else Format.roleName(f.role),
                    icon = Format.roleIcon(f.role),
                    onClick = { showMove = false; vm.moveSelection(f.id) },
                )
            }
            Spacer(Modifier.navigationBarsPadding().height(DotSpacing.l))
        }
    }
}

@Composable
private fun ThreadList(
    threads: LazyPagingItems<ThreadSummary>,
    listState: LazyListState,
    selection: Set<Long>,
    density: Density,
    swipeStart: SwipeAction,
    swipeEnd: SwipeAction,
    role: FolderRole?,
    onOpen: (ThreadSummary) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onSwipe: (MessageAction, ThreadSummary) -> Unit,
) {
    val refresh = threads.loadState.refresh
    when {
        threads.itemCount == 0 && refresh is LoadState.Loading -> SkeletonList()
        threads.itemCount == 0 && refresh is LoadState.Error -> EmptyState(
            Icons.Outlined.WifiOff, "Couldn't load mail", refresh.error.message ?: "Check your connection and pull to retry.",
        )
        threads.itemCount == 0 -> LazyColumn(Modifier.fillMaxSize()) {
            // Must be scrollable for pull-to-refresh to work on an empty list.
            item { EmptyFor(role) }
        }
        else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 112.dp),
        ) {
            items(threads.itemCount, key = threads.itemKey { it.threadId }, contentType = { "thread" }) { index ->
                val t = threads[index] ?: return@items
                SwipeableRow(
                    thread = t,
                    swipeStart = swipeStart,
                    swipeEnd = swipeEnd,
                    enabled = selection.isEmpty(),
                    onSwipe = onSwipe,
                ) {
                    ThreadRow(
                        thread = t,
                        selected = t.threadId in selection,
                        selectionMode = selection.isNotEmpty(),
                        compact = density == Density.COMPACT,
                        onClick = { onOpen(t) },
                        onLongClick = { onToggleSelect(t.threadId) },
                    )
                }
            }
            if (threads.loadState.append is LoadState.Loading) item { SkeletonRow() }
        }
    }
}

@Composable
private fun EmptyFor(role: FolderRole?) {
    val (title, msg) = when (role) {
        FolderRole.INBOX, null -> "All caught up" to "New mail will appear here."
        FolderRole.STARRED -> "No starred mail" to "Star a conversation to keep it close."
        FolderRole.DRAFTS -> "No drafts" to "Unsent messages are saved here automatically."
        FolderRole.SENT -> "Nothing sent yet" to "Messages you send appear here."
        FolderRole.SPAM -> "No spam" to "Suspicious mail lands here."
        FolderRole.TRASH -> "Trash is empty" to "Deleted conversations stay here for a while."
        else -> "Nothing here" to "This folder is empty."
    }
    EmptyState(if (role == FolderRole.INBOX || role == null) Icons.Outlined.Inbox else Format.roleIcon(role), title, msg)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRow(
    thread: ThreadSummary,
    swipeStart: SwipeAction,
    swipeEnd: SwipeAction,
    enabled: Boolean,
    onSwipe: (MessageAction, ThreadSummary) -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            val action = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> swipeStart
                SwipeToDismissBoxValue.EndToStart -> swipeEnd
                SwipeToDismissBoxValue.Settled -> null
            }
            if (action != null) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                toMessageAction(action, thread)?.let { onSwipe(it, thread) }
            }
            // Never stay dismissed: the row disappears when the DB updates (archive/trash)
            // or springs back for toggles (read/star).
            false
        },
        positionalThreshold = { it * 0.35f },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = enabled && swipeStart != SwipeAction.NONE,
        enableDismissFromEndToStart = enabled && swipeEnd != SwipeAction.NONE,
        backgroundContent = {
            val dir = state.dismissDirection
            val action = if (dir == SwipeToDismissBoxValue.StartToEnd) swipeStart else swipeEnd
            val armed = state.targetValue != SwipeToDismissBoxValue.Settled
            val bg by animateColorAsState(if (armed) Dot.colors.surfaceRaised else Dot.colors.surface, label = "swipe-bg")
            val scale by animateFloatAsState(if (armed) 1.12f else 0.9f, label = "swipe-scale")
            Box(
                Modifier.fillMaxSize().background(bg).padding(horizontal = 28.dp),
                contentAlignment = if (dir == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                val (icon, label) = swipeVisual(action, thread)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, label, Modifier.size((22 * scale).dp), tint = if (action == SwipeAction.TRASH && armed) Dot.colors.danger else Dot.colors.textPrimary)
                }
            }
        },
    ) { content() }
}

private fun toMessageAction(action: SwipeAction, t: ThreadSummary): MessageAction? = when (action) {
    SwipeAction.ARCHIVE -> MessageAction.ARCHIVE
    SwipeAction.TRASH -> MessageAction.TRASH
    SwipeAction.READ_TOGGLE -> if (t.isUnread) MessageAction.MARK_READ else MessageAction.MARK_UNREAD
    SwipeAction.STAR_TOGGLE -> if (t.isStarred) MessageAction.UNSTAR else MessageAction.STAR
    SwipeAction.NONE -> null
}

private fun swipeVisual(action: SwipeAction, t: ThreadSummary): Pair<ImageVector, String> = when (action) {
    SwipeAction.ARCHIVE -> Icons.Outlined.Archive to "Archive"
    SwipeAction.TRASH -> Icons.Outlined.Delete to "Delete"
    SwipeAction.READ_TOGGLE -> (if (t.isUnread) Icons.Outlined.MarkEmailRead else Icons.Outlined.MarkEmailUnread) to "Toggle read"
    SwipeAction.STAR_TOGGLE -> (if (t.isStarred) Icons.Outlined.StarOutline else Icons.Outlined.Star) to "Toggle star"
    SwipeAction.NONE -> Icons.Outlined.Mail to ""
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreadRow(
    thread: ThreadSummary,
    selected: Boolean,
    selectionMode: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = Dot.colors
    val haptics = LocalHapticFeedback.current
    val bg by animateColorAsState(if (selected) c.surfaceRaised else c.background, label = "row-bg")
    val unread = thread.isUnread
    val sender = senderLine(thread)
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
                onLongClickLabel = "Select",
            )
            .semantics(mergeDescendants = true) {
                stateDescription = buildString {
                    if (unread) append("Unread. ")
                    if (thread.isStarred) append("Starred. ")
                    if (thread.hasAttachments) append("Has attachments. ")
                    if (selected) append("Selected.")
                }
            }
            .padding(start = 6.dp, end = DotSpacing.gutter, top = if (compact) 10.dp else 14.dp, bottom = if (compact) 10.dp else 14.dp),
        verticalAlignment = if (compact) Alignment.CenterVertically else Alignment.Top,
    ) {
        // Unread marker lives in the left gutter so text columns stay aligned.
        Box(Modifier.width(14.dp).padding(top = if (compact) 0.dp else 16.dp), contentAlignment = Alignment.Center) {
            if (unread) UnreadDot(size = 7.dp)
        }
        if (!compact) {
            Box(Modifier.clip(CircleShape).combinedClickable(onClick = onLongClick)) {
                AnimatedContent(selected || selectionMode, label = "avatar") { sel ->
                    if (sel) SelectCircle(selected)
                    else {
                        val first = thread.participants.lastOrNull()
                        Avatar(first?.displayName ?: "?", first?.email ?: "?")
                    }
                }
            }
            Spacer(Modifier.width(DotSpacing.m))
        } else if (selectionMode) {
            SelectCircle(selected, size = 24)
            Spacer(Modifier.width(DotSpacing.m))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sender,
                    style = Dot.type.headline.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal),
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (thread.messageCount > 1) {
                    Spacer(Modifier.width(6.dp))
                    Text(thread.messageCount.toString(), style = Dot.type.caption, color = c.textTertiary)
                }
                Spacer(Modifier.weight(1f).width(DotSpacing.s))
                if (thread.hasAttachments) {
                    Icon(Icons.Outlined.Attachment, null, Modifier.size(15.dp), tint = c.textTertiary)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    Format.listDate(thread.lastMessageAt),
                    style = Dot.type.caption.copy(fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal),
                    color = if (unread) c.textPrimary else c.textTertiary,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    thread.subject.ifBlank { "(no subject)" },
                    style = Dot.type.callout.copy(fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal),
                    color = if (unread) c.textPrimary else c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (thread.isStarred) {
                    Spacer(Modifier.width(DotSpacing.s))
                    Icon(Icons.Outlined.Star, "Starred", Modifier.size(16.dp), tint = c.star)
                }
            }
            if (!compact && thread.snippet.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(thread.snippet, style = Dot.type.callout, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun senderLine(t: ThreadSummary): String {
    val names = t.participants.map { it.displayName.substringBefore(' ').ifBlank { it.displayName } }.distinct()
    return when {
        names.isEmpty() -> "(unknown sender)"
        t.participants.size == 1 -> t.participants.first().displayName
        names.size <= 3 -> names.joinToString(", ")
        else -> names.first() + " … " + names.takeLast(2).joinToString(", ")
    }
}

@Composable
private fun SelectCircle(selected: Boolean, size: Int = 40) {
    val c = Dot.colors
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (selected) c.inverse else c.surfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Outlined.Check, "Selected", Modifier.size((size / 2).dp), tint = c.onInverse)
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    role: FolderRole?,
    onClose: () -> Unit,
    onAction: (MessageAction) -> Unit,
    onMove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    DotTopBar(
        title = "$count selected",
        elevated = true,
        navigation = { DotIconButton(Icons.Outlined.Close, "Clear selection", onClick = onClose) },
        actions = {
            if (role != FolderRole.ARCHIVE) DotIconButton(Icons.Outlined.Archive, "Archive", onClick = { onAction(MessageAction.ARCHIVE) })
            if (role == FolderRole.TRASH) DotIconButton(Icons.Outlined.Delete, "Delete forever", onClick = { onAction(MessageAction.DELETE_FOREVER) })
            else DotIconButton(Icons.Outlined.Delete, "Delete", onClick = { onAction(MessageAction.TRASH) })
            DotIconButton(Icons.Outlined.MarkEmailRead, "Mark as read", onClick = { onAction(MessageAction.MARK_READ) })
            Box {
                DotIconButton(Icons.Outlined.MoreVert, "More actions", onClick = { menu = true })
                DotMenu(menu, onDismiss = { menu = false }) {
                    MenuItem("Mark as unread", Icons.Outlined.MarkEmailUnread) { menu = false; onAction(MessageAction.MARK_UNREAD) }
                    MenuItem("Star", Icons.Outlined.StarOutline) { menu = false; onAction(MessageAction.STAR) }
                    MenuItem("Move to…", Icons.Outlined.DriveFileMove) { menu = false; onMove() }
                    if (role == FolderRole.SPAM) MenuItem("Not spam", Icons.Outlined.Inbox) { menu = false; onAction(MessageAction.NOT_SPAM) }
                    else MenuItem("Report spam", Icons.Outlined.Report) { menu = false; onAction(MessageAction.SPAM) }
                }
            }
        },
    )
}

@Composable
fun DotMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = Dot.colors.surface,
        shape = DotShapes.medium,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(DotSpacing.hairline * 2, Dot.colors.border),
    ) { content() }
}

@Composable
fun MenuItem(text: String, icon: ImageVector?, color: Color = Dot.colors.textPrimary, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = Dot.type.callout, color = color) },
        leadingIcon = icon?.let { { Icon(it, null, Modifier.size(20.dp), tint = Dot.colors.textSecondary) } },
        onClick = onClick,
    )
}

/** Inverted rounded-square compose button; the label folds away once the list scrolls. */
@Composable
private fun ComposeButton(expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Dot.colors
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .height(54.dp)
            .clip(DotShapes.large)
            .background(c.inverse)
            .combinedClickable(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() })
            .semantics { contentDescription = "Compose" }
            .padding(horizontal = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Edit, null, Modifier.size(20.dp), tint = c.onInverse)
        AnimatedVisibility(expanded, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
            Text("Compose", style = Dot.type.label, color = c.onInverse, modifier = Modifier.padding(start = 10.dp, end = 2.dp))
        }
    }
}

@Composable
private fun SkeletonList() {
    Column(Modifier.fillMaxSize()) { repeat(8) { SkeletonRow() } }
}

@Composable
private fun SkeletonRow() {
    val c = Dot.colors
    val pulse by rememberInfiniteTransition(label = "sk").animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "sk-a",
    )
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = DotSpacing.gutter, top = 16.dp, bottom = 16.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.surface.copy(alpha = pulse)))
        Spacer(Modifier.width(DotSpacing.m))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.4f).height(12.dp).clip(DotShapes.small).background(c.surface.copy(alpha = pulse)))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth(0.8f).height(10.dp).clip(DotShapes.small).background(c.surface.copy(alpha = pulse)))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.6f).height(10.dp).clip(DotShapes.small).background(c.surface.copy(alpha = pulse)))
        }
    }
}

