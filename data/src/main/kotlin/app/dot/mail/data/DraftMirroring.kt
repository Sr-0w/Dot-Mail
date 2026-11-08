package app.dot.mail.data

import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import app.dot.mail.data.local.*
import app.dot.mail.data.model.Draft
import app.dot.mail.engine.*
import app.dot.mail.engine.text.EmailHtml
import jakarta.activation.DataSource
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CancellationException
import java.util.UUID

internal const val DRAFT_DEBOUNCE_MS = 2_000L
/** Queued when a local draft is trashed; `target` holds the draft's local id. */
internal const val OP_TRASH_DRAFT = "TRASH_DRAFT"
/** A trashed draft stays local this long (a little over the Undo snackbar) before its server copy moves. */
internal const val DRAFT_TRASH_GRACE_MS = 12_000L

/** Called in the same transaction as the local save/delete/undo. No network on the editor path. */
internal suspend fun RealMailGraph.queueDraftMirror(id: Long, accountId: Long, clean: Boolean = false) {
    val old = dao.draftMirror(id)
    val source = dao.draft(id)?.sourceMessageId?.let { dao.message(it) }
    val folder = source?.let { dao.folder(it.folderId) }
    dao.putDraftMirror(old?.copy(revision=old.revision+1,deleted=false,dueAt=System.currentTimeMillis()+DRAFT_DEBOUNCE_MS)
        ?: DraftMirrorRow(id,accountId,UUID.randomUUID().toString(),1,if(clean) 1 else 0,System.currentTimeMillis()+DRAFT_DEBOUNCE_MS,folder?.id,folder?.validity ?: 0,source?.uid ?: 0))
}

internal suspend fun RealMailGraph.draftMime(account: AccountRow, draft: Draft, messageId: String): MimeMessage {
    val identity = identityStore.resolve(account.id,account.name,account.email).let { all -> all.firstOrNull { it.id==draft.identityId } ?: all.first { it.isDefault } }
    val attachments = draft.attachmentUris.map { value ->
        val uri=Uri.parse(value)
        val known=dao.attachmentUri(value)
        val name=known?.name ?: context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c -> if(c.moveToFirst()) c.getString(0) else null } ?: "attachment"
        val type=known?.mime ?: context.contentResolver.getType(uri) ?: "application/octet-stream"
        OutgoingAttachment(name,type,source=object: DataSource {
            override fun getName()=name
            override fun getContentType()=type
            override fun getInputStream()=context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cached attachment unavailable")
            override fun getOutputStream(): java.io.OutputStream = throw java.io.IOException("Read only")
        })
    }
    return Mime.build(OutgoingMail(Address(identity.email,identity.name.ifBlank { account.name }),draft.to.map { Address(it.email,it.name) },draft.cc.map { Address(it.email,it.name) },draft.bcc.map { Address(it.email,it.name) },draft.subject,
        draft.bodyHtml?.let { draft.bodyText.ifBlank { EmailHtml.toPlainText(it) } } ?: draft.bodyText,
        draft.bodyHtml?.let { EmailHtml.compose(it) },attachments + app.dot.mail.data.SignatureLogos.attachmentsFor(context,draft.bodyHtml,identityStore.resolve(account.id,account.name,account.email),identity),draft.inReplyTo,draft.references,messageId,
        replyTo=listOfNotNull(identity.replyTo?.let { Address(it) })))
}

/** Sync owns the network serialization; local saves can advance revision while an APPEND is in flight. */
internal suspend fun RealMailGraph.mirrorDrafts(account: AccountRow, config: app.dot.mail.data.model.AccountConfig, creds: Credentials, folder: FolderRow) {
    if(dao.draftMirrors(account.id).isEmpty()) return
    val inventory=client.draftInventory(config.imap.endpoint(),creds,folder.path)
    for(initial in dao.draftMirrors(account.id)) {
        mirrorDraft(initial.localId,inventory,account,config,creds,folder)
        trashDraftIfRequested(initial.localId,account,config,creds,folder)
    }
}

private suspend fun RealMailGraph.mirrorDraft(localId: Long, inventory: DraftInventory, account: AccountRow, config: app.dot.mail.data.model.AccountConfig, creds: Credentials, folder: FolderRow) {
    var mirror=dao.draftMirror(localId) ?: return
    // IMAP content is immutable within a UID. A missing old UID means an external replacement/delete.
    if(mirror.uid>0 && (mirror.folderId!=folder.id || mirror.validity!=inventory.validity || mirror.uid !in inventory.activeUids)) {
        database.withTransaction {
            val current=dao.draftMirror(mirror.localId) ?: return@withTransaction
            if(current.uid!=mirror.uid || current.validity!=mirror.validity) return@withTransaction
            if(!current.deleted && current.revision==current.syncedRevision && current.attemptRevision==null) {
                dao.deleteDraft(current.localId); dao.deleteDraftMirror(current.localId)
            } else {
                dao.putDraftMirror(current.copy(uid=0,validity=0,folderId=null))
                dao.draftSource(current.localId,null)
            }
        }
        mirror=dao.draftMirror(localId) ?: return
    }
    val attempted=mirror.attemptRevision?.let { client.findDraftCopy(config.imap.endpoint(),creds,folder.path,mirror.token,it) }
    if(mirror.deleted) {
        val copies=listOfNotNull(attempted,mirror.uid.takeIf { it>0 }?.let { DraftCopy(mirror.validity,it) }).distinct()
        copies.forEach { client.deleteDraftCopy(config.imap.endpoint(),creds,folder.path,it) }
        database.withTransaction {
            val current=dao.draftMirror(mirror.localId)
            if(current?.deleted==true && current.revision==mirror.revision) dao.deleteDraftMirror(mirror.localId)
        }
        return
    }
    if(attempted!=null) {
        commitDraftCopy(mirror,attempted,mirror.attemptRevision!!,folder)
        mirror=dao.draftMirror(mirror.localId) ?: return
    } else if(mirror.attemptRevision!=null) {
        database.withTransaction {
            dao.draftMirror(mirror.localId)?.let { current ->
                if(current.attemptRevision==mirror.attemptRevision) dao.putDraftMirror(current.copy(attemptRevision=null))
            }
        }
        mirror=dao.draftMirror(mirror.localId) ?: return
    }
    if(mirror.revision==mirror.syncedRevision || mirror.dueAt>System.currentTimeMillis()) return
    val snapshot=database.withTransaction {
        val current=dao.draftMirror(mirror.localId)?.takeUnless { it.deleted || it.revision==it.syncedRevision || it.dueAt>System.currentTimeMillis() } ?: return@withTransaction null
        val draft=dao.draft(current.localId) ?: return@withTransaction null
        dao.putDraftMirror(current.copy(attemptRevision=current.revision))
        current to Codec.draft(draft.json)
    } ?: return
    val (version,draft)=snapshot
    val mime=draftMime(account,draft,"<dotdraft-${version.token}-${version.revision}@dotmail.local>")
    val copy=client.appendDraft(config.imap.endpoint(),creds,folder.path,version.token,version.revision,mime)
    commitDraftCopy(version,copy,version.revision,folder)
}

/**
 * Moves a trashed draft's server copy to Trash once it holds the latest version. The queued operation is claimed first
 * (IN_FLIGHT) so Undo cannot cancel a move that is already reaching the server.
 */
private suspend fun RealMailGraph.trashDraftIfRequested(localId: Long, account: AccountRow, config: app.dot.mail.data.model.AccountConfig, creds: Credentials, folder: FolderRow) {
    val pending=dao.operations(account.id).firstOrNull { it.action==OP_TRASH_DRAFT && it.target=="$localId" } ?: return
    val mirror=dao.draftMirror(localId)
    if(mirror==null || mirror.deleted || dao.draft(localId)==null) { dao.deleteOperation(pending.id); return }
    if(mirror.dueAt>System.currentTimeMillis() || mirror.uid<=0 || mirror.folderId!=folder.id || mirror.revision!=mirror.syncedRevision || mirror.attemptRevision!=null) return
    val trash=dao.folders(account.id).firstOrNull { it.role=="TRASH" } ?: return
    val op=database.withTransaction { dao.operation(pending.id)?.copy(error="IN_FLIGHT")?.also { dao.updateOperation(it) } } ?: return
    try { client.move(config.imap.endpoint(),creds,folder.path,longArrayOf(mirror.uid),trash.path) }
    catch(e: Exception) { dao.updateOperation(op.copy(error=null,attempts=op.attempts+1)); throw e }
    database.withTransaction {
        dao.byUid(folder.id,mirror.uid)?.let { dao.deleteMessage(it.id); rebuildThread(it.threadId) }
        dao.deleteDraftMirror(localId); dao.deleteDraft(localId); dao.deleteOperation(op.id)
    }
}

private suspend fun RealMailGraph.commitDraftCopy(previous: DraftMirrorRow, copy: DraftCopy, revision: Long, folder: FolderRow) = database.withTransaction {
    val current=dao.draftMirror(previous.localId) ?: return@withTransaction
    if(current.token!=previous.token) return@withTransaction
    if(current.uid>0 && current.validity==copy.validity && current.folderId==folder.id && current.uid!=copy.uid)
        dao.insertOperation(OperationRow(accountId=current.accountId,folderId=folder.id,validity=current.validity,uid=current.uid,action="DELETE_DRAFT"))
    dao.putDraftMirror(current.copy(uid=copy.uid,validity=copy.validity,folderId=folder.id,syncedRevision=revision,attemptRevision=null))
    dao.draftSource(current.localId,dao.byUid(folder.id,copy.uid)?.id)
}

internal suspend fun RealMailGraph.linkDraftCopies(folder: FolderRow) {
    for(mirror in dao.draftMirrors(folder.accountId).filter { it.folderId==folder.id && it.validity==folder.validity && !it.deleted }) {
        dao.byUid(folder.id,mirror.uid)?.let { dao.draftSource(mirror.localId,it.id) }
    }
}
