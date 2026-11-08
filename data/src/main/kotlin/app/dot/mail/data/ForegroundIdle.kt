package app.dot.mail.data

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One IDLE owner: app visibility OR a running foreground service; handoffs preserve existing sockets. */
internal class ForegroundIdle(private val graph: RealMailGraph, private val idle: suspend () -> Unit = { IdleConnections(graph).run() }) : DefaultLifecycleObserver {
    private val visible=MutableStateFlow(false)
    internal val serviceRunning=MutableStateFlow(false)
    private var started=false

    fun start() {
        if(started) return
        started=true
        graph.scope.launch(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this@ForegroundIdle)
            launch {
                combine(visible,serviceRunning) { foreground,service -> foreground || service }.distinctUntilChanged()
                    .collectLatest { active -> if(active) {
                        while(isActive) {
                            try { idle(); delay(30_000) }
                            catch(e:CancellationException) { throw e }
                            catch(e:Exception) { SyncDiagnostics.warn("IDLE coordinator",e); delay(30_000) }
                        }
                    } }
            }
            AccountSettingsStore.get(graph.context).sync.map { it.instantNotifications }.distinctUntilChanged().collect { enabled ->
                if(!enabled) {
                    serviceRunning.value=false
                    graph.context.stopService(Intent(graph.context,InstantIdleService::class.java))
                } else if(visible.value) startService()
            }
        }
    }
    private fun startService() {
        try { ContextCompat.startForegroundService(graph.context,Intent(graph.context,InstantIdleService::class.java)) }
        catch(e:RuntimeException) {
            SyncDiagnostics.warn("Start instant notifications",e)
            AccountSettingsStore.get(graph.context).updateSync { it.copy(instantNotifications=false) }
        }
    }
    override fun onStart(owner:LifecycleOwner) {
        visible.value=true
        graph.requestSync()
        if(AccountSettingsStore.get(graph.context).sync.value.instantNotifications) startService()
    }
    override fun onStop(owner:LifecycleOwner) { visible.value=false }
}

/** Must be called from a visible UI to comply with foreground-service start restrictions. */
internal suspend fun RealMailGraph.changeInstantNotifications(enabled:Boolean): Boolean = withContext(Dispatchers.Main.immediate) {
    val settings=AccountSettingsStore.get(context)
    if(!enabled) {
        settings.updateSync { it.copy(instantNotifications=false) }
        foregroundIdle.serviceRunning.value=false
        context.stopService(Intent(context,InstantIdleService::class.java))
        return@withContext true
    }
    if(!ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withContext false
    if(withContext(Dispatchers.IO) { dao.accounts().isEmpty() }) return@withContext false
    try {
        ContextCompat.startForegroundService(context,Intent(context,InstantIdleService::class.java))
        settings.updateSync { it.copy(instantNotifications=true) }
        true
    } catch(e:RuntimeException) {
        SyncDiagnostics.warn("Enable instant notifications",e)
        false
    }
}
