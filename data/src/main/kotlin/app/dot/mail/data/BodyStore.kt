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
            val (text,cutText)=prefix(File(dir,"plain.txt")); val(html,cutHtml)=prefix(File(dir,"body.html"))
            return MessageBody(text,html,row.remoteImages,isTruncated=cutText||cutHtml,pgp=pgp(dir,text))
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

    private fun pgp(dir: File, text: String?): app.dot.mail.data.model.PgpContent? {
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
}
