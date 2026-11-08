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
        listOf(Message.RecipientType.TO to mail.to, Message.RecipientType.CC to mail.cc, Message.RecipientType.BCC to mail.bcc).forEach { (type, list) ->
            message.setRecipients(type, list.map(::address).toTypedArray())
        }
        message.setSubject(mail.subject, "UTF-8")
        message.sentDate = Date.from(mail.date)
        require(listOfNotNull(mail.inReplyTo, mail.messageId).plus(mail.references).none { '\r' in it || '\n' in it })
        mail.inReplyTo?.let { message.setHeader("In-Reply-To", it) }
        if (mail.references.isNotEmpty()) message.setHeader("References", mail.references.joinToString(" "))
        val text = MimeBodyPart().apply { setText(mail.text, "UTF-8") }
        val body = if (mail.html != null) MimeBodyPart().apply {
            setContent(MimeMultipart("alternative").apply {
                addBodyPart(text)
                addBodyPart(MimeBodyPart().apply { setContent(mail.html, "text/html; charset=UTF-8"); dataHandler.setCommandMap(handlers) })
            }); dataHandler.setCommandMap(handlers)
        } else text
        if (mail.attachments.isEmpty()) {
            message.setDataHandler(body.dataHandler.apply { setCommandMap(handlers) })
        } else {
            message.setContent(MimeMultipart("mixed").apply {
                addBodyPart(body)
                mail.attachments.forEach { attachment -> addBodyPart(MimeBodyPart().apply {
                    dataHandler = DataHandler(ByteArrayDataSource(attachment.bytes, attachment.mimeType)).apply { setCommandMap(handlers) }
                    fileName = MimeUtility.encodeText(attachment.name, "UTF-8", null)
                    disposition = if (attachment.contentId == null) Part.ATTACHMENT else Part.INLINE
                    attachment.contentId?.let { require('\n' !in it && '\r' !in it); setHeader("Content-ID", "<${it.trim('<', '>')}>") }
                }) }
            })
        }
        message.dataHandler.setCommandMap(handlers)
        message.saveChanges()
        return message
    }
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
            if (p.isMimeType("multipart/*")) {
                val multi = p.content as Multipart
                for (i in 0 until multi.count) visit(multi.getBodyPart(i), "$id.$i", depth + 1)
            } else if (p.fileName != null || p.disposition.equals(Part.ATTACHMENT, true) || (!p.isMimeType("text/plain") && !p.isMimeType("text/html"))) {
                attachments += MimeAttachment(id, p.fileName?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) } ?: "attachment", p.contentType.substringBefore(';'), p.getHeader("Content-ID")?.firstOrNull()?.trim('<', '>'), p.disposition.equals(Part.INLINE, true), bytes(p))
            } else {
                val charset = runCatching { jakarta.mail.internet.ContentType(p.contentType).getParameter("charset")?.let { java.nio.charset.Charset.forName(it) } }.getOrNull() ?: Charsets.UTF_8
                val value = bytes(p).toString(charset)
                if (p.isMimeType("text/html")) htmls += value else texts += value
            }
        }
        visit(part, "1", 0)
        return ParsedBody(texts.joinToString("\n").takeIf { texts.isNotEmpty() }, htmls.joinToString("\n").takeIf { htmls.isNotEmpty() }, attachments)
    }
}
