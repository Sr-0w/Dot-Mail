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

internal suspend fun RealMailGraph.syncAll(accountIds: Set<Long>? = null): Boolean = syncLock.withLock {
    var success = true
    dao.accounts().filter { accountIds == null || it.id in accountIds }.forEach { account ->
        syncing.value = syncing.value + account.id
        var stage = "Connect"
        var folderLabel: String? = null
        var headerWarning: String? = null
        var redactions: Set<String> = setOf(account.email)
        try {
            val config = Codec.config(account.config); val creds = credentials(account)
            redactions = setOf(account.email, creds.secret)
            var deferredExpunge = false
            val remoteFolders = client.folders(config.imap.endpoint(), creds)
            for (remote in remoteFolders.sortedBy { when(it.role) { Role.INBOX -> 0; Role.SENT -> 1; Role.DRAFTS -> 2; else -> 3 } }) {
                folderLabel = remote.role.name
                stage = "Folder header sync"
                var folder = dao.folders(account.id).firstOrNull { it.path == remote.path }
                if (folder == null) {
                    val id = dao.insertFolder(FolderRow(accountId = account.id, path = remote.path, name = remote.name, role = remote.role.name))
                    folder = requireNotNull(dao.folder(id))
                }
                val initialCursor = SyncCursor(folder.validity, folder.nextUid, folder.modSeq)
                var result = client.sync(config.imap.endpoint(), creds, folder.path, initialCursor)
                if (result.failures.isNotEmpty()) {
                    result.failures.forEach { SyncDiagnostics.warn("Header UID=${it.uid} account=${account.id} folderRole=${remote.role}", it.error, redactions) }
                    headerWarning = "Folder ${remote.role}: some message headers could not be read; retrying"
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
                            MessageAction.DELETE_FOREVER.name -> client.setFlags(config.imap.endpoint(), creds, folder.path, longArrayOf(op.uid), delete = true)
                        }
                        dao.deleteOperation(op.id); replayed = true
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        SyncDiagnostics.warn("Operation ${op.action} account=${account.id} folderRole=${remote.role}", e, redactions)
                        dao.updateOperation(op.copy(attempts = op.attempts + 1, error = SyncDiagnostics.reason(e))); success = false; break
                    }
                }
                if (replayed) result = client.sync(config.imap.endpoint(), creds, folder.path, initialCursor)
                val notifications = mutableListOf<Pair<Long, String>>()
                database.withTransaction {
                    val old = dao.folderMessages(folder.id)
                    if (result.reset) old.filter { it.uid > 0 }.forEach { dao.deleteMessage(it.id) }
                    else old.filter { it.uid > 0 && it.uid !in result.existingUids }.forEach { dao.deleteMessage(it.id) }
                    val operations = dao.operations(account.id)
                    val pending = operations.filter { it.folderId == folder.id && it.validity == result.cursor.uidValidity }.map { it.uid }.toSet()
                    val pendingLocalIds = operations.mapNotNull { it.messageLocalId }.toSet()
                    result.headers.filter { !it.flags.deleted && it.uid !in pending }.forEach { header ->
                        val (thread, fresh) = upsertHeader(folder.copy(validity = result.cursor.uidValidity), header)
                        if (fresh && !result.reset && !header.flags.seen && remote.role == Role.INBOX) notifications += thread to header.subject
                    }
                    result.flags.filter { it.uid !in pending }.forEach { flag -> dao.byUid(folder.id, flag.uid)?.let { row ->
                        if (row.id !in pendingLocalIds) { if (flag.deleted) dao.deleteMessage(row.id) else dao.updateMessage(row.copy(unread = !flag.seen, starred = flag.starred)) }
                    } }
                    (old.map { it.threadId } + dao.folderMessages(folder.id).map { it.threadId }).distinct().forEach { rebuildThread(it) }
                    dao.updateFolder(folder.copy(name = remote.name, role = remote.role.name, unread = remote.unread, total = remote.total, validity = result.cursor.uidValidity, nextUid = result.cursor.uidNext, modSeq = result.cursor.highestModSeq))
                }
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
                notifications.distinctBy { it.first }.forEach { (id, subject) -> MailNotifications.newMail(context, account, id, subject) }
            }
            dao.syncError(account.id, if (headerWarning != null) headerWarning else if (deferredExpunge) "Move copied messages and marked the originals deleted; server lacks targeted expunge" else if (dao.operations(account.id).any { it.error != null }) "Some server changes are pending or require review" else null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            SyncDiagnostics.warn("$stage account=${account.id} folderRole=${folderLabel ?: "NONE"}", e, redactions)
            success = false
            val reason = SyncDiagnostics.reason(e)
            dao.syncError(account.id, folderLabel?.let { "Folder $it: $reason" } ?: reason, e is jakarta.mail.AuthenticationFailedException || e is app.dot.mail.data.oauth.ReauthenticationRequired)
        }
        finally { syncing.value = syncing.value - account.id }
    }
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
        val mime = saved?.let { StoredMimeMessage(it) } ?: Mime.build(OutgoingMail(Address(account.email, account.name), draft.to.map { Address(it.email, it.name) }, draft.cc.map { Address(it.email, it.name) }, draft.bcc.map { Address(it.email, it.name) }, draft.subject, draft.bodyText, attachments = attachments, inReplyTo = draft.inReplyTo, references = draft.references, messageId = "<dotmail-$id-${java.util.UUID.randomUUID()}@${account.email.substringAfter('@')}>", date = java.time.Instant.now()))
        val file = File(context.filesDir, "outbox/${account.id}/$id.eml").also { it.parentFile!!.mkdirs() }
        if (saved==null) {
            val temporary=File(file.parentFile,"${file.name}.tmp")
            try { temporary.outputStream().use { mime.writeTo(it) }; check(temporary.renameTo(file)) { "Could not persist queued MIME" } } finally { temporary.delete() }
        }
        row = row.copy(mimePath = file.absolutePath)
        dao.outboxPath(id, file.absolutePath)
        if (dao.transitionOutbox(id, "PENDING", "SUBMITTING") != 1) return@withLock true
        try {
            client.send(config.smtp.endpoint(), creds, mime)
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
