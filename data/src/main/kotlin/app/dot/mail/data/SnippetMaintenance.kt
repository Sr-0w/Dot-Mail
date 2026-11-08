package app.dot.mail.data

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.dot.mail.data.local.*
import app.dot.mail.data.model.AccountConfig
import app.dot.mail.engine.*
import app.dot.mail.engine.text.Snippets
import kotlinx.coroutines.CancellationException

internal const val SNIPPET_REVISION=2

/** Persist reset before the revision marker; a process death can only cause a harmless repeat, never skip repair. */
internal suspend fun RealMailGraph.prepareSnippetRepair(accountId:Long) {
    val settings=AccountSettingsStore.get(context)
    if(settings.snippetRevision(accountId)<SNIPPET_REVISION) {
        dao.resetSnippetRevision(accountId)
        settings.markSnippetRevision(accountId,SNIPPET_REVISION)
    }
    if(scheduling && dao.pendingSnippetRepairs(accountId)>0) WorkScheduling.snippetRepair(context,accountId)
}

internal suspend fun RealMailGraph.recoverSnippetRepairs() {
    val settings=AccountSettingsStore.get(context)
    for(account in dao.accounts()) if(!account.authError && (settings.snippetRevision(account.id)<SNIPPET_REVISION || dao.pendingSnippetRepairs(account.id)>0))
        WorkScheduling.snippetRepair(context,account.id)
}

/** Runs after the requested folder's header/flag readiness callback. Bounded work preserves fast refresh. */
internal suspend fun RealMailGraph.prefetchFolderPreviews(account:AccountRow,config:AccountConfig,creds:Credentials,folder:FolderRow,remote:RemoteFolder,validity:Long) {
    val redactions=setOf(account.email,creds.secret)
    val cached=dao.folderMessages(folder.id)
    val local=cached.filter { it.bodyLoaded && !it.snippetFetched }.sortedByDescending { it.sentAt }.take(200)
    val repaired=local.mapNotNull { row -> bodyStore.read(row)?.let { row to Snippets.fromBody(it.plainText,it.html) } }
    database.withTransaction {
        repaired.forEach { (row,value) -> dao.setSnippet(row.id,value) }
        repaired.map { it.first.threadId }.distinct().forEach { rebuildThread(it) }
    }
    val rows=previewCandidates(cached)
    if(rows.isEmpty()) return
    val previews=try { client.snippets(config.imap.endpoint(),creds,folder.path,rows.map { it.uid }.toLongArray(),validity) }
    catch(e:CancellationException) { throw e }
    catch(e:Exception) { SyncDiagnostics.warn("Snippet prefetch account=${account.id} folderRole=${remote.role}",e,redactions); emptyMap() }
    database.withTransaction {
        previews.forEach { (uid,value) -> dao.byUid(folder.id,uid)?.takeUnless { it.bodyLoaded }?.let { dao.setSnippet(it.id,value) } }
        rows.map { it.threadId }.distinct().forEach { rebuildThread(it) }
    }
}

internal fun previewCandidates(cached:List<MessageRow>):List<MessageRow> {
    val remote=cached.filter { it.uid>0 && !it.bodyLoaded && !it.snippetFetched }
    val repair=remote.filter { it.snippet.isNotBlank() }.sortedByDescending { it.sentAt }
    val newest=cached.sortedByDescending { it.sentAt }.take(200).filter { it.uid>0 && !it.bodyLoaded && !it.snippetFetched }
    return (repair+newest).distinctBy { it.id }.take(200)
}

class SnippetRepairWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result = try {
        val graph=GraphRegistry.get(applicationContext)
        val account=inputData.getLong("accountId",-1)
        if(graph.dao.account(account)==null) Result.success()
        else {
            graph.prepareSnippetRepair(account)
            if(graph.dao.pendingSnippetRepairs(account)==0) Result.success()
            else {
                val ok=graph.syncAll(setOf(account))
                if(ok && graph.dao.pendingSnippetRepairs(account)==0) Result.success()
                else if(graph.dao.account(account)?.let { row -> row.authError || row.syncError?.let { SyncDiagnostics.state(it,row.authError).errorKind?.transient==false }==true }==true) Result.failure()
                else Result.retry()
            }
        }
    } catch(e:CancellationException) { throw e }
    catch(e:Exception) { SyncDiagnostics.warn("Snippet repair worker",e); Result.retry() }
}
