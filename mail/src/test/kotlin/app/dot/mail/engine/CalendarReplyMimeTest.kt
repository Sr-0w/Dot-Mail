package app.dot.mail.engine

import jakarta.mail.Multipart
import jakarta.mail.Session
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.MimeMessage
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Properties

class CalendarReplyMimeTest {
    @Test fun replyIsAnEncodedCalendarAlternativeRatherThanAnAttachment() {
        val calendar="BEGIN:VCALENDAR\r\nVERSION:2.0\r\nMETHOD:REPLY\r\nEND:VCALENDAR\r\n"
        val mail=Mime.build(OutgoingMail(Address("invitee@example.org"),listOf(Address("organizer@example.org")),subject="Accepted",text="Accepted: Café",calendarReply=calendar))
        val bytes=ByteArrayOutputStream().also { mail.writeTo(it) }.toByteArray()
        val parsed=MimeMessage(Session.getInstance(Properties()),bytes.inputStream())
        assertTrue(parsed.isMimeType("multipart/alternative"))
        val parts=parsed.content as Multipart
        assertEquals(2,parts.count)
        assertTrue(parts.getBodyPart(0).isMimeType("text/plain"))
        val reply=parts.getBodyPart(1)
        assertEquals("REPLY",ContentType(reply.contentType).getParameter("method"))
        assertEquals("UTF-8",ContentType(reply.contentType).getParameter("charset"))
        assertEquals(calendar,reply.inputStream.bufferedReader().readText())
        assertNull(reply.fileName)
    }
}
