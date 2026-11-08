package app.dot.mail.data

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.room.withTransaction
import androidx.work.*
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import kotlinx.coroutines.*

internal enum class OfflinePass { DONE, MORE, RETRY }
internal class OfflineDownloads(private val manager:StorageManager) {
    private val graph=manager.graph
    internal var networkAllowed:(StoragePolicy)->Boolean = { policy ->
        val service=graph.context.getSystemService(ConnectivityManager::class.java)
        val caps=service?.getNetworkCapabilities(service.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true && (!policy.wifiOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }
    internal var charging:()->Boolean = { graph.context.getSystemService(BatteryManager::class.java)?.isCharging==true }
    private suspend fun allowed(id:Long,policy:StoragePolicy,pinnedOnly:Boolean=false):Boolean {
        val state=when {
            manager.preferences.paused(id) && !pinnedOnly->OfflineState.PAUSED
            !networkAllowed(policy)->OfflineState.WAITING_FOR_NETWORK
            policy.chargingOnly && !charging()->OfflineState.WAITING_FOR_CHARGING
            else->null
        }
        if(state!=null) { if(!pinnedOnly) manager.report(id,state); return false }
        return true
    }
    suspend fun run(id:Long,pageSize:Int=200,pinnedOnly:Boolean=false):OfflinePass = withContext(Dispatchers.IO) {
        val account=graph.dao.account(id) ?: return@withContext OfflinePass.DONE
        val policy=manager.preferences.policy(id).let { if(pinnedOnly) it.copy(retention=RetentionMode.ALL,downloadBodies=false) else it }
        suspend fun update(state:OfflineState,error:String?=null) { if(!pinnedOnly) manager.report(id,state,error) }
        // Cleanup works offline too; it never calls a message delete/move/flag command.
        val initialPruneMore=if(pinnedOnly) false else prune(id,policy); manager.enforce(id)
        if(!allowed(id,policy,pinnedOnly)) return@withContext OfflinePass.DONE
        update(OfflineState.DOWNLOADING)
        var redactions=setOf(account.email)
        try {
            val config=Codec.config(account.config); val credentials=graph.credentials(account); redactions=setOf(account.email,credentials.secret)
            graph.client.withImapSession(config.imap.endpoint(),credentials) {
                if(!pinnedOnly && graph.dao.folders(id).isEmpty()) {
                    val discovered=graph.client.folders(config.imap.endpoint(),credentials)
                    graph.database.withTransaction {
                        val existing=graph.dao.folders(id).map { it.path }.toSet()
                        discovered.filter { it.path !in existing }.forEach { graph.dao.insertFolder(FolderRow(accountId=id,path=it.path,name=it.name,role=it.role.name,total=it.total,unread=it.unread)) }
                    }
                }
                val folders=graph.dao.folders(id)
                val states=if(pinnedOnly) emptyList() else folders.map { folder -> folder to graph.dao.offlineFolder(folder.id) }
                var pages=0; var moreHeaders=false; var headerAdded=false
                for((folder,previous) in states.sortedWith(compareBy<Pair<FolderRow,OfflineFolderRow?>> { it.second?.visitedAt ?: 0 }.thenBy { if(it.first.role=="INBOX") 0 else 1 })) {
                    if(!allowed(id,policy,pinnedOnly)) return@withImapSession OfflinePass.DONE
                    val state=previous?.takeIf { it.validity==folder.validity } ?: OfflineFolderRow(folder.id,folder.validity)
                    val stale=state.complete && folder.nextUid>state.completedThrough
                    if(state.complete && !stale) continue
                    if(pages>=2) { moreHeaders=true; continue }
                    pages++
                    val before=if(state.complete) 0 else state.beforeUid
                    if(manager.availableBytes()<StorageManager.RESERVE_BYTES) throw CacheStorageException(true,"Device storage is low. Free space to download mail.")
                    manager.globalHeadroom()
                    val page=graph.client.headerPage(config.imap.endpoint(),credentials,folder.path,before,pageSize,folder.validity.takeIf { it>0 })
                    val oldest=page.headers.minOfOrNull { it.sentAt }
                    val cutoff=System.currentTimeMillis()-policy.days*86400000L
                    val retentionReached=when(policy.retention) {
                        RetentionMode.ALL->false
                        RetentionMode.LAST_DAYS->oldest!=null && page.headers.all { it.sentAt<cutoff }
                        RetentionMode.LATEST_MESSAGES->before>0 && graph.dao.cachedMessageCount(id)>=policy.latestMessages && page.headers.all { it.sentAt<(graph.dao.messageDateAt(id,policy.latestMessages-1) ?: 0) }
                    }
                    val complete=!page.hasMore || retentionReached || (state.completedThrough>0 && page.nextBeforeUid<state.completedThrough)
                    graph.database.withTransaction {
                        val current=graph.dao.folder(folder.id) ?: return@withTransaction
                        if(current.validity!=0L && current.validity!=page.validity) error("Folder changed during offline download")
                        val selected=current.copy(validity=page.validity)
                        if(current.validity==0L) graph.dao.updateFolder(selected.copy(total=maxOf(current.total,page.headers.size)))
                        val changed=linkedSetOf<Long>()
                        page.headers.filter { !it.flags.deleted }.forEach { val result=graph.upsertHeader(selected,it,rebuild=false); changed+=result.first; if(result.second) headerAdded=true }
                        changed.forEach { graph.rebuildThread(it) }
                        val through=if(before==0L) page.uidNext else state.scanThrough
                        graph.dao.putOfflineFolder(OfflineFolderRow(folder.id,page.validity,page.nextBeforeUid,through,if(complete) through else state.completedThrough,complete,System.currentTimeMillis(),state.headerFailures+page.failures.size))
                    }
                    page.failures.forEach { SyncDiagnostics.warn("Offline header account=$id UID=${it.uid}",it.error,setOf(account.email,credentials.secret)) }
                    if(!complete) moreHeaders=true
                    update(OfflineState.DOWNLOADING)
                }
                val pruneMore=if(initialPruneMore || headerAdded) prune(id,policy) else false
                var bodiesRemain=false
                val ids=graph.dao.offlineBodyCandidates(id,policy.downloadAttachments,policy.downloadBodies,System.currentTimeMillis()-6*3600000L,8,0,if(policy.retention==RetentionMode.LATEST_MESSAGES) policy.latestMessages else 0,if(policy.retention==RetentionMode.LAST_DAYS) System.currentTimeMillis()-policy.days*86400000L else 0)
                for(message in ids) {
                    if(pinnedOnly && graph.dao.cacheAccess(message)?.offlineSaved!=true) continue
                    if(!allowed(id,policy,pinnedOnly)) return@withImapSession OfflinePass.DONE
                    try {
                        manager.checkSpace(id)
                        manager.bodies.ensure(message,automatic=true)
                        if(policy.downloadAttachments) manager.ensureAttachments(message,automatic=true)
                    } catch(e:CancellationException) { throw e }
                    catch(e:CacheStorageException) { throw e }
                    catch(e:Exception) {
                        val kind=ConnectionFailure.classify(e)
                        if(kind!=ConnectionFailure.OTHER) throw e
                        val old=graph.dao.cacheAccess(message)
                        graph.dao.putCacheAccess(CacheAccessRow(message,old?.accessedAt ?: 0,old?.offlineSaved ?: false,System.currentTimeMillis(),SyncDiagnostics.reason(e)))
                    }
                    update(OfflineState.DOWNLOADING)
                }
                if(graph.dao.offlineBodyCandidates(id,policy.downloadAttachments,policy.downloadBodies,System.currentTimeMillis()-6*3600000L,1,0,if(policy.retention==RetentionMode.LATEST_MESSAGES) policy.latestMessages else 0,if(policy.retention==RetentionMode.LAST_DAYS) System.currentTimeMillis()-policy.days*86400000L else 0).isNotEmpty()) bodiesRemain=true
                if(moreHeaders || bodiesRemain || pruneMore) OfflinePass.MORE
                else {
                    val failures=graph.dao.downloadErrorCount(id)+graph.dao.offlineHeaderFailures(id)
                    update(if(failures>0) OfflineState.PARTIAL else OfflineState.COMPLETE,if(failures>0) "Some messages could not be downloaded. Resume to retry." else null)
                    OfflinePass.DONE
                }
            }
        } catch(e:CancellationException) { throw e }
        catch(e:CacheStorageException) { update(if(e.deviceFull) OfflineState.DEVICE_FULL else OfflineState.STORAGE_LIMIT,e.message); OfflinePass.DONE }
        catch(e:Exception) {
            val kind=ConnectionFailure.classify(e)
            update(OfflineState.ERROR,SyncDiagnostics.reason(e))
            SyncDiagnostics.warn("Offline download account=$id",e,redactions)
            if(kind==ConnectionFailure.AUTH) graph.dao.syncError(id,SyncDiagnostics.stored(e),true)
            if(e is IllegalArgumentException && e.message=="Folder UIDVALIDITY changed") graph.requestSync()
            if(kind.transient || kind==ConnectionFailure.OTHER) OfflinePass.RETRY else OfflinePass.DONE
        }
    }
    internal suspend fun prune(id:Long,policy:StoragePolicy):Boolean {
        if(!manager.preferences.configured(id)) return false
        val skip=manager.preferences.pruneCursor(id)
        val candidates=when(policy.retention) {
            RetentionMode.ALL->emptyList()
            RetentionMode.LAST_DAYS->graph.dao.outsideAge(id,System.currentTimeMillis()-policy.days*86400000L,200,skip)
            RetentionMode.LATEST_MESSAGES->graph.dao.outsideLatest(id,policy.latestMessages,skip)
        }
        val refs=manager.files.protectedFiles()
        if(id in refs.uncertainAccounts) return false
        var protected=0; var policyChanged=false; val changed=linkedSetOf<Long>()
        try {
            for(message in candidates) {
                if(manager.preferences.policy(id)!=policy) { policyChanged=true; break }
                if(manager.evict(message,refs,removeHeader=true,changedThreads=changed).second) protected++
            }
        } finally {
            withContext(NonCancellable) { graph.database.withTransaction { changed.forEach { thread -> if(!graph.dao.threadHasMessages(thread)) graph.dao.deleteThread(thread) else graph.rebuildThread(thread) } } }
        }
        manager.preferences.pruneCursor(id,if(policyChanged || candidates.size<200) 0 else skip+protected)
        return policyChanged || candidates.size>=200
    }

}

class OfflineWorker(context:android.content.Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val graph=GraphRegistry.get(applicationContext); val id=inputData.getLong("accountId",0); val pinned=inputData.getBoolean("pinnedOnly",false)
        fun next() { if(pinned) WorkScheduling.pinned(applicationContext,id,graph.storage.preferences.policy(id),append=true) else WorkScheduling.offline(applicationContext,id,graph.storage.preferences.policy(id),append=true) }
        val started=System.currentTimeMillis()
        repeat(16) {
            when(graph.storage.downloads.run(id,pinnedOnly=pinned)) {
                OfflinePass.DONE->return Result.success()
                OfflinePass.RETRY->return Result.retry()
                OfflinePass.MORE->if(System.currentTimeMillis()-started>180000) {
                    next()
                    return Result.success()
                }
            }
            yield()
        }
        next()
        return Result.success()
    }
}
