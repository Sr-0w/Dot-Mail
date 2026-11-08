package app.dot.mail.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import kotlinx.serialization.Serializable

@Serializable object WelcomeRoute
@Serializable data class AddAccountRoute(val firstAccount: Boolean = true)
@Serializable object InboxRoute
@Serializable data class ThreadRoute(val threadId: Long)
@Serializable object SearchRoute
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
        NavHost(
            navController = nav,
            startDestination = if (ready) InboxRoute else WelcomeRoute,
            enterTransition = { slideIn() },
            exitTransition = { fadeOut(tween(DotMotion.fast)) },
            popEnterTransition = { fadeIn(tween(DotMotion.medium, easing = DotMotion.decelerate)) },
            popExitTransition = { slideOut() },
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
                InboxScreen(
                    onOpenThread = { nav.navigate(ThreadRoute(it)) },
                    onCompose = { nav.openCompose() },
                    onSearch = { nav.navigate(SearchRoute) },
                    onSettings = { nav.navigate(SettingsRoute) },
                    onAddAccount = { nav.navigate(AddAccountRoute(firstAccount = false)) },
                    onOpenDraft = { nav.openCompose(ComposeRoute(draftId = it)) },
                    onOutbox = { nav.navigate(OutboxRoute) },
                )
            }
            composable<ThreadRoute> { entry ->
                ThreadScreen(
                    threadId = entry.toRoute<ThreadRoute>().threadId,
                    onBack = { nav.popBackStack() },
                    onReply = { messageId, mode -> nav.openCompose(ComposeRoute(replyToMessageId = messageId, replyMode = mode.name)) },
                    onComposeTo = { target ->
                        val m = if (target.startsWith("mailto:", true)) app.dot.mail.MailtoParser.parse(target) else ExternalRequest.Compose(to = listOf(target))
                        nav.openCompose(ComposeRoute(to = m.to, cc = m.cc, bcc = m.bcc, subject = m.subject, body = m.body))
                    },
                )
            }
            composable<ComposeRoute> { entry ->
                ComposeScreen(route = entry.toRoute(), onClose = { nav.popBackStack() })
            }
            composable<SearchRoute> {
                SearchScreen(onBack = { nav.popBackStack() }, onOpenThread = { nav.navigate(ThreadRoute(it)) })
            }
            composable<OutboxRoute> { app.dot.mail.ui.outbox.OutboxScreen(onBack = { nav.popBackStack() }) }
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
                is ExternalRequest.OpenThread -> nav.navigate(ThreadRoute(request.threadId))
                is ExternalRequest.Compose -> nav.openCompose(
                    ComposeRoute(
                        to = request.to, cc = request.cc, bcc = request.bcc,
                        subject = request.subject, body = request.body, attachments = request.attachments,
                    ),
                )
                ExternalRequest.Search -> nav.navigate(SearchRoute)
            }
            onRequestConsumed()
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.slideIn() =
    slideInHorizontally(tween(DotMotion.medium, easing = DotMotion.standard)) { it / 8 } +
        fadeIn(tween(DotMotion.medium, easing = DotMotion.decelerate))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.slideOut() =
    slideOutHorizontally(tween(DotMotion.medium, easing = DotMotion.standard)) { it / 8 } +
        fadeOut(tween(DotMotion.fast))


/** "Sending… Undo" lives at the root so it survives the compose screen closing. */
@Composable
private fun UndoSendHost(onRestored: (Long) -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalMailGraph.current
    val prefs by LocalSettings.current.ui.collectAsStateWithLifecycle()
    val seconds = prefs.undoSendSeconds
    val host = remember { SnackbarHostState() }
    val pending by UndoSendBus.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        UndoSendBus.pending.value = null
        val scheduled = p.scheduledAt != null
        if (!scheduled && seconds == 0) {
            host.showSnackbar("Sending…", duration = SnackbarDuration.Short)
            return@LaunchedEffect
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
    SnackbarHost(host, modifier.navigationBarsPadding().padding(bottom = 8.dp)) { DotSnackbar(it) }
}
