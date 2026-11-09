package app.dot.mail.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * WorkManager delays are relative, so after the wall clock or the timezone changes (or the phone restarts) a queued
 * scheduled message would fire at the wrong wall time. Each of these events re-enqueues every pending outbox item from
 * its absolute due time, replacing the earlier work (unique per item), so nothing is sent early, late or twice.
 */
class OutboxClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!handles(intent.action)) return
        val pending = goAsync()
        val graph = GraphRegistry.get(context)
        graph.scope.launch {
            try { graph.requeueOutbox() } catch (_: Exception) { /* WorkManager recovery on next start still covers it. */ }
            finally { pending.finish() }
        }
    }

    companion object {
        /** Clock set (ACTION_TIME_CHANGED), timezone change, and boot. */
        val ACTIONS = setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_BOOT_COMPLETED)
        fun handles(action: String?) = action != null && action in ACTIONS
    }
}

/** Re-enqueues every pending outbox item from its absolute due time, replacing existing work; returns how many. */
internal suspend fun RealMailGraph.requeueOutbox(enqueue: (id: Long, dueAt: Long) -> Unit = { id, due -> WorkScheduling.outbox(context, id, due, replace = true) }): Int {
    val rows = dao.pendingOutbox()
    rows.forEach { enqueue(it.id, it.dueAt) }
    return rows.size
}
