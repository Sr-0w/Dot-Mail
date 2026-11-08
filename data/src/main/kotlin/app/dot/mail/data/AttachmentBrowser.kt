package app.dot.mail.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.sqlite.db.SimpleSQLiteQuery
import app.dot.mail.data.local.AttachmentListRow
import app.dot.mail.data.model.Attachment
import app.dot.mail.data.model.AttachmentItem
import app.dot.mail.data.model.AttachmentKind
import app.dot.mail.data.model.EmailAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val IS_DOCUMENT = "((a.mime LIKE 'text/%' AND a.mime <> 'text/calendar') OR a.mime LIKE 'application/msword%' OR a.mime LIKE 'application/vnd.openxmlformats%' " +
    "OR a.mime LIKE 'application/vnd.ms-%' OR a.mime LIKE 'application/vnd.oasis.opendocument%' OR a.mime = 'application/rtf')"

/** SQL predicate on alias `a` (attachments) for [kind]; mirrors [AttachmentKind.of]. */
internal fun attachmentKindSql(kind: AttachmentKind): String = when (kind) {
    AttachmentKind.IMAGES -> "a.mime LIKE 'image/%'"
    AttachmentKind.PDFS -> "a.mime = 'application/pdf'"
    AttachmentKind.DOCUMENTS -> "($IS_DOCUMENT AND a.mime NOT LIKE 'image/%' AND a.mime <> 'application/pdf')"
    AttachmentKind.OTHER -> "(a.mime NOT LIKE 'image/%' AND a.mime <> 'application/pdf' AND NOT $IS_DOCUMENT)"
}

internal fun RealMailGraph.attachmentsPaging(kind: AttachmentKind?, query: String): Flow<PagingData<AttachmentItem>> {
    val where = StringBuilder("a.inline = 0 AND m.folderId NOT IN (SELECT id FROM folders WHERE role IN ('TRASH','SPAM'))")
    val args = mutableListOf<Any>()
    if (kind != null) where.append(" AND ").append(attachmentKindSql(kind))
    val q = query.trim()
    if (q.isNotEmpty()) {
        val like = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        where.append(" AND (a.name LIKE ? ESCAPE '!' OR m.senderSearch LIKE ? ESCAPE '!')")
        args += like; args += like
    }
    val sql = "SELECT a.id AS id, a.messageId AS messageId, a.partId AS partId, a.name AS name, a.mime AS mime, a.size AS size, a.cid AS cid, a.inline AS inline, a.localUri AS localUri, " +
        "m.accountId AS accountId, m.sentAt AS sentAt, m.header AS header, m.subject AS subject " +
        "FROM attachments a JOIN messages m ON m.id = a.messageId WHERE $where ORDER BY m.sentAt DESC, a.id DESC"
    return Pager(PagingConfig(pageSize = 40)) { dao.pageAttachments(SimpleSQLiteQuery(sql, args.toTypedArray())) }
        .flow.map { data -> data.map(::attachmentItem) }
}

internal fun attachmentItem(r: AttachmentListRow) = AttachmentItem(
    attachment = Attachment(r.id, r.messageId, r.name, r.mime, r.size, r.cid, r.inline, r.localUri),
    accountId = r.accountId,
    from = Codec.addressField(r.header, "from").firstOrNull() ?: EmailAddress(""),
    subject = r.subject, sentAt = r.sentAt,
)
