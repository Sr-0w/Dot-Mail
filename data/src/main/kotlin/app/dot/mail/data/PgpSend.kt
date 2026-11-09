package app.dot.mail.data

import android.net.Uri
import android.provider.OpenableColumns
import app.dot.mail.data.model.Draft
import app.dot.mail.engine.Address
import app.dot.mail.engine.OutgoingAttachment
import app.dot.mail.engine.OutgoingMail
import app.dot.mail.engine.PgpMime
import app.dot.mail.engine.text.EmailHtml
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** What OpenKeychain produced for one message: a detached signature, or the ciphertext of (signed and) encrypted data. */
class PgpProtected(val signature: ByteArray? = null, val ciphertext: ByteArray? = null, val micalg: String? = null)

/**
 * Signs and/or encrypts the canonical MIME [entity] of an outgoing message. Implemented in the app on top of OpenKeychain,
 * where it may suspend while the user confirms a passphrase or key. [recipients] are all To/Cc/Bcc addresses plus the
 * sender, so the sender can read their own Sent copy.
 */
fun interface PgpProtector {
    suspend fun protect(entity: ByteArray, sign: Boolean, encrypt: Boolean, recipients: List<String>): PgpProtected
}

/**
 * Builds the PGP/MIME message now (the outbox must hold the already protected bytes: the delivery worker cannot talk to the
 * user), stores it as the outbox row's MIME file and queues it. Delivery refuses to rebuild a protected draft in plain
 * form (see deliverOutbox), so a lost file can never turn into an unprotected send.
 */
internal suspend fun RealMailGraph.queueProtected(draft: Draft, undoWindowMs: Long, protector: PgpProtector): Long {
    require(draft.pgpSign || draft.pgpEncrypt) { "Nothing to protect" }
    require(draft.to.isNotEmpty() || draft.cc.isNotEmpty() || draft.bcc.isNotEmpty()) { "At least one recipient is required" }
    val account = requireNotNull(dao.account(draft.accountId))
    val frozen = withContext(Dispatchers.IO) { cacheAttachments(draft).first }
    val identity = identityStore.resolve(account.id, account.name, account.email).let { all -> all.firstOrNull { it.id == frozen.identityId } ?: all.first { it.isDefault } }
    val mail = withContext(Dispatchers.IO) { pgpOutgoing(account.email, account.name, frozen, identity, "<dotmail-pgp-${UUID.randomUUID()}@${account.email.substringAfter('@')}>") }
    val entity = PgpMime.entity(mail)
    val recipients = (frozen.to + frozen.cc + frozen.bcc).map { it.email } + identity.email
    val protected = protector.protect(entity, frozen.pgpSign, frozen.pgpEncrypt, recipients.distinctBy { it.lowercase() })
    val bytes = if (frozen.pgpEncrypt) PgpMime.assembleEncrypted(mail, requireNotNull(protected.ciphertext) { "No ciphertext" })
    else PgpMime.assembleSigned(mail, entity, requireNotNull(protected.signature) { "No signature" }, protected.micalg ?: PgpMime.MICALG)
    return withContext(Dispatchers.IO) {
        val due = maxOf(System.currentTimeMillis() + undoWindowMs.coerceIn(0, 60_000), frozen.scheduledAt ?: 0)
        val id = database.withTransaction {
            val id = dao.insertOutbox(app.dot.mail.data.local.OutboxRow(accountId = draft.accountId, draft = Codec.draft(frozen), dueAt = due))
            if (draft.localId != 0L) removeDraftAndSourcePublic(draft.localId)
            id
        }
        val file = File(context.filesDir, "outbox/${account.id}/$id.eml").also { it.parentFile!!.mkdirs() }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try { temporary.writeBytes(bytes); check(temporary.renameTo(file)) { "Could not persist protected MIME" } } finally { temporary.delete() }
        dao.outboxPath(id, file.absolutePath)
        if (scheduling) WorkScheduling.outbox(context, id, due)
        if (draft.localId != 0L) requestSync()
        id
    }
}

internal suspend fun RealMailGraph.pgpOutgoing(accountEmail: String, accountName: String, draft: Draft, identity: app.dot.mail.data.model.Identity, messageId: String): OutgoingMail {
    val attachments = draft.attachmentUris.map { value ->
        val uri = Uri.parse(value)
        val known = dao.attachmentUri(value)
        val name = known?.name ?: context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "attachment"
        val type = known?.mime ?: context.contentResolver.getType(uri) ?: "application/octet-stream"
        // Read now: the protected message is built once and must not depend on a content URI later.
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw java.io.IOException("Cached attachment unavailable")
        OutgoingAttachment(name, type, bytes)
    }
    val html = draft.bodyHtml?.let { EmailHtml.compose(it) }
    return OutgoingMail(
        Address(identity.email, identity.name.ifBlank { accountName }),
        draft.to.map { Address(it.email, it.name) }, draft.cc.map { Address(it.email, it.name) }, draft.bcc.map { Address(it.email, it.name) },
        draft.subject,
        draft.bodyHtml?.let { draft.bodyText.ifBlank { EmailHtml.toPlainText(it) } } ?: draft.bodyText,
        html = html,
        attachments = attachments + SignatureLogos.attachmentsFor(context, draft.bodyHtml, identityStore.resolve(draft.accountId, accountName, accountEmail), identity),
        inReplyTo = draft.inReplyTo, references = draft.references, replyTo = listOfNotNull(identity.replyTo?.let { Address(it) }),
        messageId = messageId, date = java.time.Instant.now(),
    )
}
