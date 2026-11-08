package app.dot.mail.data

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*

/** Applying retention/storage policy never needs a network or an IMAP connection. */
class StorageCleanupWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val graph=GraphRegistry.get(applicationContext); val id=inputData.getLong("accountId",0)
        try {
            repeat(8) {
                val more=graph.storage.downloads.prune(id,graph.storage.preferences.policy(id))
                graph.storage.enforce(id); graph.storage.trimGlobal()
                if(!more) return Result.success()
                yield()
            }
            WorkScheduling.localStorage(applicationContext,id,append=true)
            return Result.success()
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { SyncDiagnostics.warn("Local storage cleanup account=$id",e); return Result.retry() }
    }
}
