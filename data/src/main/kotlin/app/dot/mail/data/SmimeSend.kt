package app.dot.mail.data

import app.dot.mail.data.model.Draft
import app.dot.mail.engine.OutgoingMail
import app.dot.mail.engine.SmimeEngine
import app.dot.mail.engine.SmimeMessageVariant
import app.dot.mail.engine.SmimeMode
import app.dot.mail.engine.SmimeSigningIdentity
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.cert.X509Certificate

/** The account's S/MIME key material, supplied by the app from Android KeyChain. */
interface SmimeKeyAccess {
    /** Signing key and chain; throws [SmimeUnavailableException] when not configured, the alias vanished or access was denied. */
    suspend fun signingIdentity(accountId: Long): SmimeSigningIdentity

    /** The certificate that lets the sender read their own Sent copy; same failure contract. */
    suspend fun decryptionCertificate(accountId: Long): X509Certificate
}

/** S/MIME cannot be used right now ("S/MIME unavailable, pick again"). Nothing is queued, and nothing is ever sent unprotected instead. */
class SmimeUnavailableException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { NOT_CONFIGURED, KEY_MISSING, ACCESS_DENIED }
}

/** Turns one outgoing message into the protected per-recipient messages that the outbox stores. */
fun interface SmimeBuilder {
    suspend fun build(mail: OutgoingMail, mode: SmimeMode, accountId: Long): List<SmimeMessageVariant>
}

/** [SmimeEngine] + KeyChain keys + the recipient certificate store. A pinned certificate wins; several unpinned ones are ambiguous. */
internal class EngineSmimeBuilder(
    private val engine: SmimeEngine,
    private val keys: SmimeKeyAccess,
    private val certs: SmimeCertStore,
) : SmimeBuilder {
    override suspend fun build(mail: OutgoingMail, mode: SmimeMode, accountId: Long): List<SmimeMessageVariant> {
        val signing = mode == SmimeMode.SIGN || mode == SmimeMode.SIGN_AND_ENCRYPT
        val encrypting = mode == SmimeMode.ENCRYPT || mode == SmimeMode.SIGN_AND_ENCRYPT
        val signer = if (signing) keys.signingIdentity(accountId) else null
        val own = if (encrypting) keys.decryptionCertificate(accountId) else null
        val recipients = if (encrypting) (mail.to + mail.cc + mail.bcc).map { SmimeCertStore.normalize(it.email) }.distinct().flatMap { box ->
            val all = certs.candidates(accountId, box)
            val pinned = certs.pinnedFingerprint(accountId, box)
            if (pinned != null) all.filter { SmimeCertStore.fingerprint(it.certificate) == pinned } else all
        } else emptyList()
        return engine.build(mail, mode, signer, recipients, own)
    }
}

/**
 * On-disk form of a protected S/MIME outbox item: a directory (`outbox/<account>/<id>.smime`) with one MIME file per SMTP
 * envelope recipient and a manifest that records, per variant, whether it was submitted. Delivery sends each variant to its
 * own envelope recipient and writes the manifest after every step, so a retry never re-sends a delivered variant, and an
 * interrupted submission is only ever repeated after the user confirmed a possible duplicate.
 */
internal object SmimeOutbox {
    const val MANIFEST = "manifest.json"
    const val PENDING = "PENDING"
    const val SUBMITTING = "SUBMITTING"
    const val SENT = "SENT"

    class Variant(val to: String, val file: String, var state: String)
    class Manifest(val sentCopy: String, val variants: List<Variant>)

    /** The directory when [path] is an S/MIME outbox item, else null (ordinary and PGP items are single files). */
    fun dir(path: String?): File? = path?.let(::File)?.takeIf { it.isDirectory && File(it, MANIFEST).isFile }

    fun read(dir: File): Manifest {
        val json = JSONObject(File(dir, MANIFEST).readText())
        val vs = json.getJSONArray("variants")
        return Manifest(json.getString("sentCopy"), List(vs.length()) { i -> vs.getJSONObject(i).let { Variant(it.getString("to"), it.getString("file"), it.getString("state")) } })
    }

    fun write(dir: File, m: Manifest) {
        val json = JSONObject().put("sentCopy", m.sentCopy).put("variants", JSONArray().also { a -> m.variants.forEach { a.put(JSONObject().put("to", it.to).put("file", it.file).put("state", it.state)) } })
        val tmp = File(dir, "$MANIFEST.tmp")
        try {
            tmp.writeText(json.toString())
            java.nio.file.Files.move(tmp.toPath(), File(dir, MANIFEST).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { tmp.delete() }
    }

    /** The file appended to Sent: the first variant (its CMS envelope also contains the sender's own certificate). */
    fun sentCopy(path: String): File = dir(path)?.let { File(it, read(it).sentCopy) } ?: File(path)

    /** Deletes an outbox item's file or directory. */
    fun delete(path: String?) { if (path != null) File(path).deleteRecursively() }

    /** The user confirmed a possible duplicate: variants whose submission was interrupted are sent again; delivered ones never are. */
    fun confirmRetry(path: String?) {
        val dir = dir(path) ?: return
        val m = read(dir)
        m.variants.filter { it.state == SUBMITTING }.forEach { it.state = PENDING }
        write(dir, m)
    }

    /** Writes the variants and the manifest into a fresh directory next to the final one, then moves it into place. */
    fun create(parent: File, id: Long, variants: List<SmimeMessageVariant>): File {
        require(variants.isNotEmpty()) { "No S/MIME variants" }
        val target = File(parent, "$id.smime")
        val staging = File(parent, "$id.smime.tmp").also { it.deleteRecursively(); it.mkdirs() }
        val entries = variants.mapIndexed { i, v -> File(staging, "v$i.eml").also { f -> f.writeBytes(v.mimeBytes) }; Variant(v.envelopeRecipient, "v$i.eml", PENDING) }
        write(staging, Manifest(entries.first().file, entries))
        target.deleteRecursively()
        check(staging.renameTo(target)) { "Could not persist protected MIME" }
        return target
    }
}

/**
 * Builds the S/MIME messages now (the outbox must hold the already protected bytes: the delivery worker cannot reach the
 * user or KeyChain) and queues them. Anything that goes wrong - missing key, missing or untrusted recipient certificate -
 * throws before anything is queued: there is no silent downgrade to plaintext. Delivery refuses to rebuild a protected draft
 * in plain form (see deliverOutbox), so a lost directory can never turn into an unprotected send.
 */
internal suspend fun RealMailGraph.queueSmime(draft: Draft, undoWindowMs: Long, builder: SmimeBuilder): Long {
    require(draft.smimeSign || draft.smimeEncrypt) { "Nothing to protect" }
    require(!(draft.pgpSign || draft.pgpEncrypt)) { "Choose either OpenPGP or S/MIME" }
    require(draft.to.isNotEmpty() || draft.cc.isNotEmpty() || draft.bcc.isNotEmpty()) { "At least one recipient is required" }
    val account = requireNotNull(dao.account(draft.accountId))
    val frozen = withContext(Dispatchers.IO) { cacheAttachments(draft).first }
    val identity = identityStore.resolve(account.id, account.name, account.email).let { all -> all.firstOrNull { it.id == frozen.identityId } ?: all.first { it.isDefault } }
    val mail = withContext(Dispatchers.IO) { pgpOutgoing(account.email, account.name, frozen, identity, "<dotmail-smime-${java.util.UUID.randomUUID()}@${account.email.substringAfter('@')}>") }
    val mode = when { frozen.smimeSign && frozen.smimeEncrypt -> SmimeMode.SIGN_AND_ENCRYPT; frozen.smimeSign -> SmimeMode.SIGN; else -> SmimeMode.ENCRYPT }
    val variants = builder.build(mail, mode, account.id)
    return withContext(Dispatchers.IO) {
        val due = maxOf(System.currentTimeMillis() + undoWindowMs.coerceIn(0, 60_000), frozen.scheduledAt ?: 0)
        val id = database.withTransaction {
            val id = dao.insertOutbox(app.dot.mail.data.local.OutboxRow(accountId = draft.accountId, draft = Codec.draft(frozen), dueAt = due))
            if (draft.localId != 0L) removeDraftAndSourcePublic(draft.localId)
            id
        }
        val dir = SmimeOutbox.create(File(context.filesDir, "outbox/${account.id}").also { it.mkdirs() }, id, variants)
        dao.outboxPath(id, dir.absolutePath)
        if (scheduling) WorkScheduling.outbox(context, id, due)
        if (draft.localId != 0L) requestSync()
        id
    }
}
