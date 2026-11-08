package app.dot.mail.data

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

internal object WorkScheduling {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun searchIndex(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("dotmail.search-index",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SearchIndexWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build())
    }
    fun localStorage(context:Context,id:Long,append:Boolean=false,replace:Boolean=false) {
        WorkManager.getInstance(context).enqueueUniqueWork("dotmail.storage.$id",if(replace) ExistingWorkPolicy.REPLACE else if(append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<StorageCleanupWorker>().setInputData(workDataOf("accountId" to id)).build())
    }
    internal fun offlineRequest(id:Long,policy:app.dot.mail.data.model.StoragePolicy,pinnedOnly:Boolean=false):OneTimeWorkRequest {
        val constraints=Constraints.Builder().setRequiredNetworkType(if(policy.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresCharging(policy.chargingOnly).build()
        return OneTimeWorkRequestBuilder<OfflineWorker>().setInputData(workDataOf("accountId" to id,"pinnedOnly" to pinnedOnly)).setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build()
    }
    fun offline(context:Context,id:Long,policy:app.dot.mail.data.model.StoragePolicy,replace:Boolean=false,append:Boolean=false) {
        WorkManager.getInstance(context).enqueueUniqueWork("dotmail.offline.$id",if(replace) ExistingWorkPolicy.REPLACE else if(append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,offlineRequest(id,policy))
    }
    fun pinned(context:Context,id:Long,policy:app.dot.mail.data.model.StoragePolicy,append:Boolean=false) {
        WorkManager.getInstance(context).enqueueUniqueWork("dotmail.pinned.$id",if(append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,offlineRequest(id,policy,pinnedOnly=true))
    }
    fun cancelOffline(context:Context,id:Long) { WorkManager.getInstance(context).cancelUniqueWork("dotmail.offline.$id"); WorkManager.getInstance(context).cancelUniqueWork("dotmail.pinned.$id") }
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
    fun snippetRepair(context:Context,accountId:Long) {
        WorkManager.getInstance(context).enqueueUniqueWork("dotmail.snippets.$accountId",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SnippetRepairWorker>().setInputData(workDataOf("accountId" to accountId)).setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build())
    }
    fun drafts(context: Context, accountId: Long, delayMs: Long = DRAFT_DEBOUNCE_MS) {
        WorkManager.getInstance(context).enqueueUniqueWork("dotmail.drafts.$accountId",ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DraftMirrorWorker>().setInputData(workDataOf("accountId" to accountId))
                .setInitialDelay(delayMs,TimeUnit.MILLISECONDS).setConstraints(network)
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
        val graph=GraphRegistry.get(applicationContext)
        if (graph.syncAll(scheduled = inputData.getBoolean("scheduled", true))) Result.success()
        else if(graph.dao.accounts().any { row -> row.syncError?.let { SyncDiagnostics.state(it,row.authError).let { state -> !state.isAuthError && state.errorKind?.transient!=false } }==true }) Result.retry()
        else Result.failure()
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
        if(ok && graph.dao.draftMirrors(accountId).none { it.deleted || it.revision!=it.syncedRevision || it.attemptRevision!=null } && graph.dao.operations(accountId).none { it.action==OP_TRASH_DRAFT }) Result.success() else Result.retry()
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
