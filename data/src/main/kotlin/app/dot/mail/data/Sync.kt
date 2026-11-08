package app.dot.mail.data

import androidx.room.withTransaction
import app.dot.mail.data.local.*
import app.dot.mail.data.model.MessageAction
import app.dot.mail.engine.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.io.File
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.util.Properties
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Scheduled work honors account intervals. Explicit work always attempts the requested accounts. */
internal suspend fun RealMailGraph.syncAll(
    accountIds:Set<Long>?=null,
    scheduled:Boolean=false,
    primaryMailbox:app.dot.mail.data.model.Mailbox?=null,
    onReady:suspend (Long,RemoteFolder,Boolean,String?)->Unit={ _,_,_,_ -> },
):Boolean = syncLock.withLock {
    val results=kotlinx.coroutines.coroutineScope {
        val limit=kotlinx.coroutines.sync.Semaphore(2)
        dao.accounts().filter { accountIds==null || it.id in accountIds }.map { account ->
            async { limit.withPermit { syncAccount(account,scheduled,primaryMailbox,onReady) } }
        }.awaitAll()
    }
    try { cacheCleanup.run() } catch(e:Exception) { SyncDiagnostics.warn("Private cache cleanup",e) }
    results.all { it }
}

private suspend fun RealMailGraph.syncAccount(
    account:AccountRow,
    scheduled:Boolean,
    primaryMailbox:app.dot.mail.data.model.Mailbox?,
    onReady:suspend (Long,RemoteFolder,Boolean,String?)->Unit,
):Boolean {
    val settings=AccountSettingsStore.get(context)
    val preferences=settings.current(account.id)
    if(scheduled && (account.authError || !isScheduledSyncDue(preferences,settings.lastSynced(account.id)))) return true
    syncing.update { it+account.id }
    var stage="Connect"
    var redactions=setOf(account.email)
    try {
        prepareSnippetRepair(account.id)
        val config=Codec.config(account.config)
        val credentials=credentials(account)
        redactions=setOf(account.email,credentials.secret)
        return client.withImapSession(config.imap.endpoint(),credentials) {
            var success=true
            var warning:String?=null
            val previousWindow=settings.appliedWindow(account.id)
            if(previousWindow!=null && previousWindow!=preferences.syncWindowDays && dao.operations(account.id).isEmpty()) {
                dao.folders(account.id).forEach { dao.updateFolder(it.copy(validity=0,nextUid=1,modSeq=-1)) }
            }
            stage="Folder commands"
            runFolderOps(account,config,credentials).let { if(!it.success) success=false; warning=it.warning }
            stage="Folder list"
            val folders=client.folders(config.imap.endpoint(),credentials)
            if(folders.none { it.role==Role.DRAFTS } && dao.draftMirrors(account.id).any { it.deleted || it.revision!=it.syncedRevision }) {
                success=false
                warning=warning ?: "Drafts folder unavailable; local changes are queued"
            }
            reconcileFolders(account,config,credentials,folders)
            val selectedPath=(primaryMailbox as? app.dot.mail.data.model.Mailbox.InFolder)?.let { dao.folder(it.folderId)?.path }
            val selectedRole=(primaryMailbox as? app.dot.mail.data.model.Mailbox.Unified)?.role?.name
            val moves=dao.operations(account.id).filter { it.action=="MOVE" }
            val sourceIds=moves.map { it.folderId }.toSet()
            val sourcePaths=dao.folders(account.id).filter { it.id in sourceIds }.map { it.path }.toSet()
            val targetPaths=moves.mapNotNull { it.target }.toSet()
            val primary=folders.filter { it.role==Role.INBOX || it.path==selectedPath || it.role.name==selectedRole || it.path in sourcePaths }
                .sortedBy { if(it.role==Role.INBOX) 0 else 1 }
            val abort=java.util.concurrent.atomic.AtomicBoolean(false)
            suspend fun synchronize(folder:RemoteFolder):MailboxSyncOutcome {
                if(abort.get()) return MailboxSyncOutcome(false)
                val result=syncOneFolder(account,config,credentials,folder,if(preferences.syncWindowDays<=0) 5000 else 500,preferences.syncWindowDays) { ok,reason -> onReady(account.id,folder,ok,reason) }
                if(result.failure?.transient==true) abort.set(true)
                return result
            }
            val first=primary.map { synchronize(it) }
            // A target selected before a queued source MOVE needs one final UID binding pass.
            val rebound=primary.filter { it.path in targetPaths }.map { synchronize(it) }
            val remaining=kotlinx.coroutines.coroutineScope {
                val limit=kotlinx.coroutines.sync.Semaphore(3)
                folders.filter { it !in primary }.map { folder -> async { limit.withPermit { synchronize(folder) } } }.awaitAll()
            }
            val results=first+rebound+remaining
            if(results.any { !it.success }) success=false
            // Typed connection failures take precedence over unrelated advisory folder warnings.
            warning=results.firstOrNull { it.failure!=null }?.warning ?: warning ?: results.firstNotNullOfOrNull { it.warning }
            stage="Folder delete"
            finishFolderDeletes(account,config,credentials).let { if(!it.success) success=false; warning=it.warning ?: warning }
            if(success && warning==null) {
                settings.markWindowApplied(account.id,preferences.syncWindowDays)
                settings.markSynced(account.id)
            }
            val operationError=dao.operations(account.id).firstNotNullOfOrNull { it.error }
            dao.syncError(account.id,warning ?: if(results.any { it.deferredExpunge }) "Move marked originals deleted; server lacks targeted expunge"
                else operationError?.takeIf { it.startsWith("[") } ?: if(operationError!=null || !success) "Some server changes are pending. Please retry." else null)
            success && warning==null
        }
    } catch(e:CancellationException) { throw e }
    catch(e:Exception) {
        SyncDiagnostics.warn("$stage account=${account.id}",e,redactions)
        dao.syncError(account.id,SyncDiagnostics.stored(e),ConnectionFailure.classify(e)==ConnectionFailure.AUTH)
        return false
    } finally { syncing.update { it-account.id } }
}

internal suspend fun RealMailGraph.deliverOutbox(id: Long): Boolean = outboxLock.withLock {
    var row = dao.outbox(id) ?: return@withLock true
    if (row.state !in listOf("PENDING", "APPEND")) return@withLock true
    if (row.dueAt > System.currentTimeMillis()) return@withLock false
    val account = dao.account(row.accountId) ?: return@withLock true
    val config = Codec.config(account.config); val creds = credentials(account)
    if (row.state == "PENDING") {
        // All local preparation happens before claiming submission, so preparation failures are retryable.
        val draft = Codec.draft(row.draft)
        val attachments = draft.attachmentUris.map { source ->
            val uri = Uri.parse(source)
            val known = dao.attachmentUri(source)
            val metadata = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            val name=known?.name ?: metadata ?: "attachment"
            val mime=known?.mime ?: context.contentResolver.getType(uri) ?: "application/octet-stream"
            val source=object : jakarta.activation.DataSource {
                override fun getInputStream(): java.io.InputStream = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cached attachment unavailable")
                override fun getOutputStream(): java.io.OutputStream = throw java.io.IOException("Read only")
                override fun getContentType() = mime
                override fun getName() = name
            }
            OutgoingAttachment(name,mime,source=source)
        }
        val saved = row.mimePath?.let { File(it) }?.takeIf { it.isFile }
        val identity = identityStore.resolve(account.id, account.name, account.email).let { all -> all.firstOrNull { it.id == draft.identityId } ?: all.first { it.isDefault } }
        val calendarSender=org.json.JSONObject(row.draft).optString("calendarSender").takeIf { it.isNotBlank() }
        require(calendarSender==null || calendarSender.equals(identity.email,true)) { "Invited identity is no longer available" }
        val mime = saved?.let { StoredMimeMessage(it) } ?: Mime.build(OutgoingMail(Address(identity.email, identity.name.ifBlank { account.name }), draft.to.map { Address(it.email, it.name) }, draft.cc.map { Address(it.email, it.name) }, draft.bcc.map { Address(it.email, it.name) }, draft.subject,
            // Formatted drafts: text/plain alternative + email-safe HTML (inline styles, sanitised) as multipart/alternative.
            draft.bodyHtml?.let { draft.bodyText.ifBlank { app.dot.mail.engine.text.EmailHtml.toPlainText(it) } } ?: draft.bodyText,
            html = draft.bodyHtml?.let { app.dot.mail.engine.text.EmailHtml.compose(it) },
            attachments = attachments + SignatureLogos.attachmentsFor(context, draft.bodyHtml, identityStore.resolve(account.id, account.name, account.email), identity), inReplyTo = draft.inReplyTo, references = draft.references, replyTo = listOfNotNull(identity.replyTo?.let { Address(it) }), messageId = "<dotmail-$id-${java.util.UUID.randomUUID()}@${account.email.substringAfter('@')}>", date = java.time.Instant.now(), calendarReply = org.json.JSONObject(row.draft).optString("calendarReply").takeIf { it.isNotBlank() }))
        val file = File(context.filesDir, "outbox/${account.id}/$id.eml").also { it.parentFile!!.mkdirs() }
        if (saved==null) {
            val temporary=File(file.parentFile,"${file.name}.tmp")
            try { temporary.outputStream().use { mime.writeTo(it) }; check(temporary.renameTo(file)) { "Could not persist queued MIME" } } finally { temporary.delete() }
        }
        row = row.copy(mimePath = file.absolutePath)
        dao.outboxPath(id, file.absolutePath)
        if (dao.transitionOutbox(id, "PENDING", "SUBMITTING") != 1) return@withLock true
        try {
            client.send(config.smtp.endpoint(), creds, mime, envelopeFrom = account.email)
        } catch (e: SubmissionNotStartedException) {
            SyncDiagnostics.warn("Outbox SMTP connect id=$id", e, setOf(creds.secret, account.email))
            if (e.cause is jakarta.mail.AuthenticationFailedException) dao.syncError(account.id, "Sign in again to reconnect this account", true)
            dao.transitionOutbox(id, "SUBMITTING", "PENDING", "Connection failed before submission; will retry")
            return@withLock false
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.transitionOutbox(id, "SUBMITTING", "UNCERTAIN", "Submission was interrupted. Check Sent before retrying.") }
            throw e
        } catch (e: Exception) {
            SyncDiagnostics.warn("Outbox SMTP submission id=$id", e, setOf(creds.secret, account.email))
            // SMTP can lose its final reply after accepting DATA. Automatic retry could duplicate mail.
            dao.transitionOutbox(id, "SUBMITTING", "UNCERTAIN", "Delivery could not be confirmed. Check Sent before retrying.")
            dao.syncError(account.id, "A queued send could not be confirmed. Check Sent before retrying.")
            return@withLock true
        } finally { runCatching { (mime as? java.io.Closeable)?.close() }.onFailure { SyncDiagnostics.warn("Close outbox MIME id=$id",it) } }
        val autoSent = config.smtp.host.equals("smtp.gmail.com", true) || config.smtp.host.equals("smtp.googlemail.com", true)
        dao.transitionOutbox(id, "SUBMITTING", if (autoSent) "SENT" else "APPEND")
        row = requireNotNull(dao.outbox(id))
        if(row.state=="SENT") { File(requireNotNull(row.mimePath)).delete(); dao.clearOutboxMime(id) }
    }
    if (row.state == "APPEND") {
        if (dao.transitionOutbox(id, "APPEND", "APPENDING") != 1) return@withLock true
        try {
            val sent = dao.folders(account.id).firstOrNull { it.role == Role.SENT.name }?.path ?: "Sent"
            StoredMimeMessage(File(requireNotNull(row.mimePath))).use { mime ->
                mime.removeHeader("Bcc")
                client.append(config.imap.endpoint(), creds, sent, mime)
            }
            dao.transitionOutbox(id, "APPENDING", "SENT")
            File(requireNotNull(row.mimePath)).delete(); dao.clearOutboxMime(id)
        } catch (e: Exception) {
            SyncDiagnostics.warn("Outbox Sent copy id=$id", e, setOf(creds.secret, account.email))
            withContext(NonCancellable) { dao.transitionOutbox(id, "APPENDING", "APPEND", "Message sent; its Sent copy could not be saved. Retry will only save the copy.") }
            if (e is CancellationException) throw e
            return@withLock false
        }
    }
    true
}

/** Background-sync schedule of one account: never for "manual only", else when its own interval has elapsed (1 minute of slack for WorkManager jitter). */
internal fun isScheduledSyncDue(settings: AccountSettings, lastSyncedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
    settings.syncIntervalMinutes > 0 && now - lastSyncedAt >= settings.syncIntervalMinutes * 60_000L - 60_000L
