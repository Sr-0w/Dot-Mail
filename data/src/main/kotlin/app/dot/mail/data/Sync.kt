package app.dot.mail.data

import androidx.room.withTransaction
import app.dot.mail.data.local.*
import app.dot.mail.data.model.MessageAction
import app.dot.mail.engine.*
import kotlinx.coroutines.sync.withLock
import java.io.File
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.util.Properties
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** [scheduled] = background run (WorkManager): per-account "manual only" and sync intervals apply. User-initiated runs always sync. */
internal suspend fun RealMailGraph.syncAll(accountIds: Set<Long>? = null, scheduled: Boolean = false): Boolean = syncLock.withLock {
    var success = true
    val settingsStore = AccountSettingsStore.get(context)
    val notificationRules = NotificationRules.get(context)
    dao.accounts().filter { accountIds == null || it.id in accountIds }.forEach { account ->
        val accountSettings = settingsStore.current(account.id)
        if (scheduled && !isScheduledSyncDue(accountSettings, settingsStore.lastSynced(account.id))) return@forEach
        syncing.value = syncing.value + account.id
        var stage = "Connect"
        var folderLabel: String? = null
        var headerWarning: String? = null
        var folderWarning: String? = null
        var redactions: Set<String> = setOf(account.email)
        try {
            val config = Codec.config(account.config); val creds = credentials(account)
            redactions = setOf(account.email, creds.secret)
            var deferredExpunge = false
            // A changed sync window re-downloads the folder headers inside the new window (only when nothing is waiting to be sent).
            val appliedWindow = settingsStore.appliedWindow(account.id)
            if (appliedWindow == null) settingsStore.markWindowApplied(account.id, accountSettings.syncWindowDays)
            else if (appliedWindow != accountSettings.syncWindowDays && dao.operations(account.id).isEmpty()) {
                dao.folders(account.id).forEach { dao.updateFolder(it.copy(validity = 0, nextUid = 1, modSeq = -1)) }
            }
            val windowLimit = if (accountSettings.syncWindowDays <= 0) 5000 else 500
            stage = "Folder commands"
            runFolderOps(account, config, creds).let { if (!it.success) success = false; folderWarning = it.warning }
            stage = "Connect"
            val remoteFolders = client.folders(config.imap.endpoint(), creds)
            if(remoteFolders.none { it.role==Role.DRAFTS } && dao.draftMirrors(account.id).any { it.deleted || it.revision!=it.syncedRevision }) {
                headerWarning="Drafts folder unavailable; local changes are queued"
                success=false
            }
            stage = "Folder list"
            reconcileFolders(account, config, creds, remoteFolders)
            for (remote in remoteFolders.sortedBy { when(it.role) { Role.INBOX -> 0; Role.SENT -> 1; Role.DRAFTS -> 2; else -> 3 } }) {
                folderLabel = remote.role.name
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
                val initialCursor = SyncCursor(folder.validity, folder.nextUid, folder.modSeq)
                var result = client.sync(config.imap.endpoint(), creds, folder.path, initialCursor, limit = windowLimit, windowDays = accountSettings.syncWindowDays)
                if (result.failures.isNotEmpty()) {
                    result.failures.forEach { SyncDiagnostics.warn("Header UID=${it.uid} account=${account.id} folderRole=${remote.role}", it.error, redactions) }
                    headerWarning = "Folder ${remote.role}: some message headers could not be read; retrying"
                }
                val missing=dao.folderMessages(folder.id).filter { it.uid>0 && Codec.field(it.header,"category")==null && result.headers.none { h -> h.uid==it.uid } }.take(200)
                if(missing.isNotEmpty() && !result.reset) {
                    try { result=result.copy(headers=result.headers+client.headers(config.imap.endpoint(),creds,folder.path,missing.map { it.uid }.toLongArray(),result.cursor.uidValidity)) }
                    catch(e: CancellationException) { throw e } catch(e: Exception) { SyncDiagnostics.warn("Classification metadata account=${account.id} folderRole=${remote.role}",e,redactions) }
                }
                var replayed = false
                for (queued in dao.operations(account.id).filter { it.folderId == folder.id }) {
                    var op = dao.operation(queued.id) ?: continue
                    if (op.action == "DEFERRED_FLAGS") {
                        val row = op.messageLocalId?.let { dao.message(it) }
                        if (row == null) { dao.deleteOperation(op.id); continue }
                        if (row.uid < 1) continue
                        if (row.folderId != folder.id) { dao.folder(row.folderId)?.let { dao.updateOperation(op.copy(folderId = row.folderId, validity = it.validity, uid = row.uid)) }; success = false; continue }
                        if (dao.folder(row.folderId)?.validity != result.cursor.uidValidity) { dao.updateOperation(op.copy(error = "UIDVALIDITY changed; operation requires review")); continue }
                        try {
                            client.setFlags(config.imap.endpoint(), creds, folder.path, longArrayOf(row.uid), seen = op.desiredSeen, starred = op.desiredStarred)
                            dao.deleteOperation(op.id); replayed = true
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { SyncDiagnostics.warn("Deferred flags account=${account.id} folderRole=${remote.role}", e, redactions); success = false }
                        continue
                    }
                    if (op.validity != result.cursor.uidValidity) {
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
                        dao.updateOperation(op.copy(attempts = op.attempts + 1, error = SyncDiagnostics.reason(e))); success = false; break
                    }
                }
                if (replayed) result = client.sync(config.imap.endpoint(), creds, folder.path, initialCursor, limit = windowLimit, windowDays = accountSettings.syncWindowDays)
                val notifications = mutableListOf<Triple<Long, String, Long>>()
                database.withTransaction {
                    val old = dao.folderMessages(folder.id)
                    if (result.reset) old.filter { it.uid > 0 }.forEach { dao.deleteMessage(it.id) }
                    else old.filter { it.uid > 0 && it.uid !in result.existingUids }.forEach { dao.deleteMessage(it.id) }
                    val operations = dao.operations(account.id)
                    val pending = operations.filter { it.folderId == folder.id && it.validity == result.cursor.uidValidity }.map { it.uid }.toSet()
                    val pendingLocalIds = operations.mapNotNull { it.messageLocalId }.toSet()
                    result.headers.filter { !it.flags.deleted && it.uid !in pending }.forEach { header ->
                        val (thread, fresh) = upsertHeader(folder.copy(validity = result.cursor.uidValidity), header)
                        if (fresh && !result.reset) dao.deleteThreadSnooze(thread) // new mail in a snoozed conversation wakes it
                        if (fresh && !result.reset && !header.flags.seen && notificationRules.notifyForFolder(folder.id, remote.role.name) && !notificationRules.isMuted(thread)) notifications += Triple(thread, header.subject, folder.id)
                    }
                    result.flags.filter { it.uid !in pending }.forEach { flag -> dao.byUid(folder.id, flag.uid)?.let { row ->
                        if (row.id !in pendingLocalIds) { if (flag.deleted) dao.deleteMessage(row.id) else dao.updateMessage(row.copy(unread = !flag.seen, starred = flag.starred)) }
                    } }
                    (old.map { it.threadId } + dao.folderMessages(folder.id).map { it.threadId }).distinct().forEach { rebuildThread(it) }
                    dao.updateFolder(folder.copy(name = remote.name, role = remote.role.name, unread = remote.unread, total = remote.total, validity = result.cursor.uidValidity, nextUid = result.cursor.uidNext, modSeq = result.cursor.highestModSeq))
                }
                if(remote.role==Role.DRAFTS) database.withTransaction { linkDraftCopies(dao.folder(folder.id)!!) }
                // Only newest cached messages without a preview are fetched, at most 200 × 2 KiB per folder.
                val previewRows = dao.folderMessages(folder.id).filter { it.uid > 0 }.sortedByDescending { it.sentAt }.take(200).filter { !it.snippetFetched && !it.bodyLoaded }
                if (previewRows.isNotEmpty()) {
                    val snippets = try { client.snippets(config.imap.endpoint(), creds, folder.path, previewRows.map { it.uid }.toLongArray(), result.cursor.uidValidity) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { SyncDiagnostics.warn("Snippet prefetch account=${account.id} folderRole=${remote.role}", e, redactions); emptyMap() } // Header/flag sync remains authoritative even when previews fail.
                    database.withTransaction {
                        snippets.forEach { (uid, snippet) -> dao.byUid(folder.id, uid)?.let { dao.updateMessage(it.copy(snippet = snippet, snippetFetched = true)) } }
                        previewRows.map { it.threadId }.distinct().forEach { rebuildThread(it) }
                    }
                }
                notifications.distinctBy { it.first }.forEach { (id, subject, folderId) -> MailNotifications.newMail(context, account, id, subject, folderId) }
            }
            settingsStore.markWindowApplied(account.id, accountSettings.syncWindowDays)
            settingsStore.markSynced(account.id)
            stage = "Folder delete"
            finishFolderDeletes(account, config, creds).let { if (!it.success) success = false; if (folderWarning == null) folderWarning = it.warning }
            dao.syncError(account.id, if (folderWarning != null) folderWarning else if (headerWarning != null) headerWarning else if (deferredExpunge) "Move copied messages and marked the originals deleted; server lacks targeted expunge" else if (dao.operations(account.id).any { it.error != null }) "Some server changes are pending or require review" else null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            SyncDiagnostics.warn("$stage account=${account.id} folderRole=${folderLabel ?: "NONE"}", e, redactions)
            success = false
            val reason = SyncDiagnostics.reason(e)
            dao.syncError(account.id, folderLabel?.let { "Folder $it: $reason" } ?: reason, e is jakarta.mail.AuthenticationFailedException || e is app.dot.mail.data.oauth.ReauthenticationRequired)
        }
        finally { syncing.value = syncing.value - account.id }
    }
    try { cacheCleanup.run() } catch(e: Exception) { SyncDiagnostics.warn("Private cache cleanup",e) }
    success
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
            attachments = attachments, inReplyTo = draft.inReplyTo, references = draft.references, replyTo = listOfNotNull(identity.replyTo?.let { Address(it) }), messageId = "<dotmail-$id-${java.util.UUID.randomUUID()}@${account.email.substringAfter('@')}>", date = java.time.Instant.now(), calendarReply = org.json.JSONObject(row.draft).optString("calendarReply").takeIf { it.isNotBlank() }))
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
