package app.dot.mail.ui.outbox

import dev.chrisbanes.haze.hazeSource
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
import app.dot.design.components.dotSurfaceEnter
import androidx.compose.ui.res.stringResource
import app.dot.mail.R
import app.dot.mail.LocalMailGraph
import app.dot.mail.data.model.OutboxItem
import app.dot.mail.data.model.OutboxState
import app.dot.mail.ui.common.Format
import kotlinx.coroutines.launch

/** Messages that haven't fully left yet: scheduled, sending, or needing the user's decision. */
@Composable
fun OutboxScreen(onBack: () -> Unit, onEdit: (Long) -> Unit = {}) {
    val graph = LocalMailGraph.current
    val items by graph.compose.outbox.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var confirmDuplicate by remember { mutableStateOf<OutboxItem?>(null) }
    var rescheduling by remember { mutableStateOf<OutboxItem?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val reducedMotion = app.dot.design.rememberReducedMotion()
    val busyText = stringResource(R.string.outbox_busy_toast)
    fun busy(ok: Boolean) { if (!ok) scope.launch { snackbar.showSnackbar(busyText) } }
    val editUnavailable = stringResource(R.string.outbox_edit_unavailable)
    val c = Dot.colors
    Box(Modifier.fillMaxSize().background(c.background)) {
        val frostedSource=app.dot.design.components.rememberGlassBackdrop()
        app.dot.design.components.FrostedHeaderLayout(backdrop=frostedSource,header={ Column { DotTopBar(title = stringResource(R.string.outbox_title), elevated = listState.canScrollBackward, navigation = { BackButton(onBack) }) } }) { headerPadding ->

        if (items.isEmpty()) {
            Box(Modifier.padding(headerPadding)) { EmptyState(Icons.Outlined.Outbox, stringResource(R.string.outbox_empty_title), stringResource(R.string.outbox_empty_body))
            }
            return@FrostedHeaderLayout
        }
        LazyColumn(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(top=headerPadding.calculateTopPadding(),bottom = 24.dp), modifier = Modifier.fillMaxSize().hazeSource(frostedSource).navigationBarsPadding()) {
            items(items, key = { it.id }) { item ->
                Column(
                    Modifier.then(if (reducedMotion) Modifier else Modifier.animateItem()).fillMaxWidth().padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.l),
                ) {
                    Row {
                        Text(
                            item.subject.ifBlank { stringResource(R.string.outbox_no_subject) }, style = Dot.type.headline, color = c.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Text(stateLabel(item), style = Dot.type.caption, color = if (item.state == OutboxState.NEEDS_REVIEW || item.error != null) c.danger else c.textTertiary)
                    }
                    Text(
                        stringResource(R.string.outbox_to_prefix, item.recipients.joinToString(", ") { it.displayName }),
                        style = Dot.type.callout, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    item.error?.let {
                        Spacer(Modifier.height(DotSpacing.xs))
                        Text(it, style = Dot.type.caption, color = c.textTertiary)
                    }
                    if (item.canRetry || item.canDelete || item.canEdit || item.canReschedule) {
                        Spacer(Modifier.height(DotSpacing.m))
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(DotSpacing.s)) {
                            if (item.canEdit) {
                                // Takes the message back out of the queue and reopens it in the composer (nothing is lost).
                                DotButton(stringResource(R.string.outbox_edit), compact = true, style = ButtonStyle.Secondary, onClick = {
                                    scope.launch { if (graph.compose.cancelSend(item.id)) onEdit(item.draftId) else snackbar.showSnackbar(editUnavailable) }
                                })
                            }
                            if (item.canReschedule) {
                                DotButton(stringResource(R.string.outbox_reschedule), compact = true, style = ButtonStyle.Secondary, onClick = { rescheduling = item })
                            }
                            if (item.canRetry) {
                                DotButton(stringResource(R.string.outbox_retry), compact = true, style = ButtonStyle.Secondary, onClick = {
                                    if (item.state == OutboxState.NEEDS_REVIEW) confirmDuplicate = item
                                    else scope.launch { busy(graph.compose.retrySend(item.id)) }
                                })
                            }
                            if (item.canDelete) {
                                DotButton(stringResource(R.string.outbox_delete), compact = true, style = ButtonStyle.Ghost, onClick = { scope.launch { busy(graph.compose.deleteOutbox(item.id)) } })
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
    rescheduling?.let { item ->
        app.dot.mail.ui.schedule.ScheduleSheet(
            title = stringResource(R.string.outbox_reschedule_title),
            onDismiss = { rescheduling = null },
            onPick = { at -> rescheduling = null; scope.launch { busy(graph.compose.rescheduleSend(item.id, at)) } },
        )
    }
    confirmDuplicate?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDuplicate = null },
            modifier = Modifier.dotSurfaceEnter(),
            containerColor = c.surface,
            shape = DotShapes.large,
            title = { Text(stringResource(R.string.outbox_send_again_title), style = Dot.type.title, color = c.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.outbox_send_again_body),
                    style = Dot.type.callout, color = c.textSecondary,
                )
            },
            confirmButton = {
                DotButton(stringResource(R.string.outbox_send_anyway), compact = true, onClick = {
                    confirmDuplicate = null
                    scope.launch { busy(graph.compose.retrySend(item.id, confirmPossibleDuplicate = true)) }
                })
            },
            dismissButton = { DotButton(stringResource(R.string.outbox_cancel), { confirmDuplicate = null }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
}

@Composable
private fun stateLabel(item: OutboxItem): String = when (item.state) {
    OutboxState.QUEUED -> if (app.dot.mail.data.SendSchedule.isScheduled(item.sendAt, System.currentTimeMillis())) stringResource(R.string.outbox_state_scheduled, app.dot.mail.ui.schedule.scheduleLabel(androidx.compose.ui.platform.LocalContext.current, item.sendAt))
        else stringResource(if (item.error != null) R.string.outbox_state_waiting_retry else R.string.outbox_state_queued)
    OutboxState.SENDING -> stringResource(R.string.outbox_state_sending)
    OutboxState.SAVING_SENT -> stringResource(R.string.outbox_state_saving_sent)
    OutboxState.NEEDS_REVIEW -> stringResource(R.string.outbox_state_needs_review)
}
