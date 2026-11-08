package app.dot.mail.data

import androidx.room.withTransaction
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.html.HtmlSanitizer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** Downloads and eviction share a bounded set of per-message locks; optimistic flag changes never wait on them. */
internal class StorageBodies(private val graph:RealMailGraph,private val manager:StorageManager) {
    private val locks=Array(64) { Mutex() }
    internal suspend fun <T> locked(id:Long,block:suspend ()->T):T = locks[(id xor (id ushr 32)).toInt() and 63].withLock { block() }
    internal suspend fun <T> tryLocked(id:Long,block:suspend ()->T):T? {
        val mutex=locks[(id xor (id ushr 32)).toInt() and 63]
        if(!mutex.tryLock()) return null
        return try { block() } finally { mutex.unlock() }
    }
    private val files=StorageFiles(graph)
    suspend fun touch(id:Long) { val old=graph.dao.cacheAccess(id); graph.dao.putCacheAccess(CacheAccessRow(id,System.currentTimeMillis(),old?.offlineSaved ?: false)) }
    private suspend fun source(message:MessageRow):Triple<FolderRow,app.dot.mail.data.local.AccountRow,app.dot.mail.engine.Credentials> {
        val pending=if(message.uid<0) graph.pendingMove(message) else null
        if(message.uid<0 && pending==null) throw MailActionException(MailActionFailure.OPERATION_PENDING,"This message is moving. Try shortly.",ActionErrorKind.MESSAGE_MOVING)
        val folder=graph.dao.folder(pending?.folderId ?: message.folderId) ?: throw MailActionException(MailActionFailure.FOLDER_UNAVAILABLE,"Message folder unavailable",ActionErrorKind.MESSAGE_FOLDER_GONE)
        val account=graph.dao.account(message.accountId) ?: error("Account unavailable")
        return Triple(folder.copy(validity=pending?.validity ?: folder.validity),account,graph.credentials(account))
    }
    suspend fun ensure(id:Long,automatic:Boolean=false,attachments:Boolean=false) = withContext(Dispatchers.IO) { locked(id) {
        val message=graph.dao.message(id) ?: return@locked
        if(!automatic) touch(id)
        val valid=message.bodyLoaded && (message.bodyPath==null || File(message.bodyPath).let { File(it,".complete").exists() || File(it,"plain.txt").exists() || File(it,"body.html").exists() })
        if(valid) return@locked
        val (folder,account,credentials)=source(message); val config=Codec.config(account.config)
        graph.client.withImapSession(config.imap.endpoint(),credentials) {
        manager.prepareSpace(message.accountId,25L*1024*1024,automatic,id)
        manager.checkSpace(message.accountId)
        val pending=if(message.uid<0) graph.pendingMove(message) else null
        val directory=File(graph.context.filesDir,"bodies/${account.id}/${UUID.randomUUID()}")
        val budget=manager.reserve(account.id,25L*1024*1024,2)
        val created=mutableListOf<File>(); var committed=false; var delta=0L
        try {
            val body=graph.client.cacheBodyParts(config.imap.endpoint(),credentials,folder.path,pending?.uid ?: message.uid,directory,folder.validity,attachments,budget.bytes)
            if (body.encryptionHint == app.dot.mail.engine.EncryptionHint.UNSUPPORTED) File(directory,".unsupported-encryption").writeText("")
            val previous=graph.dao.attachments(id).associateBy { it.partId }
            val saved=body.attachments.associate { part ->
                val target=graph.attachmentFile(account.id,"${UUID.randomUUID()}/${graph.safeFileName(part.name)}")
                created+=target
                if(!part.file.renameTo(target)) { part.file.copyTo(target); part.file.delete() }
                part.partId to graph.uri(target)
            }
            val parts=body.parts.map { part ->
                val old=previous[part.partId]
                AttachmentRow(id=old?.id ?: 0,messageId=id,partId=part.partId,name=part.name,mime=part.mimeType,size=saved[part.partId]?.let { files.uriFile(it)?.length() } ?: part.size,cid=part.contentId,inline=part.inline,localUri=saved[part.partId] ?: old?.localUri?.takeIf(files::exists))
            }
            File(directory,".complete").writeText("")
            val temporary=message.copy(bodyLoaded=true,bodyPath=directory.absolutePath)
            val preview=graph.bodyStore.read(temporary)
            val remote=preview?.html?.let { HtmlSanitizer.sanitize(it).hasRemoteImages } ?: false
            val snippet=app.dot.mail.engine.text.Snippets.fromBody(preview?.plainText,preview?.html).ifBlank { message.snippet }
            graph.database.withTransaction {
                val current=graph.dao.message(id) ?: return@withTransaction
                for(part in parts) if(part.id==0L) graph.dao.insertAttachment(part) else graph.dao.updateAttachment(part)
                previous.values.filter { old -> parts.none { it.partId==old.partId } }.forEach { graph.dao.deleteAttachment(it.id) }
                graph.dao.updateMessage(current.copy(text=null,html=null,bodyPath=directory.absolutePath,bodyLoaded=true,snippetFetched=true,remoteImages=remote,snippet=snippet,hasAttachments=parts.any { !it.inline }))
                graph.dao.cacheAccess(id)?.let { if(it.downloadError!=null) graph.dao.putCacheAccess(it.copy(failureAt=0,downloadError=null)) }; graph.rebuildThread(current.threadId); committed=true
            }
            if(committed) {
                delta=files.size(directory)+created.sumOf { it.length() }
                val protected=files.protectedFiles()
                val oldSize=message.bodyPath?.let { files.size(File(it)) } ?: 0
                files.deleteBodyIfUnreferenced(message.bodyPath)
                delta-=(if(message.bodyPath!=null && !File(message.bodyPath).exists()) oldSize else 0)
                previous.values.forEach { part -> val old=files.uriFile(part.localUri); val bytes=old?.length() ?: 0; files.deleteAttachmentIfUnreferenced(part.localUri,protected); if(old!=null && !old.exists()) delta-=bytes }
            }
        } finally { if(!committed) { directory.deleteRecursively(); created.forEach { files.deleteCreated(it) } }; manager.release(budget,if(committed) delta else 0) }
        }
    } }
    suspend fun open(attachmentId:Long,maxBytes:Long=512L*1024*1024,automatic:Boolean=false):String = withContext(Dispatchers.IO) {
        val initial=graph.dao.attachment(attachmentId) ?: error("Attachment no longer available")
        locked(initial.messageId) {
            val row=graph.dao.attachment(attachmentId) ?: error("Attachment no longer available")
            if(!automatic) touch(row.messageId)
            row.localUri?.takeIf(files::exists)?.let { return@locked it }
            val message=graph.dao.message(row.messageId) ?: error("Message no longer available")
            val (folder,account,credentials)=source(message); val config=Codec.config(account.config)
            graph.client.withImapSession(config.imap.endpoint(),credentials) {
            manager.prepareSpace(message.accountId,minOf(maxBytes,if(row.size>=0) row.size+1024 else maxBytes),automatic,row.messageId)
            manager.checkSpace(message.accountId)
            val pending=if(message.uid<0) graph.pendingMove(message) else null
            val directory=File(graph.context.cacheDir,"attachment-${UUID.randomUUID()}")
            val budget=manager.reserve(account.id,minOf(maxBytes,if(row.size>=0) (row.size+1024).coerceAtLeast(1024) else maxBytes))
            var target:File?=null; var committed=false
            try {
                val part=graph.client.cacheAttachment(config.imap.endpoint(),credentials,folder.path,pending?.uid ?: message.uid,row.partId,directory,folder.validity,budget.bytes)
                val file=graph.attachmentFile(account.id,"${UUID.randomUUID()}/${graph.safeFileName(row.name)}"); target=file
                if(!part.file.renameTo(file)) { part.file.copyTo(file); part.file.delete() }
                val uri=graph.uri(file)
                graph.database.withTransaction { val current=graph.dao.attachment(attachmentId) ?: error("Attachment no longer available"); graph.dao.updateAttachment(current.copy(localUri=uri,size=file.length())); graph.dao.cacheAccess(current.messageId)?.let { if(it.downloadError!=null) graph.dao.putCacheAccess(it.copy(failureAt=0,downloadError=null)) }; committed=true }
                uri
            } finally { directory.deleteRecursively(); if(!committed) target?.let { files.deleteCreated(it) }; manager.release(budget,if(committed) target?.length() ?: 0 else 0) }
            }
        }
    }
}
