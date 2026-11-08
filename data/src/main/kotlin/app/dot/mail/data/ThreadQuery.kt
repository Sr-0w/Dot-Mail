package app.dot.mail.data

import app.dot.mail.data.model.FolderRole
import app.dot.mail.data.model.InboxSort
import app.dot.mail.data.model.SmartFolder
import app.dot.mail.data.model.ThreadFilters

/** A SQL fragment with its bound arguments. */
internal data class Sql(val text: String, val args: List<Any> = emptyList())

/**
 * Builds the conversation-list WHERE clauses for quick filters and smart folders (over `threads t`), and the ORDER BY
 * for a sort choice. Pure strings and arguments, so the builder is unit-tested without a database.
 */
internal object ThreadQuery {
    private val EXCLUDED = "('TRASH','SPAM','DRAFTS')"

    private fun like(value: String): String = "%" + value.trim().lowercase().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"

    /** Conditions for [filters]; [ownEmails] are my addresses (for "to me"). */
    fun filters(filters: ThreadFilters, ownEmails: Collection<String>): Sql? {
        val parts = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (filters.unread) parts += "t.unread>0"
        if (filters.starred) parts += "t.starred=1"
        if (filters.attachments) parts += "t.attachments=1"
        if (filters.toMe) {
            val mine = ownEmails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
            if (mine.isEmpty()) parts += "0" else {
                parts += "EXISTS(SELECT 1 FROM messages m WHERE m.threadId=t.id AND (" + mine.joinToString(" OR ") { "m.recipientSearch LIKE ? ESCAPE '!'" } + "))"
                args.addAll(mine.map { like(it) })
            }
        }
        if (filters.fromContacts) {
            parts += "EXISTS(SELECT 1 FROM messages m JOIN contacts c ON c.accountId=m.accountId AND instr(lower(m.senderSearch), lower(c.email))>0 WHERE m.threadId=t.id)"
        }
        return if (parts.isEmpty()) null else Sql(parts.joinToString(" AND "), args)
    }

    /** The WHERE fragment (over `threads t`) selecting the conversations that have a message matching [folder]. */
    fun smartFolder(folder: SmartFolder): Sql {
        val parts = mutableListOf("m.threadId=t.id")
        val args = mutableListOf<Any>()
        if (folder.accountIds.isNotEmpty()) {
            parts += "m.accountId IN (" + folder.accountIds.joinToString(",") { "?" } + ")"
            args.addAll(folder.accountIds)
        }
        val roles = folder.roles.filter { it != FolderRole.CUSTOM }
        if (roles.isNotEmpty()) {
            parts += "f.role IN (" + roles.joinToString(",") { "?" } + ")"
            args.addAll(roles.map { it.name })
        } else parts += "f.role NOT IN $EXCLUDED"
        fun text(column: String, value: String) { if (value.isNotBlank()) { parts += "lower($column) LIKE ? ESCAPE '!'"; args += like(value) } }
        text("m.senderSearch", folder.from)
        text("m.recipientSearch", folder.to)
        text("m.subject", folder.subject)
        folder.category?.let { parts += "m.header LIKE ?"; args += "%\"category\":\"${it.name}\"%" }
        if (folder.unreadOnly) parts += "m.unread=1"
        if (folder.starredOnly) parts += "m.starred=1"
        if (folder.hasAttachment) parts += "m.hasAttachments=1"
        folder.afterMillis?.let { parts += "m.sentAt>=?"; args += it }
        folder.beforeMillis?.let { parts += "m.sentAt<?"; args += it }
        return Sql("EXISTS(SELECT 1 FROM messages m JOIN folders f ON f.id=m.folderId WHERE ${parts.joinToString(" AND ")})", args)
    }

    /** ORDER BY over the outer query (columns `sortAt`, `unread`, `starred`, `participants`, `id`). */
    fun orderBy(sort: InboxSort): String = when (sort) {
        InboxSort.DATE -> "sortAt DESC,id DESC"
        InboxSort.UNREAD_FIRST -> "(unread>0) DESC,sortAt DESC,id DESC"
        InboxSort.STARRED_FIRST -> "starred DESC,sortAt DESC,id DESC"
        InboxSort.SENDER -> "lower(participants) ASC,sortAt DESC,id DESC"
    }

    /** Combines SQL fragments with AND (null parts are skipped). */
    fun and(vararg parts: Sql?): Sql {
        val present = parts.filterNotNull()
        return Sql(present.joinToString(" AND ") { "(${it.text})" }.ifEmpty { "1" }, present.flatMap { it.args })
    }
}
