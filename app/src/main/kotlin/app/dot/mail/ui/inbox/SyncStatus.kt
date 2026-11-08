package app.dot.mail.ui.inbox

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import app.dot.design.DotMotion
import app.dot.design.DotSpacing
import app.dot.design.components.DotMenu
import app.dot.design.components.DotMenuItem
import app.dot.design.rememberReducedMotion
import app.dot.mail.R
import app.dot.mail.data.model.Account
import app.dot.mail.data.model.SyncState
import kotlinx.coroutines.delay

/**
 * Inbox title with a quiet sync status line: "<account> · Synced 14:02". When a sync finishes cleanly, a small
 * checkmark fades/scales in next to the label for a moment. Nothing is shown (and the time never advances) for a failed
 * or partial sync: [Account.lastSyncedAt] only moves on a complete, error-free sync, and the error banner takes over.
 * In the unified inbox, the label is the *oldest* account's time (honest), and tapping it lists each account.
 */
@Composable
internal fun InboxTitle(title: String, subtitle: String?, accounts: List<Account>, userSyncing: Boolean) {
    val syncing = userSyncing || accounts.any { it.syncState is SyncState.Syncing }
    val failing = accounts.any { it.syncState is SyncState.Error }
    val oldest = if (accounts.isEmpty() || accounts.any { it.lastSyncedAt <= 0L }) 0L else accounts.minOf { it.lastSyncedAt }
    val newest = accounts.maxOfOrNull { it.lastSyncedAt } ?: 0L
    val reducedMotion = rememberReducedMotion()

    // Checkmark: only when a sync we saw running ended with a newer success time and nothing failing.
    var newestAtStart by remember { mutableLongStateOf(-1L) }
    var showCheck by remember { mutableStateOf(false) }
    LaunchedEffect(syncing) {
        if (syncing) { newestAtStart = newest; return@LaunchedEffect }
        if (newestAtStart >= 0 && newest > newestAtStart && !failing) {
            showCheck = true
            delay(1600)
            showCheck = false
        }
        newestAtStart = -1L
    }

    var details by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // System 12/24-hour setting for the time; the date is added when it wasn't today.
    fun time(at: Long): String {
        val clock = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(at))
        return if (DateUtils.isToday(at)) clock else android.text.format.DateFormat.getMediumDateFormat(context).format(java.util.Date(at)) + " " + clock
    }

    Column {
        Text(title, style = Dot.type.title, color = Dot.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box {
            Row(
                Modifier.then(if (accounts.size > 1 && oldest > 0) Modifier.clickable(onClickLabel = stringResource(R.string.inbox_synced_details)) { details = true } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val parts = listOfNotNull(subtitle, if (oldest > 0 && !failing) stringResource(R.string.inbox_last_synced, time(oldest)) else null)
                if (parts.isNotEmpty()) {
                    Text(
                        parts.joinToString(" · "), style = Dot.type.caption, color = Dot.colors.textTertiary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                }
                AnimatedVisibility(
                    visible = showCheck,
                    enter = if (reducedMotion) EnterTransition.None else
                        fadeIn(tween(DotMotion.fast)) + scaleIn(tween(DotMotion.fast), initialScale = 0.6f) + expandHorizontally(tween(DotMotion.fast)),
                    exit = if (reducedMotion) ExitTransition.None else
                        fadeOut(tween(DotMotion.medium)) + scaleOut(tween(DotMotion.medium), targetScale = 0.8f) + shrinkHorizontally(tween(DotMotion.medium)),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(DotSpacing.xs))
                        Icon(
                            Icons.Outlined.Check, stringResource(R.string.inbox_sync_complete),
                            Modifier.size(14.dp).semantics { liveRegion = LiveRegionMode.Polite }, tint = Dot.colors.accent,
                        )
                    }
                }
            }
            DotMenu(expanded = details, onDismiss = { details = false }) {
                accounts.forEach { a ->
                    DotMenuItem(
                        text = a.email + "\n" + if (a.lastSyncedAt > 0) stringResource(R.string.inbox_last_synced, time(a.lastSyncedAt)) else stringResource(R.string.inbox_never_synced),
                        icon = null, onClick = { details = false },
                    )
                }
            }
        }
    }
}
