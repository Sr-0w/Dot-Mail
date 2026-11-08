package app.dot.mail.engine

import jakarta.activation.DataHandler
import jakarta.activation.MailcapCommandMap
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.internet.MimeUtility
import jakarta.mail.util.ByteArrayDataSource
import java.util.Date
import java.util.Properties

object Mime {
    // Explicit map avoids Android's absent desktop activation handlers and resource lookup ambiguity.
    private val handlers = MailcapCommandMap().apply {
        addMailcap("text/plain;; x-java-content-handler=org.eclipse.angus.mail.handlers.text_plain")
        addMailcap("text/html;; x-java-content-handler=org.eclipse.angus.mail.handlers.text_html")
        addMailcap("multipart/*;; x-java-content-handler=org.eclipse.angus.mail.handlers.multipart_mixed")
        addMailcap("message/rfc822;; x-java-content-handler=org.eclipse.angus.mail.handlers.message_rfc822")
    }
    private fun address(a: Address): InternetAddress {
        require(listOfNotNull(a.email, a.name).none { '\r' in it || '\n' in it }) { "Address contains a header newline" }
        return InternetAddress(a.email, a.name, "UTF-8").apply { validate() }
    }
    fun build(mail: OutgoingMail, session: Session = Session.getInstance(Properties())): MimeMessage {
        require('\r' !in mail.subject && '\n' !in mail.subject) { "Subject contains a header newline" }
        mail.attachments.forEach { attachment ->
            require('\r' !in attachment.name && '\n' !in attachment.name)
            require(attachment.mimeType.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))) { "Invalid MIME type" }
        }
        val message = object : MimeMessage(session) {
            override fun updateMessageID() {
                if (mail.messageId != null) setHeader("Message-ID", mail.messageId) else super.updateMessageID()
            }
        }
        message.setFrom(address(mail.from))
        if (mail.replyTo.isNotEmpty()) message.setReplyTo(mail.replyTo.map(::address).toTypedArray())
        listOf(Message.RecipientType.TO to mail.to, Message.RecipientType.CC to mail.cc, Message.RecipientType.BCC to mail.bcc).forEach { (type, list) ->
            message.setRecipients(type, list.map(::address).toTypedArray())
        }
        message.setSubject(mail.subject, "UTF-8")
        message.sentDate = Date.from(mail.date)
        require(listOfNotNull(mail.inReplyTo, mail.messageId).plus(mail.references).none { '\r' in it || '\n' in it })
        mail.inReplyTo?.let { message.setHeader("In-Reply-To", it) }
        if (mail.references.isNotEmpty()) message.setHeader("References", mail.references.joinToString(" "))
        val text = MimeBodyPart().apply { setText(mail.text, "UTF-8") }
        val alternative = if (mail.html != null || mail.calendarReply != null) MimeBodyPart().apply {
            setContent(MimeMultipart("alternative").apply {
                addBodyPart(text)
                mail.html?.let { html -> addBodyPart(MimeBodyPart().apply { setContent(html, "text/html; charset=UTF-8"); dataHandler.setCommandMap(handlers) }) }
                mail.calendarReply?.let { calendar -> addBodyPart(MimeBodyPart().apply {
                    dataHandler = DataHandler(ByteArrayDataSource(calendar, "text/calendar; charset=UTF-8; method=REPLY")).apply { setCommandMap(handlers) }
                    setHeader("Content-Transfer-Encoding", "base64")
                }) }
            }); dataHandler.setCommandMap(handlers)
        } else text
        fun part(attachment: OutgoingAttachment) = MimeBodyPart().apply {
            dataHandler = DataHandler(attachment.source ?: ByteArrayDataSource(attachment.bytes, attachment.mimeType)).apply { setCommandMap(handlers) }
            setHeader("Content-Transfer-Encoding", "base64")
            fileName = MimeUtility.encodeText(attachment.name, "UTF-8", null)
            disposition = if (attachment.contentId == null) Part.ATTACHMENT else Part.INLINE
            attachment.contentId?.let { require('\n' !in it && '\r' !in it); setHeader("Content-ID", "<${it.trim('<', '>')}>") }
        }
        // Inline images (cid:) travel with the HTML in multipart/related; real attachments wrap everything in mixed.
        val inline = if (mail.html != null) mail.attachments.filter { it.contentId != null } else emptyList()
        val files = mail.attachments - inline.toSet()
        val body = if (inline.isEmpty()) alternative else MimeBodyPart().apply {
            setContent(MimeMultipart("related").apply { addBodyPart(alternative); inline.forEach { addBodyPart(part(it)) } })
            dataHandler.setCommandMap(handlers)
        }
        if (files.isEmpty()) {
            message.setDataHandler(body.dataHandler.apply { setCommandMap(handlers) })
        } else {
            message.setContent(MimeMultipart("mixed").apply {
                addBodyPart(body)
                files.forEach { addBodyPart(part(it)) }
            })
        }
        message.dataHandler.setCommandMap(handlers)
        message.saveChanges()
        return message
    }
    /** Streams decoded MIME parts into caller-owned private files, retaining no attachment arrays. */
    fun cache(part: Part, directory: java.io.File, maxBytes: Long = 25L * 1024 * 1024,includeAttachments:Boolean=true,checkCancelled:()->Unit={}): CachedBody {
        directory.mkdirs()
        val texts = mutableListOf<java.io.File>(); val htmls = mutableListOf<java.io.File>(); val attachments = mutableListOf<CachedPart>()
        val metadata=mutableListOf<RemotePart>()
        var remaining = maxBytes
        var partCount=0
        fun output(file:java.io.File):java.io.OutputStream = object:java.io.FilterOutputStream(file.outputStream()) {
            override fun write(value:Int) { checkCancelled(); require(remaining>0) { "Message exceeds download limit" }; remaining--; out.write(value) }
            override fun write(bytes:ByteArray,offset:Int,length:Int) { checkCancelled(); require(length<=remaining) { "Message exceeds download limit" }; remaining-=length; out.write(bytes,offset,length) }
        }
        fun save(p: Part, name: String): java.io.File = java.io.File(directory,name).also { file ->
            p.inputStream.use { input -> output(file).use { target -> input.copyTo(target,8192) } }
        }
        fun visit(p: Part, id: String, depth: Int,materialized:Boolean=false) {
            checkCancelled()
            require(depth < 40) { "MIME nesting limit exceeded" }
            require(++partCount<=512) { "MIME part limit exceeded" }
            val type = safeType(p)
            val disposition = runCatching { p.disposition }.getOrNull()
            val name = runCatching { p.fileName }.getOrNull()
            val cid = runCatching { p.getHeader("Content-ID")?.firstOrNull()?.trim()?.trim('<','>')?.takeIf { it.length<=512 } }.getOrNull()
            if (type == "multipart/encrypted" && Pgp.protocol(p) == "application/pgp-encrypted") {
                // PGP/MIME (RFC 3156): keep only the ciphertext; the reader decrypts it in memory through OpenKeychain.
                val multipart = p.content as Multipart
                if (multipart.count >= 2) save(multipart.getBodyPart(1), Pgp.ENCRYPTED_FILE) else for (i in 0 until multipart.count) visit(multipart.getBodyPart(i), "$id.$i", depth+1,materialized)
            } else if (type == "multipart/signed" && Pgp.protocol(p) == "application/pgp-signature") {
                // Keep the signed entity byte-for-byte (headers + body, CRLF) and the detached signature for verification;
                // the signed content is still shown normally.
                val multipart = p.content as Multipart
                if (multipart.count >= 2) {
                    val raw=java.io.File(directory,"signed-entity-$id.tmp")
                    output(raw).use { out -> multipart.getBodyPart(0).writeTo(out) }
                    raw.copyTo(java.io.File(directory,Pgp.SIGNED_FILE),overwrite=true)
                    save(multipart.getBodyPart(1), Pgp.SIGNATURE_FILE)
                    // The signed envelope already contains its attachments. Read cached bytes, preserving
                    // the original verification entity and avoiding a second network transfer.
                    jakarta.mail.util.SharedFileInputStream(raw).use { input -> visit(MimeBodyPart(input),"$id.0",depth+1,true) }
                    raw.delete()
                } else for (i in 0 until multipart.count) visit(multipart.getBodyPart(i), "$id.$i", depth+1,materialized)
            } else if (type.startsWith("multipart/")) {
                val multipart = p.content as Multipart
                for (i in 0 until multipart.count) visit(multipart.getBodyPart(i), "$id.$i", depth+1,materialized)
            } else if (name != null || disposition.equals(Part.ATTACHMENT,true) || type !in setOf("text/plain","text/html") || cid != null) {
                val filename=(name?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) } ?: "attachment").let { raw -> if(raw.length<=1024) raw else raw.substring(0,if(Character.isHighSurrogate(raw[1023])) 1023 else 1024) }
                val inline=disposition.equals(Part.INLINE,true) || (cid != null && !disposition.equals(Part.ATTACHMENT,true))
                metadata+=RemotePart(id,filename,type,cid,inline,runCatching { p.size.toLong() }.getOrDefault(-1))
                if(includeAttachments || inline || materialized) attachments += CachedPart(id,filename,type,cid,inline,save(p,"part-$id"))
            } else {
                val charset = runCatching { jakarta.mail.internet.ContentType(p.contentType).getParameter("charset")?.let { java.nio.charset.Charset.forName(it) } }.getOrNull() ?: Charsets.UTF_8
                val utf=java.io.File(directory,"utf-$id")
                p.inputStream.reader(charset).use { input -> output(utf).writer(Charsets.UTF_8).use { target -> input.copyTo(target) } }
                if (type=="text/html") htmls += utf else texts += utf
            }
        }
        fun join(files: List<java.io.File>, name: String): java.io.File? {
            if (files.isEmpty()) return null
            val target=java.io.File(directory,name)
            target.outputStream().use { out -> files.forEachIndexed { index,file -> if(index>0) out.write(10); file.inputStream().use { it.copyTo(out) }; file.delete() } }
            return target
        }
        try { visit(part,"1",0); return CachedBody(join(texts,"plain.txt"),join(htmls,"body.html"),attachments,metadata) }
        catch (e: Exception) { directory.deleteRecursively(); throw e }
    }
    private fun safeType(part: Part): String = runCatching { part.contentType.substringBefore(';').trim().lowercase() }.getOrDefault("application/octet-stream")
    fun parse(part: Part, maxBytes: Int = 25 * 1024 * 1024): ParsedBody {
        val texts = mutableListOf<String>(); val htmls = mutableListOf<String>(); val attachments = mutableListOf<MimeAttachment>()
        var remaining = maxBytes
        fun bytes(p: Part): ByteArray {
            val data = p.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    require(output.size() <= remaining) { "Message exceeds body download limit" }
                }
                output.toByteArray()
            }
            require(data.size <= remaining) { "Message exceeds body download limit" }; remaining -= data.size; return data
        }
        fun visit(p: Part, id: String, depth: Int) {
            require(depth < 40) { "MIME nesting limit exceeded" }
            val type = safeType(p)
            val disposition = runCatching { p.disposition }.getOrNull()
            val name = runCatching { p.fileName }.getOrNull()
            val cid = runCatching { p.getHeader("Content-ID")?.firstOrNull()?.trim()?.trim('<','>') }.getOrNull()
            if (type.startsWith("multipart/")) {
                val multi = p.content as Multipart
                for (i in 0 until multi.count) visit(multi.getBodyPart(i), "$id.$i", depth + 1)
            } else if (name != null || disposition.equals(Part.ATTACHMENT, true) || type !in setOf("text/plain","text/html") || cid != null) {
                attachments += MimeAttachment(id, name?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) } ?: "attachment", type, cid, disposition.equals(Part.INLINE, true) || (cid != null && !disposition.equals(Part.ATTACHMENT, true)), bytes(p))
            } else {
                val charset = runCatching { jakarta.mail.internet.ContentType(p.contentType).getParameter("charset")?.let { java.nio.charset.Charset.forName(it) } }.getOrNull() ?: Charsets.UTF_8
                val value = bytes(p).toString(charset)
                if (type == "text/html") htmls += value else texts += value
            }
        }
        visit(part, "1", 0)
        return ParsedBody(texts.joinToString("\n").takeIf { texts.isNotEmpty() }, htmls.joinToString("\n").takeIf { htmls.isNotEmpty() }, attachments)
    }
}
