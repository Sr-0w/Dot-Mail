package app.dot.mail.ui

import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import app.dot.design.components.DotSnackbar
import app.dot.mail.LocalSettings
import app.dot.mail.ui.compose.UndoSendBus
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.dot.design.Dot
import app.dot.design.DotMotion
import app.dot.design.rememberReducedMotion
import app.dot.mail.ExternalRequest
import app.dot.mail.LocalMailGraph
import app.dot.mail.ui.account.AddAccountScreen
import app.dot.mail.ui.account.WelcomeScreen
import app.dot.mail.ui.compose.ComposeScreen
import app.dot.mail.ui.inbox.InboxScreen
import app.dot.mail.ui.search.SearchScreen
import app.dot.mail.ui.settings.SettingsScreen
import app.dot.mail.ui.thread.ThreadScreen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable object WelcomeRoute
@Serializable data class AddAccountRoute(val firstAccount: Boolean = true)
@Serializable object InboxRoute
/** [sourceFolderId]/[sourceRole] describe the mailbox the conversation was opened from, so actions stay scoped to it. */
@Serializable data class ThreadRoute(val threadId: Long, val sourceFolderId: Long = 0, val sourceRole: String? = null) {
    constructor(threadId: Long, source: app.dot.mail.data.model.Mailbox?) : this(
        threadId,
        (source as? app.dot.mail.data.model.Mailbox.InFolder)?.folderId ?: 0,
        (source as? app.dot.mail.data.model.Mailbox.Unified)?.role?.name,
    )
    val source: app.dot.mail.data.model.Mailbox?
        get() = when {
            sourceFolderId != 0L -> app.dot.mail.data.model.Mailbox.InFolder(sourceFolderId)
            sourceRole != null -> sourceRole.let { r -> runCatching { app.dot.mail.data.model.Mailbox.Unified(app.dot.mail.data.model.FolderRole.valueOf(r)) }.getOrNull() }
            else -> null
        }
}
/** [folderId] / [role]: the mailbox the search was opened from (0 / null = none). */
@Serializable data class SearchRoute(val folderId: Long = 0, val role: String? = null)
@Serializable object AttachmentsRoute
@Serializable object SettingsRoute
@Serializable object OutboxRoute

@Serializable
data class ComposeRoute(
    val draftId: Long = 0,
    val replyToMessageId: Long = 0,
    /** ReplyMode name, or null for a new message. */
    val replyMode: String? = null,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String? = null,
    val body: String? = null,
    val attachments: List<String> = emptyList(),
)

fun NavHostController.openCompose(route: ComposeRoute = ComposeRoute()) = navigate(route) { launchSingleTop = true }

@Composable
fun DotMailRoot(requests: StateFlow<ExternalRequest?>, onRequestConsumed: () -> Unit) {
    val graph = LocalMailGraph.current
    // null until the first DB emission, so we never flash the welcome screen for existing users.
    val hasAccounts by produceState<Boolean?>(null) {
        graph.accounts.accounts.collect { value = it.isNotEmpty() }
    }
    Box(Modifier.fillMaxSize().background(Dot.colors.background)) {
        val ready = hasAccounts ?: return@Box
        val nav = rememberNavController()
        val reducedMotion = rememberReducedMotion()
        NavHost(
            navController = nav,
            startDestination = if (ready) InboxRoute else WelcomeRoute,
            enterTransition = { quietEnter(reducedMotion) },
            exitTransition = { quietExit(reducedMotion) },
            popEnterTransition = { quietPopEnter(reducedMotion) },
            popExitTransition = { quietPopExit(reducedMotion) },
        ) {
            composable<WelcomeRoute> {
                WelcomeScreen(onAddAccount = { nav.navigate(AddAccountRoute(firstAccount = true)) })
            }
            composable<AddAccountRoute> {
                AddAccountScreen(
                    onBack = { nav.popBackStack() },
                    onDone = {
                        nav.navigate(InboxRoute) { popUpTo(0) { inclusive = true } }
                    },
                )
            }
            composable<InboxRoute> {
                val composeTo: (String) -> Unit = { target ->
                    val m = if (target.startsWith("mailto:", true)) app.dot.mail.MailtoParser.parse(target) else ExternalRequest.Compose(to = listOf(target))
                    nav.openCompose(ComposeRoute(to = m.to, cc = m.cc, bcc = m.bcc, subject = m.subject, body = m.body))
                }
                // Wide windows (tablets, unfolded foldables, phones in landscape): list and conversation side by side.
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    val twoPane = maxWidth >= TWO_PANE_MIN_WIDTH
                    var selected by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
                    var selectedSource by androidx.compose.runtime.remember { mutableStateOf<app.dot.mail.data.model.Mailbox?>(null) }
                    val inbox = @Composable { mod: Modifier ->
                        Box(mod) {
                            InboxScreen(
                                onOpenThread = { id, source -> if (twoPane) { selected = id; selectedSource = source } else nav.navigate(ThreadRoute(id, source)) },
                                onCompose = { nav.openCompose() },
                                onSearch = { box ->
                                    nav.navigate(
                                        when (box) {
                                            is app.dot.mail.data.model.Mailbox.InFolder -> SearchRoute(folderId = box.folderId)
                                            is app.dot.mail.data.model.Mailbox.Unified -> SearchRoute(role = box.role.name)
                                            else -> SearchRoute()
                                        },
                                    )
                                },
                                onSettings = { nav.navigate(SettingsRoute) },
                                onAddAccount = { nav.navigate(AddAccountRoute(firstAccount = false)) },
                                onOpenDraft = { nav.openCompose(ComposeRoute(draftId = it)) },
                                onOutbox = { nav.navigate(OutboxRoute) },
                                onAttachments = { nav.navigate(AttachmentsRoute) },
                            )
                        }
                    }
                    if (!twoPane) {
                        inbox(Modifier.fillMaxSize())
                    } else {
                        Row(Modifier.fillMaxSize()) {
                            inbox(Modifier.weight(0.42f).widthIn(min = 340.dp).fillMaxHeight())
                            Box(Modifier.width(1.dp).fillMaxHeight().background(app.dot.design.Dot.colors.border))
                            Box(Modifier.weight(0.58f).fillMaxHeight()) {
                                val id = selected
                                if (id == null) {
                                    app.dot.design.components.EmptyState(
                                        androidx.compose.material.icons.Icons.Outlined.MailOutline,
                                        "No conversation selected", "Pick one from the list.",
                                    )
                                } else {
                                    androidx.compose.runtime.key(id) {
                                        ThreadScreen(
                                            threadId = id,
                                            source = selectedSource,
                                            onBack = { selected = null },
                                            onOpenAdjacent = { next -> selected = next },
                                            onReply = { messageId, mode -> nav.openCompose(ComposeRoute(replyToMessageId = messageId, replyMode = mode.name)) },
                                            onComposeTo = composeTo,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            composable<ThreadRoute> { entry ->
                val route = entry.toRoute<ThreadRoute>()
                ThreadScreen(
                    threadId = route.threadId,
                    source = route.source,
                    onBack = { nav.popBackStack() },
                    onOpenAdjacent = { next -> nav.navigate(ThreadRoute(next, route.source)) { popUpTo<ThreadRoute> { inclusive = true } } },
                    onReply = { messageId, mode -> nav.openCompose(ComposeRoute(replyToMessageId = messageId, replyMode = mode.name)) },
                    onComposeTo = { target ->
                        val m = if (target.startsWith("mailto:", true)) app.dot.mail.MailtoParser.parse(target) else ExternalRequest.Compose(to = listOf(target))
                        nav.openCompose(ComposeRoute(to = m.to, cc = m.cc, bcc = m.bcc, subject = m.subject, body = m.body))
                    },
                )
            }
            composable<ComposeRoute>(
                enterTransition = { composeEnter(reducedMotion) },
                exitTransition = { if (reducedMotion) ExitTransition.None else fadeOut(tween(DotMotion.fast)) },
                popEnterTransition = { if (reducedMotion) EnterTransition.None else fadeIn(tween(NAV_DURATION_MS, easing = DotMotion.decelerate)) },
                popExitTransition = { composePopExit(reducedMotion) },
            ) { entry ->
                ComposeScreen(route = entry.toRoute(), onClose = { nav.popBackStack() })
            }
            composable<SearchRoute>(
                // Search opens like a field growing out of the header: a short fade with a hint of downward travel.
                enterTransition = { if (reducedMotion) EnterTransition.None else fadeIn(tween(DotMotion.medium, easing = DotMotion.decelerate)) + slideInVertically(tween(DotMotion.medium, easing = DotMotion.decelerate)) { -it / 24 } },
                exitTransition = { if (reducedMotion) ExitTransition.None else fadeOut(tween(DotMotion.fast)) },
                popEnterTransition = { if (reducedMotion) EnterTransition.None else fadeIn(tween(DotMotion.medium, easing = DotMotion.decelerate)) },
                popExitTransition = { if (reducedMotion) ExitTransition.None else fadeOut(tween(DotMotion.fast)) + slideOutVertically(tween(DotMotion.fast, easing = DotMotion.accelerate)) { -it / 32 } },
            ) { entry ->
                val route = entry.toRoute<SearchRoute>()
                SearchScreen(onBack = { nav.popBackStack() }, onOpenThread = { nav.navigate(ThreadRoute(it)) }, folderId = route.folderId, role = route.role)
            }
            composable<OutboxRoute> { app.dot.mail.ui.outbox.OutboxScreen(onBack = { nav.popBackStack() }) }
            composable<AttachmentsRoute> { app.dot.mail.ui.attachments.AttachmentsScreen(onBack = { nav.popBackStack() }) }
            composable<SettingsRoute> {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onAddAccount = { nav.navigate(AddAccountRoute(firstAccount = false)) },
                    onAllAccountsRemoved = { nav.navigate(WelcomeRoute) { popUpTo(0) { inclusive = true } } },
                )
            }
        }

        UndoSendHost(
            onRestored = { draftId -> nav.openCompose(ComposeRoute(draftId = draftId)) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        val pending by requests.collectAsStateWithLifecycle()
        LaunchedEffect(pending, ready) {
            val request = pending ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect // handled once an account exists
            when (request) {
                is ExternalRequest.OpenThread -> {
                    // Reply button of a notification: compose a reply to the newest message; otherwise just open the conversation.
                    val last = if (request.reply) runCatching { graph.mail.thread(request.threadId).first().lastOrNull() }.getOrNull() else null
                    if (last != null) nav.openCompose(ComposeRoute(replyToMessageId = last.id, replyMode = app.dot.mail.data.ReplyMode.REPLY.name))
                    else nav.navigate(ThreadRoute(request.threadId, sourceFolderId = request.folderId))
                }
                is ExternalRequest.Compose -> nav.openCompose(
                    ComposeRoute(
                        to = request.to, cc = request.cc, bcc = request.bcc,
                        subject = request.subject, body = request.body, attachments = request.attachments,
                    ),
                )
                ExternalRequest.Search -> nav.navigate(SearchRoute())
            }
            onRequestConsumed()
        }
    }
}

private const val NAV_DURATION_MS = 220

private fun AnimatedContentTransitionScope<NavBackStackEntry>.quietEnter(reducedMotion: Boolean): EnterTransition =
    if (reducedMotion) EnterTransition.None else {
        fadeIn(tween(NAV_DURATION_MS, easing = DotMotion.decelerate)) +
            slideInHorizontally(tween(NAV_DURATION_MS, easing = DotMotion.standard)) { it / 12 }
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.quietExit(reducedMotion: Boolean): ExitTransition =
    if (reducedMotion) ExitTransition.None else {
        fadeOut(tween(DotMotion.fast, easing = DotMotion.accelerate)) +
            slideOutHorizontally(tween(NAV_DURATION_MS, easing = DotMotion.standard)) { -it / 12 }
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.quietPopEnter(reducedMotion: Boolean): EnterTransition =
    if (reducedMotion) EnterTransition.None else {
        fadeIn(tween(NAV_DURATION_MS, easing = DotMotion.decelerate)) +
            slideInHorizontally(tween(NAV_DURATION_MS, easing = DotMotion.standard)) { -it / 12 }
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.quietPopExit(reducedMotion: Boolean): ExitTransition =
    if (reducedMotion) ExitTransition.None else {
        fadeOut(tween(DotMotion.fast, easing = DotMotion.accelerate)) +
            slideOutHorizontally(tween(NAV_DURATION_MS, easing = DotMotion.standard)) { it / 12 }
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.composeEnter(reducedMotion: Boolean): EnterTransition =
    if (reducedMotion) EnterTransition.None else {
        slideInVertically(tween(NAV_DURATION_MS, easing = DotMotion.decelerate)) { it / 6 } +
            fadeIn(tween(NAV_DURATION_MS, easing = DotMotion.decelerate))
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.composePopExit(reducedMotion: Boolean): ExitTransition =
    if (reducedMotion) ExitTransition.None else {
        slideOutVertically(tween(NAV_DURATION_MS, easing = DotMotion.accelerate)) { it / 6 } +
            fadeOut(tween(DotMotion.fast, easing = DotMotion.accelerate))
    }


/** "Sending… Undo" lives at the root so it survives the compose screen closing. */
@Composable
private fun UndoSendHost(onRestored: (Long) -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalMailGraph.current
    val prefs by LocalSettings.current.ui.collectAsStateWithLifecycle()
    val seconds = prefs.undoSendSeconds
    val host = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        UndoSendBus.pending.collect { pendingSend ->
        val p = pendingSend ?: return@collect
        UndoSendBus.pending.value = null
        val scheduled = p.scheduledAt != null
        if (!scheduled && seconds == 0) {
            host.showSnackbar("Sending…", duration = SnackbarDuration.Short)
            return@collect
        }
        val result = kotlinx.coroutines.withTimeoutOrNull(if (scheduled) 6_000L else seconds * 1_000L) {
            host.showSnackbar(if (scheduled) "Message scheduled" else "Sending…", actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
        }
        if (result == null) host.currentSnackbarData?.dismiss()
        if (result == SnackbarResult.ActionPerformed) {
            if (graph.compose.cancelSend(p.outboxId)) onRestored(p.draftId)
            else host.showSnackbar("Too late — the message was already sent")
        }
        }
    }
    SnackbarHost(host, modifier.navigationBarsPadding().padding(bottom = 8.dp)) { DotSnackbar(it) }
}

/** Window width from which the inbox shows the conversation next to the list. */
private val TWO_PANE_MIN_WIDTH = 840.dp
