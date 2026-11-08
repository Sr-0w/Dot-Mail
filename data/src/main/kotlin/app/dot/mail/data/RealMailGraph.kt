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
import app.dot.mail.data.oauth.*

fun createMailGraph(context: Context): MailGraph {
    val graph = GraphRegistry.get(context)
    return object : MailGraph { override val accounts: AccountRepository = graph; override val mail: MailRepository = graph; override val compose: ComposeRepository = graph }
}
internal object GraphRegistry {
    @Volatile private var graph: RealMailGraph? = null
    fun get(context: Context): RealMailGraph = graph ?: synchronized(this) {
        graph ?: RealMailGraph(context.applicationContext,
            Room.databaseBuilder(context.applicationContext, MailDatabase::class.java, "dotmail.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build(),
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
    oauthClients: Map<OAuthProvider, String> = mapOf(OAuthProvider.GOOGLE to BuildConfig.OAUTH_GOOGLE_CLIENT_ID, OAuthProvider.MICROSOFT to BuildConfig.OAUTH_MICROSOFT_CLIENT_ID),
    tokenEndpoint: TokenEndpoint = HttpsTokenEndpoint(),
) : AccountRepository, MailRepository, ComposeRepository {
    internal val dao = database.mail()
    internal val bodyStore = BodyStore(context, dao)
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val syncLock = Mutex()
    internal val outboxLock = Mutex()
    private val searchLock = Mutex()
    private val bodyLocks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()
    private val oauth = OAuthManager(context, database, vault, oauthClients, tokenEndpoint)
    internal val syncing = MutableStateFlow<Set<Long>>(emptySet())
    override val accounts: Flow<List<Account>> = combine(dao.observeAccounts(), syncing) { rows, active -> rows.map { row ->
        Account(row.id, row.name, row.email, row.color, listOf(Identity(row.id, row.id, row.name, row.email, isDefault = true)),
            if (row.id in active) SyncState.Syncing else row.syncError?.let { SyncState.Error(it, isAuthError = row.authError) } ?: SyncState.Idle)
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
    internal suspend fun credentials(row: AccountRow): Credentials = withContext(Dispatchers.IO) {
        if (Codec.config(row.config).authType == AuthType.OAUTH2) oauth.credentials(row.id) else Credentials(vault.decrypt(row.email, row.secret))
    }
    override fun oauthAvailability(provider: OAuthProvider) = oauth.availability(provider)
    override suspend fun beginOAuth(email: String, provider: OAuthProvider, accountId: Long?): android.content.Intent = oauth.begin(email, provider, accountId)
    override suspend fun completeOAuth(resultUri: android.net.Uri): Long = oauth.complete(resultUri) { config, credentials -> client.verify(config.imap.endpoint(), config.smtp.endpoint(), credentials) }.also { requestSync() }
    private val syncEnqueuePending = java.util.concurrent.atomic.AtomicBoolean(false)
    internal fun requestSync() {
        if (scheduling && syncEnqueuePending.compareAndSet(false, true)) scope.launch {
            try { delay(300); WorkScheduling.sync(context) } finally { syncEnqueuePending.set(false) }
        }
    }

    override suspend fun discover(email: String): AccountConfig? = withContext(Dispatchers.IO) {
        val result = AutoDiscovery(mxResolver = AndroidMxResolver()).discover(email) ?: return@withContext null
        fun endpoint(s: app.dot.mail.engine.autoconfig.DiscoveredServer) = ServerConfig(s.host, s.port, when(s.socket) { SocketType.SSL -> Security.SSL_TLS; SocketType.STARTTLS -> Security.STARTTLS; SocketType.PLAIN -> Security.NONE }, s.username)
        val provider = when { result.imap.host.endsWith("gmail.com") || result.imap.host.endsWith("googlemail.com") -> OAuthProvider.GOOGLE; result.imap.host == "outlook.office365.com" -> OAuthProvider.MICROSOFT; else -> null }
        val hint = provider?.let { oauth.availability(it).reason } ?: result.hint
        AccountConfig(email, email.substringBefore('@'), endpoint(result.imap), endpoint(result.smtp), if (result.oauthRequired || provider != null) AuthType.OAUTH2 else AuthType.PASSWORD, hint)
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
        require(config.authType == AuthType.PASSWORD) { "OAuth accounts must be added through browser sign-in" }
        require(config.email.isNotBlank() && config.imap.host.isNotBlank() && config.smtp.host.isNotBlank())
        val id = dao.insertAccount(AccountRow(email = config.email, name = config.displayName, color = dao.accounts().size % 6, config = Codec.config(config), secret = vault.encrypt(config.email, password)))
        requestSync(); id
    }
    override suspend fun removeAccount(accountId: Long) = withContext(Dispatchers.IO) { syncLock.withLock { dao.deleteAccount(accountId); File(context.filesDir, "attachments/$accountId").deleteRecursively(); File(context.filesDir, "outbox/$accountId").deleteRecursively(); Unit } }
    override fun folders(accountId: Long): Flow<List<app.dot.mail.data.model.Folder>> = dao.observeFolders(accountId).map { rows -> rows.map { app.dot.mail.data.model.Folder(it.id, it.accountId, it.path, it.name, FolderRole.valueOf(it.role), it.unread, it.total) } }
    internal suspend fun summary(t: ThreadRow, draftView: Boolean = false): ThreadSummary {
        if (t.id < 0) {
            val row = dao.draft(-t.id)
            val d = row?.let { Codec.draft(it.json) }
            return ThreadSummary(t.id, t.accountId, d?.subject.orEmpty(), d?.to.orEmpty(), d?.bodyText?.let { app.dot.mail.engine.text.Snippets.fromPlainText(it) }.orEmpty(), row?.updatedAt ?: t.lastAt, 1, 0, false, d?.attachmentUris?.isNotEmpty() == true, draftId = -t.id, recipients = d?.to.orEmpty())
        }
        val messages = dao.threadMessages(t.id)
        val drafts = if (draftView) dao.folders(t.accountId).filter { it.role == "DRAFTS" }.map { it.id }.toSet() else emptySet()
        val latest = (if (draftView) messages.filter { it.folderId in drafts } else messages).lastOrNull()
        val handle = if (draftView) latest?.let { -it.id } else null
        return ThreadSummary(t.id, t.accountId, t.subject, Codec.addresses(t.participants), t.snippet, t.lastAt, t.count, t.unread, t.starred, t.attachments, draftId = handle, recipients = latest?.let { Codec.addressField(it.header, "to") }.orEmpty())
    }
    private fun paging(where: String, args: List<Any>, draftView: Boolean = false, draftAccount: Long? = null): Flow<PagingData<ThreadSummary>> = Pager(PagingConfig(pageSize = 40)) {
        mailboxSource(where, args, draftView, draftAccount)
    }.flow.map { data -> data.map { summary(it, draftView) } }
    internal fun mailboxSource(where: String, args: List<Any>, draftView: Boolean, draftAccount: Long?): PagingSource<Int, ThreadRow> {
        val remote = "SELECT t.* FROM threads t WHERE t.count>0 AND ($where)" + if (draftView) " AND NOT EXISTS(SELECT 1 FROM drafts d JOIN messages m ON m.id=d.sourceMessageId WHERE m.threadId=t.id)" else ""
        val local = if (draftView) " UNION ALL SELECT -id AS id,accountId,'' AS subject,'[]' AS participants,'' AS snippet,updatedAt AS lastAt,1 AS count,0 AS unread,0 AS starred,0 AS attachments FROM drafts" + if (draftAccount != null) " WHERE accountId=?" else "" else ""
        val params = args + if (draftView && draftAccount != null) listOf(draftAccount) else emptyList()
        return dao.page(SimpleSQLiteQuery("SELECT * FROM ($remote$local) ORDER BY lastAt DESC,id DESC", params.toTypedArray()))
    }
    override fun threads(mailbox: Mailbox): Flow<PagingData<ThreadSummary>> = flow {
        when (mailbox) {
            is Mailbox.InFolder -> {
                val folder = dao.folder(mailbox.folderId)
                emitAll(paging("EXISTS(SELECT 1 FROM messages m WHERE m.threadId=t.id AND m.folderId=?)", listOf(mailbox.folderId), folder?.role == "DRAFTS", folder?.accountId))
            }
            is Mailbox.Unified -> emitAll(paging("EXISTS(SELECT 1 FROM messages m JOIN folders f ON f.id=m.folderId WHERE m.threadId=t.id AND f.role=?)", listOf(mailbox.role.name), mailbox.role == FolderRole.DRAFTS))
        }
    }
    override fun search(query: String, accountId: Long?): Flow<PagingData<ThreadSummary>> = flow {
        val job = scope.launch { runCatching { serverSearch(query, accountId) } }
        try { emitAll(Pager(PagingConfig(pageSize = 40)) { searchSource(query, accountId) }.flow.map { it.map { row -> summary(row) } }) }
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
    private suspend fun serverSearch(query: String, accountId: Long?) = searchLock.withLock {
        if (query.isBlank()) return@withLock
        dao.accounts().filter { accountId == null || it.id == accountId }.forEach { account ->
            val config = Codec.config(account.config); val creds = credentials(account)
            dao.folders(account.id).forEach { folder ->
                val headers = client.search(config.imap.endpoint(), creds, folder.path, query)
                database.withTransaction { headers.forEach { upsertHeader(folder, it) } }
            }
        }
    }
    override fun thread(threadId: Long): Flow<List<app.dot.mail.data.model.Message>> = dao.observeThread(threadId).map { allRows ->
        val rows = allRows.groupBy { Codec.field(it.header, "messageId") ?: "local:${it.id}" }.values.map { copies -> copies.firstOrNull { it.bodyLoaded } ?: copies.first() }.sortedBy { it.sentAt }
        val attachments = dao.attachmentsFor(rows.map { it.id }).groupBy { it.messageId }
        rows.map { row ->
        app.dot.mail.data.model.Message(row.id, row.accountId, row.folderId, row.threadId, Codec.addressField(row.header, "from").firstOrNull() ?: EmailAddress(""), Codec.addressField(row.header, "to"), Codec.addressField(row.header, "cc"), Codec.addressField(row.header, "bcc"), Codec.addressField(row.header, "replyTo"), row.subject, row.sentAt, row.snippet, row.unread, row.starred,
            bodyStore.read(row), attachments[row.id].orEmpty().map(::attachment), Codec.field(row.header, "messageId"), Codec.strings(Codec.field(row.header, "refs") ?: "[]"), Codec.field(row.header, "unsubscribe"))
    } }.flowOn(Dispatchers.IO)
    private fun attachment(a: AttachmentRow) = Attachment(a.id, a.messageId, a.name, a.mime, a.size, a.cid, a.inline, a.localUri)
    private fun attachmentFile(accountId: Long, name: String = UUID.randomUUID().toString()): File = File(context.filesDir, "attachments/$accountId/$name").also { it.parentFile!!.mkdirs() }
    private fun safeFileName(name: String): String = name.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }.joinToString("").take(180).ifBlank { "attachment" }
    private fun uri(file: File): String = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file).toString()
    override suspend fun loadBody(messageId: Long) = withContext(Dispatchers.IO) { bodyLocks.getOrPut(messageId) { Mutex() }.withLock {
        val message = dao.message(messageId) ?: return@withLock
        if (message.bodyLoaded) return@withLock
        val pending = if (message.uid < 0) pendingMove(message) else null
        if (message.uid < 0 && pending == null) throw MailActionException(MailActionFailure.OPERATION_PENDING, "This message is moving. Try opening it shortly.")
        val folder = dao.folder(pending?.folderId ?: message.folderId) ?: throw MailActionException(MailActionFailure.FOLDER_UNAVAILABLE, "Message folder is no longer available")
        val account = requireNotNull(dao.account(message.accountId)); val config = Codec.config(account.config)
        val directory = File(context.filesDir,"bodies/${account.id}/${UUID.randomUUID()}")
        val body = client.cacheBody(config.imap.endpoint(), credentials(account), folder.path, pending?.uid ?: message.uid, directory, pending?.validity ?: folder.validity)
        val parts = body.attachments.map { part ->
            val file=attachmentFile(account.id,"${UUID.randomUUID()}/${safeFileName(part.name)}")
            if (!part.file.renameTo(file)) { part.file.copyTo(file); part.file.delete() }
            AttachmentRow(messageId=messageId,partId=part.partId,name=part.name,mime=part.mimeType,size=file.length(),cid=part.contentId,inline=part.inline,localUri=uri(file))
        }
        val temporary = message.copy(bodyLoaded=true,bodyPath=directory.absolutePath)
        val preview = bodyStore.read(temporary)
        val remote = preview?.html?.let { HtmlSanitizer.sanitize(it).hasRemoteImages } ?: false
        val snippet = preview?.plainText?.let { app.dot.mail.engine.text.Snippets.fromPlainText(it) } ?: preview?.html?.let { app.dot.mail.engine.text.Snippets.fromHtml(it) }.orEmpty()
        database.withTransaction {
            val current = dao.message(messageId)
            if(current==null) { directory.deleteRecursively(); return@withTransaction }
            dao.clearAttachments(messageId); parts.forEach { dao.insertAttachment(it) }
            dao.updateMessage(current.copy(text=null,html=null,bodyPath=directory.absolutePath,bodyLoaded=true,snippetFetched=true,remoteImages=remote,snippet=snippet,hasAttachments=parts.any { !it.inline }))
            bodyStore.index(messageId,body.text ?: body.html,body.text==null)
            rebuildThread(message.threadId)
        }
    } }
    override suspend fun openAttachment(attachment: Attachment): String = withContext(Dispatchers.IO) {
        dao.attachment(attachment.id)?.localUri ?: run { loadBody(attachment.messageId); dao.attachment(attachment.id)?.localUri ?: error("Attachment no longer available") }
    }
    internal suspend fun upsertHeader(folder: FolderRow, header: Header): Pair<Long, Boolean> {
        val old = dao.byUid(folder.id, header.uid) ?: header.messageId?.let { mid -> dao.pendingMessage(folder.id,mid) ?: dao.unkeyedPending(folder.id).firstNotNullOfOrNull { id -> dao.message(id)?.takeIf { Codec.field(it.header,"messageId")==mid } } }
        if (old != null) {
            // Pending optimistic changes win until replay completes.
            val pending = dao.operations(folder.accountId).any { it.messageLocalId == old.id || (it.folderId == folder.id && it.uid == header.uid && it.validity == folder.validity) }
            dao.updateMessage(old.copy(uid = header.uid, messageIdKey=header.messageId, unread = if (pending) old.unread else !header.flags.seen, starred = if (pending) old.starred else header.flags.starred))
            rebuildThread(old.threadId); return old.threadId to false
        }
        val keys = Threading.keys(header)
        val exact = keys.mapNotNull { dao.threadKey(folder.accountId, it) }.distinct()
        val fallback = if (exact.isEmpty() && Threading.mayFallback(header)) Threading.fallbackKey(header)?.let { dao.threadKey(folder.accountId, it) }?.takeIf { id -> dao.threadRow(id)?.let { kotlin.math.abs(header.sentAt - it.lastAt) <= 30L * 24 * 60 * 60 * 1000 } == true } else null
        val threadId = exact.firstOrNull() ?: fallback ?: dao.insertThread(ThreadRow(accountId = folder.accountId, subject = header.subject))
        exact.filter { it != threadId }.forEach { other -> dao.mergeMessages(other, threadId); dao.mergeKeys(other, threadId); dao.deleteThread(other) }
        keys.forEach { dao.insertKey(ThreadKeyRow(folder.accountId, it, threadId)) }
        if (header.gmailThreadId == null) Threading.fallbackKey(header)?.let { key ->
            val previous = dao.threadKey(folder.accountId, key)?.let { dao.threadRow(it) }
            if (previous == null || previous.lastAt <= header.sentAt) dao.putKey(ThreadKeyRow(folder.accountId, key, threadId))
        }
        dao.insertMessage(MessageRow(accountId = folder.accountId, folderId = folder.id, threadId = threadId, uid = header.uid, header = Codec.header(header), messageIdKey=header.messageId, senderSearch = header.from.joinToString(" ") { "${it.name.orEmpty()} ${it.email}" }, recipientSearch = header.to.joinToString(" ") { "${it.name.orEmpty()} ${it.email}" }, subject = header.subject, sentAt = header.sentAt, unread = !header.flags.seen, starred = header.flags.starred, hasAttachments = header.hasAttachments))
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
    private suspend fun moveMessage(message: MessageRow, target: FolderRow) {
        if (message.accountId != target.accountId) throw MailActionException(MailActionFailure.ACCOUNT_MISMATCH, "Choose a destination in the same account")
        if (message.folderId == target.id) return
        val source = dao.folder(message.folderId) ?: return
        if (message.uid < 0) {
            val op = pendingMove(message) ?: throw MailActionException(MailActionFailure.OPERATION_PENDING, "This move is still being synchronized. Try again shortly.")
            if (op.error == "IN_FLIGHT") throw MailActionException(MailActionFailure.OPERATION_PENDING, "This move is being sent to the server. Try again shortly.")
            if (op.folderId == target.id) {
                dao.deleteOperation(op.id)
                if (op.desiredSeen != null || op.desiredStarred != null) dao.insertOperation(op.copy(id = 0, action = "FLAGS", target = null))
                dao.updateMessage(message.copy(folderId = target.id, uid = op.uid)); return
            }
            dao.updateOperation(op.copy(target = target.path))
        } else dao.insertOperation(OperationRow(accountId = message.accountId, folderId = source.id, validity = source.validity, uid = message.uid, action = "MOVE", target = target.path, messageLocalId = message.id))
        dao.updateMessage(message.copy(folderId = target.id, uid = -message.id, messageIdKey=message.messageIdKey ?: Codec.field(message.header,"messageId")))
    }
    private suspend fun pendingMove(message: MessageRow): OperationRow? {
        val candidates = dao.operations(message.accountId).filter { it.action == "MOVE" && (it.messageLocalId == message.id || it.messageLocalId == null) }
        return candidates.firstOrNull { it.messageLocalId == message.id } ?: candidates.filter { it.target == dao.folder(message.folderId)?.path }.singleOrNull()
    }
    override suspend fun apply(action: MessageAction, threadIds: Collection<Long>) = withContext(Dispatchers.IO) {
        database.withTransaction {
            for (thread in threadIds.distinct()) {
                if (thread < 0) { if (action in setOf(MessageAction.TRASH, MessageAction.DELETE_FOREVER)) removeDraftAndSource(-thread); continue }
                for (message in dao.threadMessages(thread)) {
                    if (action == MessageAction.ARCHIVE && dao.folder(message.folderId)?.role != "INBOX") continue
                    val seen = when(action) { MessageAction.MARK_READ -> true; MessageAction.MARK_UNREAD -> false; else -> null }
                    val starred = when(action) { MessageAction.STAR -> true; MessageAction.UNSTAR -> false; else -> null }
                    if (seen != null || starred != null) {
                        if (message.uid < 0) {
                            val move = pendingMove(message)?.takeUnless { it.error == "IN_FLIGHT" }
                            val existing = move ?: dao.operations(message.accountId).firstOrNull { it.action == "DEFERRED_FLAGS" && it.messageLocalId == message.id }
                            val op = existing ?: OperationRow(accountId = message.accountId, folderId = message.folderId, validity = 0, uid = 0, action = "DEFERRED_FLAGS", messageLocalId = message.id)
                            val updated = op.copy(desiredSeen = seen ?: op.desiredSeen, desiredStarred = starred ?: op.desiredStarred)
                            if (existing != null) dao.updateOperation(updated) else dao.insertOperation(updated)
                        } else dao.folder(message.folderId)?.let { folder -> dao.insertOperation(OperationRow(accountId = message.accountId, folderId = folder.id, validity = folder.validity, uid = message.uid, action = action.name, messageLocalId = message.id)) }
                        dao.updateMessage(message.copy(unread = seen?.not() ?: message.unread, starred = starred ?: message.starred))
                        continue
                    }
                    val role = when(action) { MessageAction.ARCHIVE -> FolderRole.ARCHIVE; MessageAction.TRASH -> FolderRole.TRASH; MessageAction.SPAM -> FolderRole.SPAM; MessageAction.NOT_SPAM -> FolderRole.INBOX; else -> null }
                    if (role != null) {
                        val folders = dao.folders(message.accountId)
                        val target = folders.firstOrNull { it.role == role.name } ?: if (role == FolderRole.ARCHIVE) folders.firstOrNull { it.role == "ALL_MAIL" } else null
                        if (target == null) throw MailActionException(MailActionFailure.FOLDER_UNAVAILABLE, "This account has no ${role.name.lowercase().replace('_',' ')} folder")
                        moveMessage(message, target)
                    } else if (action == MessageAction.DELETE_FOREVER) {
                        val move = if (message.uid < 0) pendingMove(message) else null
                        if (move?.error == "IN_FLIGHT") throw MailActionException(MailActionFailure.OPERATION_PENDING, "This move is being sent to the server. Try again shortly.")
                        if (move != null) dao.updateOperation(move.copy(action = action.name, target = null))
                        else if (message.uid > 0) dao.folder(message.folderId)?.let { dao.insertOperation(OperationRow(accountId = message.accountId, folderId = it.id, validity = it.validity, uid = message.uid, action = action.name, messageLocalId = message.id)) }
                        else throw MailActionException(MailActionFailure.OPERATION_PENDING, "This message is still being synchronized")
                        dao.deleteMessage(message.id)
                    }
                }
                rebuildThread(thread)
            }
        }; requestSync()
    }
    override suspend fun move(threadIds: Collection<Long>, targetFolderId: Long) = withContext(Dispatchers.IO) {
        val target = dao.folder(targetFolderId) ?: throw MailActionException(MailActionFailure.FOLDER_UNAVAILABLE, "Destination folder is no longer available")
        database.withTransaction {
            if (threadIds.any { it < 0 }) throw MailActionException(MailActionFailure.OPERATION_PENDING, "Open or discard a local draft instead of moving it")
            val messages = threadIds.distinct().flatMap { dao.threadMessages(it) }
            if (messages.any { it.accountId != target.accountId }) throw MailActionException(MailActionFailure.ACCOUNT_MISMATCH, "Choose conversations and a destination in the same account")
            messages.forEach { moveMessage(it, target) }
            threadIds.distinct().forEach { rebuildThread(it) }
        }; requestSync()
    }
    override suspend fun refresh(mailbox: Mailbox) {
        val ids = when(mailbox) { is Mailbox.InFolder -> dao.folder(mailbox.folderId)?.accountId?.let { setOf(it) } ?: throw MailSyncException("Folder is no longer available"); is Mailbox.Unified -> null }
        try {
            withTimeout(60_000) { if (!syncAll(ids)) throw MailSyncException("Could not sync mail. Check the account status and connection.") }
        } catch (_: TimeoutCancellationException) {
            withContext(NonCancellable) { dao.accounts().filter { ids == null || it.id in ids }.forEach { dao.syncError(it.id, "Sync timed out. Try again shortly.", it.authError) } }
            throw MailSyncException("Sync timed out. Try again shortly.")
        }
    }
    override suspend fun suggestContacts(prefix: String, limit: Int): List<EmailAddress> = withContext(Dispatchers.IO) {
        dao.contacts(prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%", limit.coerceIn(1, 50)).map { EmailAddress(it.email, it.name) }
    }
    private val draftLock = Mutex()
    override suspend fun saveDraft(draft: Draft): Long = withContext(Dispatchers.IO) { draftLock.withLock {
        requireNotNull(dao.account(draft.accountId))
        val previous = if (draft.localId != 0L) dao.draft(draft.localId) else null
        if (draft.localId != 0L && previous?.accountId != draft.accountId) throw MailActionException(MailActionFailure.ACCOUNT_MISMATCH, "Save a new draft when changing accounts")
        val (cached, sources) = cacheAttachments(draft)
        val json = org.json.JSONObject(Codec.draft(cached)).put("attachmentSources", sources).toString()
        dao.putDraft(DraftRow(draft.localId, draft.accountId, json, System.currentTimeMillis(), previous?.sourceMessageId))
    } }
    override suspend fun loadDraft(localId: Long): Draft? {
        if (localId >= 0) return dao.draft(localId)?.let { Codec.draft(it.json).copy(localId = it.id) }
        val messageId = -localId
        dao.draftForMessage(messageId)?.let { return Codec.draft(it.json).copy(localId = it.id) }
        loadBody(messageId)
        return draftLock.withLock { database.withTransaction {
            dao.draftForMessage(messageId)?.let { return@withTransaction Codec.draft(it.json).copy(localId = it.id) }
            val message = dao.message(messageId) ?: return@withTransaction null
            if (dao.folder(message.folderId)?.role != "DRAFTS") return@withTransaction null
            val d = Draft(accountId = message.accountId, identityId = message.accountId, to = Codec.addressField(message.header, "to"), cc = Codec.addressField(message.header, "cc"), bcc = Codec.addressField(message.header, "bcc"), subject = message.subject, bodyText = bodyStore.read(message)?.plainText.orEmpty(), attachmentUris = dao.attachments(messageId).filter { !it.inline }.mapNotNull { it.localUri }, references = Codec.strings(Codec.field(message.header, "refs") ?: "[]"))
            val id = dao.putDraft(DraftRow(accountId = message.accountId, json = Codec.draft(d), updatedAt = System.currentTimeMillis(), sourceMessageId = messageId))
            d.copy(localId = id)
        } }
    }
    private suspend fun removeDraftAndSource(localId: Long) {
        val source = dao.draft(localId)?.sourceMessageId?.let { dao.message(it) }
        if (source != null && source.uid > 0) {
            dao.folder(source.folderId)?.let { folder -> dao.insertOperation(OperationRow(accountId = source.accountId, folderId = folder.id, validity = folder.validity, uid = source.uid, action = MessageAction.DELETE_FOREVER.name, messageLocalId = source.id)) }
            dao.deleteMessage(source.id); rebuildThread(source.threadId)
        }
        dao.deleteDraft(localId)
    }
    override suspend fun discardDraft(localId: Long) {
        database.withTransaction { removeDraftAndSource(localId) }; requestSync()
    }
    override suspend fun prepareReply(messageId: Long, mode: ReplyMode): Draft = withContext(Dispatchers.IO) {
        loadBody(messageId)
        val m = requireNotNull(dao.message(messageId)); val account = requireNotNull(dao.account(m.accountId))
        val from = Codec.addressField(m.header, "replyTo").ifEmpty { Codec.addressField(m.header, "from") }
        val to = if (mode == ReplyMode.FORWARD) emptyList() else from
        val cc = if (mode == ReplyMode.REPLY_ALL) (Codec.addressField(m.header, "to") + Codec.addressField(m.header, "cc")).filter { a -> a.email != account.email && to.none { it.email == a.email } }.distinctBy { it.email } else emptyList()
        val messageHeader = Codec.field(m.header, "messageId")
        Draft(accountId = account.id, identityId = account.id, to = to, cc = cc, subject = (if (mode == ReplyMode.FORWARD) "Fwd: " else "Re: ") + Threading.baseSubject(m.subject), bodyText = "\n\n" + bodyStore.read(m)?.let { it.plainText ?: org.jsoup.Jsoup.parse(it.html.orEmpty()).text() }.orEmpty().lineSequence().joinToString("\n") { "> $it" },
            attachmentUris = if (mode == ReplyMode.FORWARD) dao.attachments(m.id).filter { !it.inline }.mapNotNull { it.localUri } else emptyList(), inReplyTo = if (mode == ReplyMode.FORWARD) null else messageHeader, references = if (mode == ReplyMode.FORWARD) emptyList() else (Codec.strings(Codec.field(m.header, "refs") ?: "[]") + listOfNotNull(messageHeader)).distinct())
    }
    override suspend fun send(draft: Draft, undoWindowMs: Long): Long = withContext(Dispatchers.IO) {
        require(draft.to.isNotEmpty() || draft.cc.isNotEmpty() || draft.bcc.isNotEmpty()) { "At least one recipient is required" }
        requireNotNull(dao.account(draft.accountId))
        val frozen = cacheAttachments(draft).first
        val due = maxOf(System.currentTimeMillis() + undoWindowMs.coerceIn(0, 60_000), draft.scheduledAt ?: 0)
        val id = database.withTransaction { val id = dao.insertOutbox(OutboxRow(accountId = draft.accountId, draft = Codec.draft(frozen), dueAt = due)); if (draft.localId != 0L) removeDraftAndSource(draft.localId); id }
        if (scheduling) WorkScheduling.outbox(context, id, due); id
    }
    override suspend fun cancelSend(outboxId: Long): Boolean = database.withTransaction {
        val row = dao.outbox(outboxId) ?: return@withTransaction false
        if (dao.transitionOutbox(outboxId, "PENDING", "CANCELLED") != 1) return@withTransaction false
        val draft = Codec.draft(row.draft)
        dao.putDraft(DraftRow(draft.localId, row.accountId, row.draft, System.currentTimeMillis())); true
    }
    override suspend fun retrySend(outboxId: Long, confirmPossibleDuplicate: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!outboxLock.tryLock()) return@withContext false
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
        } finally { outboxLock.unlock() }
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
    internal suspend fun recoverOutbox() = outboxLock.withLock {
        dao.submittingOutbox().forEach { row ->
            dao.transitionOutbox(row.id, "SUBMITTING", "UNCERTAIN", "App stopped during SMTP submission. Check Sent before retrying.")
            dao.syncError(row.accountId, "A send could not be confirmed. Check Sent before retrying.")
        }
        dao.appendingOutbox().forEach { dao.transitionOutbox(it.id, "APPENDING", "APPEND", "Sent; saving its copy will resume") }
        if (scheduling) dao.pendingOutbox().forEach { WorkScheduling.outbox(context, it.id, it.dueAt) }
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
