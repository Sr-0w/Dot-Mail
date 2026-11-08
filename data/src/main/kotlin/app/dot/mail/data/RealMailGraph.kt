package app.dot.mail.data

import android.content.Context
import androidx.core.content.FileProvider
import androidx.paging.*
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import app.dot.mail.engine.autoconfig.AutoDiscovery
import app.dot.mail.engine.autoconfig.SocketType
import jakarta.mail.AuthenticationFailedException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import javax.net.ssl.SSLException

fun createMailGraph(context: Context): MailGraph {
    val graph = GraphRegistry.get(context)
    return object : MailGraph { override val accounts: AccountRepository = graph; override val mail: MailRepository = graph; override val compose: ComposeRepository = graph }
}
internal object GraphRegistry {
    @Volatile private var graph: RealMailGraph? = null
    fun get(context: Context): RealMailGraph = graph ?: synchronized(this) {
        graph ?: RealMailGraph(context.applicationContext,
            Room.databaseBuilder(context.applicationContext, MailDatabase::class.java, "dotmail.db").build(),
            KeystoreCredentialVault()).also { graph = it; it.start() }
    }
}
internal fun ServerConfig.endpoint() = Endpoint(host, port, username, TransportSecurity.valueOf(security.name))

internal class RealMailGraph(
    internal val context: Context,
    internal val database: MailDatabase,
    private val vault: CredentialVault,
    internal val client: MailClient = MailClient(),
    private val scheduling: Boolean = true,
) : AccountRepository, MailRepository, ComposeRepository {
    internal val dao = database.mail()
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val syncLock = Mutex()
    internal val syncing = MutableStateFlow<Set<Long>>(emptySet())
    override val accounts: Flow<List<Account>> = combine(dao.observeAccounts(), syncing) { rows, active -> rows.map { row ->
        Account(row.id, row.name, row.email, row.color, listOf(Identity(row.id, row.id, row.name, row.email, isDefault = true)),
            if (row.id in active) SyncState.Syncing else row.syncError?.let { SyncState.Error(it) } ?: SyncState.Idle)
    } }
    override val outbox: Flow<List<OutboxItem>> = dao.observeOutbox().map { rows -> rows.map { row ->
        val draft = Codec.draft(row.draft)
        val state = when (row.state) {
            "SUBMITTING" -> OutboxState.SENDING
            "APPEND", "APPENDING" -> OutboxState.SAVING_SENT
            "UNCERTAIN" -> OutboxState.NEEDS_REVIEW
            else -> OutboxState.QUEUED
        }
        OutboxItem(row.id, row.accountId, draft.subject, (draft.to + draft.cc + draft.bcc).distinctBy { it.email }, row.dueAt, state, row.error,
            row.state in setOf("UNCERTAIN", "APPEND") || (row.state == "PENDING" && row.error != null), row.state in setOf("PENDING", "APPEND", "UNCERTAIN"))
    } }.flowOn(Dispatchers.IO)
    internal fun start() { if (scheduling) { WorkScheduling.periodic(context); ForegroundIdle(this).start(); scope.launch { recoverOutbox() } } }
    internal suspend fun credentials(row: AccountRow): Credentials = withContext(Dispatchers.IO) { Credentials(vault.decrypt(row.email, row.secret), Codec.config(row.config).authType == AuthType.OAUTH2) }
    private val syncEnqueuePending = java.util.concurrent.atomic.AtomicBoolean(false)
    internal fun requestSync() {
        if (scheduling && syncEnqueuePending.compareAndSet(false, true)) scope.launch {
            try { delay(300); WorkScheduling.sync(context) } finally { syncEnqueuePending.set(false) }
        }
    }

    override suspend fun discover(email: String): AccountConfig? = withContext(Dispatchers.IO) {
        val result = AutoDiscovery().discover(email) ?: return@withContext null
        fun endpoint(s: app.dot.mail.engine.autoconfig.DiscoveredServer) = ServerConfig(s.host, s.port, when(s.socket) { SocketType.SSL -> Security.SSL_TLS; SocketType.STARTTLS -> Security.STARTTLS; SocketType.PLAIN -> Security.NONE }, s.username)
        AccountConfig(email, email.substringBefore('@'), endpoint(result.imap), endpoint(result.smtp), if (result.oauthRequired) AuthType.OAUTH2 else AuthType.PASSWORD, result.hint)
    }
    override suspend fun verify(config: AccountConfig, password: String): VerifyResult = withContext(Dispatchers.IO) {
        try { client.verify(config.imap.endpoint(), config.smtp.endpoint(), Credentials(password, config.authType == AuthType.OAUTH2)); VerifyResult.Ok }
        catch (e: AuthenticationFailedException) { VerifyResult.AuthFailed("Authentication failed. Check your password or app password.") }
        catch (e: Exception) {
            if (generateSequence<Throwable>(e) { it.cause }.any { it is SSLException }) VerifyResult.CertificateProblem("Server TLS certificate could not be verified")
            else VerifyResult.ConnectionFailed("Could not connect to the mail server")
        }
    }
    override suspend fun addAccount(config: AccountConfig, password: String): Long = withContext(Dispatchers.IO) {
        require(config.email.isNotBlank() && config.imap.host.isNotBlank() && config.smtp.host.isNotBlank())
        val id = dao.insertAccount(AccountRow(email = config.email, name = config.displayName, color = dao.accounts().size % 6, config = Codec.config(config), secret = vault.encrypt(config.email, password)))
        requestSync(); id
    }
    override suspend fun removeAccount(accountId: Long) = withContext(Dispatchers.IO) { syncLock.withLock { dao.deleteAccount(accountId); File(context.filesDir, "attachments/$accountId").deleteRecursively(); File(context.filesDir, "outbox/$accountId").deleteRecursively(); Unit } }
    override fun folders(accountId: Long): Flow<List<app.dot.mail.data.model.Folder>> = dao.observeFolders(accountId).map { rows -> rows.map { app.dot.mail.data.model.Folder(it.id, it.accountId, it.path, it.name, FolderRole.valueOf(it.role), it.unread, it.total) } }
    private fun summary(t: ThreadRow) = ThreadSummary(t.id, t.accountId, t.subject, Codec.addresses(t.participants), t.snippet, t.lastAt, t.count, t.unread, t.starred, t.attachments)
    private fun paging(where: String, args: List<Any>): Flow<PagingData<ThreadSummary>> = Pager(PagingConfig(pageSize = 40)) {
        dao.page(SimpleSQLiteQuery("SELECT t.* FROM threads t WHERE t.count>0 AND ($where) ORDER BY t.lastAt DESC,t.id DESC", args.toTypedArray()))
    }.flow.map { data -> data.map(::summary) }
    override fun threads(mailbox: Mailbox): Flow<PagingData<ThreadSummary>> = when (mailbox) {
        is Mailbox.InFolder -> paging("EXISTS(SELECT 1 FROM messages m WHERE m.threadId=t.id AND m.folderId=?)", listOf(mailbox.folderId))
        is Mailbox.Unified -> paging("EXISTS(SELECT 1 FROM messages m JOIN folders f ON f.id=m.folderId WHERE m.threadId=t.id AND f.role=?)", listOf(mailbox.role.name))
    }
    override fun search(query: String, accountId: Long?): Flow<PagingData<ThreadSummary>> = flow {
        val job = scope.launch { runCatching { serverSearch(query, accountId) } }
        try { emitAll(Pager(PagingConfig(pageSize = 40)) { searchSource(query, accountId) }.flow.map { it.map(::summary) }) }
        finally { job.cancel() }
    }
    internal fun searchSource(query: String, accountId: Long?): PagingSource<Int, ThreadRow> {
        val predicates = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (accountId != null) { predicates += "t.accountId=?"; args += accountId }
        fun like(value: String): String = "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        for (term in MailSearch.parse(query).terms) {
            val field = when (term.field) {
                "from" -> "m.senderSearch"; "to" -> "m.recipientSearch"; "subject" -> "m.subject"; else -> null
            }
            val condition = when {
                field != null -> { args += like(term.value); "$field LIKE ? ESCAPE '!'" }
                term.field == "has" -> "m.hasAttachments=1"
                term.field == "is" -> if (term.value.equals("unread", true)) "m.unread=1" else "m.starred=1"
                else -> { repeat(4) { args += like(term.value) }; "(m.senderSearch LIKE ? ESCAPE '!' OR m.subject LIKE ? ESCAPE '!' OR m.snippet LIKE ? ESCAPE '!' OR m.text LIKE ? ESCAPE '!')" }
            }
            predicates += "EXISTS(SELECT 1 FROM messages m WHERE m.threadId=t.id AND ($condition))"
        }
        val where = predicates.joinToString(" AND ").ifBlank { "1=1" }
        return dao.page(SimpleSQLiteQuery("SELECT t.* FROM threads t WHERE t.count>0 AND ($where) ORDER BY t.lastAt DESC,t.id DESC", args.toTypedArray()))
    }
    private suspend fun serverSearch(query: String, accountId: Long?) = syncLock.withLock {
        if (query.isBlank()) return@withLock
        dao.accounts().filter { accountId == null || it.id == accountId }.forEach { account ->
            val config = Codec.config(account.config); val creds = credentials(account)
            dao.folders(account.id).forEach { folder ->
                val headers = client.search(config.imap.endpoint(), creds, folder.path, query)
                database.withTransaction { headers.forEach { upsertHeader(folder, it) } }
            }
        }
    }
    override fun thread(threadId: Long): Flow<List<app.dot.mail.data.model.Message>> = dao.observeThread(threadId).map { rows -> rows.map { row ->
        app.dot.mail.data.model.Message(row.id, row.accountId, row.folderId, row.threadId, Codec.addressField(row.header, "from").firstOrNull() ?: EmailAddress(""), Codec.addressField(row.header, "to"), Codec.addressField(row.header, "cc"), Codec.addressField(row.header, "bcc"), Codec.addressField(row.header, "replyTo"), row.subject, row.sentAt, row.snippet, row.unread, row.starred,
            if (row.bodyLoaded) MessageBody(row.text, row.html, row.remoteImages) else null, dao.attachments(row.id).map(::attachment), Codec.field(row.header, "messageId"), Codec.strings(Codec.field(row.header, "refs") ?: "[]"), Codec.field(row.header, "unsubscribe"))
    } }.flowOn(Dispatchers.IO)
    private fun attachment(a: AttachmentRow) = Attachment(a.id, a.messageId, a.name, a.mime, a.size, a.cid, a.inline, a.localUri)
    private fun attachmentFile(accountId: Long, name: String = UUID.randomUUID().toString()): File = File(context.filesDir, "attachments/$accountId/$name").also { it.parentFile!!.mkdirs() }
    private fun safeFileName(name: String): String = name.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }.joinToString("").take(180).ifBlank { "attachment" }
    private fun uri(file: File): String = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file).toString()
    override suspend fun loadBody(messageId: Long) = withContext(Dispatchers.IO) { syncLock.withLock {
        val message = dao.message(messageId) ?: return@withLock
        if (message.bodyLoaded) return@withLock
        val folder = requireNotNull(dao.folder(message.folderId)); val account = requireNotNull(dao.account(message.accountId)); val config = Codec.config(account.config)
        val body = client.body(config.imap.endpoint(), credentials(account), folder.path, message.uid, folder.validity)
        val parts = body.attachments.map { part -> val file = attachmentFile(account.id, "${UUID.randomUUID()}/${safeFileName(part.name)}"); file.writeBytes(part.bytes); AttachmentRow(messageId = messageId, partId = part.partId, name = part.name, mime = part.mimeType, size = part.bytes.size.toLong(), cid = part.contentId, inline = part.inline, localUri = uri(file)) }
        database.withTransaction {
            dao.clearAttachments(messageId); parts.forEach { dao.insertAttachment(it) }
            dao.updateMessage(message.copy(text = body.text ?: body.html?.let { org.jsoup.Jsoup.parse(it).text() }, html = body.html, bodyLoaded = true, remoteImages = body.html?.let { HtmlSanitizer.sanitize(it).hasRemoteImages } ?: false, snippet = (body.text ?: body.html?.let { org.jsoup.Jsoup.parse(it).text() })?.replace(Regex("\\s+"), " ")?.take(180) ?: "", hasAttachments = parts.any { !it.inline }))
            rebuildThread(message.threadId)
        }
    } }
    override suspend fun openAttachment(attachment: Attachment): String = withContext(Dispatchers.IO) {
        dao.attachment(attachment.id)?.localUri ?: run { loadBody(attachment.messageId); dao.attachment(attachment.id)?.localUri ?: error("Attachment no longer available") }
    }
    internal suspend fun upsertHeader(folder: FolderRow, header: Header): Pair<Long, Boolean> {
        val old = dao.byUid(folder.id, header.uid) ?: header.messageId?.let { mid -> dao.folderMessages(folder.id).firstOrNull { it.uid < 0 && Codec.field(it.header, "messageId") == mid } }
        if (old != null) {
            // Pending optimistic changes win until replay completes.
            if (dao.operations(folder.accountId).none { it.folderId == folder.id && it.uid == header.uid && it.validity == folder.validity }) dao.updateMessage(old.copy(uid = header.uid, unread = !header.flags.seen, starred = header.flags.starred))
            rebuildThread(old.threadId); return old.threadId to false
        }
        val keys = Threading.keys(header)
        val threadId = keys.firstNotNullOfOrNull { dao.threadKey(folder.accountId, it) } ?: dao.insertThread(ThreadRow(accountId = folder.accountId, subject = header.subject))
        keys.forEach { dao.insertKey(ThreadKeyRow(folder.accountId, it, threadId)) }
        dao.insertMessage(MessageRow(accountId = folder.accountId, folderId = folder.id, threadId = threadId, uid = header.uid, header = Codec.header(header), senderSearch = header.from.joinToString(" ") { "${it.name.orEmpty()} ${it.email}" }, recipientSearch = header.to.joinToString(" ") { "${it.name.orEmpty()} ${it.email}" }, subject = header.subject, sentAt = header.sentAt, unread = !header.flags.seen, starred = header.flags.starred, hasAttachments = header.hasAttachments))
        (header.from + header.to + header.cc).forEach { dao.putContact(ContactRow(folder.accountId, it.email, it.name, header.sentAt)) }
        rebuildThread(threadId); return threadId to true
    }
    internal suspend fun rebuildThread(id: Long) {
        val row = dao.threadRow(id) ?: return
        val messages = dao.threadMessages(id)
        val distinct = messages.distinctBy { Codec.field(it.header, "messageId") ?: "local:${it.id}" }
        val latest = distinct.lastOrNull()
        dao.updateThread(row.copy(subject = latest?.subject ?: row.subject, participants = Codec.addresses(distinct.flatMap { Codec.addressField(it.header, "from") }.distinctBy { it.email }), snippet = latest?.snippet.orEmpty(), lastAt = latest?.sentAt ?: 0, count = distinct.size, unread = distinct.count { it.unread }, starred = distinct.any { it.starred }, attachments = distinct.any { it.hasAttachments }))
    }
    override suspend fun apply(action: MessageAction, threadIds: Collection<Long>) = withContext(Dispatchers.IO) { syncLock.withLock {
        database.withTransaction {
            threadIds.distinct().forEach { thread ->
                dao.threadMessages(thread).forEach { message ->
                    require(message.uid > 0) { "Message move is awaiting server synchronization" }
                    val folder = requireNotNull(dao.folder(message.folderId))
                    val targetRole = when (action) { MessageAction.ARCHIVE -> FolderRole.ARCHIVE; MessageAction.TRASH -> FolderRole.TRASH; MessageAction.SPAM -> FolderRole.SPAM; MessageAction.NOT_SPAM -> FolderRole.INBOX; else -> null }
                    val target = targetRole?.let { role -> dao.folders(message.accountId).firstOrNull { it.role == role.name } ?: if (role == FolderRole.ARCHIVE) dao.folders(message.accountId).firstOrNull { it.role == FolderRole.ALL_MAIL.name } else null }
                    require(targetRole == null || target != null) { "No ${targetRole?.name} folder available" }
                    dao.insertOperation(OperationRow(accountId = message.accountId, folderId = folder.id, validity = folder.validity, uid = message.uid, action = if (target != null) "MOVE" else action.name, target = target?.path))
                    when (action) {
                        MessageAction.MARK_READ -> dao.updateMessage(message.copy(unread = false)); MessageAction.MARK_UNREAD -> dao.updateMessage(message.copy(unread = true))
                        MessageAction.STAR -> dao.updateMessage(message.copy(starred = true)); MessageAction.UNSTAR -> dao.updateMessage(message.copy(starred = false))
                        else -> if (target != null) dao.updateMessage(message.copy(folderId = target.id, uid = -message.id)) else dao.deleteMessage(message.id)
                    }
                }; rebuildThread(thread)
            }
        }; requestSync()
    } }
    override suspend fun move(threadIds: Collection<Long>, targetFolderId: Long) = withContext(Dispatchers.IO) { syncLock.withLock {
        val target = requireNotNull(dao.folder(targetFolderId))
        database.withTransaction { threadIds.distinct().forEach { thread -> dao.threadMessages(thread).forEach { message ->
            require(message.accountId == target.accountId) { "Moving between accounts is unsupported" }
            if (message.folderId != target.id) { val source = requireNotNull(dao.folder(message.folderId)); dao.insertOperation(OperationRow(accountId = message.accountId, folderId = source.id, validity = source.validity, uid = message.uid, action = "MOVE", target = target.path)); dao.updateMessage(message.copy(folderId = target.id, uid = -message.id)) }
        }; rebuildThread(thread) } }; requestSync()
    } }
    override suspend fun refresh(mailbox: Mailbox) { requestSync() }
    override suspend fun suggestContacts(prefix: String, limit: Int): List<EmailAddress> = withContext(Dispatchers.IO) {
        dao.contacts(prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%", limit.coerceIn(1, 50)).map { EmailAddress(it.email, it.name) }
    }
    override suspend fun saveDraft(draft: Draft): Long = withContext(Dispatchers.IO) {
        requireNotNull(dao.account(draft.accountId))
        if (draft.localId != 0L) require(dao.draft(draft.localId)?.accountId == draft.accountId)
        val (cached, sources) = cacheAttachments(draft)
        val json = org.json.JSONObject(Codec.draft(cached)).put("attachmentSources", sources).toString()
        dao.putDraft(DraftRow(draft.localId, draft.accountId, json))
    }
    override suspend fun loadDraft(localId: Long): Draft? = dao.draft(localId)?.let { Codec.draft(it.json).copy(localId = it.id) }
    override suspend fun discardDraft(localId: Long) { dao.deleteDraft(localId) }
    override suspend fun prepareReply(messageId: Long, mode: ReplyMode): Draft = withContext(Dispatchers.IO) {
        loadBody(messageId)
        val m = requireNotNull(dao.message(messageId)); val account = requireNotNull(dao.account(m.accountId))
        val from = Codec.addressField(m.header, "replyTo").ifEmpty { Codec.addressField(m.header, "from") }
        val to = if (mode == ReplyMode.FORWARD) emptyList() else from
        val cc = if (mode == ReplyMode.REPLY_ALL) (Codec.addressField(m.header, "to") + Codec.addressField(m.header, "cc")).filter { a -> a.email != account.email && to.none { it.email == a.email } }.distinctBy { it.email } else emptyList()
        val messageHeader = Codec.field(m.header, "messageId")
        Draft(accountId = account.id, identityId = account.id, to = to, cc = cc, subject = (if (mode == ReplyMode.FORWARD) "Fwd: " else "Re: ") + Threading.baseSubject(m.subject), bodyText = "\n\n" + (m.text ?: org.jsoup.Jsoup.parse(m.html.orEmpty()).text()).lineSequence().joinToString("\n") { "> $it" },
            attachmentUris = if (mode == ReplyMode.FORWARD) dao.attachments(m.id).filter { !it.inline }.mapNotNull { it.localUri } else emptyList(), inReplyTo = if (mode == ReplyMode.FORWARD) null else messageHeader, references = if (mode == ReplyMode.FORWARD) emptyList() else (Codec.strings(Codec.field(m.header, "refs") ?: "[]") + listOfNotNull(messageHeader)).distinct())
    }
    override suspend fun send(draft: Draft, undoWindowMs: Long): Long = withContext(Dispatchers.IO) {
        require(draft.to.isNotEmpty() || draft.cc.isNotEmpty() || draft.bcc.isNotEmpty()) { "At least one recipient is required" }
        requireNotNull(dao.account(draft.accountId))
        val frozen = cacheAttachments(draft).first
        val due = maxOf(System.currentTimeMillis() + undoWindowMs.coerceIn(0, 60_000), draft.scheduledAt ?: 0)
        val id = database.withTransaction { val id = dao.insertOutbox(OutboxRow(accountId = draft.accountId, draft = Codec.draft(frozen), dueAt = due)); if (draft.localId != 0L) dao.deleteDraft(draft.localId); id }
        if (scheduling) WorkScheduling.outbox(context, id, due); id
    }
    override suspend fun cancelSend(outboxId: Long): Boolean = database.withTransaction {
        val row = dao.outbox(outboxId) ?: return@withTransaction false
        if (dao.transitionOutbox(outboxId, "PENDING", "CANCELLED") != 1) return@withTransaction false
        val draft = Codec.draft(row.draft)
        dao.putDraft(DraftRow(draft.localId, row.accountId, row.draft)); true
    }
    override suspend fun retrySend(outboxId: Long, confirmPossibleDuplicate: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!syncLock.tryLock()) return@withContext false
        try {
            val retry = database.withTransaction {
                val row = dao.outbox(outboxId) ?: return@withTransaction null
                val next = when (row.state) {
                    "UNCERTAIN" -> if (confirmPossibleDuplicate) "PENDING" else return@withTransaction null
                    "APPEND" -> "APPEND"
                    "PENDING" -> if (row.error != null) "PENDING" else return@withTransaction null
                    else -> return@withTransaction null
                }
                if (dao.transitionOutbox(outboxId, row.state, next) != 1) null else row.copy(state = next, error = null)
            } ?: return@withContext false
            if (scheduling) WorkScheduling.outbox(context, outboxId, retry.dueAt, replace = true)
            true
        } finally { syncLock.unlock() }
    }
    override suspend fun deleteOutbox(outboxId: Long): Boolean = withContext(Dispatchers.IO) {
        val deleted = database.withTransaction {
            val row = dao.outbox(outboxId) ?: return@withTransaction null
            if (row.state !in setOf("PENDING", "APPEND", "UNCERTAIN")) return@withTransaction null
            if (dao.transitionOutbox(outboxId, row.state, "DISCARDED") == 1) row else null
        } ?: return@withContext false
        if (scheduling) androidx.work.WorkManager.getInstance(context).cancelUniqueWork("dotmail.outbox.$outboxId")
        deleted.mimePath?.let { File(it).delete() }
        true
    }
    private suspend fun recoverOutbox() {
        dao.submittingOutbox().forEach { row ->
            dao.transitionOutbox(row.id, "SUBMITTING", "UNCERTAIN", "App stopped during SMTP submission. Check Sent before retrying.")
            dao.syncError(row.accountId, "A send could not be confirmed. Check Sent before retrying.")
        }
        dao.appendingOutbox().forEach { dao.transitionOutbox(it.id, "APPENDING", "APPEND", "Sent; saving its copy will resume") }
        dao.pendingOutbox().forEach { WorkScheduling.outbox(context, it.id, it.dueAt) }
    }
    private suspend fun cacheAttachments(draft: Draft): Pair<Draft, org.json.JSONObject> {
        require(draft.attachmentUris.size <= 50) { "Too many attachments" }
        val previous = if (draft.localId != 0L) dao.draft(draft.localId)?.let { org.json.JSONObject(it.json).optJSONObject("attachmentSources") } else null
        val sources = org.json.JSONObject()
        var total = 0L
        val cached = draft.attachmentUris.map { source ->
            val sourceUri = android.net.Uri.parse(source)
            require(sourceUri.scheme == "content") { "Attachments must be content URIs granted by the picker or sharing app" }
            val prior = previous?.optString(source)?.takeIf { it.isNotBlank() }
            val alreadyPrivate = source.startsWith("content://${context.packageName}.attachments/mail_attachments/${draft.accountId}/")
            val reuse = if (alreadyPrivate) source else prior
            if (reuse != null && runCatching { requireNotNull(context.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(reuse), "r")).use { total += it.length.coerceAtLeast(0) } }.isSuccess) {
                require(total <= 25 * 1024 * 1024) { "Attachments exceed 25 MiB" }
                sources.put(source, reuse); reuse
            } else {
                val parsedUri = android.net.Uri.parse(source)
                val originalName = context.contentResolver.query(parsedUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: parsedUri.lastPathSegment ?: "attachment"
                val safeName = safeFileName(originalName)
                val file = attachmentFile(draft.accountId, "${UUID.randomUUID()}/$safeName")
                try {
                    context.contentResolver.openInputStream(parsedUri)!!.use { input -> file.outputStream().use { output -> copyBounded(input, output, (25 * 1024 * 1024 - total).toInt()) } }
                    total += file.length()
                } catch (e: Exception) { file.delete(); throw e }
                uri(file).also { sources.put(source, it) }
            }
        }
        return draft.copy(attachmentUris = cached) to sources
    }
    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Int) {
        val buffer = ByteArray(8192); var total = 0
        while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= limit) { "Attachment exceeds 25 MiB limit" }; output.write(buffer, 0, count) }
    }
}
