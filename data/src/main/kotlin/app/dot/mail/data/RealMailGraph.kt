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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import app.dot.mail.data.oauth.*

fun createMailGraph(context: Context): MailGraph {
    val graph = GraphRegistry.get(context)
    return object : MailGraph { override val accounts: AccountRepository = graph; override val mail: MailRepository = graph; override val compose: ComposeRepository = graph }
}
internal object GraphRegistry {
    @Volatile private var graph: RealMailGraph? = null
    fun get(context: Context): RealMailGraph = graph ?: synchronized(this) {
        graph ?: RealMailGraph(context.applicationContext,
            Room.databaseBuilder(context.applicationContext, MailDatabase::class.java, "dotmail.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).build(),
            KeystoreCredentialVault()).also { graph = it; it.start() }
    }
}
internal fun ServerConfig.endpoint() = Endpoint(host, port, username, TransportSecurity.valueOf(security.name))

internal class RealMailGraph(
    internal val context: Context,
    internal val database: MailDatabase,
    private val vault: CredentialVault,
    internal val client: MailClient = MailClient(),
    internal val scheduling: Boolean = true,
    oauthClients: Map<OAuthProvider, String> = mapOf(OAuthProvider.GOOGLE to BuildConfig.OAUTH_GOOGLE_CLIENT_ID, OAuthProvider.MICROSOFT to BuildConfig.OAUTH_MICROSOFT_CLIENT_ID),
    tokenEndpoint: TokenEndpoint = HttpsTokenEndpoint(),
) : AccountRepository, MailRepository, ComposeRepository {
    internal val dao = database.mail()
    internal val identityStore = IdentityStore.get(context)
    /** Loads the user's classification rules into [ClassificationRules] before the first sync. */
    internal val categorySettings = CategorySettings.get(context)
    internal val smartFolders = SmartFolderStore.get(context)
    internal val cacheCleanup = PrivateCacheCleanup(context,dao)
    internal val bodyStore = BodyStore(context, dao)
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val syncLock = Mutex()
    internal val outboxLock = Mutex()
    internal val searchIndex = SearchIndex(this)
    internal val fullSearch = FullContentSearch(this)
    override val searchIndexProgress: Flow<SearchIndexProgress> get() = searchIndex.progress
    override fun search(query:String,scope:SearchScope):Flow<PagingData<SearchResult>> = fullSearch.search(query,scope)
    override fun searchProgress(query:String,scope:SearchScope):Flow<SearchProgress> = fullSearch.progress(query,scope)

    internal val storage=StorageManager(this)
    internal val addressBook=AddressBookIndex(this)
    override suspend fun refreshKnownContacts() { addressBook.refresh(force=true) }
    override val globalStorageLimit:Flow<Long> = storage.preferences.version.map { storage.preferences.globalLimit() }.distinctUntilChanged()
    override suspend fun setGlobalStorageLimit(bytes:Long) { storage.globalLimit(bytes) }
    override fun storagePolicy(accountId:Long):Flow<StoragePolicy> = storage.preferences.version.map { storage.preferences.policy(accountId) }.distinctUntilChanged()
    override suspend fun setStoragePolicy(accountId:Long,policy:StoragePolicy) { storage.setPolicy(accountId,policy) }
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun offlineProgress(accountId:Long):Flow<OfflineProgress> = storagePolicy(accountId).flatMapLatest { activePolicy -> combine(storage.preferences.version,dao.observeOfflineCounts(accountId,if(activePolicy.retention==RetentionMode.LATEST_MESSAGES) activePolicy.latestMessages else 0,if(activePolicy.retention==RetentionMode.LAST_DAYS) System.currentTimeMillis()-activePolicy.days*86400000L else 0),storage.signals,if(scheduling) androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("dotmail.offline.$accountId") else flowOf(emptyList())) { _,counts,_,work ->
        val stored=storage.preferences.progress(accountId); val policy=storage.preferences.policy(accountId)
        val target=when(policy.retention) { RetentionMode.ALL->counts.serverTotal; RetentionMode.LATEST_MESSAGES->maxOf(counts.headers,minOf(counts.serverTotal,policy.latestMessages)); RetentionMode.LAST_DAYS->if(stored.state in setOf(OfflineState.COMPLETE,OfflineState.PARTIAL)) counts.headers else 0 }
        val incomplete=policy.downloadBodies && (counts.bodies<counts.headers || (policy.downloadAttachments && counts.downloadedAttachments<counts.attachments))
        val pending=stored.state in setOf(OfflineState.QUEUED,OfflineState.DOWNLOADING,OfflineState.WAITING_FOR_NETWORK,OfflineState.WAITING_FOR_CHARGING)
        val state=when {
            storage.preferences.paused(accountId)->OfflineState.PAUSED
            pending && !storage.downloads.networkAllowed(policy)->OfflineState.WAITING_FOR_NETWORK
            pending && policy.chargingOnly && !storage.downloads.charging()->OfflineState.WAITING_FOR_CHARGING
            stored.state==OfflineState.COMPLETE && incomplete->OfflineState.PARTIAL
            scheduling && stored.state==OfflineState.DOWNLOADING && work.none { it.state==androidx.work.WorkInfo.State.RUNNING }->OfflineState.QUEUED
            stored.state in setOf(OfflineState.WAITING_FOR_NETWORK,OfflineState.WAITING_FOR_CHARGING)->OfflineState.QUEUED
            else->stored.state
        }
        stored.copy(cachedMessages=counts.headers,totalMessages=target,downloadedBodies=counts.bodies,downloadedAttachments=counts.downloadedAttachments,totalAttachments=counts.attachments,state=state)
    }.distinctUntilChanged() }.flowOn(Dispatchers.IO)
    override suspend fun pauseOfflineDownload(accountId:Long) { storage.pause(accountId) }
    override suspend fun resumeOfflineDownload(accountId:Long) { storage.resume(accountId) }
    override suspend fun setOfflineSaved(messageIds:Collection<Long>,saved:Boolean) { storage.saveOffline(messageIds,saved) }
    override fun offlineSavedMessages():Flow<Set<Long>> = dao.observeOfflineSaved().map { it.toSet() }
    override suspend fun storageUsage():StorageUsage = storage.usage()
    override suspend fun clearCachedMail(accountId:Long):CacheCleanupResult = storage.clear(accountId)

    private val oauth = OAuthManager(context, database, vault, oauthClients, tokenEndpoint)
    internal val syncing = MutableStateFlow<Set<Long>>(emptySet())
    override val accounts: Flow<List<Account>> = combine(dao.observeAccounts(), syncing, identityStore.version, AccountSettingsStore.get(context).lastSyncedAll) { rows, active, _, synced -> rows.map { row ->
        Account(row.id, row.name, row.email, row.color, identityStore.resolve(row.id, row.name, row.email),
            if (row.id in active) SyncState.Syncing else row.syncError?.let { SyncDiagnostics.state(it,row.authError) } ?: SyncState.Idle,
            synced[row.id] ?: 0L)
    } }
    override val outbox: Flow<List<OutboxItem>> = dao.observeOutbox().map { rows -> rows.map { row ->
        val draft = Codec.draft(row.draft)
        val state = when (row.state) {
            "SUBMITTING" -> OutboxState.SENDING
            "APPEND", "APPENDING" -> OutboxState.SAVING_SENT
            "UNCERTAIN" -> OutboxState.NEEDS_REVIEW
            else -> OutboxState.QUEUED
        }
        val editable = row.state == "PENDING" && org.json.JSONObject(row.draft).optString("calendarReply").isBlank()
        OutboxItem(row.id, row.accountId, draft.subject, (draft.to + draft.cc + draft.bcc).distinctBy { it.email }, row.dueAt, state, row.error,
            row.state in setOf("UNCERTAIN", "APPEND") || (row.state == "PENDING" && row.error != null), row.state in setOf("PENDING", "APPEND", "UNCERTAIN"),
            draftId = if (editable) draft.localId else 0, canEdit = editable, canReschedule = editable)
    } }.flowOn(Dispatchers.IO)
    internal val foregroundIdle by lazy { ForegroundIdle(this) }
    override val instantNotifications: Flow<Boolean> = AccountSettingsStore.get(context).sync.map { it.instantNotifications }.distinctUntilChanged()
    override suspend fun setInstantNotificationsEnabled(enabled: Boolean): Boolean = changeInstantNotifications(enabled)
    internal fun start() { if (scheduling) { addressBook.start(); scope.launch { dao.accounts().forEach { account -> if(storage.preferences.configured(account.id) && !storage.preferences.paused(account.id) && storage.preferences.progress(account.id).state !in setOf(OfflineState.COMPLETE,OfflineState.PARTIAL)) WorkScheduling.offline(context,account.id,storage.preferences.policy(account.id)) } }; scope.launch { searchIndex.ensure(); dao.observeSearchPendingCount().collect { if(it>0) WorkScheduling.searchIndex(context) } }; WorkScheduling.periodic(context); foregroundIdle.start(); scope.launch { recoverOutbox(); dao.accounts().forEach { account -> if(dao.draftMirrors(account.id).isNotEmpty()) WorkScheduling.drafts(context,account.id) } }; scope.launch { resurfaceDue() }; scope.launch { recoverSnippetRepairs() } } }
    internal suspend fun credentials(row: AccountRow): Credentials = withContext(Dispatchers.IO) {
        if (Codec.config(row.config).authType == AuthType.OAUTH2) oauth.credentials(row.id) else try { Credentials(vault.decrypt(row.email, row.secret)) }
        catch(e:java.security.GeneralSecurityException) { dao.syncError(row.id,"Saved credentials are unavailable. Sign in again.",true); throw ReauthenticationRequired(e) }
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
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { verificationFailure(e) }
    }

    override suspend fun addAccount(config: AccountConfig, password: String): Long = withContext(Dispatchers.IO) {
        require(config.authType == AuthType.PASSWORD) { "OAuth accounts must be added through browser sign-in" }
        require(config.email.isNotBlank() && config.imap.host.isNotBlank() && config.smtp.host.isNotBlank())
        val id = dao.insertAccount(AccountRow(email = config.email, name = config.displayName, color = dao.accounts().size % 6, config = Codec.config(config), secret = vault.encrypt(config.email, password)))
        requestSync(); id
    }
    override fun identities(accountId: Long): Flow<List<Identity>> = accounts.map { list -> list.firstOrNull { it.id == accountId }?.identities.orEmpty() }
    override suspend fun saveIdentity(identity: Identity): Long = withContext(Dispatchers.IO) {
        val account = requireNotNull(dao.account(identity.accountId)) { "Unknown account" }
        identityStore.save(identity, account.email)
    }
    override suspend fun deleteIdentity(accountId: Long, identityId: Long) = withContext(Dispatchers.IO) { identityStore.delete(accountId, identityId) }
    override suspend fun setDefaultIdentity(accountId: Long, identityId: Long) = withContext(Dispatchers.IO) { identityStore.setDefault(accountId, identityId) }
    override suspend fun exportBackup(): String = exportAccountsBackup()
    override suspend fun previewBackup(json: String): BackupPreview = previewAccountsBackup(json)
    override suspend fun applyBackup(json: String): BackupPreview = applyAccountsBackup(json)
    override suspend fun renameAccount(accountId: Long, displayName: String) = withContext(Dispatchers.IO) { dao.accountName(accountId, displayName.trim().ifEmpty { return@withContext }) }
    override suspend fun updatePassword(accountId: Long, password: String): VerifyResult = withContext(Dispatchers.IO) {
        val snapshot = dao.account(accountId) ?: return@withContext VerifyResult.ConnectionFailed("Account no longer exists")
        val config = Codec.config(snapshot.config)
        if (config.authType == AuthType.OAUTH2) return@withContext VerifyResult.AuthFailed("Use browser sign-in to reconnect this account")
        if (password.isBlank()) return@withContext VerifyResult.AuthFailed("Enter your password or app password")
        val verified = verify(config, password)
        if (verified != VerifyResult.Ok) return@withContext verified
        val result = syncLock.withLock {
            val current = dao.account(accountId)
            if (current == null || current.config != snapshot.config || current.secret != snapshot.secret)
                return@withLock VerifyResult.ConnectionFailed("Account changed during sign-in. Try again.")
            try {
                val encrypted = vault.encrypt(current.email, password)
                if (dao.accountSecret(accountId, encrypted) == 1) VerifyResult.Ok
                else VerifyResult.ConnectionFailed("Account no longer exists")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                SyncDiagnostics.warn("Save account password account=$accountId", e, setOf(current.email,password))
                VerifyResult.ConnectionFailed("Could not securely save the password. Try again.")
            }
        }
        if (result == VerifyResult.Ok) requestSync()
        result
    }

    override suspend fun removeAccount(accountId: Long) = withContext(Dispatchers.IO) { syncLock.withLock { dao.deleteAccount(accountId); identityStore.forget(accountId); storage.preferences.forget(accountId); if(scheduling) WorkScheduling.cancelOffline(context,accountId); smartFolders.forgetAccount(accountId); AccountSettingsStore.get(context).forget(accountId); File(context.filesDir, "bodies/$accountId").deleteRecursively(); File(context.filesDir, "attachments/$accountId").deleteRecursively(); File(context.filesDir, "outbox/$accountId").deleteRecursively(); storage.invalidate(accountId); Unit } }
    override fun folders(accountId: Long): Flow<List<app.dot.mail.data.model.Folder>> = combine(dao.observeFolders(accountId), dao.observeDeletingFolderIds()) { rows, deleting -> rows.filter { it.id !in deleting } }.map { rows -> rows.map { app.dot.mail.data.model.Folder(it.id, it.accountId, it.path, it.name, FolderRole.valueOf(it.role), it.unread, it.total) } }

    override suspend fun reclassifyAll() {
        val rules = categorySettings.rules.value
        var after = 0L
        while (true) {
            val batch = dao.headersAfter(after, 500)
            if (batch.isEmpty()) break
            database.withTransaction {
                batch.forEach { row -> runCatching { Codec.reclassified(row.header, row.subject, rules) }.getOrNull()?.let { dao.setHeader(row.id, it) } }
            }
            after = batch.last().id
        }
    }
    internal suspend fun summary(t: ThreadRow, draftView: Boolean = false): ThreadSummary {
        if (t.id < 0) {
            val row = dao.draft(-t.id)
            val d = row?.let { Codec.draft(it.json) }
            return ThreadSummary(t.id, t.accountId, d?.subject.orEmpty(), d?.to.orEmpty(), d?.bodyText?.let { app.dot.mail.engine.text.Snippets.fromPlainText(it) }.orEmpty(), row?.updatedAt ?: t.lastAt, 1, 0, false, d?.attachmentUris?.isNotEmpty() == true, draftId = -t.id, recipients = d?.to.orEmpty())
        }
        if (draftView && ConversationIds.isMessage(t.id)) {
            val message=dao.message(ConversationIds.messageIdOf(t.id))
            if(message!=null) return messageSummary(message).copy(draftId=-message.id)
        }
        val messages = dao.threadMessages(t.id)
        val drafts = if (draftView) dao.folders(t.accountId).filter { it.role == "DRAFTS" }.map { it.id }.toSet() else emptySet()
        val latest = (if (draftView) messages.filter { it.folderId in drafts } else messages).lastOrNull()
        val handle = if (draftView) latest?.let { -it.id } else null
        return ThreadSummary(t.id, t.accountId, t.subject, Codec.addresses(t.participants), t.snippet, t.lastAt, t.count, t.unread, t.starred, t.attachments, draftId = handle, recipients = latest?.let { Codec.addressField(it.header, "to") }.orEmpty(), category=latest?.let(::messageCategory))
    }
    private fun paging(where: String, args: List<Any>, draftView: Boolean = false, draftAccount: Long? = null, extra: Sql? = null, orderBy: String = ThreadQuery.orderBy(InboxSort.DATE)): Flow<PagingData<ThreadSummary>> = Pager(PagingConfig(pageSize = 40)) {
        if (extra == null) mailboxSource(where, args, draftView, draftAccount, orderBy) else mailboxSource("($where) AND (${extra.text})", args + extra.args, draftView, draftAccount, orderBy)
    }.flow.map { data -> data.map { summary(it, draftView) } }
    internal fun mailboxSource(where: String, args: List<Any>, draftView: Boolean, draftAccount: Long?, orderBy: String = ThreadQuery.orderBy(InboxSort.DATE)): PagingSource<Int, ThreadRow> {
        val bump = "(SELECT z.resurfacedAt FROM snoozed z WHERE z.threadId=t.id AND z.messageId IS NULL AND z.resurfacedAt IS NOT NULL ORDER BY z.resurfacedAt DESC LIMIT 1)"
        val hidden = " AND NOT EXISTS(SELECT 1 FROM snoozed z WHERE z.threadId=t.id AND z.messageId IS NULL AND z.resurfacedAt IS NULL)"
        val remote = if(draftView) {
            val source = if(draftAccount!=null) "m.folderId=?" else "f.role=?"
            "SELECT m.id+${ConversationIds.MESSAGE_BASE} AS id,m.accountId,m.subject,'[]' AS participants,m.snippet,m.sentAt AS lastAt,1 AS count,m.unread,m.starred,m.hasAttachments AS attachments,m.sentAt AS sortAt FROM messages m JOIN folders f ON f.id=m.folderId WHERE ($source) AND NOT EXISTS(SELECT 1 FROM drafts d WHERE d.sourceMessageId=m.id) AND NOT EXISTS(SELECT 1 FROM draft_mirrors dm WHERE dm.folderId=m.folderId AND dm.uid=m.uid AND dm.validity=f.validity)"
        } else "SELECT t.*, MAX(t.lastAt, COALESCE($bump,0)) AS sortAt FROM threads t WHERE t.count>0 AND ($where)$hidden"

        val local = if (draftView) " UNION ALL SELECT -id AS id,accountId,'' AS subject,'[]' AS participants,'' AS snippet,updatedAt AS lastAt,1 AS count,0 AS unread,0 AS starred,0 AS attachments,updatedAt AS sortAt  FROM drafts WHERE id NOT IN (SELECT CAST(target AS INTEGER) FROM operations WHERE action='$OP_TRASH_DRAFT')" + if (draftAccount != null) " AND accountId=?" else "" else ""
        val params = args + if (draftView && draftAccount != null) listOf(draftAccount) else emptyList()
        return dao.page(SimpleSQLiteQuery("SELECT * FROM ($remote$local) ORDER BY $orderBy", params.toTypedArray()))
    }
    override fun threads(mailbox: Mailbox): Flow<PagingData<ThreadSummary>> = threads(mailbox, ListOptions())
    internal suspend fun ownEmailsForFilters(): List<String> = ownEmails()
    private suspend fun ownEmails(): List<String> = dao.accounts().flatMap { a -> identityStore.resolve(a.id, a.name, a.email).map { it.email } }
    override fun threads(mailbox: Mailbox, options: ListOptions): Flow<PagingData<ThreadSummary>> = flow {
        if(options.filters.fromContacts) addressBook.refresh()
        val extra = ThreadQuery.filters(options.filters, if (options.filters.toMe) ownEmails() else emptyList())
        val order = ThreadQuery.orderBy(options.sort)
        fun p(where: String, args: List<Any>, draftView: Boolean = false, draftAccount: Long? = null) = paging(where, args, draftView, draftAccount, extra, order)
        when (mailbox) {
            is Mailbox.Smart -> smartFolders.get(mailbox.id)?.let { sf -> ThreadQuery.smartFolder(sf).let { q -> emitAll(p(q.text, q.args)) } } ?: emitAll(flowOf(PagingData.empty()))
            is Mailbox.InFolder -> {
                val folder = dao.folder(mailbox.folderId)
                emitAll(p("EXISTS(SELECT 1 FROM messages m WHERE m.threadId=t.id AND m.folderId=?)", listOf(mailbox.folderId), folder?.role == "DRAFTS", folder?.accountId))
            }
            is Mailbox.Unified -> if (mailbox.role.category() != null) emitAll(p("EXISTS(SELECT 1 FROM messages m JOIN folders f ON f.id=m.folderId WHERE m.threadId=t.id AND ${categoryPredicate("m","f",mailbox.role.category()!!)})",emptyList())) else if (mailbox.role == FolderRole.SNOOZED) emitAll(snoozedThreadsPaging()) else emitAll(p("EXISTS(SELECT 1 FROM messages m JOIN folders f ON f.id=m.folderId WHERE m.threadId=t.id AND f.role=?)", listOf(mailbox.role.name), mailbox.role == FolderRole.DRAFTS))
        }
    }
    override fun search(query: String, accountId: Long?): Flow<PagingData<ThreadSummary>> = fullSearch.search(query,SearchScope(accountIds=accountId?.let { setOf(it) } ?: emptySet())).map { page -> page.map { it.thread } }
    override fun thread(threadId: Long): Flow<List<app.dot.mail.data.model.Message>> = (if (ConversationIds.isMessage(threadId)) dao.observeMessage(ConversationIds.messageIdOf(threadId)) else dao.observeThread(threadId)).onStart { storage.watch(threadId,true) }.onCompletion { storage.watch(threadId,false) }.map { allRows ->
        val rows = allRows.groupBy { Codec.field(it.header, "messageId") ?: "local:${it.id}" }.values.map { copies -> copies.firstOrNull { it.bodyLoaded } ?: copies.first() }.sortedBy { it.sentAt }
        val attachments = dao.attachmentsFor(rows.map { it.id }).groupBy { it.messageId }
        rows.map { row ->
        app.dot.mail.data.model.Message(row.id, row.accountId, row.folderId, row.threadId, Codec.addressField(row.header, "from").firstOrNull() ?: EmailAddress(""), Codec.addressField(row.header, "to"), Codec.addressField(row.header, "cc"), Codec.addressField(row.header, "bcc"), Codec.addressField(row.header, "replyTo"), row.subject, row.sentAt, row.snippet, row.unread, row.starred,
            bodyStore.read(row), attachments[row.id].orEmpty().map(::attachment), Codec.field(row.header, "messageId"), Codec.strings(Codec.field(row.header, "refs") ?: "[]"), Codec.field(row.header, "unsubscribe"), Codec.field(row.header, "unsubOneClick"),listId=Codec.field(row.header,"listId"),category=messageCategory(row))
    } }.flowOn(Dispatchers.IO)
    internal fun attachment(a: AttachmentRow) = Attachment(a.id, a.messageId, a.name, a.mime, a.size, a.cid, a.inline, a.localUri)
    internal fun attachmentFile(accountId: Long, name: String = UUID.randomUUID().toString()): File = File(context.filesDir, "attachments/$accountId/$name").also { it.parentFile!!.mkdirs() }
    internal fun safeFileName(name:String):String {
        val result=StringBuilder(); var bytes=0; var stopped=false
        name.codePoints().forEach { cp ->
            if(!stopped) {
                val value=if(cp=='/'.code || cp=='\\'.code || Character.isISOControl(cp)) "_" else String(Character.toChars(cp))
                val size=value.toByteArray(Charsets.UTF_8).size
                if(bytes+size>180) stopped=true else { result.append(value); bytes+=size }
            }
        }
        return result.toString().takeUnless { it.isBlank() || it=="." || it==".." } ?: "attachment"
    }
    internal fun uri(file: File): String = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file).toString()
    override suspend fun loadBody(messageId:Long) { storage.ensureBody(messageId) }
    override suspend fun openAttachment(attachment:Attachment):String = storage.open(attachment.id)
    internal suspend fun upsertHeader(folder: FolderRow, header: Header, rebuild: Boolean = true): Pair<Long, Boolean> {
        if(folder.role=="SENT") markCorresponded(folder.accountId,(header.to+header.cc+header.bcc).map { EmailAddress(it.email,it.name) },header.sentAt)
        val old = dao.byUid(folder.id, header.uid) ?: header.messageId?.let { mid -> dao.pendingMessage(folder.id,mid) ?: dao.unkeyedPending(folder.id).firstNotNullOfOrNull { id -> dao.message(id)?.takeIf { Codec.field(it.header,"messageId")==mid } } }
        if (old != null) {
            // Pending optimistic changes win until replay completes.
            val pending = dao.operations(folder.accountId).any { it.messageLocalId == old.id || (it.folderId == folder.id && it.uid == header.uid && it.validity == folder.validity) }
            dao.updateMessage(old.copy(uid = header.uid, header=Codec.header(header), messageIdKey=header.messageId, unread = if (pending) old.unread else !header.flags.seen, starred = if (pending) old.starred else header.flags.starred))
            if(old.header!=Codec.header(header)) indexSenders(old.id,header)
            if(rebuild) rebuildThread(old.threadId); return old.threadId to false
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
        val localId=dao.insertMessage(MessageRow(accountId = folder.accountId, folderId = folder.id, threadId = threadId, uid = header.uid, header = Codec.header(header), messageIdKey=header.messageId, senderSearch = header.from.joinToString(" ") { "${it.name.orEmpty()} ${it.email}" }, recipientSearch = header.to.joinToString(" ") { "${it.name.orEmpty()} ${it.email}" }, subject = header.subject, sentAt = header.sentAt, unread = !header.flags.seen, starred = header.flags.starred, hasAttachments = header.hasAttachments))
        indexSenders(localId,header)
        (header.from + header.to + header.cc).forEach { address -> canonicalContactEmail(address.email)?.let { dao.putContact(ContactRow(folder.accountId,it,address.name?.take(512),header.sentAt)) } }
        if(rebuild) rebuildThread(threadId); return threadId to true
    }
    internal suspend fun rebuildThread(id: Long) {
        val row = dao.threadRow(id) ?: return
        val messages = dao.threadMessages(id)
        val distinct = messages.distinctBy { Codec.field(it.header, "messageId") ?: "local:${it.id}" }
        val latest = distinct.lastOrNull()
        dao.updateThread(row.copy(subject = latest?.subject ?: row.subject, participants = Codec.addresses(distinct.flatMap { Codec.addressField(it.header, "from") }.distinctBy { it.email }), snippet = latest?.snippet.orEmpty(), lastAt = latest?.sentAt ?: 0, count = distinct.size, unread = distinct.count { it.unread }, starred = distinct.any { it.starred }, attachments = distinct.any { it.hasAttachments }))
    }
    internal suspend fun moveMessage(message: MessageRow, target: FolderRow) {
        if (message.accountId != target.accountId) throw MailActionException(MailActionFailure.ACCOUNT_MISMATCH, "Choose a destination in the same account", ActionErrorKind.DESTINATION_OTHER_ACCOUNT)
        if (message.folderId == target.id) return
        val source = dao.folder(message.folderId) ?: return
        if (message.uid < 0) {
            val op = pendingMove(message) ?: throw MailActionException(MailActionFailure.OPERATION_PENDING, "This move is still being synchronized. Try again shortly.", ActionErrorKind.MOVE_SYNCING)
            if (op.error == "IN_FLIGHT") throw MailActionException(MailActionFailure.OPERATION_PENDING, "This move is being sent to the server. Try again shortly.", ActionErrorKind.MOVE_SENDING)
            if (op.folderId == target.id) {
                dao.deleteOperation(op.id)
                if (op.desiredSeen != null || op.desiredStarred != null) dao.insertOperation(op.copy(id = 0, action = "FLAGS", target = null))
                dao.updateMessage(message.copy(folderId = target.id, uid = op.uid)); return
            }
            dao.updateOperation(op.copy(target = target.path))
        } else dao.insertOperation(OperationRow(accountId = message.accountId, folderId = source.id, validity = source.validity, uid = message.uid, action = "MOVE", target = target.path, messageLocalId = message.id))
        dao.updateMessage(message.copy(folderId = target.id, uid = -message.id, messageIdKey=message.messageIdKey ?: Codec.field(message.header,"messageId")))
    }
    internal suspend fun pendingMove(message: MessageRow): OperationRow? {
        val candidates = dao.operations(message.accountId).filter { it.action == "MOVE" && (it.messageLocalId == message.id || it.messageLocalId == null) }
        return candidates.firstOrNull { it.messageLocalId == message.id } ?: candidates.filter { it.target == dao.folder(message.folderId)?.path }.singleOrNull()
    }
    @Deprecated("Pass the displayed sourceMailbox to scope conversation actions")
    override suspend fun apply(action: MessageAction, threadIds: Collection<Long>) =
        applyScoped(action, threadIds, if (action == MessageAction.ARCHIVE) Mailbox.Unified(FolderRole.INBOX) else null)

    override suspend fun apply(action: MessageAction, threadIds: Collection<Long>, sourceMailbox: Mailbox) =
        applyScoped(action, threadIds, sourceMailbox)

    override suspend fun applyUndoable(action: MessageAction, threadIds: Collection<Long>, sourceMailbox: Mailbox): app.dot.mail.data.model.UndoToken? {
        if (action !in setOf(MessageAction.ARCHIVE, MessageAction.TRASH, MessageAction.SPAM)) { applyScoped(action, threadIds, sourceMailbox); return null }
        // Record where each affected message lives *before* moving it; drafts (negative ids) have no undo here.
        val origins = withContext(Dispatchers.IO) {
            threadIds.distinct().filter { it >= 0 }.flatMap { actionRows(it, sourceMailbox, true) }.map { it.id to it.folderId }
        }
        val drafts = if (action == MessageAction.TRASH) threadIds.filter { it < 0 }.map { -it }.distinct() else emptyList()
        applyScoped(action, threadIds, sourceMailbox)
        return app.dot.mail.data.model.UndoToken(origins, drafts)
    }

    override suspend fun undo(token: app.dot.mail.data.model.UndoToken): Int = withContext(Dispatchers.IO) {
        var failed = 0
        database.withTransaction {
            val threads = mutableSetOf<Long>()
            for ((messageId, folderId) in token.moves) {
                val message = dao.message(messageId); val folder = dao.folder(folderId)
                if (message == null || folder == null) { failed++; continue }
                // A move not yet sent to the server is simply cancelled here (see moveMessage), so nothing reaches IMAP.
                runCatching { moveMessage(message, folder) }.onSuccess { threads += message.threadId }.onFailure { failed++ }
            }
            threads.forEach { rebuildThread(it) }
            // A trashed draft waits for sync to move it; cancelling the queued move brings it straight back to Drafts.
            for (localId in token.drafts) {
                val op = dao.draft(localId)?.let { d -> dao.operations(d.accountId).firstOrNull { it.action == OP_TRASH_DRAFT && it.target == "$localId" } }
                if (op == null || op.error == "IN_FLIGHT") failed++ else dao.deleteOperation(op.id)
            }
        }
        requestSync()
        failed
    }

    private suspend fun actionRows(id: Long, mailbox: Mailbox?, moving: Boolean): List<MessageRow> {
        val rows = threadOrMessageRows(id)
        if (mailbox == null) {
            if (moving && rows.map { it.folderId }.distinct().size > 1)
                throw MailActionException(MailActionFailure.SOURCE_REQUIRED, "Choose the source mailbox before moving this conversation", ActionErrorKind.SOURCE_REQUIRED)
            return rows
        }
        return when (mailbox) {
            is Mailbox.Smart -> rows
            is Mailbox.InFolder -> rows.filter { it.folderId == mailbox.folderId }
            is Mailbox.Unified -> if(mailbox.role.category()!=null) rows.filter { isInCategory(it,dao.folder(it.folderId)?.role,mailbox.role.category()!!) } else rows.filter { dao.folder(it.folderId)?.role == mailbox.role.name }
        }
    }

    private suspend fun applyScoped(action: MessageAction, threadIds: Collection<Long>, sourceMailbox: Mailbox?) = withContext(Dispatchers.IO) {
        database.withTransaction {
            for (thread in threadIds.distinct()) {
                if (thread < 0) { when (action) { MessageAction.TRASH -> trashDraft(-thread); MessageAction.DELETE_FOREVER -> removeDraftAndSource(-thread); else -> {} }; continue }
                val rows = actionRows(thread, sourceMailbox, action in setOf(MessageAction.ARCHIVE, MessageAction.TRASH, MessageAction.SPAM, MessageAction.NOT_SPAM, MessageAction.DELETE_FOREVER))
                for (message in rows) {
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
                        if (target == null) throw MailActionException(MailActionFailure.FOLDER_UNAVAILABLE, "This account has no ${role.name.lowercase().replace('_',' ')} folder", ActionErrorKind.NO_ROLE_FOLDER, role.name)
                        moveMessage(message, target)
                    } else if (action == MessageAction.DELETE_FOREVER) {
                        val move = if (message.uid < 0) pendingMove(message) else null
                        if (move?.error == "IN_FLIGHT") throw MailActionException(MailActionFailure.OPERATION_PENDING, "This move is being sent to the server. Try again shortly.", ActionErrorKind.MOVE_SENDING)
                        if (move != null) dao.updateOperation(move.copy(action = action.name, target = null))
                        else if (message.uid > 0) dao.folder(message.folderId)?.let { dao.insertOperation(OperationRow(accountId = message.accountId, folderId = it.id, validity = it.validity, uid = message.uid, action = action.name, messageLocalId = message.id)) }
                        else throw MailActionException(MailActionFailure.OPERATION_PENDING, "This message is still being synchronized", ActionErrorKind.MESSAGE_SYNCING)
                        dao.deleteMessage(message.id)
                    }
                }
                if (!ConversationIds.isMessage(thread)) rebuildThread(thread) else rows.firstOrNull()?.let { rebuildThread(it.threadId) }
            }
        }; requestSync()
    }
    override suspend fun setMuted(threadIds: Collection<Long>, muted: Boolean) = withContext(Dispatchers.IO) {
        val real = threadIds.mapNotNull { id -> if (ConversationIds.isMessage(id)) dao.message(ConversationIds.messageIdOf(id))?.threadId else id.takeIf { it > 0 } }
        NotificationRules.get(context).setMuted(real, muted)
    }
    override fun mutedThreads(): Flow<Set<Long>> = NotificationRules.get(context).muted
    override suspend fun snooze(threadIds: Collection<Long>, untilMillis: Long) = snoozeLocal(threadIds, untilMillis)
    override suspend fun unsnooze(threadIds: Collection<Long>) = unsnoozeLocal(threadIds)
    override fun snoozedCount(): Flow<Int> = dao.observeSnoozedCount()
    override fun attachments(kind: AttachmentKind?, query: String): Flow<PagingData<AttachmentItem>> = attachmentsPaging(kind, query)
    override fun messages(mailbox: Mailbox): Flow<PagingData<ThreadSummary>> = messagesPaging(mailbox)
    override fun messages(mailbox: Mailbox, options: ListOptions): Flow<PagingData<ThreadSummary>> = messagesPaging(mailbox, options)
    override suspend fun createFolder(accountId: Long, parentFolderId: Long?, name: String): Long = createFolderLocal(accountId, parentFolderId, name)
    override suspend fun renameFolder(folderId: Long, newName: String) = relocateFolderLocal(folderId, keepParent = true, newParentId = null, newName = newName)
    override suspend fun moveFolder(folderId: Long, newParentFolderId: Long?) = relocateFolderLocal(folderId, keepParent = false, newParentId = newParentFolderId, newName = null)
    override suspend fun deleteFolder(folderId: Long, moveContentsTo: Long?) = deleteFolderLocal(folderId, moveContentsTo)
    @Deprecated("Pass the displayed sourceMailbox to avoid moving other folder copies")
    override suspend fun move(threadIds: Collection<Long>, targetFolderId: Long) = moveScoped(threadIds, targetFolderId, null)

    override suspend fun move(threadIds: Collection<Long>, targetFolderId: Long, sourceMailbox: Mailbox) = moveScoped(threadIds, targetFolderId, sourceMailbox)

    private suspend fun moveScoped(threadIds: Collection<Long>, targetFolderId: Long, sourceMailbox: Mailbox?) = withContext(Dispatchers.IO) {
        val target = dao.folder(targetFolderId) ?: throw MailActionException(MailActionFailure.FOLDER_UNAVAILABLE, "Destination folder is no longer available", ActionErrorKind.DESTINATION_GONE)
        database.withTransaction {
            if (threadIds.any { it < 0 }) throw MailActionException(MailActionFailure.OPERATION_PENDING, "Open or discard a local draft instead of moving it", ActionErrorKind.LOCAL_DRAFT_MOVE)
            val messages = threadIds.distinct().flatMap { actionRows(it, sourceMailbox, true) }
            if (messages.any { it.accountId != target.accountId }) throw MailActionException(MailActionFailure.ACCOUNT_MISMATCH, "Choose conversations and a destination in the same account", ActionErrorKind.MIXED_ACCOUNTS)
            messages.forEach { moveMessage(it, target) }
            messages.map { it.threadId }.distinct().forEach { rebuildThread(it) }
        }; requestSync()
    }
    override suspend fun refresh(mailbox: Mailbox) = refreshAttempt(mailbox,null)
    override suspend fun retrySync(accountId:Long) = refreshAttempt(Mailbox.Unified(FolderRole.INBOX),setOf(accountId))

    private suspend fun refreshAttempt(mailbox:Mailbox,accounts:Set<Long>?) {
        val ids=accounts ?: when(mailbox) {
            is Mailbox.InFolder -> dao.folder(mailbox.folderId)?.accountId?.let { setOf(it) } ?: throw MailSyncException("Folder is no longer available", SyncProblem.FOLDER_UNAVAILABLE)
            is Mailbox.Unified, is Mailbox.Smart -> dao.accounts().map { it.id }.toSet()
        }
        val ready=CompletableDeferred<Unit>()
        val remaining=ids.toMutableSet()
        val early=when(mailbox) { is Mailbox.InFolder -> true; is Mailbox.Smart -> false; is Mailbox.Unified -> mailbox.role.category()==null && mailbox.role != FolderRole.SNOOZED }
        val progressLock=Mutex()
        scope.launch {
            try {
                val ok=syncAll(ids,primaryMailbox=mailbox) { account,folder,success,reason ->
                    val matches=when(mailbox) { is Mailbox.Smart -> false; is Mailbox.InFolder -> dao.folder(mailbox.folderId)?.path==folder.path; is Mailbox.Unified -> mailbox.role.name==folder.role.name }
                    if(early && matches) progressLock.withLock {
                        if(!success) ready.completeExceptionally((reason?.let { SyncDiagnostics.state(it,false) }.let { st -> MailSyncException(st?.message ?: "Some message headers could not be synchronized", st?.problem ?: SyncProblem.HEADERS_UNREADABLE, st?.errorKind) }))
                        remaining.remove(account)
                        if(remaining.isEmpty()) ready.complete(Unit)
                    }
                }
                if(ok) ready.complete(Unit)
                else ready.completeExceptionally((dao.accounts().firstOrNull { it.id in ids && it.syncError!=null }?.syncError?.let { SyncDiagnostics.state(it,false) }.let { st -> MailSyncException(st?.message ?: "Could not sync mail. Please retry.", st?.problem ?: SyncProblem.OTHER, st?.errorKind) }))
            } catch(e:CancellationException) { ready.cancel(e); throw e }
            catch(e:Exception) { ready.completeExceptionally(MailSyncException(SyncDiagnostics.reason(e), SyncDiagnostics.problem(e), app.dot.mail.engine.ConnectionFailure.classify(e))) }
        }
        try { withTimeout(60_000) { ready.await() } }
        catch(_:TimeoutCancellationException) { throw MailSyncException("Sync timed out. Please retry.", SyncProblem.TIMEOUT) }
    }
    override suspend fun suggestContacts(prefix: String, limit: Int): List<EmailAddress> = withContext(Dispatchers.IO) {
        val cap = limit.coerceIn(1, 50)
        val history = dao.contacts(prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%", cap).map { EmailAddress(it.email, it.name) }
        // Address-book matches only fill what mail history left over, and only when the permission was already granted.
        if (history.size >= cap) history else mergeSuggestions(history, addressBook.suggest(prefix, cap), cap)
    }
    private val draftLock = Mutex()
    override suspend fun saveDraft(draft: Draft): Long = withContext(Dispatchers.IO) { draftLock.withLock {
        requireNotNull(dao.account(draft.accountId))
        val previous = if (draft.localId != 0L) dao.draft(draft.localId) else null
        if (draft.localId != 0L && previous?.accountId != draft.accountId) throw MailActionException(MailActionFailure.ACCOUNT_MISMATCH, "Save a new draft when changing accounts", ActionErrorKind.DRAFT_ACCOUNT_CHANGE)
        val (cached, sources) = cacheAttachments(draft)
        val json = org.json.JSONObject(Codec.draft(cached)).put("attachmentSources", sources).toString()
        val id = database.withTransaction {
            val id = dao.putDraft(DraftRow(draft.localId, draft.accountId, json, maxOf(System.currentTimeMillis(),(previous?.updatedAt ?: 0)+1), previous?.sourceMessageId))
            // An encrypted message is never uploaded as a server draft (it would sit there as plaintext). If an earlier,
            // unencrypted copy was already mirrored, that copy is removed.
            if (draft.pgpEncrypt) dao.draftMirror(id)?.let { dao.putDraftMirror(it.copy(deleted = true, dueAt = 0)) } else queueDraftMirror(id,draft.accountId)
            id
        }
        if (scheduling) WorkScheduling.drafts(context,draft.accountId)
        id
    } }
    override suspend fun loadDraft(localId: Long): Draft? {
        if (localId >= 0) return dao.draft(localId)?.let { Codec.draft(it.json).copy(localId = it.id) }
        val messageId = -localId
        dao.draftForMessage(messageId)?.let { return Codec.draft(it.json).copy(localId = it.id) }
        loadBody(messageId)
        storage.ensureAttachments(messageId)
        return draftLock.withLock { database.withTransaction {
            dao.draftForMessage(messageId)?.let { return@withTransaction Codec.draft(it.json).copy(localId = it.id) }
            val message = dao.message(messageId) ?: return@withTransaction null
            if (dao.folder(message.folderId)?.role != "DRAFTS") return@withTransaction null
            val d = Draft(accountId = message.accountId, identityId = message.accountId, to = Codec.addressField(message.header, "to"), cc = Codec.addressField(message.header, "cc"), bcc = Codec.addressField(message.header, "bcc"), subject = message.subject, bodyText = bodyStore.read(message)?.let { b -> b.plainText ?: b.html?.let { app.dot.mail.engine.text.EmailHtml.toPlainText(it) } }.orEmpty(), bodyHtml = bodyStore.read(message)?.html, attachmentUris = dao.attachments(messageId).filter { !it.inline }.mapNotNull { it.localUri }, references = Codec.strings(Codec.field(message.header, "refs") ?: "[]"))
            val id = dao.putDraft(DraftRow(accountId = message.accountId, json = Codec.draft(d), updatedAt = System.currentTimeMillis(), sourceMessageId = messageId))
            queueDraftMirror(id,message.accountId,clean=true)
            d.copy(localId = id)
        } }
    }
    internal suspend fun removeDraftAndSourcePublic(localId: Long) = removeDraftAndSource(localId)
    private suspend fun removeDraftAndSource(localId: Long) {
        val draft = dao.draft(localId)
        var mirror = dao.draftMirror(localId)
        if (mirror==null && draft!=null) { queueDraftMirror(localId,draft.accountId,clean=true); mirror=dao.draftMirror(localId) }
        mirror?.let { dao.putDraftMirror(it.copy(deleted=true,dueAt=0)) }
        val source = dao.draft(localId)?.sourceMessageId?.let { dao.message(it) }
        if (source != null && source.uid > 0) {
            if(mirror==null) dao.folder(source.folderId)?.let { folder -> dao.insertOperation(OperationRow(accountId = source.accountId, folderId = folder.id, validity = folder.validity, uid = source.uid, action = MessageAction.DELETE_FOREVER.name, messageLocalId = source.id)) }
            dao.deleteMessage(source.id); rebuildThread(source.threadId)
        }
        dao.deleteDraft(localId)
    }
    /**
     * Trash, not delete: the latest version is uploaded to Drafts (if it is not there yet) and sync then moves that copy to
     * Trash, where it can be recovered like any message. Until then the draft is hidden locally and Undo can cancel it.
     * Accounts without a Trash (or Drafts) folder have nowhere to keep it, so the draft is deleted as before.
     */
    private suspend fun trashDraft(localId: Long) {
        val draft = dao.draft(localId) ?: return
        val folders = dao.folders(draft.accountId)
        val drafts = folders.firstOrNull { it.role == "DRAFTS" }
        // An encrypted draft never goes to the server, not even to its Trash: it is deleted on the device.
        if (drafts == null || folders.none { it.role == "TRASH" } || Codec.draft(draft.json).pgpEncrypt) { removeDraftAndSource(localId); return }
        if (dao.draftMirror(localId) == null) queueDraftMirror(localId, draft.accountId)
        // Upload and move only after the Undo window, so Undo never races the server.
        dao.draftMirror(localId)?.let { dao.putDraftMirror(it.copy(dueAt = System.currentTimeMillis() + DRAFT_TRASH_GRACE_MS)) }
        if (scheduling) WorkScheduling.drafts(context, draft.accountId, DRAFT_TRASH_GRACE_MS + 1_000)
        if (dao.operations(draft.accountId).none { it.action == OP_TRASH_DRAFT && it.target == "$localId" })
            dao.insertOperation(OperationRow(accountId = draft.accountId, folderId = drafts.id, validity = 0, uid = 0, action = OP_TRASH_DRAFT, target = "$localId"))
    }
    override suspend fun discardDraft(localId: Long) {
        database.withTransaction { removeDraftAndSource(localId) }; requestSync()
    }
    override suspend fun prepareReply(messageId: Long, mode: ReplyMode): Draft = withContext(Dispatchers.IO) {
        loadBody(messageId)
        if(mode==ReplyMode.FORWARD) storage.ensureAttachments(messageId)
        val m = requireNotNull(dao.message(messageId)); val account = requireNotNull(dao.account(m.accountId))
        val from = Codec.addressField(m.header, "replyTo").ifEmpty { Codec.addressField(m.header, "from") }
        val to = if (mode == ReplyMode.FORWARD) emptyList() else from
        val identities = identityStore.resolve(account.id, account.name, account.email)
        val own = ReplyIdentity.ownAddresses(identities)
        val originalTo = Codec.addressField(m.header, "to"); val originalCc = Codec.addressField(m.header, "cc")
        val identity = ReplyIdentity.pick(identities, originalTo.map { it.email }, originalCc.map { it.email })
        val cc = if (mode == ReplyMode.REPLY_ALL) (originalTo + originalCc).filter { a -> a.email.lowercase() !in own && to.none { it.email.equals(a.email, true) } }.distinctBy { it.email.lowercase() } else emptyList()
        val messageHeader = Codec.field(m.header, "messageId")
        Draft(accountId = account.id, identityId = identity?.id ?: account.id, to = to, cc = cc, subject = (if (mode == ReplyMode.FORWARD) "Fwd: " else "Re: ") + Threading.baseSubject(m.subject), bodyText = "\n\n" + bodyStore.read(m)?.let { it.plainText ?: org.jsoup.Jsoup.parse(it.html.orEmpty()).text() }.orEmpty().lineSequence().joinToString("\n") { "> $it" },
            attachmentUris = if (mode == ReplyMode.FORWARD) dao.attachments(m.id).filter { !it.inline }.mapNotNull { it.localUri } else emptyList(), inReplyTo = if (mode == ReplyMode.FORWARD) null else messageHeader, references = if (mode == ReplyMode.FORWARD) emptyList() else (Codec.strings(Codec.field(m.header, "refs") ?: "[]") + listOfNotNull(messageHeader)).distinct())
    }
    override suspend fun sendCalendarReply(messageId: Long, partstat: app.dot.mail.engine.calendar.Ics.PartStat): Long = queueCalendarReply(messageId,partstat)

    override suspend fun send(draft: Draft, undoWindowMs: Long): Long = withContext(Dispatchers.IO) {
        require(draft.to.isNotEmpty() || draft.cc.isNotEmpty() || draft.bcc.isNotEmpty()) { "At least one recipient is required" }
        requireNotNull(dao.account(draft.accountId))
        val frozen = cacheAttachments(draft).first
        val due = maxOf(System.currentTimeMillis() + undoWindowMs.coerceIn(0, 60_000), draft.scheduledAt ?: 0)
        val id = database.withTransaction { val id = dao.insertOutbox(OutboxRow(accountId = draft.accountId, draft = Codec.draft(frozen), dueAt = due)); markCorresponded(draft.accountId,frozen.to+frozen.cc+frozen.bcc); if (draft.localId != 0L) removeDraftAndSource(draft.localId); id }
        if (scheduling) WorkScheduling.outbox(context, id, due)
        if(draft.localId!=0L) requestSync()
        id
    }
    override suspend fun sendProtected(draft: Draft, undoWindowMs: Long, protector: PgpProtector): Long = queueProtected(draft, undoWindowMs, protector)
    override suspend fun rescheduleSend(outboxId: Long, sendAt: Long): Boolean = withContext(Dispatchers.IO) {
        if (SendSchedule.validate(sendAt, System.currentTimeMillis()) != SendSchedule.Validity.OK) return@withContext false
        // Only a message nobody has claimed yet: the state check is part of the UPDATE, so it cannot race with delivery.
        if (dao.outboxDue(outboxId, sendAt) != 1) return@withContext false
        if (scheduling) WorkScheduling.outbox(context, outboxId, sendAt, replace = true)
        true
    }
    override suspend fun cancelSend(outboxId: Long): Boolean = withContext(Dispatchers.IO) {
        val cancelled=database.withTransaction {
            val row=dao.outbox(outboxId) ?: return@withTransaction null
            if(dao.transitionOutbox(outboxId,"PENDING","CANCELLED")!=1) return@withTransaction null
            if(org.json.JSONObject(row.draft).optString("calendarReply").isBlank()) {
                val draft=Codec.draft(row.draft)
                val id = dao.putDraft(DraftRow(draft.localId,row.accountId,row.draft,System.currentTimeMillis()))
                queueDraftMirror(id,row.accountId)
            }
            row
        } ?: return@withContext false
        cancelled.mimePath?.let { File(it).delete() }; dao.clearOutboxMime(outboxId)
        if(scheduling && org.json.JSONObject(cancelled.draft).optString("calendarReply").isBlank()) WorkScheduling.drafts(context,cancelled.accountId)
        true
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
    internal suspend fun cacheAttachments(draft: Draft): Pair<Draft, org.json.JSONObject> {
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
                    total += file.length(); storage.changed(draft.accountId,file.length())
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
