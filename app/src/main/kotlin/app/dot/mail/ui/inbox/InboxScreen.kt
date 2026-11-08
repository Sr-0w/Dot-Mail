package app.dot.mail.ui.inbox

import androidx.compose.foundation.focusable

import androidx.compose.ui.focus.focusRequester

import androidx.compose.ui.input.key.isMetaPressed

import androidx.compose.ui.input.key.isAltPressed

import androidx.compose.ui.input.key.isCtrlPressed

import androidx.compose.ui.input.key.onPreviewKeyEvent

import androidx.compose.ui.input.key.type

import androidx.compose.ui.input.key.key

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Snooze
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import app.dot.design.DotMotion
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.Avatar
import app.dot.design.components.DotIconButton
import app.dot.design.components.DotMenu
import app.dot.design.components.DotMenuItem
import app.dot.design.components.DotSnackbar
import app.dot.design.components.DotTopBar
import app.dot.design.components.EmptyState
import app.dot.design.components.ListRow
import app.dot.design.components.UnreadDot
import app.dot.design.components.dotShimmer
import app.dot.design.components.dotSurfaceEnter
import app.dot.design.rememberReducedMotion
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.dot.mail.R
import app.dot.mail.Density
import app.dot.mail.LocalMailGraph
import app.dot.mail.LocalSettings
import app.dot.mail.SwipeAction
import app.dot.mail.data.model.FolderRole
import app.dot.mail.data.model.MessageAction
import app.dot.mail.data.model.ThreadSummary
import app.dot.mail.ui.common.Format
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpenThread: (threadId: Long, source: app.dot.mail.data.model.Mailbox) -> Unit,
    onCompose: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onAddAccount: () -> Unit,
    onOpenDraft: (Long) -> Unit,
    onOutbox: () -> Unit,
    onAttachments: () -> Unit = {},
) {
    val graph = LocalMailGraph.current
    val appSettings = LocalSettings.current
    val appResources = androidx.compose.ui.platform.LocalContext.current.applicationContext.resources
    val vm: InboxViewModel = viewModel { InboxViewModel(graph, createSavedStateHandle(), appSettings.ui.map { it.conversationView }, resources = appResources) }
    val prefs by LocalSettings.current.ui.collectAsStateWithLifecycle()
    val mailbox by vm.mailbox.collectAsStateWithLifecycle()
    val header by vm.header.collectAsStateWithLifecycle()
    val drawer by vm.drawer.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val userSyncing by vm.syncing.collectAsStateWithLifecycle()
    val threads = vm.threads.collectAsLazyPagingItems()
    val outbox by graph.compose.outbox.collectAsStateWithLifecycle(emptyList())
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var showMove by remember { mutableStateOf(false) }
    val mutedThreads by graph.mail.mutedThreads().collectAsStateWithLifecycle(emptySet())
    // Conversations waiting for a snooze time (from the selection bar, or a swipe).
    var snoozeTargets by remember { mutableStateOf<List<Long>?>(null) }
    // Account whose password the user is re-entering (from the sync-error banner).
    var signInAccount by remember { mutableStateOf<app.dot.mail.data.model.Account?>(null) }
    var pendingTrash by remember { mutableStateOf<List<Long>?>(null) }
    val pullState = rememberPullToRefreshState()

    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
    AskNotificationPermissionOnce()
    // Back closes the selection first.
    androidx.activity.compose.BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }
    // Back closes the menu first (ModalNavigationDrawer doesn't do it by itself), instead of leaving the app.
    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    // Hardware keyboard: c = compose, / = search.
    val keyFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keyFocus.requestFocus() } }
    ModalNavigationDrawer(
        modifier = Modifier.focusRequester(keyFocus).focusable().onPreviewKeyEvent { e ->
            if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return@onPreviewKeyEvent false
            when (e.key) {
                androidx.compose.ui.input.key.Key.C -> { onCompose(); true }
                androidx.compose.ui.input.key.Key.Slash -> { onSearch(); true }
                else -> false
            }
        },
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
                onAttachments = { scope.launch { drawerState.close() }; onAttachments() },
            )
        },
    ) {
        Box(Modifier.fillMaxSize().background(Dot.colors.background)) {
            val reducedMotion = rememberReducedMotion()
            val screenHaptics = app.dot.design.rememberDotHaptics()
            Column(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = selection.isNotEmpty(),
                    transitionSpec = {
                        if (reducedMotion) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else if (targetState) {
                            (slideInVertically(tween(200, easing = DotMotion.decelerate)) { -it / 4 } +
                                fadeIn(tween(180, easing = DotMotion.decelerate))) togetherWith
                                (slideOutVertically(tween(150, easing = DotMotion.accelerate)) { it / 4 } +
                                    fadeOut(tween(120, easing = DotMotion.accelerate)))
                        } else {
                            (slideInVertically(tween(200, easing = DotMotion.decelerate)) { it / 4 } +
                                fadeIn(tween(180, easing = DotMotion.decelerate))) togetherWith
                                (slideOutVertically(tween(150, easing = DotMotion.accelerate)) { -it / 4 } +
                                    fadeOut(tween(120, easing = DotMotion.accelerate)))
                        }.using(SizeTransform(clip = false))
                    },
                    label = "topbar",
                ) { selecting ->
                    if (selecting) {
                        SelectionBar(
                            count = selection.size,
                            role = header.role,
                            onClose = vm::clearSelection,
                            onAction = { if (it == MessageAction.TRASH && prefs.confirmDelete) pendingTrash = selection.toList() else { screenHaptics.confirm(); vm.act(it) } },
                            onMove = { showMove = true },
                            onSnooze = { snoozeTargets = selection.toList() },
                            allMuted = selection.isNotEmpty() && selection.all { it in mutedThreads },
                            onMute = { vm.mute(selection.toList(), muted = !(selection.isNotEmpty() && selection.all { it in mutedThreads })) },
                            onUnsnooze = { vm.unsnooze() },
                        )
                    } else {
                        DotTopBar(
                            title = header.title,
                            subtitle = header.subtitle,
                            elevated = listState.canScrollBackward,
                            navigation = { DotIconButton(Icons.Outlined.Menu, stringResource(R.string.inbox_open_mailboxes), onClick = { scope.launch { drawerState.open() } }) },
                            actions = { DotIconButton(Icons.Outlined.Search, stringResource(R.string.inbox_search), onClick = onSearch) },
                        )
                    }
                }
                val accountsNow by vm.accounts.collectAsStateWithLifecycle()
                val failing = accountsNow.filter { it.syncState is app.dot.mail.data.model.SyncState.Error }
                androidx.compose.animation.AnimatedVisibility(failing.isNotEmpty() && selection.isEmpty()) {
                    val err = failing.firstOrNull()?.syncState as? app.dot.mail.data.model.SyncState.Error
                    // Sign-in problems take priority: tapping the banner opens the password dialog for that account.
                    val authFailing = failing.firstOrNull { (it.syncState as? app.dot.mail.data.model.SyncState.Error)?.isAuthError == true }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs)
                            .clip(DotShapes.medium).background(Dot.colors.surface)
                            .combinedClickable(
                                onClickLabel = stringResource(if (authFailing != null) R.string.inbox_banner_sign_in_again else R.string.inbox_banner_open_settings),
                                onClick = { if (authFailing != null) signInAccount = authFailing else onSettings() },
                            ).padding(horizontal = DotSpacing.m, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.WifiOff, null, Modifier.size(18.dp), tint = Dot.colors.danger)
                        Spacer(Modifier.width(DotSpacing.s))
                        Text(
                            (if (failing.size == 1) stringResource(R.string.inbox_failing_prefix_single, failing.first().email) else pluralStringResource(R.plurals.inbox_failing_prefix_many, failing.size, failing.size)) +
                                syncErrorText(err) + if (err?.isAuthError == true) stringResource(R.string.inbox_sign_in_again_suffix) else "",
                            style = Dot.type.caption, color = Dot.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        val retryable = failing.firstOrNull { (it.syncState as? app.dot.mail.data.model.SyncState.Error)?.canRetry == true }
                        if (authFailing == null && retryable != null) {
                            Spacer(Modifier.width(DotSpacing.s))
                            Text(
                                stringResource(R.string.inbox_retry), style = Dot.type.label, color = Dot.colors.accent,
                                modifier = Modifier.clip(DotShapes.small).combinedClickable(onClick = { failing.forEach { vm.retrySync(it.id) } })
                                    .padding(horizontal = DotSpacing.s, vertical = DotSpacing.xs),
                            )
                        }
                    }
                }
                // Real sync activity (pull-to-refresh after its spinner left, background or IDLE syncs): a hairline
                // under the header that never covers or blocks the list. Indeterminate — sync has no honest percentage.
                val anySyncing = userSyncing || accountsNow.any { it.syncState is app.dot.mail.data.model.SyncState.Syncing }
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = anySyncing && !refreshing,
                        enter = androidx.compose.animation.fadeIn(tween(DotMotion.fast)),
                        exit = androidx.compose.animation.fadeOut(tween(DotMotion.medium)),
                    ) { app.dot.design.components.DotLinearProgress(modifier = Modifier.fillMaxWidth(), showTrack = false) }
                }
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = vm::refresh,
                    modifier = Modifier.weight(1f),
                    state = pullState,
                    indicator = { QuietRefreshIndicator(pullState, refreshing, Modifier.align(Alignment.TopCenter)) },
                ) {
                    ThreadList(
                        myAddresses = remember(accountsNow) { accountsNow.map { it.email.lowercase() }.toSet() },
                        threads = threads,
                        mutedThreads = mutedThreads,
                        listState = listState,
                        selection = selection,
                        density = prefs.density,
                        swipeStart = prefs.swipeStart,
                        swipeEnd = prefs.swipeEnd,
                        role = header.role,
                        onOpen = { t ->
                            when {
                                selection.isNotEmpty() -> vm.toggleSelection(t.threadId)
                                t.draftId != null -> onOpenDraft(t.draftId!!)
                                else -> onOpenThread(t.threadId, mailbox)
                            }
                        },
                        onToggleSelect = vm::toggleSelection,
                        onSnooze = { t -> snoozeTargets = listOf(t.threadId) },
                        onSwipe = { action, t ->
                            if (action == MessageAction.TRASH && prefs.confirmDelete) pendingTrash = listOf(t.threadId) else vm.act(action, listOf(t.threadId))
                        },
                    )
                }
            }
            app.dot.design.components.DotFrostedPill(
                icon = Icons.Outlined.Edit,
                label = stringResource(R.string.inbox_compose),
                expanded = !listState.canScrollBackward,
                onClick = onCompose,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(DotSpacing.xl),
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 76.dp)) { DotSnackbar(it) }
        }
    }

    pendingTrash?.let { ids ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingTrash = null }, modifier = Modifier.dotSurfaceEnter(), containerColor = Dot.colors.surface, shape = DotShapes.large,
            title = { Text(pluralStringResource(R.plurals.inbox_confirm_trash_title, ids.size, ids.size), style = Dot.type.title, color = Dot.colors.textPrimary) },
            confirmButton = { app.dot.design.components.DotButton(stringResource(R.string.inbox_delete), { pendingTrash = null; vm.act(MessageAction.TRASH, ids) }, style = app.dot.design.components.ButtonStyle.Danger, compact = true) },
            dismissButton = { app.dot.design.components.DotButton(stringResource(R.string.inbox_cancel), { pendingTrash = null }, style = app.dot.design.components.ButtonStyle.Ghost, compact = true) },
        )
    }
    signInAccount?.let { account ->
        app.dot.mail.ui.account.UpdatePasswordDialog(account, onDismiss = { signInAccount = null }, onSuccess = { vm.refresh() })
    }

    snoozeTargets?.let { targets ->
        app.dot.mail.ui.snooze.SnoozeSheet(onDismiss = { snoozeTargets = null }, onPick = { until -> snoozeTargets = null; vm.snooze(targets, until) })
    }

    if (showMove) {
        ModalBottomSheet(
            onDismissRequest = { showMove = false },
            modifier = Modifier.dotSurfaceEnter(),
            containerColor = Dot.colors.surface,
            shape = DotShapes.sheet,
            dragHandle = null,
        ) {
            Text(stringResource(R.string.inbox_move_to), style = Dot.type.title, color = Dot.colors.textPrimary, modifier = Modifier.padding(DotSpacing.gutter))
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
    myAddresses: Set<String>,
    threads: LazyPagingItems<ThreadSummary>,
    mutedThreads: Set<Long>,
    listState: LazyListState,
    selection: Set<Long>,
    density: Density,
    swipeStart: SwipeAction,
    swipeEnd: SwipeAction,
    role: FolderRole?,
    onOpen: (ThreadSummary) -> Unit,
    onToggleSelect: (Long) -> Unit,
    onSwipe: (MessageAction, ThreadSummary) -> Unit,
    onSnooze: (ThreadSummary) -> Unit,
) {
    val listReducedMotion = rememberReducedMotion()
    val refresh = threads.loadState.refresh
    when {
        threads.itemCount == 0 && refresh is LoadState.Loading -> SkeletonList()
        threads.itemCount == 0 && refresh is LoadState.Error -> EmptyState(
            Icons.Outlined.WifiOff, stringResource(R.string.inbox_error_load_title), refresh.error.message ?: stringResource(R.string.inbox_error_load_body),
        )
        threads.itemCount == 0 -> LazyColumn(Modifier.fillMaxSize()) {
            // Must be scrollable for pull-to-refresh to work on an empty list.
            item { EmptyFor(role) }
        }
        else -> {
            LaunchedEffect(threads.itemSnapshotList.items) { app.dot.mail.ui.thread.ThreadOrder.ids = threads.itemSnapshotList.items.map { it.threadId } }
            LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 112.dp),
        ) {
            items(threads.itemCount, key = threads.itemKey { it.threadId }, contentType = { "thread" }) { index ->
                val t = threads[index] ?: return@items
                // Archive/delete/arrivals animate instead of popping (fade + slide of neighbours).
                // With animations turned off system-wide the rows simply change (null specs disable the animation).
                Box(Modifier.animateItem(
                    fadeInSpec = if (listReducedMotion) null else tween(DotMotion.medium),
                    placementSpec = if (listReducedMotion) null else androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow, visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold),
                    fadeOutSpec = if (listReducedMotion) null else tween(DotMotion.fast),
                )) {
                SwipeableRow(
                    thread = t,
                    swipeStart = swipeStart,
                    swipeEnd = swipeEnd,
                    enabled = selection.isEmpty(),
                    onSwipe = onSwipe,
                    onSnooze = onSnooze,
                ) {
                    ThreadRow(
                        thread = t,
                        showRecipients = role == FolderRole.SENT || role == FolderRole.DRAFTS,
                        myAddresses = myAddresses,
                        selected = t.threadId in selection,
                        muted = t.threadId in mutedThreads,
                        selectionMode = selection.isNotEmpty(),
                        compact = density == Density.COMPACT,
                        onClick = { onOpen(t) },
                        onLongClick = { onToggleSelect(t.threadId) },
                    )
                }
                }
            }
            if (threads.loadState.append is LoadState.Loading) item { SkeletonRow() }
        }
        }
    }
}

@Composable
private fun EmptyFor(role: FolderRole?) {
    val (title, msg) = when (role) {
        FolderRole.INBOX, null -> R.string.inbox_empty_inbox_title to R.string.inbox_empty_inbox_body
        FolderRole.STARRED -> R.string.inbox_empty_starred_title to R.string.inbox_empty_starred_body
        FolderRole.DRAFTS -> R.string.inbox_empty_drafts_title to R.string.inbox_empty_drafts_body
        FolderRole.SENT -> R.string.inbox_empty_sent_title to R.string.inbox_empty_sent_body
        FolderRole.SPAM -> R.string.inbox_empty_spam_title to R.string.inbox_empty_spam_body
        FolderRole.TRASH -> R.string.inbox_empty_trash_title to R.string.inbox_empty_trash_body
        FolderRole.NEWSLETTERS -> R.string.inbox_empty_newsletters_title to R.string.inbox_empty_newsletters_body
        FolderRole.SNOOZED -> R.string.inbox_empty_snoozed_title to R.string.inbox_empty_snoozed_body
        else -> R.string.inbox_empty_folder_title to R.string.inbox_empty_folder_body
    }
    EmptyState(if (role == FolderRole.INBOX || role == null) Icons.Outlined.Inbox else Format.roleIcon(role), stringResource(title), stringResource(msg))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRow(
    thread: ThreadSummary,
    swipeStart: SwipeAction,
    swipeEnd: SwipeAction,
    enabled: Boolean,
    onSwipe: (MessageAction, ThreadSummary) -> Unit,
    onSnooze: (ThreadSummary) -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = app.dot.design.rememberDotHaptics()
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            val action = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> swipeStart
                SwipeToDismissBoxValue.EndToStart -> swipeEnd
                SwipeToDismissBoxValue.Settled -> null
            }
            if (action != null) {
                haptics.confirm()
                if (action == SwipeAction.SNOOZE) onSnooze(thread) else toMessageAction(action, thread)?.let { onSwipe(it, thread) }
            }
            // Never stay dismissed: the row disappears when the DB updates (archive/trash)
            // or springs back for toggles (read/star).
            false
        },
        positionalThreshold = { it * 0.35f },
    )
    // A light tick the moment the swipe becomes committable, so release is predictable.
    val armedNow = state.targetValue != SwipeToDismissBoxValue.Settled
    androidx.compose.runtime.LaunchedEffect(armedNow) { if (armedNow) haptics.tick() }
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
    SwipeAction.SNOOZE, SwipeAction.NONE -> null
}

@Composable
private fun swipeVisual(action: SwipeAction, t: ThreadSummary): Pair<ImageVector, String> = when (action) {
    SwipeAction.ARCHIVE -> Icons.Outlined.Archive to stringResource(R.string.inbox_action_archive)
    SwipeAction.TRASH -> Icons.Outlined.Delete to stringResource(R.string.inbox_action_delete)
    SwipeAction.READ_TOGGLE -> (if (t.isUnread) Icons.Outlined.MarkEmailRead else Icons.Outlined.MarkEmailUnread) to stringResource(R.string.inbox_action_toggle_read)
    SwipeAction.STAR_TOGGLE -> (if (t.isStarred) Icons.Outlined.StarOutline else Icons.Outlined.Star) to stringResource(R.string.inbox_action_toggle_star)
    SwipeAction.SNOOZE -> Icons.Outlined.Snooze to stringResource(R.string.inbox_action_snooze)
    SwipeAction.NONE -> Icons.Outlined.Mail to ""
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreadRow(
    thread: ThreadSummary,
    selected: Boolean,
    /** Conversation is muted: a small glyph, new mail doesn't notify. */
    muted: Boolean = false,
    showRecipients: Boolean = false,
    myAddresses: Set<String> = emptySet(),
    selectionMode: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = Dot.colors
    val haptics = LocalHapticFeedback.current
    val reducedMotion = rememberReducedMotion()
    val bg by animateColorAsState(
        if (selected) c.surfaceRaised else c.background,
        animationSpec = DotMotion.fastSpec(reducedMotion),
        label = "row-bg",
    )
    val unread = thread.isUnread
    val unreadLabel = stringResource(R.string.inbox_state_unread)
    val starredLabel = stringResource(R.string.inbox_state_starred)
    val attachmentsLabel = stringResource(R.string.inbox_state_has_attachments)
    val selectedLabel = stringResource(R.string.inbox_state_selected)
    val sender = if (showRecipients && thread.recipients.isNotEmpty()) {
        stringResource(R.string.inbox_to_prefix, thread.recipients.joinToString(", ") { it.displayName.substringBefore(' ').ifBlank { it.displayName } })
    } else if (thread.draftId != null && thread.recipients.isEmpty()) stringResource(R.string.inbox_draft_label) else senderLine(thread, myAddresses, stringResource(R.string.inbox_me), stringResource(R.string.inbox_unknown_sender))
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
                onLongClickLabel = stringResource(R.string.inbox_select),
            )
            .semantics(mergeDescendants = true) {
                stateDescription = buildString {
                    if (unread) append(unreadLabel).append(" ")
                    if (thread.isStarred) append(starredLabel).append(" ")
                    if (thread.hasAttachments) append(attachmentsLabel).append(" ")
                    if (selected) append(selectedLabel)
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
            Box(Modifier.clip(CircleShape).combinedClickable(onClickLabel = stringResource(if (selected) R.string.inbox_deselect else R.string.inbox_select), onClick = onLongClick).clearAndSetSemantics { }) {
                AnimatedContent(
                    targetState = selected || selectionMode,
                    transitionSpec = {
                        if (reducedMotion) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            (scaleIn(tween(180, easing = DotMotion.decelerate), initialScale = 0.85f) +
                                fadeIn(tween(150, easing = DotMotion.decelerate))) togetherWith
                                (scaleOut(tween(120, easing = DotMotion.accelerate), targetScale = 0.85f) +
                                    fadeOut(tween(120, easing = DotMotion.accelerate)))
                        }
                    },
                    label = "avatar",
                ) { sel ->
                    if (sel) SelectCircle(selected)
                    else {
                        val first = if (showRecipients) thread.recipients.firstOrNull() ?: thread.participants.lastOrNull() else thread.participants.lastOrNull()
                        app.dot.mail.ui.avatar.SenderAvatar(first ?: app.dot.mail.data.model.EmailAddress("?"))
                    }
                }
            }
            Spacer(Modifier.width(DotSpacing.m))
        } else {
            AnimatedVisibility(
                visible = selectionMode,
                enter = if (reducedMotion) EnterTransition.None else expandHorizontally(tween(180, easing = DotMotion.standard)) + fadeIn(tween(150)),
                exit = if (reducedMotion) ExitTransition.None else shrinkHorizontally(tween(150, easing = DotMotion.standard)) + fadeOut(tween(120)),
            ) {
                Row {
                    SelectCircle(selected, size = 24)
                    Spacer(Modifier.width(DotSpacing.m))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Sender + count share the free width; attachment glyph and date stay pinned right.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
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
                        Text(
                            thread.messageCount.toString(), style = Dot.type.caption, color = c.textTertiary,
                            modifier = Modifier.semantics { contentDescription = "${thread.messageCount} messages" },
                        )
                    }
                }
                Spacer(Modifier.width(DotSpacing.s))
                if (muted) {
                    Icon(Icons.Outlined.NotificationsOff, stringResource(R.string.inbox_muted), Modifier.size(15.dp), tint = c.textTertiary)
                    Spacer(Modifier.width(6.dp))
                }
                if (thread.hasAttachments) {
                    Icon(Icons.Outlined.Attachment, null, Modifier.size(15.dp), tint = c.textTertiary)
                    Spacer(Modifier.width(6.dp))
                }
                if (thread.snoozedUntil != null) {
                    // Snoozed list: when it comes back matters more than when it arrived.
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Text(
                        stringResource(R.string.inbox_snoozed_until, app.dot.mail.ui.snooze.describeWake(context, thread.snoozedUntil!!)),
                        style = Dot.type.caption.copy(fontWeight = FontWeight.Medium), color = c.accent,
                    )
                } else Text(
                    Format.listDate(thread.lastMessageAt),
                    style = Dot.type.caption.copy(fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal),
                    color = if (unread) c.textPrimary else c.textTertiary,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    thread.subject.ifBlank { stringResource(R.string.inbox_no_subject) },
                    style = Dot.type.callout.copy(fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal),
                    color = if (unread) c.textPrimary else c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                AnimatedVisibility(
                    visible = thread.isStarred,
                    enter = if (rememberReducedMotion()) EnterTransition.None else scaleIn(androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 600f)) + fadeIn(tween(100)),
                    exit = if (rememberReducedMotion()) ExitTransition.None else scaleOut(tween(100)) + fadeOut(tween(100)),
                ) {
                    Icon(Icons.Outlined.Star, null, Modifier.padding(start = DotSpacing.s).size(16.dp), tint = c.star)
                }
            }
            // A preview of only an ellipsis / punctuation is no preview: no empty third line.
            if (!compact && thread.snippet.any { it.isLetterOrDigit() }) {
                Spacer(Modifier.height(2.dp))
                Text(thread.snippet, style = Dot.type.callout, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun senderLine(t: ThreadSummary, me: Set<String>, meLabel: String, unknownLabel: String): String {
    fun short(a: app.dot.mail.data.model.EmailAddress) =
        if (a.email.lowercase() in me) meLabel else a.displayName.substringBefore(' ').ifBlank { a.displayName }
    val names = t.participants.map(::short).distinct()
    return when {
        names.isEmpty() -> unknownLabel
        t.participants.size == 1 -> t.participants.first().let { if (it.email.lowercase() in me) meLabel else it.displayName }
        names.size <= 3 -> names.joinToString(", ")
        else -> names.first() + " … " + names.takeLast(2).joinToString(", ")
    }
}

@Composable
private fun SelectCircle(selected: Boolean, size: Int = 40) {
    val c = Dot.colors
    val reducedMotion = rememberReducedMotion()
    val bg by animateColorAsState(
        if (selected) c.inverse else c.surfaceRaised,
        animationSpec = DotMotion.fastSpec(reducedMotion),
        label = "sel-circle-bg",
    )
    val checkScale by animateFloatAsState(
        if (selected) 1f else 0.6f,
        animationSpec = DotMotion.spec(180, DotMotion.decelerate, reducedMotion),
        label = "sel-check-scale",
    )
    val checkAlpha by animateFloatAsState(
        if (selected) 1f else 0f,
        animationSpec = DotMotion.fastSpec(reducedMotion),
        label = "sel-check-alpha",
    )
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (selected || checkAlpha > 0.01f) {
            Icon(
                Icons.Outlined.Check,
                if (selected) stringResource(R.string.inbox_selected) else null,
                Modifier
                    .size((size / 2).dp)
                    .graphicsLayer {
                        scaleX = checkScale
                        scaleY = checkScale
                        alpha = checkAlpha
                    },
                tint = c.onInverse,
            )
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    role: FolderRole?,
    onClose: () -> Unit,
    onAction: (MessageAction) -> Unit,
    onMove: () -> Unit,
    onSnooze: () -> Unit,
    onUnsnooze: () -> Unit,
    allMuted: Boolean,
    onMute: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    DotTopBar(
        title = pluralStringResource(R.plurals.inbox_selected_count, count, count),
        elevated = true,
        navigation = { DotIconButton(Icons.Outlined.Close, stringResource(R.string.inbox_clear_selection), onClick = onClose) },
        actions = {
            if (role == FolderRole.SNOOZED) DotIconButton(Icons.Outlined.Snooze, stringResource(R.string.inbox_action_unsnooze), onClick = onUnsnooze)
            if (role != FolderRole.ARCHIVE) DotIconButton(Icons.Outlined.Archive, stringResource(R.string.inbox_action_archive), onClick = { onAction(MessageAction.ARCHIVE) })
            if (role == FolderRole.TRASH) DotIconButton(Icons.Outlined.Delete, stringResource(R.string.inbox_action_delete_forever), onClick = { onAction(MessageAction.DELETE_FOREVER) })
            else DotIconButton(Icons.Outlined.Delete, stringResource(R.string.inbox_action_delete), onClick = { onAction(MessageAction.TRASH) })
            DotIconButton(Icons.Outlined.MarkEmailRead, stringResource(R.string.inbox_action_mark_read), onClick = { onAction(MessageAction.MARK_READ) })
            Box {
                DotIconButton(Icons.Outlined.MoreVert, stringResource(R.string.inbox_more_actions), onClick = { menu = true })
                DotMenu(menu, onDismiss = { menu = false }) {
                    DotMenuItem(stringResource(R.string.inbox_action_mark_unread), Icons.Outlined.MarkEmailUnread) { menu = false; onAction(MessageAction.MARK_UNREAD) }
                    DotMenuItem(stringResource(R.string.inbox_action_star), Icons.Outlined.StarOutline) { menu = false; onAction(MessageAction.STAR) }
                    DotMenuItem(stringResource(R.string.inbox_action_snooze_ellipsis), Icons.Outlined.Snooze) { menu = false; onSnooze() }
                    DotMenuItem(stringResource(if (allMuted) R.string.inbox_action_unmute else R.string.inbox_action_mute), if (allMuted) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff) { menu = false; onMute() }
                    DotMenuItem(stringResource(R.string.inbox_action_move_ellipsis), Icons.Outlined.DriveFileMove) { menu = false; onMove() }
                    if (role == FolderRole.SPAM) DotMenuItem(stringResource(R.string.inbox_action_not_spam), Icons.Outlined.Inbox) { menu = false; onAction(MessageAction.NOT_SPAM) }
                    else DotMenuItem(stringResource(R.string.inbox_action_report_spam), Icons.Outlined.Report) { menu = false; onAction(MessageAction.SPAM) }
                }
            }
        },
    )
}

/** Inverted rounded-square compose button; the label folds away once the list scrolls. */
@Composable
private fun ComposeButton(expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Dot.colors
    val composeLabel = stringResource(R.string.inbox_compose)
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .height(54.dp)
            .clip(DotShapes.large)
            .background(c.inverse)
            .combinedClickable(onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() })
            .semantics { contentDescription = composeLabel }
            .padding(horizontal = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Edit, null, Modifier.size(20.dp), tint = c.onInverse)
        AnimatedVisibility(expanded, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
            Text(composeLabel, style = Dot.type.label, color = c.onInverse, modifier = Modifier.padding(start = 10.dp, end = 2.dp))
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
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.surface.copy(alpha = pulse)).dotShimmer())
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


/** Android 13+: ask once for notifications, the first time the user reaches their inbox. */
@Composable
private fun AskNotificationPermissionOnce() {
    if (android.os.Build.VERSION.SDK_INT < 33) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted && !prefs.getBoolean("askedNotifications", false)) {
            prefs.edit().putBoolean("askedNotifications", true).apply()
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * Pull-to-refresh in the Quiet style: a small raised disc that follows the finger, drawing a ring
 * that fills as you pull; while syncing it becomes the design-system spinner. No Material arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietRefreshIndicator(
    state: androidx.compose.material3.pulltorefresh.PullToRefreshState,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = Dot.colors
    val fraction = state.distanceFraction
    val visible = refreshing || fraction > 0f
    // One subtle tick when the pull crosses the trigger threshold (the system haptics setting still applies).
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val armed = fraction >= 1f
    androidx.compose.runtime.LaunchedEffect(armed) {
        if (armed && !refreshing) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate)
    }
    val offset by androidx.compose.animation.core.animateDpAsState(
        if (refreshing) 16.dp else (fraction.coerceAtMost(1.5f) * 56).dp - 40.dp, label = "ptr-offset",
    )
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (visible) 1f else 0f, tween(DotMotion.fast), label = "ptr-alpha")
    Box(
        modifier
            .offset(y = offset)
            .graphicsLayer { this.alpha = alpha; scaleX = 0.6f + 0.4f * alpha; scaleY = scaleX }
            .size(36.dp)
            .clip(CircleShape)
            .background(c.surfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        if (refreshing) {
            app.dot.design.components.DotSpinner(size = 18.dp, color = c.textPrimary)
        } else {
            val sweep = (fraction.coerceIn(0f, 1f) * 300f)
            androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                drawArc(
                    color = c.textPrimary.copy(alpha = 0.35f + 0.65f * fraction.coerceIn(0f, 1f)),
                    startAngle = -90f, sweepAngle = sweep, useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
        }
    }
}

/** The banner text for a sync error, in the user's language when the kind of failure is known. */
@Composable
private fun syncErrorText(err: app.dot.mail.data.model.SyncState.Error?): String = when (err?.errorKind) {
    null -> err?.message ?: stringResource(R.string.inbox_sync_problem)
    app.dot.mail.engine.ConnectionFailure.DNS -> stringResource(R.string.sync_error_dns)
    app.dot.mail.engine.ConnectionFailure.TIMEOUT -> stringResource(R.string.sync_error_timeout)
    app.dot.mail.engine.ConnectionFailure.TLS -> stringResource(R.string.sync_error_tls)
    app.dot.mail.engine.ConnectionFailure.AUTH -> stringResource(R.string.sync_error_auth)
    app.dot.mail.engine.ConnectionFailure.SERVER_CLOSED -> stringResource(R.string.sync_error_server_closed)
    app.dot.mail.engine.ConnectionFailure.THROTTLED -> stringResource(R.string.sync_error_throttled)
    app.dot.mail.engine.ConnectionFailure.NETWORK -> stringResource(R.string.sync_error_network)
    app.dot.mail.engine.ConnectionFailure.OTHER -> stringResource(R.string.sync_error_other)
}
