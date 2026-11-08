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

internal suspend fun RealMailGraph.syncAll(): Boolean = syncLock.withLock {
    var success = true
    dao.accounts().forEach { account ->
        syncing.value = syncing.value + account.id
        try {
            val config = Codec.config(account.config); val creds = credentials(account)
            var deferredExpunge = false
            val remoteFolders = client.folders(config.imap.endpoint(), creds)
            for (remote in remoteFolders) {
                var folder = dao.folders(account.id).firstOrNull { it.path == remote.path }
                if (folder == null) {
                    val id = dao.insertFolder(FolderRow(accountId = account.id, path = remote.path, name = remote.name, role = remote.role.name))
                    folder = requireNotNull(dao.folder(id))
                }
                val initialCursor = SyncCursor(folder.validity, folder.nextUid, folder.modSeq)
                var result = client.sync(config.imap.endpoint(), creds, folder.path, initialCursor)
                var replayed = false
                for (op in dao.operations(account.id).filter { it.folderId == folder.id }) {
                    if (op.validity != result.cursor.uidValidity) {
                        dao.updateOperation(op.copy(error = "UIDVALIDITY changed; operation requires review", attempts = op.attempts + 1)); continue
                    }
                    try {
                        when (op.action) {
                            "MOVE" -> { if (client.move(config.imap.endpoint(), creds, folder.path, longArrayOf(op.uid), requireNotNull(op.target)) == MoveOutcome.COPIED_AND_MARKED_DELETED) deferredExpunge = true }
                            MessageAction.MARK_READ.name -> client.setFlags(config.imap.endpoint(), creds, folder.path, longArrayOf(op.uid), seen = true)
                            MessageAction.MARK_UNREAD.name -> client.setFlags(config.imap.endpoint(), creds, folder.path, longArrayOf(op.uid), seen = false)
                            MessageAction.STAR.name -> client.setFlags(config.imap.endpoint(), creds, folder.path, longArrayOf(op.uid), starred = true)
                            MessageAction.UNSTAR.name -> client.setFlags(config.imap.endpoint(), creds, folder.path, longArrayOf(op.uid), starred = false)
                            MessageAction.DELETE_FOREVER.name -> client.setFlags(config.imap.endpoint(), creds, folder.path, longArrayOf(op.uid), delete = true)
                        }
                        dao.deleteOperation(op.id); replayed = true
                    } catch (e: Exception) { dao.updateOperation(op.copy(attempts = op.attempts + 1, error = "Server operation failed")); success = false; break }
                }
                if (replayed) result = client.sync(config.imap.endpoint(), creds, folder.path, initialCursor)
                val notifications = mutableListOf<Pair<Long, String>>()
                database.withTransaction {
                    val old = dao.folderMessages(folder.id)
                    if (result.reset) old.filter { it.uid > 0 }.forEach { dao.deleteMessage(it.id) }
                    else old.filter { it.uid > 0 && it.uid !in result.existingUids }.forEach { dao.deleteMessage(it.id) }
                    val pending = dao.operations(account.id).filter { it.folderId == folder.id && it.validity == result.cursor.uidValidity }.map { it.uid }.toSet()
                    result.headers.filter { !it.flags.deleted && it.uid !in pending }.forEach { header ->
                        val (thread, fresh) = upsertHeader(folder.copy(validity = result.cursor.uidValidity), header)
                        if (fresh && !result.reset && !header.flags.seen && remote.role == Role.INBOX) notifications += thread to header.subject
                    }
                    result.flags.filter { it.uid !in pending }.forEach { flag -> dao.byUid(folder.id, flag.uid)?.let { row ->
                        if (flag.deleted) dao.deleteMessage(row.id) else dao.updateMessage(row.copy(unread = !flag.seen, starred = flag.starred))
                    } }
                    (old.map { it.threadId } + dao.folderMessages(folder.id).map { it.threadId }).distinct().forEach { rebuildThread(it) }
                    dao.updateFolder(folder.copy(name = remote.name, role = remote.role.name, unread = remote.unread, total = remote.total, validity = result.cursor.uidValidity, nextUid = result.cursor.uidNext, modSeq = result.cursor.highestModSeq))
                }
                notifications.distinctBy { it.first }.forEach { (id, subject) -> MailNotifications.newMail(context, account, id, subject) }
            }
            dao.syncError(account.id, if (deferredExpunge) "Move copied messages and marked the originals deleted; server lacks targeted expunge" else if (dao.operations(account.id).any { it.error != null }) "Some server changes are pending or require review" else null)
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; success = false; dao.syncError(account.id, if (e is jakarta.mail.AuthenticationFailedException) "Authentication failed" else "Could not synchronize account") }
        finally { syncing.value = syncing.value - account.id }
    }
    success
}

internal suspend fun RealMailGraph.deliverOutbox(id: Long): Boolean = syncLock.withLock {
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
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            OutgoingAttachment(known?.name ?: metadata ?: "attachment", known?.mime ?: context.contentResolver.getType(uri) ?: "application/octet-stream", bytes)
        }
        val mime = (row.mimePath?.let { File(it) }?.takeIf { it.isFile }?.inputStream()?.use { MimeMessage(Session.getInstance(Properties()), it) }) ?: Mime.build(OutgoingMail(Address(account.email, account.name), draft.to.map { Address(it.email, it.name) }, draft.cc.map { Address(it.email, it.name) }, draft.bcc.map { Address(it.email, it.name) }, draft.subject, draft.bodyText, attachments = attachments, inReplyTo = draft.inReplyTo, references = draft.references, messageId = "<dotmail-$id-${java.util.UUID.randomUUID()}@${account.email.substringAfter('@')}>", date = java.time.Instant.now()))
        val file = File(context.filesDir, "outbox/${account.id}/$id.eml").also { it.parentFile!!.mkdirs() }
        file.outputStream().use { mime.writeTo(it) }
        row = row.copy(mimePath = file.absolutePath)
        dao.outboxPath(id, file.absolutePath)
        if (dao.transitionOutbox(id, "PENDING", "SUBMITTING") != 1) return@withLock true
        try {
            client.send(config.smtp.endpoint(), creds, mime)
        } catch (e: SubmissionNotStartedException) {
            dao.transitionOutbox(id, "SUBMITTING", "PENDING", "Connection failed before submission; will retry")
            return@withLock false
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.transitionOutbox(id, "SUBMITTING", "UNCERTAIN", "Submission was interrupted. Check Sent before retrying.") }
            throw e
        } catch (e: Exception) {
            // SMTP can lose its final reply after accepting DATA. Automatic retry could duplicate mail.
            dao.transitionOutbox(id, "SUBMITTING", "UNCERTAIN", "Delivery could not be confirmed. Check Sent before retrying.")
            dao.syncError(account.id, "A queued send could not be confirmed. Check Sent before retrying.")
            return@withLock true
        }
        val autoSent = config.imap.host.equals("imap.gmail.com", true) || config.imap.host.equals("imap.googlemail.com", true)
        dao.transitionOutbox(id, "SUBMITTING", if (autoSent) "SENT" else "APPEND")
        row = requireNotNull(dao.outbox(id))
    }
    if (row.state == "APPEND") {
        if (dao.transitionOutbox(id, "APPEND", "APPENDING") != 1) return@withLock true
        try {
            val sent = dao.folders(account.id).firstOrNull { it.role == Role.SENT.name }?.path ?: "Sent"
            val mime = File(requireNotNull(row.mimePath)).inputStream().use { MimeMessage(Session.getInstance(Properties()), it) }
            mime.removeHeader("Bcc")
            client.append(config.imap.endpoint(), creds, sent, mime)
            dao.transitionOutbox(id, "APPENDING", "SENT")
        } catch (e: Exception) {
            withContext(NonCancellable) { dao.transitionOutbox(id, "APPENDING", "APPEND", "Message sent; its Sent copy could not be saved. Retry will only save the copy.") }
            if (e is CancellationException) throw e
            return@withLock false
        }
    }
    true
}
