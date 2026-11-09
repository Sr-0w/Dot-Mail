package app.dot.mail.data

import androidx.room.withTransaction
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException

internal data class MailboxSyncOutcome(val success:Boolean=true,val warning:String?=null,val deferredExpunge:Boolean=false,val failure:ConnectionFailure?=null)

internal suspend fun RealMailGraph.syncOneFolder(account:AccountRow,config:AccountConfig,creds:Credentials,remote:RemoteFolder,windowLimit:Int,windowDays:Int,ready:suspend (Boolean,String?)->Unit):MailboxSyncOutcome {
    var success=true
    var headerWarning:String?=null
    var deferredExpunge=false
    var stage="Folder header sync"
    val redactions=setOf(account.email,creds.secret)
    try {

        stage = "Folder header sync"
        var folder = dao.folders(account.id).firstOrNull { it.path == remote.path }
        if (folder == null) {
            val id = dao.insertFolder(FolderRow(accountId = account.id, path = remote.path, name = remote.name, role = remote.role.name))
            folder = requireNotNull(dao.folder(id))
        }
        if(remote.role==Role.DRAFTS) {
            stage="Draft mirroring"
            mirrorDrafts(account,config,creds,folder)
            stage="Folder header sync"
        }
        val initialCursor = SyncCursor(if(folder.nextUid==1L && folder.modSeq<0) 0 else folder.validity, folder.nextUid, folder.modSeq,folder.total)
        var result = client.sync(config.imap.endpoint(), creds, folder.path, initialCursor, limit = windowLimit, windowDays = windowDays)
        val pending=dao.hasFolderOperations(folder.id)
        val needsMetadata=dao.folderNeedsMetadata(folder.id)
        val needsPreview=dao.folderNeedsPreviews(folder.id)
        if(result.unchanged && !pending && !needsMetadata && !needsPreview) {
            if(folder.name!=remote.name || folder.role!=remote.role.name || folder.unread!=remote.unread || folder.total!=remote.total)
                dao.updateFolder(folder.copy(name=remote.name,role=remote.role.name,unread=remote.unread,total=remote.total))
            ready(true,null)
            return MailboxSyncOutcome()
        }
        if (result.failures.isNotEmpty()) {
            result.failures.forEach { SyncDiagnostics.warn("Header UID=${it.uid} account=${account.id} folderRole=${remote.role}", it.error, redactions) }
            headerWarning = "Folder ${remote.role}: some message headers could not be read; retrying"
        }
        val fetched=result.headers.map { it.uid }.toSet()
        val missing=dao.folderMetadataRepairs(folder.id).filter { it.uid !in fetched }
        if(missing.isNotEmpty() && !result.reset) {
            try { result=result.copy(headers=result.headers+client.headers(config.imap.endpoint(),creds,folder.path,missing.map { it.uid }.toLongArray(),result.cursor.uidValidity)) }
            catch(e: CancellationException) { throw e } catch(e: Exception) { SyncDiagnostics.warn("Classification metadata account=${account.id} folderRole=${remote.role}",e,redactions) }
        }
        stage="Queued operations"
        val replay=replayFolderOperations(account,config,creds,folder,remote,result.cursor.uidValidity)
        success=success && replay.success
        deferredExpunge=replay.deferredExpunge
        if (replay.replayed) result = client.sync(config.imap.endpoint(), creds, folder.path, initialCursor, limit = windowLimit, windowDays = windowDays)
        stage="Header database commit"
        val notifications=applyFolderHeaders(account,folder,remote,result)
        if(remote.role==Role.DRAFTS) database.withTransaction { linkDraftCopies(dao.folder(folder.id)!!) }
        notifications.distinctBy { it.first }.forEach { (id, subject, folderId) -> if(!isThreadMuted(id)) MailNotifications.newMail(context, account, id, subject, folderId) }
        ready(success && headerWarning==null,headerWarning)
        prefetchFolderPreviews(account,config,creds,folder,remote,result.cursor.uidValidity)
        return MailboxSyncOutcome(success,headerWarning,deferredExpunge)
    } catch(e:CancellationException) { throw e }
    catch(e:Exception) {
        SyncDiagnostics.warn("$stage account=${account.id} folderRole=${remote.role}",e,redactions)
        val reason=SyncDiagnostics.stored(e,remote.role.name)
        dao.syncError(account.id,reason,ConnectionFailure.classify(e)==ConnectionFailure.AUTH)
        syncing.update { it - account.id }
        ready(false,reason)
        if(ConnectionFailure.classify(e)==ConnectionFailure.AUTH) throw e
        return MailboxSyncOutcome(false,reason,failure=ConnectionFailure.classify(e))
    }
}

private data class ReplayOutcome(val success:Boolean,val replayed:Boolean,val deferredExpunge:Boolean)
private suspend fun RealMailGraph.replayFolderOperations(account:AccountRow,config:AccountConfig,creds:Credentials,folder:FolderRow,remote:RemoteFolder,validity:Long):ReplayOutcome {
    var success=true
    var deferredExpunge=false
    val redactions=setOf(account.email,creds.secret)
    var replayed = false
    for (queued in dao.operations(account.id).filter { it.folderId == folder.id }) {
        var op = dao.operation(queued.id) ?: continue
        if (op.action == OP_TRASH_DRAFT) continue // run by draft mirroring, once the latest version is on the server
        if (op.action == "DEFERRED_FLAGS") {
            val row = op.messageLocalId?.let { dao.message(it) }
            if (row == null) { dao.deleteOperation(op.id); continue }
            if (row.uid < 1) continue
            if (row.folderId != folder.id) { dao.folder(row.folderId)?.let { dao.updateOperation(op.copy(folderId = row.folderId, validity = it.validity, uid = row.uid)) }; success = false; continue }
            if (dao.folder(row.folderId)?.validity != validity) { dao.updateOperation(op.copy(error = "UIDVALIDITY changed; operation requires review")); continue }
            try {
                client.setFlags(config.imap.endpoint(), creds, folder.path, longArrayOf(row.uid), seen = op.desiredSeen, starred = op.desiredStarred)
                dao.deleteOperation(op.id); replayed = true
            } catch (e: CancellationException) { throw e } catch (e: Exception) { SyncDiagnostics.warn("Deferred flags account=${account.id} folderRole=${remote.role}", e, redactions); success = false }
            continue
        }
        if (op.validity != validity) {
            dao.updateOperation(op.copy(error = "UIDVALIDITY changed; operation requires review", attempts = op.attempts + 1)); continue
        }
        val claimed = database.withTransaction {
            dao.operation(op.id)?.copy(error = "IN_FLIGHT")?.also { dao.updateOperation(it) }
        } ?: continue
        op = claimed
        try {
            when (op.action) {
                "MOVE" -> {
                    if (op.desiredSeen != null || op.desiredStarred != null) client.setFlags(config.imap.endpoint(), creds, folder.path, longArrayOf(op.uid), seen = op.desiredSeen, starred = op.desiredStarred)
                    if (client.move(config.imap.endpoint(), creds, folder.path, longArrayOf(op.uid), requireNotNull(op.target)) == MoveOutcome.COPIED_AND_MARKED_DELETED) deferredExpunge = true }
                "FLAGS" -> client.setFlags(config.imap.endpoint(), creds, folder.path, longArrayOf(op.uid), seen = op.desiredSeen, starred = op.desiredStarred)
                MessageAction.MARK_READ.name -> client.setFlags(config.imap.endpoint(), creds, folder.path, longArrayOf(op.uid), seen = true)
                MessageAction.MARK_UNREAD.name -> client.setFlags(config.imap.endpoint(), creds, folder.path, longArrayOf(op.uid), seen = false)
                MessageAction.STAR.name -> client.setFlags(config.imap.endpoint(), creds, folder.path, longArrayOf(op.uid), starred = true)
                MessageAction.UNSTAR.name -> client.setFlags(config.imap.endpoint(), creds, folder.path, longArrayOf(op.uid), starred = false)
                "DELETE_DRAFT" -> client.deleteDraftCopy(config.imap.endpoint(),creds,folder.path,DraftCopy(op.validity,op.uid))
                MessageAction.DELETE_FOREVER.name -> client.setFlags(config.imap.endpoint(), creds, folder.path, longArrayOf(op.uid), delete = true)
            }
            dao.deleteOperation(op.id); replayed = true
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            SyncDiagnostics.warn("Operation ${op.action} account=${account.id} folderRole=${remote.role}", e, redactions)
            dao.updateOperation(op.copy(attempts = op.attempts + 1, error = SyncDiagnostics.stored(e))); success = false; break
        }
    }
    return ReplayOutcome(success,replayed,deferredExpunge)
}

internal suspend fun RealMailGraph.applyFolderHeaders(account:AccountRow,folder:FolderRow,remote:RemoteFolder,result:HeaderSync):List<Triple<Long,String,Long>> {
    val notificationRules=NotificationRules.get(context)
    val notifications = mutableListOf<Triple<Long, String, Long>>()
    val dbStart=System.nanoTime()
    database.withTransaction {
        val old = if(result.unchanged) emptyList() else dao.folderInventory(folder.id)
        val oldByUid=old.associateBy { it.uid }
        val changedThreads=linkedSetOf<Long>()
        val removed=if(result.reset && dao.folder(folder.id)?.validity!=result.cursor.uidValidity) old.filter { it.uid>0 }
            else if(!result.unchanged) old.filter { it.uid>0 && it.uid !in result.existingUids } else emptyList()
        removed.forEach { dao.deleteMessage(it.id); changedThreads+=it.threadId }
        val operations = dao.operations(account.id)
        val pending = operations.filter { it.folderId == folder.id && it.validity == result.cursor.uidValidity }.map { it.uid }.toSet()
        val pendingLocalIds = operations.mapNotNull { it.messageLocalId }.toSet()
        val headerUids=result.headers.map { it.uid }.toHashSet()
        // The final flag inventory wins if a server updates flags between its header/flag FETCHes.
        val headerFlags=result.flags.asSequence().filter { it.uid in headerUids }.associateBy { it.uid }
        result.headers.map { header -> headerFlags[header.uid]?.let { header.copy(flags=it) } ?: header }
            .filter { !it.flags.deleted && it.uid !in pending }.forEach { header ->
            val (thread, fresh) = upsertHeader(folder.copy(validity = result.cursor.uidValidity), header, rebuild=false)
            changedThreads+=thread
            if (fresh && !result.reset) dao.deleteThreadSnooze(thread) // new mail in a snoozed conversation wakes it
            if (fresh && !result.reset && !header.flags.seen && notificationRules.notifyForFolder(folder.id, remote.role.name) && !isThreadMuted(thread)) notifications += Triple(thread, header.subject, folder.id)
        }
        result.flags.filter { it.uid !in pending }.forEach { flag ->
            val state=oldByUid[flag.uid] ?: dao.byUid(folder.id,flag.uid)?.let { FolderMessageState(it.id,it.uid,it.threadId,it.unread,it.starred) }
            if(state!=null && state.id !in pendingLocalIds) {
                if(flag.deleted) { dao.deleteMessage(state.id); changedThreads+=state.threadId }
                else if(state.unread != !flag.seen || state.starred!=flag.starred) {
                    // Fetch full metadata only for changed flags, preserving headers and cached bodies.
                    dao.message(state.id)?.let { row ->
                        val updated=row.copy(unread=!flag.seen,starred=flag.starred)
                        if(updated!=row) dao.updateMessage(updated)
                    }
                    changedThreads+=state.threadId
                }
            }
        }
        changedThreads.forEach { rebuildThread(it) }
        dao.updateFolder(folder.copy(name = remote.name, role = remote.role.name, unread = remote.unread, total = result.cursor.messageCount.takeIf { it>=0 } ?: remote.total, validity = result.cursor.uidValidity, nextUid = result.cursor.uidNext, modSeq = result.cursor.highestModSeq))
    }
    client.recordTiming("db",(System.nanoTime()-dbStart)/1_000_000)
    return notifications
}
