package app.dot.mail.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.dot.mail.DotMailApp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the widget fresh while the process is alive:
 *  - when the app comes to the foreground;
 *  - whenever the unified-inbox unread count changes (sync, read/archive in the app), debounced.
 * Observing only runs while at least one widget is placed. Call [install] once from Application.onCreate.
 */
object DotWidgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observing: Job? = null

    /** Cheap: only starts observing if a widget exists. */
    fun install(app: DotMailApp) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch { if (hasWidgets(app)) { start(app); update(app) } }
            }
        })
        scope.launch { if (hasWidgets(app)) start(app) }
    }

    @OptIn(FlowPreview::class)
    @Synchronized
    fun start(app: DotMailApp) {
        if (observing?.isActive == true) return
        observing = scope.launch {
            WidgetRepository.unreadCount(app.graph).distinctUntilChanged().debounce(1_000).collect { update(app) }
        }
    }

    @Synchronized
    fun stop() {
        observing?.cancel()
        observing = null
    }

    suspend fun update(context: Context) = DotMailWidget().updateAll(context)

    private suspend fun hasWidgets(context: Context) =
        GlanceAppWidgetManager(context).getGlanceIds(DotMailWidget::class.java).isNotEmpty()
}

/** Safety net for when the process isn't running: refresh at most every 30 minutes. */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        withContext(Dispatchers.Default) { DotWidgets.update(applicationContext) }
        return Result.success()
    }

    companion object {
        private const val NAME = "dotmail-widget"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES).build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
