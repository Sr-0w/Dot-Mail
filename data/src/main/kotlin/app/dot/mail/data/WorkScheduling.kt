package app.dot.mail.data

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

internal object WorkScheduling {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun periodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("dotmail.periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun sync(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("dotmail.sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setInputData(workDataOf("scheduled" to false)).setConstraints(network).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    /** One-time work at [dueAt] (exact-ish, no exact-alarm permission): wakes whatever snoozed items are due. */
    fun snooze(context: Context, id: Long, dueAt: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork("dotmail.snooze.$id", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SnoozeWorker>().setInitialDelay((dueAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS).build())
    }
    fun cancelSnooze(context: Context, id: Long) { WorkManager.getInstance(context).cancelUniqueWork("dotmail.snooze.$id") }
    fun drafts(context: Context, accountId: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork("dotmail.drafts.$accountId",ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DraftMirrorWorker>().setInputData(workDataOf("accountId" to accountId))
                .setInitialDelay(DRAFT_DEBOUNCE_MS,TimeUnit.MILLISECONDS).setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build())
    }
    fun outbox(context: Context, id: Long, dueAt: Long, replace: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork("dotmail.outbox.$id", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OutboxWorker>().setInputData(workDataOf("id" to id)).setInitialDelay((dueAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS).setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        GraphRegistry.get(applicationContext).resurfaceDue() // safety net if a snooze worker was missed
        if (GraphRegistry.get(applicationContext).syncAll(scheduled = inputData.getBoolean("scheduled", true))) Result.success() else Result.retry()
    } catch (e: CancellationException) { throw e } catch (e: Exception) { SyncDiagnostics.warn("Sync worker", e); Result.retry() }
}
class SnoozeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        GraphRegistry.get(applicationContext).resurfaceDue(); Result.success()
    } catch (e: CancellationException) { throw e } catch (e: Exception) { SyncDiagnostics.warn("Snooze worker", e); Result.retry() }
}
class DraftMirrorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = try {
        val graph=GraphRegistry.get(applicationContext)
        val accountId=inputData.getLong("accountId",-1)
        val ok=graph.syncAll(setOf(accountId))
        if(ok && graph.dao.draftMirrors(accountId).none { it.deleted || it.revision!=it.syncedRevision || it.attemptRevision!=null }) Result.success() else Result.retry()
    } catch(e: CancellationException) { throw e } catch(e: Exception) { SyncDiagnostics.warn("Draft mirror worker",e); Result.retry() }
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
