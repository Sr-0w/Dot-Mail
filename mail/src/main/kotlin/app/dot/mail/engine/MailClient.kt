package app.dot.mail.engine

import jakarta.mail.*
import jakarta.mail.event.MessageCountAdapter
import jakarta.mail.event.MessageCountEvent
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.*
import kotlinx.coroutines.*
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPMessage
import org.eclipse.angus.mail.imap.IMAPStore
import java.io.Closeable
import java.util.Date
import java.util.Properties
import kotlin.math.max

/** Each operation owns its connection. No shared selected folder or blocking calls on Main. */
class MailClient(val timeoutMs: Int = 30_000) {
    internal fun session(endpoint: Endpoint, credentials: Credentials, smtp: Boolean = false, envelopeFrom: String? = null): Session {
        val protocol = if (smtp) "smtp" else "imap"
        return Session.getInstance(Properties().apply {
            val tlsProtocols = MailTlsPolicy.protocols()
            for (name in listOf("imap", "imaps", "smtp", "smtps")) setProperty("mail.$name.ssl.protocols", tlsProtocols)
            setProperty("mail.debug", "false")
            setProperty("mail.debug.auth", "false")
            setProperty("mail.$protocol.connectiontimeout", timeoutMs.toString())
            setProperty("mail.$protocol.timeout", timeoutMs.toString())
            setProperty("mail.$protocol.writetimeout", timeoutMs.toString())
            setProperty("mail.$protocol.ssl.enable", (endpoint.security == TransportSecurity.SSL_TLS).toString())
            setProperty("mail.$protocol.starttls.enable", (endpoint.security == TransportSecurity.STARTTLS).toString())
            setProperty("mail.$protocol.starttls.required", (endpoint.security == TransportSecurity.STARTTLS).toString())
            setProperty("mail.$protocol.ssl.checkserveridentity", "true")
            setProperty("mail.$protocol.auth", "true")
            setProperty("mail.imap.peek", "true")
            setProperty("mail.imap.enablecondstore", "true")
            if (credentials.oauth2) setProperty("mail.$protocol.auth.mechanisms", "XOAUTH2")
            // SMTP MAIL FROM. Providers reject envelope senders that are not the logged-in account, so aliases
            // (a different From header) still submit with the account address.
            if (smtp && envelopeFrom != null) setProperty("mail.smtp.from", envelopeFrom)
        })
    }
    private fun connect(endpoint: Endpoint, credentials: Credentials): IMAPStore =
        EngineImapStore(session(endpoint, credentials)).apply { connect(endpoint.host, endpoint.port, endpoint.username, credentials.secret) }
    private suspend fun <T> store(endpoint: Endpoint, credentials: Credentials, block: (IMAPStore) -> T): T = withContext(Dispatchers.IO) {
        connect(endpoint, credentials).use { block(it) }
    }
    private fun <T> folder(store: IMAPStore, path: String, write: Boolean = false, block: (IMAPFolder) -> T): T {
        val folder = store.getFolder(path) as IMAPFolder
        folder.open(if (write) Folder.READ_WRITE else Folder.READ_ONLY)
        try { return block(folder) } finally { if (folder.isOpen) folder.close(false) }
    }
    suspend fun verify(imap: Endpoint, smtp: Endpoint, credentials: Credentials) {
        store(imap, credentials) { }
        withContext(Dispatchers.IO) { session(smtp, credentials, true).getTransport("smtp").use { it.connect(smtp.host, smtp.port, smtp.username, credentials.secret) } }
    }
    suspend fun folders(endpoint: Endpoint, credentials: Credentials): List<RemoteFolder> = store(endpoint, credentials) { store ->
        uniqueRoles(store.defaultFolder.list("*").filter { it.type and Folder.HOLDS_MESSAGES != 0 }.map { f ->
            RemoteFolder(f.fullName, f.name, role(f.fullName, (f as IMAPFolder).attributes.toList()), f.unreadMessageCount.coerceAtLeast(0), f.messageCount.coerceAtLeast(0), f.attributes.any { it.lowercase() in setOf("\\sent", "\\drafts", "\\archive", "\\trash", "\\junk", "\\all", "\\flagged", "\\inbox") }, f.separator.takeIf { it != 0.toChar() } ?: '/')
        })
    }

    /**
     * CREATE (+ SUBSCRIBE) a folder called [name] under [parent] (null = top level). The full path is built with the
     * server's own hierarchy separator, which differs between servers ("/" or "."). Returns the created path.
     */
    suspend fun createFolder(endpoint: Endpoint, credentials: Credentials, parent: String?, name: String): String = store(endpoint, credentials) { s ->
        val f = child(s, parent, name)
        if (f.exists()) throw FolderOpException(FolderOpException.Kind.EXISTS, "A folder named \"$name\" already exists")
        if (!f.create(Folder.HOLDS_MESSAGES)) throw FolderOpException(FolderOpException.Kind.REJECTED, "The server refused to create the folder")
        runCatching { f.isSubscribed = true }
        f.fullName
    }

    /**
     * RENAME [from] to [newName] under [newParent] (null = top level); a different parent moves the folder. The server
     * renames children too. Keeps the subscription. Returns the new path.
     */
    suspend fun renameFolder(endpoint: Endpoint, credentials: Credentials, from: String, newParent: String?, newName: String): String = store(endpoint, credentials) { s ->
        val src = s.getFolder(from)
        if (!src.exists()) throw FolderOpException(FolderOpException.Kind.NOT_FOUND, "The folder no longer exists on the server")
        val dst = child(s, newParent, newName)
        if (dst.exists()) throw FolderOpException(FolderOpException.Kind.EXISTS, "A folder named \"$newName\" already exists")
        runCatching { src.isSubscribed = false }
        if (!src.renameTo(dst)) {
            runCatching { src.isSubscribed = true }
            throw FolderOpException(FolderOpException.Kind.REJECTED, "The server refused to rename the folder")
        }
        runCatching { s.getFolder(dst.fullName).isSubscribed = true }
        dst.fullName
    }

    private fun child(s: IMAPStore, parent: String?, name: String): Folder =
        if (parent == null) s.defaultFolder.getFolder(name) else s.getFolder(parent).getFolder(name)

    /** UIDVALIDITY of a folder (a cheap STATUS): a renamed folder keeps it, which is how a rename elsewhere is told from delete + create. */
    suspend fun folderValidity(endpoint: Endpoint, credentials: Credentials, path: String): Long = store(endpoint, credentials) { s ->
        runCatching { (s.getFolder(path) as IMAPFolder).uidValidity }.getOrDefault(0L)
    }

    /** UNSUBSCRIBE + DELETE. Refuses a folder that still holds messages: deleting it would destroy them. Missing = already done. */
    suspend fun deleteFolder(endpoint: Endpoint, credentials: Credentials, path: String) = store(endpoint, credentials) { s ->
        val f = s.getFolder(path)
        if (!f.exists()) return@store
        val count = runCatching { f.messageCount }.getOrDefault(0) // \Noselect parents cannot be counted: they hold none
        if (count > 0) throw FolderOpException(FolderOpException.Kind.NOT_EMPTY, "The folder still contains $count messages on the server")
        runCatching { f.isSubscribed = false }
        if (!f.delete(false)) throw FolderOpException(FolderOpException.Kind.REJECTED, "The server refused to delete the folder")
    }

    suspend fun sync(endpoint: Endpoint, credentials: Credentials, path: String, cursor: SyncCursor = SyncCursor(), now: Long = System.currentTimeMillis(), limit: Int = 500, windowDays: Int = 30): HeaderSync = store(endpoint, credentials) { store ->
        require(limit in 1..5000)
        folder(store, path) { f ->
            val validity = f.uidValidity
            val reset = validity != cursor.uidValidity
            val next = f.uidNext
            // A UID-only inventory reconciles expunges without downloading bodies or envelopes.
            val inventory = f.getMessagesByUID(1, UIDFolder.LASTUID).filterNotNull().toTypedArray()
            f.fetch(inventory, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) })
            val existing = inventory.map { f.getUID(it) }.toSet()
            val candidates = if (reset) {
                // windowDays == 0: no date limit (still capped by [limit]).
                (if (windowDays <= 0) f.messages else f.search(ReceivedDateTerm(ComparisonTerm.GE, Date(now - windowDays * 24L * 60 * 60 * 1000)))).takeLast(limit).toTypedArray()
            } else if (next > cursor.uidNext) f.getMessagesByUID(max(1, cursor.uidNext), UIDFolder.LASTUID).filterNotNull().take(limit).toTypedArray() else emptyArray()
            val failures = mutableListOf<HeaderFailure>()
            try { fetchHeaders(f, candidates, store.hasCapability("X-GM-EXT-1")) }
            catch (e: MessagingException) { failures += HeaderFailure(0, e) }
            val flagsMessages = if (!reset && cursor.highestModSeq >= 0 && store.hasCapability("CONDSTORE")) {
                f.getMessagesByUIDChangedSince(1, UIDFolder.LASTUID, cursor.highestModSeq)
            } else inventory
            f.fetch(flagsMessages, FetchProfile().apply { add(FetchProfile.Item.FLAGS); add(UIDFolder.FetchProfileItem.UID) })
            val headers = candidates.mapNotNull { message ->
                try { header(f, message) }
                catch (e: Exception) { failures += HeaderFailure(f.getUID(message), e); null }
            }
            // Do not advance past a partial page. HighestModSeq is only advanced after flag inventory succeeds.
            val pageEnd = if (candidates.size == limit && !reset) (headers.maxOfOrNull { it.uid } ?: cursor.uidNext - 1) + 1 else next
            val checkpoint = minOf(pageEnd, failures.map { it.uid }.filter { it > 0 }.minOrNull() ?: pageEnd)
            val flagResults = flagsMessages.mapNotNull { message -> try { flags(f, message) } catch (e: Exception) { failures += HeaderFailure(f.getUID(message), e); null } }
            HeaderSync(SyncCursor(validity, checkpoint, if (failures.isEmpty()) runCatching { f.highestModSeq }.getOrDefault(-1) else -1), reset, headers, flagResults, existing, failures)
        }
    }
    private fun fetchHeaders(f: IMAPFolder, messages: Array<out Message>, gmail: Boolean) {
        f.fetch(messages, FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(FetchProfile.Item.CONTENT_INFO); add(UIDFolder.FetchProfileItem.UID)
            listOf("Message-ID", "References", "In-Reply-To", "List-Unsubscribe", "List-Unsubscribe-Post").forEach { add(it) }
            if (gmail) add(GmailThreadFetch.profile)
        })
    }
    private fun flags(f: IMAPFolder, m: Message) = RemoteFlags(f.getUID(m), m.isSet(Flags.Flag.SEEN), m.isSet(Flags.Flag.FLAGGED), m.isSet(Flags.Flag.DELETED))
    private fun addresses(list: Array<out jakarta.mail.Address>?): List<Address> = list.orEmpty().filterIsInstance<InternetAddress>().map { Address(it.address, it.personal) }
    internal fun header(f: IMAPFolder, message: Message): Header = try { envelopeHeader(f, message) }
    catch (failure: MessagingException) {
        // Some servers/clients fail ENVELOPE parsing while RFC header fields are still available.
        fun raw(name: String) = message.getHeader(name)?.firstOrNull()
        val from = raw("From")
        if (from.isNullOrBlank()) throw failure
        fun list(name: String) = raw(name)?.let { runCatching { InternetAddress.parseHeader(it, false) }.getOrNull() }?.map { Address(it.address, it.personal) }.orEmpty()
        Header(f.getUID(message), raw("Message-ID"), Threading.ids(raw("References")), Threading.ids(raw("In-Reply-To")).firstOrNull(),
            list("From"), list("To"), list("Cc"), list("Bcc"), list("Reply-To").ifEmpty { list("From") },
            raw("Subject")?.let { runCatching { jakarta.mail.internet.MimeUtility.decodeText(it) }.getOrDefault(it) }.orEmpty(),
            raw("Date")?.let { runCatching { jakarta.mail.internet.MailDateFormat().parse(it).time }.getOrNull() } ?: 0,
            flags(f,message), runCatching { hasAttachments(message) }.getOrDefault(false),
            if ((f.store as IMAPStore).hasCapability("X-GM-EXT-1")) (message as IMAPMessage).getItem(GmailThreadFetch.item) as? Long else null)
    }
    private fun envelopeHeader(f: IMAPFolder, m: Message): Header = Header(
        f.getUID(m), m.getHeader("Message-ID")?.firstOrNull(), Threading.ids(m.getHeader("References")?.joinToString(" ")), Threading.ids(m.getHeader("In-Reply-To")?.firstOrNull()).firstOrNull(),
        addresses(m.from), addresses(m.getRecipients(Message.RecipientType.TO)), addresses(m.getRecipients(Message.RecipientType.CC)), addresses(m.getRecipients(Message.RecipientType.BCC)), addresses(m.replyTo),
        m.subject.orEmpty(), (m.sentDate ?: m.receivedDate ?: Date(0)).time, flags(f, m),
        // BODYSTRUCTURE only: never access content or inputStream during header sync.
        runCatching { hasAttachments(m) }.getOrDefault(false), if ((f.store as IMAPStore).hasCapability("X-GM-EXT-1")) (m as IMAPMessage).getItem(GmailThreadFetch.item) as? Long else null, m.getHeader("List-Unsubscribe")?.firstOrNull()?.let { Regex("<(https://[^>]+|mailto:[^>]+)>", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) },
        unsubscribeOneClick(m),
    )
    private fun unsubscribeOneClick(m: Message): String? = runCatching {
        val list = m.getHeader("List-Unsubscribe")?.toList() ?: return null
        val post = m.getHeader("List-Unsubscribe-Post")?.toList() ?: return null
        app.dot.mail.engine.text.ListUnsubscribe.parse(mapOf("List-Unsubscribe" to list, "List-Unsubscribe-Post" to post))?.oneClickHttps
    }.getOrNull()
    private fun hasAttachments(part: Part, depth: Int = 0): Boolean {
        if (depth >= 40) return false
        if (part.disposition.equals(Part.ATTACHMENT, true) || (part.fileName != null && !part.disposition.equals(Part.INLINE, true))) return true
        if (!part.isMimeType("multipart/*")) return false
        // Angus constructs these child objects entirely from cached BODYSTRUCTURE.
        val source = part.dataHandler.dataSource as? MultipartDataSource ?: return false
        return (0 until source.count).any { hasAttachments(source.getBodyPart(it), depth + 1) }
    }
    private suspend fun <T> rawBody(endpoint: Endpoint, credentials: Credentials, path: String, uid: Long, expectedValidity: Long?, directory: java.io.File, parse: (MimeMessage) -> T): T = store(endpoint, credentials) { store ->
        require(uid > 0) { "A server message UID must be positive" }
        directory.mkdirs()
        val raw = java.io.File.createTempFile("raw-", ".eml", directory)
        try { folder(store,path) { folder ->
            require(expectedValidity == null || expectedValidity == folder.uidValidity) { "Folder UIDVALIDITY changed" }
            val message = requireNotNull(folder.getMessageByUID(uid)) { "Message no longer exists" }
            raw.outputStream().use { file ->
                val bounded = object : java.io.FilterOutputStream(file) {
                    var count = 0L
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { require(count + length <= 40L * 1024 * 1024) { "Encoded message exceeds download limit" }; out.write(bytes,offset,length); count += length }
                    override fun write(value: Int) { require(++count <= 40L * 1024 * 1024) { "Encoded message exceeds download limit" }; out.write(value) }
                }
                message.writeTo(bounded)
            }
            jakarta.mail.util.SharedFileInputStream(raw).use { input -> parse(MimeMessage(Session.getInstance(Properties()), input)) }
        } } finally { raw.delete() }
    }
    suspend fun body(endpoint: Endpoint, credentials: Credentials, path: String, uid: Long, expectedValidity: Long? = null): ParsedBody {
        val directory = java.io.File.createTempFile("dotmail-body-", "").apply { delete(); mkdirs() }
        return try { rawBody(endpoint,credentials,path,uid,expectedValidity,directory) { Mime.parse(it) } } finally { directory.deleteRecursively() }
    }
    suspend fun cacheBody(endpoint: Endpoint, credentials: Credentials, path: String, uid: Long, directory: java.io.File, expectedValidity: Long? = null): CachedBody =
        rawBody(endpoint,credentials,path,uid,expectedValidity,directory) { Mime.cache(it, directory) }
    /** Bounded partial text previews only. Never changes flags or populates a complete body. */
    suspend fun snippets(endpoint: Endpoint, credentials: Credentials, path: String, uids: LongArray, expectedValidity: Long, maxBytes: Int = 2048): Map<Long, String> = store(endpoint, credentials) { store ->
        require(uids.size <= 200 && uids.all { it > 0 } && maxBytes in 128..2048)
        folder(store, path) { folder ->
            require(folder.uidValidity == expectedValidity) { "Folder UIDVALIDITY changed" }
            val messages = folder.getMessagesByUID(uids).filterNotNull().toTypedArray()
            folder.fetch(messages, FetchProfile().apply { add(FetchProfile.Item.CONTENT_INFO); add(UIDFolder.FetchProfileItem.UID) })
            messages.associate { message -> val uid = folder.getUID(message); uid to SnippetFetcher.fetch(folder, message, uid, maxBytes) }
        }
    }
    suspend fun setFlags(endpoint: Endpoint, credentials: Credentials, path: String, uids: LongArray, seen: Boolean? = null, starred: Boolean? = null, delete: Boolean = false) = store(endpoint, credentials) { s -> folder(s, path, true) { f ->
        val messages = f.getMessagesByUID(uids).filterNotNull().toTypedArray()
        seen?.let { f.setFlags(messages, Flags(Flags.Flag.SEEN), it) }
        starred?.let { f.setFlags(messages, Flags(Flags.Flag.FLAGGED), it) }
        if (delete) { require(s.hasCapability("UIDPLUS")) { "Safe deletion requires UIDPLUS" }; f.setFlags(messages, Flags(Flags.Flag.DELETED), true); f.expunge(messages) }
    } }
    suspend fun move(endpoint: Endpoint, credentials: Credentials, path: String, uids: LongArray, target: String) = store(endpoint, credentials) { s -> folder(s, path, true) { f ->
        val messages = f.getMessagesByUID(uids).filterNotNull().toTypedArray()
        val destination = s.getFolder(target)
        if (!destination.exists()) destination.create(Folder.HOLDS_MESSAGES)
        if (s.hasCapability("MOVE")) { f.moveMessages(messages, destination); MoveOutcome.MOVED } else {
            // Generic EXPUNGE would delete other clients' marked messages. Require targeted UID EXPUNGE.
            f.copyMessages(messages, destination); f.setFlags(messages, Flags(Flags.Flag.DELETED), true)
            if (s.hasCapability("UIDPLUS")) { f.expunge(messages); MoveOutcome.MOVED } else MoveOutcome.COPIED_AND_MARKED_DELETED
        }
    } }
    suspend fun draftInventory(endpoint: Endpoint, credentials: Credentials, path: String): DraftInventory = store(endpoint, credentials) { s -> folder(s,path) { f ->
        val messages = f.search(FlagTerm(Flags(Flags.Flag.DELETED),false))
        f.fetch(messages, FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) })
        DraftInventory(f.uidValidity,messages.map { f.getUID(it) }.toSet())
    } }
    private fun findDraft(f: IMAPFolder, token: String, revision: Long): Message? {
        val id = "<dotdraft-$token-$revision@dotmail.local>"
        return f.search(AndTerm(HeaderTerm("Message-ID",id),FlagTerm(Flags(Flags.Flag.DELETED),false)))
            .firstOrNull { it.getHeader("Message-ID")?.firstOrNull()==id && it.getHeader("X-Dot-Draft-Id")?.firstOrNull()==token }
    }
    suspend fun findDraftCopy(endpoint: Endpoint, credentials: Credentials, path: String, token: String, revision: Long): DraftCopy? = store(endpoint,credentials) { s -> folder(s,path) { f ->
        findDraft(f,token,revision)?.let { DraftCopy(f.uidValidity,f.getUID(it)) }
    } }
    /** Idempotent revision APPEND, with identity separate from Message-ID so external edits stay distinguishable by UID. */
    suspend fun appendDraft(endpoint: Endpoint, credentials: Credentials, path: String, token: String, revision: Long, message: MimeMessage): DraftCopy = store(endpoint,credentials) { s -> folder(s,path,true) { f ->
        require(token.matches(Regex("[a-zA-Z0-9-]{1,80}")) && revision>0)
        message.setHeader("X-Dot-Draft-Id",token)
        message.setHeader("Message-ID","<dotdraft-$token-$revision@dotmail.local>")
        message.setFlag(Flags.Flag.DRAFT,true)
        var found = findDraft(f,token,revision)
        if (found==null) { f.appendMessages(arrayOf(message)); found=findDraft(f,token,revision) }
        val appended = requireNotNull(found) { "Server did not expose the appended draft" }
        DraftCopy(f.uidValidity,f.getUID(appended))
    } }
    /** Only the specific known copy. Without UIDPLUS, mark it deleted and leave expunge to the server. */
    suspend fun deleteDraftCopy(endpoint: Endpoint, credentials: Credentials, path: String, copy: DraftCopy) = store(endpoint,credentials) { s -> folder(s,path,true) { f ->
        if (f.uidValidity!=copy.validity) return@folder
        val message=f.getMessageByUID(copy.uid) ?: return@folder
        f.setFlags(arrayOf(message),Flags(Flags.Flag.DELETED),true)
        if(s.hasCapability("UIDPLUS")) f.expunge(arrayOf(message))
    } }
    suspend fun append(endpoint: Endpoint, credentials: Credentials, path: String, message: MimeMessage, draft: Boolean = false) = store(endpoint, credentials) { s ->
        val f = s.getFolder(path)
        if (!f.exists()) f.create(Folder.HOLDS_MESSAGES)
        if (draft) message.setFlag(Flags.Flag.DRAFT, true) else message.setFlag(Flags.Flag.SEEN, true)
        folder(s, path, true) { opened ->
            val id = message.messageID
            if (id == null || opened.search(HeaderTerm("Message-ID", id)).isEmpty()) opened.appendMessages(arrayOf(message))
        }
    }
    suspend fun send(endpoint: Endpoint, credentials: Credentials, message: MimeMessage, envelopeFrom: String? = null) = withContext(Dispatchers.IO) {
        session(endpoint, credentials, true, envelopeFrom).getTransport("smtp").use { transport ->
            try { transport.connect(endpoint.host, endpoint.port, endpoint.username, credentials.secret) }
            catch (e: Exception) { throw SubmissionNotStartedException(e) }
            transport.sendMessage(message, message.allRecipients)
        }
    }
    suspend fun search(endpoint: Endpoint, credentials: Credentials, path: String, query: String, limit: Int = 500): List<Header> = store(endpoint, credentials) { s -> folder(s, path) { f ->
        val parsed = MailSearch.parse(query)
        val messages = f.search(parsed.serverTerm()).takeLast(limit).toTypedArray()
        fetchHeaders(f, messages, s.hasCapability("X-GM-EXT-1")); messages.map { header(f, it) }.filter { !parsed.needsAttachments || it.hasAttachments }
    } }
    /** Dedicated IDLE socket; cancellation closes it immediately, including while idle blocks. */
    suspend fun idle(endpoint: Endpoint, credentials: Credentials, path: String = "INBOX", changed: () -> Unit) = coroutineScope {
        val s = withContext(Dispatchers.IO) { connect(endpoint, credentials) }
        val f = s.getFolder(path) as IMAPFolder
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { runCatching { s.close() } } }
        try { withContext(Dispatchers.IO) {
            f.open(Folder.READ_ONLY)
            if (!s.hasCapability("IDLE")) return@withContext
            f.addMessageCountListener(object : MessageCountAdapter() { override fun messagesAdded(e: MessageCountEvent) { changed() }; override fun messagesRemoved(e: MessageCountEvent) { changed() } })
            f.addMessageChangedListener { changed() }
            while (isActive && f.isOpen) f.idle()
        } } finally { closer.cancel(); withContext(NonCancellable + Dispatchers.IO) { runCatching { s.close() } } }
    }
    companion object {
        internal fun uniqueRoles(folders: List<RemoteFolder>): List<RemoteFolder> {
            val winners = folders.filter { it.role != Role.CUSTOM }.groupBy { it.role }.mapValues { (_, choices) ->
                choices.sortedWith(compareByDescending<RemoteFolder> { it.specialUse }.thenByDescending { it.total }.thenBy { it.path }).first().path
            }
            return folders.map { if (it.role != Role.CUSTOM && winners[it.role] != it.path) it.copy(role = Role.CUSTOM) else it }
        }
        fun role(path: String, attributes: List<String>): Role {
            val special = mapOf("\\Inbox" to Role.INBOX, "\\Sent" to Role.SENT, "\\Drafts" to Role.DRAFTS, "\\Archive" to Role.ARCHIVE, "\\Trash" to Role.TRASH, "\\Junk" to Role.SPAM, "\\All" to Role.ALL_MAIL, "\\Flagged" to Role.STARRED)
            special.entries.firstOrNull { (attr, _) -> attributes.any { it.equals(attr, true) } }?.let { return it.value }
            return when (path.substringAfterLast('/').lowercase()) {
                "inbox" -> Role.INBOX; "sent", "sent mail", "sent items", "envoyés" -> Role.SENT
                "drafts", "brouillons" -> Role.DRAFTS; "archive", "archives" -> Role.ARCHIVE
                "trash", "deleted items", "corbeille", "bin" -> Role.TRASH; "junk", "spam", "courrier indésirable" -> Role.SPAM
                "all mail", "tous les messages" -> Role.ALL_MAIL; "starred", "suivis" -> Role.STARRED; else -> Role.CUSTOM
            }
        }
    }
}

/** Connection/authentication failed before any SMTP envelope or DATA was submitted; retry is safe. */
class SubmissionNotStartedException(cause: Throwable) : Exception("SMTP submission did not start", cause)

/** A folder command was refused by the server. [kind] lets callers explain it; replaying a command may treat some kinds as success. */
class FolderOpException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { EXISTS, NOT_FOUND, NOT_EMPTY, REJECTED }
}
