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
    private fun session(endpoint: Endpoint, credentials: Credentials, smtp: Boolean = false): Session {
        val protocol = if (smtp) "smtp" else "imap"
        return Session.getInstance(Properties().apply {
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
            RemoteFolder(f.fullName, f.name, role(f.fullName, (f as IMAPFolder).attributes.toList()), f.unreadMessageCount.coerceAtLeast(0), f.messageCount.coerceAtLeast(0), f.attributes.any { it.lowercase() in setOf("\\sent", "\\drafts", "\\archive", "\\trash", "\\junk", "\\all", "\\flagged", "\\inbox") })
        })
    }
    suspend fun sync(endpoint: Endpoint, credentials: Credentials, path: String, cursor: SyncCursor = SyncCursor(), now: Long = System.currentTimeMillis(), limit: Int = 500): HeaderSync = store(endpoint, credentials) { store ->
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
                f.search(ReceivedDateTerm(ComparisonTerm.GE, Date(now - 30L * 24 * 60 * 60 * 1000))).takeLast(limit).toTypedArray()
            } else if (next > cursor.uidNext) f.getMessagesByUID(max(1, cursor.uidNext), UIDFolder.LASTUID).filterNotNull().take(limit).toTypedArray() else emptyArray()
            fetchHeaders(f, candidates, store.hasCapability("X-GM-EXT-1"))
            val flagsMessages = if (!reset && cursor.highestModSeq >= 0 && store.hasCapability("CONDSTORE")) {
                f.getMessagesByUIDChangedSince(1, UIDFolder.LASTUID, cursor.highestModSeq)
            } else inventory
            f.fetch(flagsMessages, FetchProfile().apply { add(FetchProfile.Item.FLAGS); add(UIDFolder.FetchProfileItem.UID) })
            val headers = candidates.map { header(f, it) }
            // Do not advance past a partial page. HighestModSeq is only advanced after flag inventory succeeds.
            val checkpoint = if (candidates.size == limit && !reset) (headers.maxOfOrNull { it.uid } ?: cursor.uidNext - 1) + 1 else next
            HeaderSync(SyncCursor(validity, checkpoint, runCatching { f.highestModSeq }.getOrDefault(-1)), reset, headers, flagsMessages.map { flags(f, it) }, existing)
        }
    }
    private fun fetchHeaders(f: IMAPFolder, messages: Array<out Message>, gmail: Boolean) {
        f.fetch(messages, FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(FetchProfile.Item.CONTENT_INFO); add(UIDFolder.FetchProfileItem.UID)
            listOf("Message-ID", "References", "In-Reply-To", "List-Unsubscribe").forEach { add(it) }
            if (gmail) add(GmailThreadFetch.profile)
        })
    }
    private fun flags(f: IMAPFolder, m: Message) = RemoteFlags(f.getUID(m), m.isSet(Flags.Flag.SEEN), m.isSet(Flags.Flag.FLAGGED), m.isSet(Flags.Flag.DELETED))
    private fun addresses(list: Array<out jakarta.mail.Address>?): List<Address> = list.orEmpty().filterIsInstance<InternetAddress>().map { Address(it.address, it.personal) }
    private fun header(f: IMAPFolder, m: Message): Header = Header(
        f.getUID(m), m.getHeader("Message-ID")?.firstOrNull(), Threading.ids(m.getHeader("References")?.joinToString(" ")), Threading.ids(m.getHeader("In-Reply-To")?.firstOrNull()).firstOrNull(),
        addresses(m.from), addresses(m.getRecipients(Message.RecipientType.TO)), addresses(m.getRecipients(Message.RecipientType.CC)), addresses(m.getRecipients(Message.RecipientType.BCC)), addresses(m.replyTo),
        m.subject.orEmpty(), (m.sentDate ?: m.receivedDate ?: Date(0)).time, flags(f, m),
        // BODYSTRUCTURE only: never access content or inputStream during header sync.
        hasAttachments(m), if ((f.store as IMAPStore).hasCapability("X-GM-EXT-1")) (m as IMAPMessage).getItem(GmailThreadFetch.item) as? Long else null, m.getHeader("List-Unsubscribe")?.firstOrNull()?.let { Regex("<(https://[^>]+|mailto:[^>]+)>", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) },
    )
    private fun hasAttachments(part: Part, depth: Int = 0): Boolean {
        if (depth >= 40) return false
        if (part.disposition.equals(Part.ATTACHMENT, true) || (part.fileName != null && !part.disposition.equals(Part.INLINE, true))) return true
        if (!part.isMimeType("multipart/*")) return false
        // Angus constructs these child objects entirely from cached BODYSTRUCTURE.
        val source = part.dataHandler.dataSource as? MultipartDataSource ?: return false
        return (0 until source.count).any { hasAttachments(source.getBodyPart(it), depth + 1) }
    }
    suspend fun body(endpoint: Endpoint, credentials: Credentials, path: String, uid: Long, expectedValidity: Long? = null): ParsedBody = store(endpoint, credentials) { s -> folder(s, path) { f -> require(expectedValidity == null || expectedValidity == f.uidValidity) { "Folder UIDVALIDITY changed" }
        require(uid > 0) { "A server message UID must be positive" }
        val message = requireNotNull(f.getMessageByUID(uid)) { "Message no longer exists" }
        val output = object : java.io.ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) { require(count.toLong() + len <= 25 * 1024 * 1024) { "Message exceeds 25 MiB" }; super.write(b, off, len) }
            override fun write(b: Int) { require(count < 25 * 1024 * 1024) { "Message exceeds 25 MiB" }; super.write(b) }
        }
        message.writeTo(output)
        Mime.parse(MimeMessage(Session.getInstance(Properties()), java.io.ByteArrayInputStream(output.toByteArray()))) } }
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
    suspend fun append(endpoint: Endpoint, credentials: Credentials, path: String, message: MimeMessage, draft: Boolean = false) = store(endpoint, credentials) { s ->
        val f = s.getFolder(path)
        if (!f.exists()) f.create(Folder.HOLDS_MESSAGES)
        if (draft) message.setFlag(Flags.Flag.DRAFT, true) else message.setFlag(Flags.Flag.SEEN, true)
        folder(s, path, true) { opened ->
            val id = message.messageID
            if (id == null || opened.search(HeaderTerm("Message-ID", id)).isEmpty()) opened.appendMessages(arrayOf(message))
        }
    }
    suspend fun send(endpoint: Endpoint, credentials: Credentials, message: MimeMessage) = withContext(Dispatchers.IO) {
        session(endpoint, credentials, true).getTransport("smtp").use { transport ->
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
