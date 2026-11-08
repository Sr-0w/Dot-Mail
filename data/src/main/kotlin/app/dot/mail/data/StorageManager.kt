package app.dot.mail.data

import android.os.StatFs
import androidx.room.withTransaction
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class CacheStorageException(val deviceFull:Boolean,message:String):IOException(message)
internal data class CacheBudget(val account:Long,val bytes:Long,val diskBytes:Long)
internal class StorageManager(internal val graph:RealMailGraph) {
    val preferences=StoragePreferences(graph.context)
    val files=StorageFiles(graph)
    val bodies=StorageBodies(graph,this)
    val downloads=OfflineDownloads(this)
    val signals=storageSignals(graph.context).shareIn(graph.scope,SharingStarted.WhileSubscribed(5000),replay=1)
    private val activeViews=ConcurrentHashMap<Long,AtomicLong>()
    fun watch(id:Long,enter:Boolean) { activeViews.compute(id) { _,old -> val value=old ?: AtomicLong(); if(value.addAndGet(if(enter) 1 else -1)>0) value else null } }
    private val usage=ConcurrentHashMap<Long,AtomicLong>()
    @Volatile private var totalCached:AtomicLong?=null
    private val reservations=hashMapOf<Long,Long>()
    private var diskReserved=0L
    private val gate=Any()
    internal var availableBytes:()->Long = { StatFs(graph.context.filesDir.path).availableBytes }
    private fun cacheBytes(id:Long)=usage.computeIfAbsent(id) { AtomicLong(files.size(File(graph.context.filesDir,"bodies/$id"))+files.size(File(graph.context.filesDir,"attachments/$id"))) }.get()
    fun changed(id:Long,delta:Long) { usage[id]?.updateAndGet { (it+delta).coerceAtLeast(0) }; totalCached?.updateAndGet { (it+delta).coerceAtLeast(0) } }
    fun invalidate(id:Long) { usage.remove(id); totalCached=null }
    fun invalidateAll() { usage.clear(); totalCached=null }
    private fun databaseBytes():Long {
        val db=graph.context.getDatabasePath("dotmail.db")
        return listOf(db,File(db.path+"-wal"),File(db.path+"-shm")).sumOf { it.length() }
    }
    private fun totalCacheBytes():Long = totalCached?.get() ?: synchronized(gate) {
        (totalCached ?: AtomicLong(files.size(File(graph.context.filesDir,"bodies"))+files.size(File(graph.context.filesDir,"attachments"))).also { totalCached=it }).get()
    }
    fun globalHeadroom(estimated:Long=2L*1024*1024) {
        val limit=preferences.globalLimit()
        if(limit>0 && totalCacheBytes()+databaseBytes()+files.size(File(graph.context.filesDir,"outbox"))+estimated>limit)
            throw CacheStorageException(false,"Total mail storage limit reached. Clear cached mail or increase the limit.")
    }
    suspend fun globalLimit(bytes:Long)=withContext(Dispatchers.IO) {
        preferences.globalLimit(bytes)
        trimGlobal()
    }
    suspend fun trimGlobal():Boolean {
        val limit=preferences.globalLimit(); if(limit==0L) return true
        val refs=files.protectedFiles()
        var offset=0
        repeat(10) {
            try { globalHeadroom(0); return true } catch(_:CacheStorageException) {}
            val ids=graph.dao.globalEvictionCandidates(200,offset); if(ids.isEmpty()) return@repeat
            var removed=0
            for(id in ids) { val result=evict(id,refs); if(!result.second) removed++ }
            if(removed==0) offset+=ids.size
        }
        // Free SQLite pages are reusable but still occupy disk. Compact only with enough temporary space.
        val file=graph.context.getDatabasePath("dotmail.db")
        if(file.isFile && databaseBytes()>8L*1024*1024 && availableBytes()>databaseBytes()+RESERVE_BYTES) {
            runCatching { graph.database.openHelper.writableDatabase.execSQL("VACUUM") }
        }
        return try { globalHeadroom(0); true } catch(_:CacheStorageException) { false }
    }
    fun checkSpace(id:Long) {
        if(availableBytes()<RESERVE_BYTES+8192) throw CacheStorageException(true,"Device storage is low. Free space to download mail.")
        globalHeadroom()
        val policy=preferences.policy(id)
        if(policy.maxBytes>0 && cacheBytes(id)>=policy.maxBytes) throw CacheStorageException(false,"Mail storage limit reached. Increase the limit or clear cached mail.")
    }
    fun reserve(id:Long,desired:Long,temporaryFactor:Int=1):CacheBudget {
        val used=cacheBytes(id); val policy=preferences.policy(id)
        return synchronized(gate) {
            val disk=(availableBytes()-RESERVE_BYTES-diskReserved)/temporaryFactor
            val accountAllowance=if(policy.maxBytes==0L) Long.MAX_VALUE else policy.maxBytes-used-(reservations[id] ?: 0)
            val global=preferences.globalLimit()
            val totalAllowance=if(global==0L) Long.MAX_VALUE else global-totalCacheBytes()-databaseBytes()-files.size(File(graph.context.filesDir,"outbox"))-diskReserved-2L*1024*1024
            val allowance=minOf(accountAllowance,totalAllowance)
            val limit=minOf(desired,allowance,disk)
            if(limit<1) throw CacheStorageException(disk<=0,if(disk<=0) "Device storage is low. Free space to download mail." else "Mail storage limit reached. Increase the limit or clear cached mail.")
            reservations[id]=(reservations[id] ?: 0)+limit; diskReserved+=limit*temporaryFactor
            CacheBudget(id,limit,limit*temporaryFactor)
        }
    }
    fun release(token:CacheBudget,delta:Long) { synchronized(gate) { changed(token.account,delta); reservations[token.account]=((reservations[token.account] ?: 0)-token.bytes).coerceAtLeast(0); diskReserved=(diskReserved-token.diskBytes).coerceAtLeast(0) } }
    suspend fun prepareSpace(id:Long,desired:Long,automatic:Boolean,exclude:Long) {
        trimGlobal()
        val policy=preferences.policy(id)
        val pinned=graph.dao.cacheAccess(exclude)?.offlineSaved==true
        if(availableBytes()<RESERVE_BYTES+desired*2) {
            val refs=files.protectedFiles()
            for(message in graph.dao.globalEvictionCandidates(200,0)) {
                val candidate=graph.dao.message(message) ?: continue
                if(message!=exclude && !(automatic && policy.retention==RetentionMode.ALL && candidate.accountId==id)) evict(message,refs)
                if(availableBytes()>=RESERVE_BYTES+desired*2) break
            }
        }
        if(policy.maxBytes==0L || (automatic && policy.retention==RetentionMode.ALL && !pinned)) return
        val target=(policy.maxBytes-desired.coerceAtMost(policy.maxBytes)).coerceAtLeast(0)
        if(cacheBytes(id)<=target) return
        val refs=files.protectedFiles()
        for(message in graph.dao.evictionCandidates(id,200,0)) {
            if(message!=exclude) evict(message,refs)
            if(cacheBytes(id)<=target) break
        }
    }
    suspend fun ensureBody(id:Long) = bodies.ensure(id)
    suspend fun open(id:Long):String = bodies.open(id)
    suspend fun ensureAttachments(id:Long,automatic:Boolean=false) {
        bodies.ensure(id,automatic)
        graph.dao.attachments(id).filter { !files.exists(it.localUri) }.forEach { bodies.open(it.id,automatic=automatic) }
    }
    suspend fun setPolicy(id:Long,policy:StoragePolicy)=withContext(Dispatchers.IO) {
        requireNotNull(graph.dao.account(id)) { "Account unavailable" }
        preferences.save(id,policy); graph.dao.clearDownloadErrors(id); graph.dao.resetOfflineFolders(id)
        graph.dao.folders(id).forEach { graph.dao.updateFolder(it.copy(nextUid=1,modSeq=-1)) }
        preferences.pause(id,false); report(id,OfflineState.QUEUED)
        if(graph.scheduling) { WorkScheduling.localStorage(graph.context,id,replace=true); WorkScheduling.offline(graph.context,id,preferences.policy(id),replace=true); graph.requestSync() }
    }
    suspend fun pause(id:Long)=withContext(Dispatchers.IO) { preferences.pause(id,true); report(id,OfflineState.PAUSED); if(graph.scheduling) WorkScheduling.cancelOffline(graph.context,id) }
    suspend fun resume(id:Long)=withContext(Dispatchers.IO) { preferences.pause(id,false); graph.dao.clearDownloadErrors(id); graph.dao.restartCompletedOfflineFolders(id); report(id,OfflineState.QUEUED); if(graph.scheduling) WorkScheduling.offline(graph.context,id,preferences.policy(id),replace=true) }
    suspend fun saveOffline(ids:Collection<Long>,saved:Boolean)=withContext(Dispatchers.IO) {
        val accounts=linkedSetOf<Long>()
        for(id in ids) graph.database.withTransaction { val row=graph.dao.message(id); if(row!=null) { val old=graph.dao.cacheAccess(id); graph.dao.putCacheAccess(CacheAccessRow(id,old?.accessedAt ?: 0,saved)); if(saved && (!row.bodyLoaded || (preferences.policy(row.accountId).downloadAttachments && graph.dao.attachments(id).any { !files.exists(it.localUri) }))) accounts+=row.accountId } }
        if(graph.scheduling && saved) accounts.forEach { WorkScheduling.pinned(graph.context,it,preferences.policy(it)) }
    }
    suspend fun report(id:Long,state:OfflineState,error:String?=null) { preferences.progress(OfflineProgress(id,state,graph.dao.cachedMessageCount(id),graph.dao.serverMessageCount(id),graph.dao.cachedBodyCount(id),error,graph.dao.downloadErrorCount(id)+graph.dao.offlineHeaderFailures(id))) }
    suspend fun protected(row:MessageRow,refs:ProtectedCacheFiles):Boolean = row.accountId in refs.uncertainAccounts || activeViews.containsKey(row.threadId) || activeViews.containsKey(ConversationIds.ofMessage(row.id)) || graph.dao.protectedMessage(row.id) || graph.dao.attachments(row.id).any { files.uriFile(it.localUri)?.path in refs.files }
    /** Metadata stays searchable after body eviction; full body/PDF tokens disappear in the same transaction. */
    private suspend fun metadataIndex(row:MessageRow) {
        val names=runCatching { Codec.strings(Codec.field(row.header,"attachmentNames") ?: "[]") }.getOrDefault(emptyList()).joinToString("\n").let { SearchText.prefix(it,8192) }
        graph.dao.putSearchDocument(SearchDocumentRow(row.id,row.snippet,names,"")); graph.dao.clearMessageFts(row.id)
        graph.dao.putMessageFts(MessageFtsRow(row.id,SearchText.normalize(SearchText.prefix(row.subject,4096)),SearchText.normalize(SearchText.prefix(row.senderSearch,8192)),SearchText.normalize(SearchText.prefix(row.recipientSearch,16384)),SearchText.normalize(row.snippet),SearchText.normalize(names),""))
    }
    internal suspend fun evict(id:Long,refs:ProtectedCacheFiles,removeHeader:Boolean=false,changedThreads:MutableSet<Long>?=null):Pair<Long,Boolean> = bodies.tryLocked(id) {
        val row=graph.dao.message(id) ?: return@tryLocked 0L to false
        if(protected(row,refs)) return@tryLocked 0L to true
        val attachments=graph.dao.attachments(id)
        var removed=false
        graph.database.withTransaction {
            val current=graph.dao.message(id) ?: return@withTransaction
            if(protected(current,refs)) return@withTransaction
            if(removeHeader) { graph.dao.deleteMessage(id); if(changedThreads!=null) changedThreads+=current.threadId else if(!graph.dao.threadHasMessages(current.threadId)) graph.dao.deleteThread(current.threadId) else graph.rebuildThread(current.threadId) }
            else { graph.dao.evictBody(id); graph.dao.evictAttachments(id); metadataIndex(current) }
            removed=true
        }
        if(!removed) return@tryLocked 0L to true
        val before= row.bodyPath?.let { files.size(File(it)) } ?: 0
        files.deleteBodyIfUnreferenced(row.bodyPath)
        var freed=if(row.bodyPath!=null && !File(row.bodyPath).exists()) before else 0
        for(attachment in attachments) {
            val file=files.uriFile(attachment.localUri); val bytes=file?.length() ?: 0
            files.deleteAttachmentIfUnreferenced(attachment.localUri,refs)
            if(file!=null && !file.exists()) freed+=bytes
        }
        changed(row.accountId,-freed); freed to false
    } ?: (0L to true)
    suspend fun clear(id:Long):CacheCleanupResult=withContext(Dispatchers.IO) {
        pause(id) // A deliberate clear must not immediately be undone by automatic offline downloads.
        val refs=files.protectedFiles(); var freed=0L; var skipped=0; var after=0L
        while(true) {
            val rows=graph.dao.storageCandidates(id,after,200); if(rows.isEmpty()) break
            for(row in rows) { currentCoroutineContext().ensureActive(); val result=evict(row.id,refs); freed+=result.first; if(result.second) skipped++ }
            after=rows.last().id; yield()
        }
        report(id,OfflineState.PAUSED)
        CacheCleanupResult(freed,skipped)
    }
    suspend fun enforce(id:Long,limit:Int=200):Boolean {
        val policy=preferences.policy(id); if(policy.maxBytes==0L || cacheBytes(id)<=policy.maxBytes) return true
        val refs=files.protectedFiles()
        for(message in graph.dao.evictionCandidates(id,limit,0)) { evict(message,refs); if(cacheBytes(id)<=policy.maxBytes) return true }
        return cacheBytes(id)<=policy.maxBytes
    }
    suspend fun usage():StorageUsage=withContext(Dispatchers.IO) {
        synchronized(gate) { if(diskReserved==0L) invalidateAll() }
        val refs=files.protectedFiles(); val rows=graph.dao.accounts(); val result=mutableListOf<AccountStorageUsage>(); var draftBytes=0L; var approximate=refs.uncertainAccounts.isNotEmpty()
        for(account in rows) {
            val protectedPaths=hashSetOf<String>(); var accountEstimate=account.id in refs.uncertainAccounts
            val privatePaths=refs.files.filter { it.startsWith(File(graph.context.filesDir,"attachments/${account.id}").canonicalPath+File.separator) }.toMutableSet()
            var after=0L
            while(true) {
                val batch=graph.dao.storagePathBatch(account.id,after); if(batch.isEmpty()) break
                val attachments=graph.dao.attachmentsFor(batch.map { it.id }).groupBy { it.messageId }
                for(row in batch) {
                    val parts=attachments[row.id].orEmpty()
                    val paths=parts.mapNotNull { files.uriFile(it.localUri)?.path }
                    privatePaths.removeAll(paths.toSet())
                    if(row.protected || activeViews.containsKey(row.threadId) || activeViews.containsKey(ConversationIds.ofMessage(row.id)) || paths.any { it in refs.files }) {
                        val values=listOfNotNull(row.bodyPath)+paths
                        for(path in values) if(protectedPaths.size<4096) protectedPaths+=path else accountEstimate=true
                    }
                }
                after=batch.last().id
            }
            val bodyBytes=files.size(File(graph.context.filesDir,"bodies/${account.id}")); val allAttachments=files.size(File(graph.context.filesDir,"attachments/${account.id}"))
            val privateDrafts=privatePaths.sumOf { File(it).length() }; draftBytes+=privateDrafts
            approximate=approximate || accountEstimate
            val protectedBytes=if(accountEstimate) bodyBytes+allAttachments else protectedPaths.sumOf { files.size(File(it)) }
            result+=AccountStorageUsage(account.id,graph.dao.cachedMessageCount(account.id),graph.dao.cachedBodyCount(account.id),bodyBytes,(allAttachments-privateDrafts).coerceAtLeast(0),protectedBytes,graph.dao.indexedTextBytes(account.id))
        }
        val db=graph.context.getDatabasePath("dotmail.db"); val dbBytes=listOf(db,File(db.path+"-wal"),File(db.path+"-shm")).sumOf { it.length() }
        val accounts=rows.map { it.id.toString() }.toSet(); var orphans=0L
        for(rootName in listOf("bodies","attachments")) {
            val root=File(graph.context.filesDir,rootName)
            if(root.isDirectory) java.nio.file.Files.newDirectoryStream(root.toPath()).use { paths -> paths.forEach { path -> if(path.fileName.toString() !in accounts) orphans+=files.size(path.toFile()) } }
        }
        StorageUsage(result,dbBytes,files.size(File(graph.context.filesDir,"outbox")),draftBytes,availableBytes(),approximate,orphans)
    }
    companion object { const val RESERVE_BYTES=64L*1024*1024 }
}
