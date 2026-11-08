package app.dot.mail.demo

import androidx.paging.PagingData
import app.dot.mail.data.AccountRepository
import app.dot.mail.data.ComposeRepository
import app.dot.mail.data.MailGraph
import app.dot.mail.data.MailRepository
import app.dot.mail.data.ReplyMode
import app.dot.mail.data.model.Account
import app.dot.mail.data.model.AccountConfig
import app.dot.mail.data.model.Attachment
import app.dot.mail.data.model.Draft
import app.dot.mail.data.model.EmailAddress
import app.dot.mail.data.model.Folder
import app.dot.mail.data.model.FolderException
import app.dot.mail.data.model.FolderFailure
import app.dot.mail.data.model.FolderRole
import app.dot.mail.data.model.Mailbox
import app.dot.mail.data.model.Message
import app.dot.mail.data.model.MessageAction
import app.dot.mail.data.model.MessageBody
import app.dot.mail.data.model.ThreadSummary
import app.dot.mail.data.model.VerifyResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Fully in-memory MailGraph with believable sample mail. Never touches the network.
 * Used by demo mode (debug builds), Compose UI tests and screenshots.
 */
class DemoMailGraph(now: Long = System.currentTimeMillis()) : MailGraph {
    private val me = EmailAddress("robin@example.org", "Robin")
    private val accountsState = MutableStateFlow(
        listOf(Account(1, "Robin", me.email, 0), Account(2, "Robin (work)", "robin@studio.example", 1)),
    )
    private val foldersState = MutableStateFlow(initialFolders())
    private val folders get() = foldersState.value
    private var nextFolderId = 100L

    private fun initialFolders() = listOf(
        Folder(10, 1, "INBOX", "Inbox", FolderRole.INBOX, 3, 12),
        Folder(11, 1, "Sent", "Sent", FolderRole.SENT, 0, 4),
        Folder(12, 1, "Drafts", "Drafts", FolderRole.DRAFTS, 0, 0),
        Folder(13, 1, "Archive", "Archive", FolderRole.ARCHIVE, 0, 40),
        Folder(14, 1, "Junk", "Junk", FolderRole.SPAM, 0, 1),
        Folder(15, 1, "Trash", "Trash", FolderRole.TRASH, 0, 2),
        Folder(16, 1, "Receipts", "Receipts", FolderRole.CUSTOM, 0, 9),
        Folder(20, 2, "INBOX", "Inbox", FolderRole.INBOX, 1, 5),
        Folder(21, 2, "Sent", "Sent", FolderRole.SENT, 0, 2),
        Folder(23, 2, "Archive", "Archive", FolderRole.ARCHIVE, 0, 7),
        Folder(25, 2, "Trash", "Trash", FolderRole.TRASH, 0, 0),
    )
    private val messages = MutableStateFlow(sample(now))
    private val drafts = MutableStateFlow<Map<Long, Draft>>(emptyMap())
    private var nextId = 10_000L

    override val accounts = object : AccountRepository {
        override val accounts: Flow<List<Account>> = accountsState
        override fun oauthAvailability(provider: app.dot.mail.data.model.OAuthProvider) =
            app.dot.mail.data.model.OAuthAvailability(provider, false, "Not available in demo mode")
        override suspend fun beginOAuth(email: String, provider: app.dot.mail.data.model.OAuthProvider, accountId: Long?): android.content.Intent =
            throw UnsupportedOperationException("Demo mode")
        override suspend fun completeOAuth(resultUri: android.net.Uri): Long = throw UnsupportedOperationException("Demo mode")
        override suspend fun discover(email: String): AccountConfig? = null
        override suspend fun verify(config: AccountConfig, password: String): VerifyResult = VerifyResult.Ok
        override suspend fun addAccount(config: AccountConfig, password: String): Long {
            val id = (accountsState.value.maxOfOrNull { it.id } ?: 0) + 1
            accountsState.update { it + Account(id, config.displayName, config.email, it.size) }
            return id
        }
        override suspend fun removeAccount(accountId: Long) = accountsState.update { list -> list.filterNot { it.id == accountId } }
    }

    override val mail = object : MailRepository {
        override fun folders(accountId: Long): Flow<List<Folder>> = combine(messages, foldersState) { msgs, _ -> msgs }.map { msgs ->
            folders.filter { it.accountId == accountId }.map { f -> f.copy(unreadCount = msgs.count { it.folderId == f.id && it.isUnread }) }
        }

        override fun threads(mailbox: Mailbox): Flow<PagingData<ThreadSummary>> = combine(messages, accountsState, drafts) { msgs, accts, localDrafts ->
            val draftsBox = when (mailbox) {
                is Mailbox.Unified -> mailbox.role == FolderRole.DRAFTS
                is Mailbox.InFolder -> folders.firstOrNull { it.id == mailbox.folderId }?.role == FolderRole.DRAFTS
            }
            val draftRows = if (!draftsBox) emptyList() else localDrafts.values.map { d ->
                ThreadSummary(
                    threadId = -d.localId, accountId = d.accountId, subject = d.subject, participants = emptyList(),
                    snippet = d.bodyText.take(140), lastMessageAt = System.currentTimeMillis(), messageCount = 1, unreadCount = 0,
                    isStarred = false, hasAttachments = d.attachmentUris.isNotEmpty(), draftId = d.localId, recipients = d.to,
                )
            }
            val ids = accts.map { it.id }.toSet()
            val inBox = when (mailbox) {
                is Mailbox.Unified -> {
                    val fIds = folders.filter { it.role == mailbox.role }.map { it.id }.toSet()
                    if (mailbox.role == FolderRole.STARRED) msgs.filter { it.isStarred } else msgs.filter { it.folderId in fIds }
                }
                is Mailbox.InFolder -> msgs.filter { it.folderId == mailbox.folderId }
            }.filter { it.accountId in ids }
            PagingData.from(draftRows + summaries(inBox))
        }

        override fun search(query: String, accountId: Long?): Flow<PagingData<ThreadSummary>> = messages.map { msgs ->
            val q = query.lowercase()
            PagingData.from(
                summaries(
                    msgs.filter { m ->
                        (accountId == null || m.accountId == accountId) &&
                            (m.subject.lowercase().contains(q) || m.from.displayName.lowercase().contains(q) || m.snippet.lowercase().contains(q))
                    },
                ),
            )
        }

        override fun thread(threadId: Long): Flow<List<Message>> = messages.map { all ->
            val folder = all.firstOrNull { it.threadId == threadId }?.folderId
            all.filter { it.threadId == threadId && (it.folderId == folder) }.sortedBy { it.sentAt }
        }

        override suspend fun loadBody(messageId: Long) {
            delay(250)
        }

        override suspend fun openAttachment(attachment: Attachment): String = throw UnsupportedOperationException("Demo mode")

        override suspend fun apply(action: MessageAction, threadIds: Collection<Long>) {
            val role = when (action) {
                MessageAction.ARCHIVE -> FolderRole.ARCHIVE
                MessageAction.TRASH -> FolderRole.TRASH
                MessageAction.SPAM -> FolderRole.SPAM
                MessageAction.NOT_SPAM -> FolderRole.INBOX
                else -> null
            }
            messages.update { list ->
                list.mapNotNull { m ->
                    if (m.threadId !in threadIds) return@mapNotNull m
                    when (action) {
                        MessageAction.MARK_READ -> m.copy(isUnread = false)
                        MessageAction.MARK_UNREAD -> m.copy(isUnread = true)
                        MessageAction.STAR -> m.copy(isStarred = true)
                        MessageAction.UNSTAR -> m.copy(isStarred = false)
                        MessageAction.DELETE_FOREVER -> null
                        else -> folders.firstOrNull { it.accountId == m.accountId && it.role == role }?.let { m.copy(folderId = it.id) } ?: m
                    }
                }
            }
        }

        override suspend fun move(threadIds: Collection<Long>, targetFolderId: Long) =
            messages.update { list -> list.map { if (it.threadId in threadIds) it.copy(folderId = targetFolderId) else it } }

        override suspend fun refresh(mailbox: Mailbox) = delay(700)

        private fun checkName(raw: String): String {
            val name = raw.trim()
            if (name.isEmpty() || name.length > 100 || name.any { it in "/.*%#\\" }) throw FolderException(FolderFailure.INVALID_NAME, "Folder names can't be empty or contain / . * % # \\")
            return name
        }

        override suspend fun createFolder(accountId: Long, parentFolderId: Long?, name: String): Long {
            val n = checkName(name)
            val parent = parentFolderId?.let { id -> folders.firstOrNull { it.id == id } ?: throw FolderException(FolderFailure.NOT_FOUND, "The parent folder no longer exists") }
            val path = parent?.let { it.path + "/" + n } ?: n
            if (folders.any { it.accountId == accountId && it.path.equals(path, true) }) throw FolderException(FolderFailure.ALREADY_EXISTS, "A folder named \"$n\" already exists here")
            val id = nextFolderId++
            foldersState.update { it + Folder(id, accountId, path, n, FolderRole.CUSTOM, 0, 0) }
            return id
        }

        private fun relocate(folderId: Long, newParent: Folder?, name: String) {
            val f = folders.firstOrNull { it.id == folderId } ?: throw FolderException(FolderFailure.NOT_FOUND, "The folder no longer exists")
            if (f.role != FolderRole.CUSTOM) throw FolderException(FolderFailure.PROTECTED_FOLDER, "System folders can't be renamed, moved or deleted")
            if (newParent != null && (newParent.id == f.id || newParent.path.startsWith(f.path + "/"))) throw FolderException(FolderFailure.INVALID_TARGET, "A folder can't be moved into itself or its subfolders")
            val path = newParent?.let { it.path + "/" + name } ?: name
            if (folders.any { it.id != f.id && it.accountId == f.accountId && it.path.equals(path, true) }) throw FolderException(FolderFailure.ALREADY_EXISTS, "A folder named \"$name\" already exists there")
            foldersState.update { list -> list.map { x ->
                when {
                    x.id == f.id -> x.copy(path = path, name = name)
                    x.path.startsWith(f.path + "/") -> x.copy(path = path + x.path.removePrefix(f.path))
                    else -> x
                }
            } }
        }

        override suspend fun renameFolder(folderId: Long, newName: String) {
            val f = folders.firstOrNull { it.id == folderId } ?: throw FolderException(FolderFailure.NOT_FOUND, "The folder no longer exists")
            val parentPath = f.path.removeSuffix(f.name).removeSuffix("/").ifEmpty { null }
            relocate(folderId, parentPath?.let { p -> folders.firstOrNull { it.accountId == f.accountId && it.path == p } }, checkName(newName))
        }

        override suspend fun moveFolder(folderId: Long, newParentFolderId: Long?) {
            val f = folders.firstOrNull { it.id == folderId } ?: throw FolderException(FolderFailure.NOT_FOUND, "The folder no longer exists")
            relocate(folderId, newParentFolderId?.let { id -> folders.firstOrNull { it.id == id } }, f.name)
        }

        override suspend fun deleteFolder(folderId: Long, moveContentsTo: Long?) {
            val f = folders.firstOrNull { it.id == folderId } ?: throw FolderException(FolderFailure.NOT_FOUND, "The folder no longer exists")
            if (f.role != FolderRole.CUSTOM) throw FolderException(FolderFailure.PROTECTED_FOLDER, "System folders can't be deleted")
            if (folders.any { it.path.startsWith(f.path + "/") && it.accountId == f.accountId }) throw FolderException(FolderFailure.HAS_SUBFOLDERS, "Delete or move the subfolders first")
            if (messages.value.any { it.folderId == folderId }) {
                val target = moveContentsTo ?: throw FolderException(FolderFailure.NOT_EMPTY, "This folder contains messages")
                messages.update { list -> list.map { if (it.folderId == folderId) it.copy(folderId = target) else it } }
            }
            foldersState.update { list -> list.filterNot { it.id == folderId } }
        }

        override suspend fun suggestContacts(prefix: String, limit: Int): List<EmailAddress> =
            messages.value.flatMap { listOf(it.from) + it.to }.distinctBy { it.email }
                .filter { it.email.startsWith(prefix, true) || it.name?.startsWith(prefix, true) == true }
                .filter { it.email != me.email }.take(limit)
    }

    override val compose = object : ComposeRepository {
        override val outbox: Flow<List<app.dot.mail.data.model.OutboxItem>> = MutableStateFlow(emptyList())
        override suspend fun retrySend(outboxId: Long, confirmPossibleDuplicate: Boolean): Boolean = false
        override suspend fun deleteOutbox(outboxId: Long): Boolean = false
        override suspend fun saveDraft(draft: Draft): Long {
            val id = draft.localId.takeIf { it > 0 } ?: nextId++
            drafts.update { it + (id to draft.copy(localId = id)) }
            return id
        }
        override suspend fun loadDraft(localId: Long): Draft? = drafts.value[localId]
        override suspend fun discardDraft(localId: Long) = drafts.update { it - localId }
        override suspend fun prepareReply(messageId: Long, mode: ReplyMode): Draft {
            val m = messages.value.first { it.id == messageId }
            val subject = if (mode == ReplyMode.FORWARD) "Fwd: ${m.subject}" else if (m.subject.startsWith("Re:")) m.subject else "Re: ${m.subject}"
            val to = when (mode) {
                ReplyMode.REPLY -> listOf(m.from)
                ReplyMode.REPLY_ALL -> (listOf(m.from) + m.to + m.cc).filter { it.email != me.email }.distinctBy { it.email }
                ReplyMode.FORWARD -> emptyList()
            }
            val quote = m.body?.plainText.orEmpty().lines().joinToString("\n") { "> $it" }
            return Draft(accountId = m.accountId, identityId = null, to = to, subject = subject, bodyText = "\n\nOn ${java.util.Date(m.sentAt)}, ${m.from.displayName} wrote:\n$quote")
        }
        override suspend fun send(draft: Draft, undoWindowMs: Long): Long = draft.localId
        override suspend fun cancelSend(outboxId: Long): Boolean = true
    }

    private fun summaries(list: List<Message>): List<ThreadSummary> = list.groupBy { it.threadId }.map { (id, ms) ->
        val sorted = ms.sortedBy { it.sentAt }
        val last = sorted.last()
        ThreadSummary(
            threadId = id, accountId = last.accountId, subject = sorted.first().subject,
            participants = sorted.map { it.from }.distinctBy { it.email },
            snippet = last.snippet, lastMessageAt = last.sentAt, messageCount = sorted.size,
            unreadCount = sorted.count { it.isUnread }, isStarred = sorted.any { it.isStarred },
            hasAttachments = sorted.any { m -> m.attachments.any { !it.isInline } },
            recipients = last.to,
        )
    }.sortedByDescending { it.lastMessageAt }

    private fun sample(now: Long): List<Message> {
        val h = 3_600_000L
        val d = 24 * h
        var id = 0L
        fun msg(
            thread: Long, folder: Long, from: EmailAddress, subject: String, ago: Long, text: String,
            unread: Boolean = false, starred: Boolean = false, html: String? = null, attachments: List<Pair<String, Long>> = emptyList(),
            account: Long = 1, to: List<EmailAddress> = listOf(me),
        ): Message {
            val mid = ++id
            return Message(
                id = mid, accountId = account, folderId = folder, threadId = thread, from = from, to = to, cc = emptyList(), bcc = emptyList(),
                replyTo = emptyList(), subject = subject, sentAt = now - ago,
                snippet = text.lineSequence().filter { it.isNotBlank() && !it.startsWith(">") }.joinToString(" ").take(140),
                isUnread = unread, isStarred = starred, body = MessageBody(text, html, html?.contains("http") == true),
                attachments = attachments.mapIndexed { i, (n, s) -> Attachment(mid * 10 + i, mid, n, if (n.endsWith(".pdf")) "application/pdf" else "image/jpeg", s, null, false, null) },
                messageIdHeader = "<$mid@demo>", references = emptyList(), unsubscribe = null,
            )
        }
        val anna = EmailAddress("anna.keller@example.ch", "Anna Keller")
        val marc = EmailAddress("marc@atelier.example", "Marc Dubois")
        val lea = EmailAddress("lea.martin@example.fr", "Léa Martin")
        val bank = EmailAddress("no-reply@bank.example", "Alpine Bank")
        val rail = EmailAddress("tickets@rail.example", "Swiss Rail")
        val news = EmailAddress("letters@slowread.example", "The Slow Read")
        val sam = EmailAddress("sam@studio.example", "Sam Okafor")
        return listOf(
            msg(1, 10, anna, "Saturday hike — final plan", 5 * h, "Hi Robin,\n\nWe're meeting at 8:15 at the station, platform 7. Bring layers, it'll be cold at the top.\n\nAnna", unread = true),
            msg(1, 10, me, "Re: Saturday hike — final plan", 4 * h, "Perfect, I'll bring coffee for everyone.\n\n> We're meeting at 8:15 at the station, platform 7.", to = listOf(anna)),
            msg(1, 10, marc, "Re: Saturday hike — final plan", 40 * 60_000L, "Count me in. Can someone grab an extra ticket for my brother?\n\nMarc", unread = true, to = listOf(anna, me)),
            msg(2, 10, rail, "Your e-ticket: Lausanne → Zermatt", 9 * h, "Your journey on Saturday, 8:32 from Lausanne.\nSeat 64, coach 3.\n\nShow the attached ticket on board.", attachments = listOf("ticket-0832.pdf" to 184_000L), starred = true),
            msg(3, 10, lea, "Photos from the weekend", 1 * d + 2 * h, "A few favourites attached — the light on Sunday morning was unreal.\n\nLéa", attachments = listOf("lake.jpg" to 2_400_000L, "ridge.jpg" to 3_100_000L), unread = true),
            msg(4, 10, news, "Issue 112: In praise of boring software", 2 * d, "This week: why the tools we trust most are the ones we never think about.",
                html = "<h2 style='font-family:Georgia'>In praise of boring software</h2><p>This week: why the tools we trust most are the ones we never think about.</p><p><img src='https://track.example/pixel.gif' width=1 height=1></p><p><a href='https://slowread.example/112'>Read online</a></p>"),
            msg(5, 10, bank, "Your November statement is ready", 3 * d, "Your statement for November is now available in online banking."),
            msg(6, 10, marc, "Studio keys", 6 * d, "Left the keys with the concierge. Let me know when you've picked them up."),
            msg(7, 16, EmailAddress("orders@shop.example", "Shop"), "Order #48213 confirmed", 12 * d, "Thanks for your order. Estimated delivery: Thursday."),
            msg(8, 11, me, "Draft agenda for Monday", 2 * d, "Here's a first pass at Monday's agenda.", to = listOf(sam)),
            msg(9, 20, sam, "Moodboard v3", 3 * h, "Updated the palette — less blue, more graphite. Thoughts?", unread = true, account = 2, to = listOf(EmailAddress("robin@studio.example"))),
            msg(10, 20, EmailAddress("billing@hosting.example", "Hosting"), "Invoice INV-2026-11", 4 * d, "Your invoice is attached.", attachments = listOf("INV-2026-11.pdf" to 92_000L), account = 2, to = listOf(EmailAddress("robin@studio.example"))),
        )
    }
}
