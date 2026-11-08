package app.dot.mail.ui.outbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import app.dot.design.components.DotSnackbar
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.BackButton
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotTopBar
import app.dot.design.components.EmptyState
import app.dot.design.components.Hairline
import app.dot.mail.LocalMailGraph
import app.dot.mail.data.model.OutboxItem
import app.dot.mail.data.model.OutboxState
import app.dot.mail.ui.common.Format
import kotlinx.coroutines.launch

/** Messages that haven't fully left yet: scheduled, sending, or needing the user's decision. */
@Composable
fun OutboxScreen(onBack: () -> Unit) {
    val graph = LocalMailGraph.current
    val items by graph.compose.outbox.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var confirmDuplicate by remember { mutableStateOf<OutboxItem?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    fun busy(ok: Boolean) { if (!ok) scope.launch { snackbar.showSnackbar("This message is being processed. Try again in a moment.") } }
    val c = Dot.colors
    Box(Modifier.fillMaxSize().background(c.background)) {
    Column(Modifier.fillMaxSize()) {
        DotTopBar(title = "Outbox", elevated = listState.canScrollBackward, navigation = { BackButton(onBack) })
        if (items.isEmpty()) {
            EmptyState(Icons.Outlined.Outbox, "Outbox is empty", "Scheduled and pending messages appear here.")
            return@Column
        }
        LazyColumn(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp), modifier = Modifier.navigationBarsPadding()) {
            items(items, key = { it.id }) { item ->
                Column(Modifier.fillMaxWidth().padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.l)) {
                    Row {
                        Text(
                            item.subject.ifBlank { "(no subject)" }, style = Dot.type.headline, color = c.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Text(stateLabel(item), style = Dot.type.caption, color = if (item.state == OutboxState.NEEDS_REVIEW || item.error != null) c.danger else c.textTertiary)
                    }
                    Text(
                        "To " + item.recipients.joinToString(", ") { it.displayName },
                        style = Dot.type.callout, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    item.error?.let {
                        Spacer(Modifier.height(DotSpacing.xs))
                        Text(it, style = Dot.type.caption, color = c.textTertiary)
                    }
                    if (item.canRetry || item.canDelete) {
                        Spacer(Modifier.height(DotSpacing.m))
                        Row(horizontalArrangement = Arrangement.spacedBy(DotSpacing.s)) {
                            if (item.canRetry) {
                                DotButton("Retry", compact = true, style = ButtonStyle.Secondary, onClick = {
                                    if (item.state == OutboxState.NEEDS_REVIEW) confirmDuplicate = item
                                    else scope.launch { busy(graph.compose.retrySend(item.id)) }
                                })
                            }
                            if (item.canDelete) {
                                DotButton("Delete", compact = true, style = ButtonStyle.Ghost, onClick = { scope.launch { busy(graph.compose.deleteOutbox(item.id)) } })
                            }
                        }
                    }
                }
                Hairline(Modifier.padding(horizontal = DotSpacing.gutter))
            }
        }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp)) { DotSnackbar(it) }
    }
    confirmDuplicate?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDuplicate = null },
            containerColor = c.surface,
            shape = DotShapes.large,
            title = { Text("Send again?", style = Dot.type.title, color = c.textPrimary) },
            text = {
                Text(
                    "The connection dropped while this message was being sent, so it may already have been delivered. " +
                        "Check your Sent folder or ask the recipient before sending it again.",
                    style = Dot.type.callout, color = c.textSecondary,
                )
            },
            confirmButton = {
                DotButton("Send anyway", compact = true, onClick = {
                    confirmDuplicate = null
                    scope.launch { busy(graph.compose.retrySend(item.id, confirmPossibleDuplicate = true)) }
                })
            },
            dismissButton = { DotButton("Cancel", { confirmDuplicate = null }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
}

private fun stateLabel(item: OutboxItem): String = when (item.state) {
    OutboxState.QUEUED -> if (item.sendAt > System.currentTimeMillis() + 60_000) "Scheduled · " + Format.listDate(item.sendAt) else if (item.error != null) "Waiting to retry" else "Queued"
    OutboxState.SENDING -> "Sending…"
    OutboxState.SAVING_SENT -> "Saving to Sent…"
    OutboxState.NEEDS_REVIEW -> "Needs review"
}
