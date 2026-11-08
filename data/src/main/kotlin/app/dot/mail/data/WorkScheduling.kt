package app.dot.mail.data

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.TimeUnit

internal object WorkScheduling {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun periodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("dotmail.periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun sync(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("dotmail.sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun outbox(context: Context, id: Long, dueAt: Long, replace: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork("dotmail.outbox.$id", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OutboxWorker>().setInputData(workDataOf("id" to id)).setInitialDelay((dueAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS).setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (GraphRegistry.get(applicationContext).syncAll()) Result.success() else Result.retry()
    } catch (e: CancellationException) { throw e } catch (e: Exception) { SyncDiagnostics.warn("Sync worker", e); Result.retry() }
}
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (GraphRegistry.get(applicationContext).deliverOutbox(inputData.getLong("id", -1))) Result.success() else Result.retry()
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        SyncDiagnostics.warn("Outbox worker id=${inputData.getLong("id", -1)}", e)
        val graph = GraphRegistry.get(applicationContext)
        val id = inputData.getLong("id", -1)
        graph.dao.outbox(id)?.takeIf { it.state == "PENDING" }?.let {
            graph.dao.transitionOutbox(id, "PENDING", "PENDING", "Queued send could not be prepared; check attachments and account access before retrying")
        }
        Result.retry()
    }
}
internal class ForegroundIdle(private val graph: RealMailGraph) : DefaultLifecycleObserver {
    private var job: Job? = null
    fun start() {
        // LifecycleRegistry must be accessed from Main.
        graph.scope.launch(Dispatchers.Main.immediate) { ProcessLifecycleOwner.get().lifecycle.addObserver(this@ForegroundIdle) }
    }
    override fun onStart(owner: LifecycleOwner) {
        graph.requestSync()
        job?.cancel()
        job = graph.scope.launch {
            graph.dao.observeAccounts().collectLatest { accounts -> coroutineScope {
                accounts.forEach { account -> launch {
                    while (isActive) {
                        try {
                            val config = Codec.config(account.config)
                            val inbox = graph.dao.folders(account.id).firstOrNull { it.role == "INBOX" }?.path ?: "INBOX"
                            graph.client.idle(config.imap.endpoint(), graph.credentials(account), inbox) { graph.requestSync() }
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                        delay(30_000)
                    }
                } }
            } }
        }
    }
    override fun onStop(owner: LifecycleOwner) { job?.cancel(); job = null }
}
