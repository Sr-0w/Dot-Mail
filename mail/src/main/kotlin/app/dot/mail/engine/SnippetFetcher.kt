package app.dot.mail.engine

import app.dot.mail.engine.text.Snippets
import jakarta.mail.MultipartDataSource
import jakarta.mail.Part
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimePart
import jakarta.mail.internet.MimeUtility
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.protocol.BODY
import org.eclipse.angus.mail.imap.protocol.FetchResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

internal object SnippetFetcher {
    private data class TextPart(val part: Part, val section: String, val html: Boolean)
    private fun parts(part: Part, section: String = "", depth: Int = 0): List<TextPart> {
        if (depth >= 40 || part.disposition.equals(Part.ATTACHMENT, true) || part.fileName != null) return emptyList()
        if (part.isMimeType("text/plain") || part.isMimeType("text/html")) return listOf(TextPart(part, section.ifEmpty { "1" }, part.isMimeType("text/html")))
        if (!part.isMimeType("multipart/*")) return emptyList()
        val source = part.dataHandler.dataSource as? MultipartDataSource ?: return emptyList()
        return (0 until source.count).flatMap { index -> parts(source.getBodyPart(index), if (section.isEmpty()) "${index + 1}" else "$section.${index + 1}", depth + 1) }
    }
    fun fetch(folder:IMAPFolder,message:jakarta.mail.Message,uid:Long,maxBytes:Int,fallbackToHtml:Boolean=true):String {
        val available=parts(message)
        val plain=available.firstOrNull { !it.html }
        val html=available.firstOrNull { it.html }
        if(plain==null) return html?.let { Snippets.fromHtml(readPart(folder,it,uid,maxBytes)) }.orEmpty()
        val text=readPart(folder,plain,uid,maxBytes)
        val preview=Snippets.fromPlainText(text)
        if(!fallbackToHtml || html==null || !Snippets.needsHtmlFallback(preview)) return preview
        // At most one additional bounded HTML prefix (max 64 KiB), only for damaged plain alternatives.
        return Snippets.fromBody(text,readPart(folder,html,uid,(maxBytes*32).coerceAtMost(65_536)))
    }
    private fun readPart(folder:IMAPFolder,chosen:TextPart,uid:Long,maxBytes:Int):String {
        val bytes = folder.doCommand { protocol ->
            // UID addresses survive unrelated concurrent EXPUNGE events. Section comes only from BODYSTRUCTURE traversal.
            val responses = protocol.command("UID FETCH $uid (BODY.PEEK[${chosen.section}]<0.$maxBytes>)", null)
            val body = responses.filterIsInstance<FetchResponse>().firstNotNullOfOrNull { it.getItem(BODY::class.java) }
            val data = body?.byteArrayInputStream?.use { it.readBytes().take(maxBytes).toByteArray() } ?: byteArrayOf()
            protocol.notifyResponseHandlers(responses)
            protocol.handleResult(responses.last())
            data
        } as ByteArray
        val encoding = (chosen.part as? MimePart)?.encoding ?: "7bit"
        val decoded = ByteArrayOutputStream()
        // A bounded prefix may end inside a base64 quartet or quoted-printable escape. Keep its valid prefix.
        runCatching {
            MimeUtility.decode(ByteArrayInputStream(bytes), encoding).use { input ->
                while (decoded.size() < maxBytes) { val value = input.read(); if (value < 0) break; decoded.write(value) }
            }
        }
        val charset = runCatching { ContentType(chosen.part.contentType).getParameter("charset")?.let { charset(it) } }.getOrNull() ?: Charsets.UTF_8
        val bytesDecoded=decoded.toByteArray()
        val output=java.nio.CharBuffer.allocate(bytesDecoded.size)
        val decoder=charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
        // endOfInput=false leaves a cut UTF-8/UTF-16 suffix unconsumed instead of emitting a broken character.
        decoder.decode(java.nio.ByteBuffer.wrap(bytesDecoded),output,false)
        output.flip()
        return output.toString()
    }
}
