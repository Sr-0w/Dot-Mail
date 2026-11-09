package app.dot.mail.data

import android.content.Context
import app.dot.mail.data.local.*
import app.dot.mail.data.model.MessageBody
import java.io.File

/** Bodies live in private files. Metadata queries never project potentially enormous legacy columns. */
internal class BodyStore(private val context: Context, private val dao: MailDao) {
    private val displayLimit = 512 * 1024
    private fun prefix(file: File?): Pair<String?, Boolean> {
        if (file?.isFile != true) return null to false
        return file.reader(Charsets.UTF_8).use { input ->
            val chars=CharArray(displayLimit+1); var count=0
            while(count<chars.size) { val n=input.read(chars,count,chars.size-count); if(n<0) break; count+=n }
            String(chars,0,minOf(count,displayLimit)) to (count>displayLimit)
        }
    }
    suspend fun read(row: MessageRow): MessageBody? {
        if (!row.bodyLoaded) return null
        if (row.bodyPath != null) {
            val dir=File(row.bodyPath)
            if (legacySmime(row.id, dir)) return null
            val (text,cutText)=prefix(File(dir,"plain.txt")); val(html,cutHtml)=prefix(File(dir,"body.html"))
            return MessageBody(text,html,row.remoteImages,isTruncated=cutText||cutHtml,pgp=pgp(dir,text),smime=smime(dir))
        }
        val text=StringBuilder(); val html=StringBuilder(); var offset=1; var truncated=false
        while(text.length<=displayLimit && html.length<=displayLimit) {
            val chunk=dao.bodyChunk(row.id,offset,32_768)
            val t=chunk?.text.orEmpty(); val h=chunk?.html.orEmpty()
            if(t.isEmpty() && h.isEmpty()) break
            if(text.length<displayLimit) text.append(t.take(displayLimit-text.length)) else if(t.isNotEmpty()) truncated=true
            if(html.length<displayLimit) html.append(h.take(displayLimit-html.length)) else if(h.isNotEmpty()) truncated=true
            offset+=32_768
            if(offset>displayLimit) { truncated=truncated||t.length==32_768||h.length==32_768; break }
        }
        return MessageBody(text.toString().takeIf { it.isNotEmpty() },html.toString().takeIf { it.isNotEmpty() },row.remoteImages,isTruncated=truncated)
    }

    /**
     * True for a body cached before the S/MIME parser existed: its signature or envelope was stored as an ordinary
     * attachment, so it would never be verified or decrypted. Treated as not loaded, so the reader fetches it again and
     * [Mime] stores it in the current layout. Bodies cached since carry [FORMAT_MARKER]; legacy bodies without S/MIME get
     * the marker on first read, so this costs one attachment query per body, once.
     */
    suspend fun legacySmime(id: Long, dir: File): Boolean {
        val marker = File(dir, FORMAT_MARKER)
        if (marker.exists() || !dir.isDirectory) return false
        val smimeFiles = listOf(app.dot.mail.engine.SmimeParts.SIGNED_FILE, app.dot.mail.engine.SmimeParts.ENCRYPTED_FILE, app.dot.mail.engine.SmimeParts.OPAQUE_FILE)
        val legacy = smimeFiles.none { File(dir, it).isFile } && dao.attachments(id).any { it.mime.substringBefore(';').trim().lowercase() in SMIME_TYPES }
        if (!legacy) runCatching { marker.writeText("") }
        return legacy
    }

    private fun smime(dir: File): app.dot.mail.data.model.SmimeContent? {
        val signed = File(dir, app.dot.mail.engine.SmimeParts.SIGNED_FILE); val signature = File(dir, app.dot.mail.engine.SmimeParts.SIGNATURE_FILE)
        val envelope = File(dir, app.dot.mail.engine.SmimeParts.ENCRYPTED_FILE)
        if (envelope.isFile) return app.dot.mail.data.model.SmimeContent.Encrypted(envelope.path)
        val opaque = File(dir, app.dot.mail.engine.SmimeParts.OPAQUE_FILE)
        if (opaque.isFile) return app.dot.mail.data.model.SmimeContent.Opaque(opaque.path)
        return if (signed.isFile && signature.isFile) app.dot.mail.data.model.SmimeContent.Signed(signed.path, signature.path) else null
    }

    private fun pgp(dir: File, text: String?): app.dot.mail.data.model.PgpContent? {
        if (File(dir, ".unsupported-encryption").isFile) return app.dot.mail.data.model.PgpContent.Unsupported
        val encrypted = File(dir, app.dot.mail.engine.Pgp.ENCRYPTED_FILE)
        if (encrypted.isFile) return app.dot.mail.data.model.PgpContent.Encrypted(encrypted.path)
        val signed = File(dir, app.dot.mail.engine.Pgp.SIGNED_FILE); val signature = File(dir, app.dot.mail.engine.Pgp.SIGNATURE_FILE)
        if (signed.isFile && signature.isFile) return app.dot.mail.data.model.PgpContent.Signed(signed.path, signature.path)
        return when (app.dot.mail.engine.Pgp.inline(text)) {
            app.dot.mail.engine.Pgp.Inline.ENCRYPTED -> app.dot.mail.data.model.PgpContent.InlineEncrypted
            app.dot.mail.engine.Pgp.Inline.SIGNED -> app.dot.mail.data.model.PgpContent.InlineSigned
            null -> null
        }
    }

    companion object {
        /** Written into every body directory cached with the S/MIME-aware parser. */
        const val FORMAT_MARKER = ".format-smime"
        private val SMIME_TYPES = setOf("application/pkcs7-signature", "application/x-pkcs7-signature", "application/pkcs7-mime", "application/x-pkcs7-mime")
    }
}
