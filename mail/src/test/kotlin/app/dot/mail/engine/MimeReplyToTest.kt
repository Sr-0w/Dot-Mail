package app.dot.mail.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MimeReplyToTest {
    @Test fun `reply-to header is set only when given`() {
        val plain = Mime.build(OutgoingMail(Address("a@example.org"), listOf(Address("b@example.org")), subject = "s", text = "t"))
        assertThat(plain.getHeader("Reply-To")).isNull()
        val withReplyTo = Mime.build(
            OutgoingMail(Address("shop@example.org", "Shop"), listOf(Address("b@example.org")), subject = "s", text = "t", replyTo = listOf(Address("support@example.org", "Support"))),
        )
        assertThat(withReplyTo.getHeader("Reply-To").single()).contains("support@example.org")
        assertThat(withReplyTo.from.single().toString()).contains("shop@example.org")
    }

    @Test fun `inline logo travels in multipart related inside alternative, files in mixed`() {
        val html = "<p>Hi</p><p><img src=\"cid:dotsig-logo\"></p>"
        val logo = OutgoingAttachment("logo.png", "image/png", byteArrayOf(1, 2, 3), contentId = "dotsig-logo")
        val mail = Mime.build(OutgoingMail(Address("a@example.org"), listOf(Address("b@example.org")), subject = "s", text = "Hi", html = html, attachments = listOf(logo)))
        assertThat(mail.contentType).startsWith("multipart/related")
        val related = mail.content as jakarta.mail.internet.MimeMultipart
        assertThat(related.getBodyPart(0).contentType).startsWith("multipart/alternative")
        assertThat(related.getBodyPart(1).getHeader("Content-ID").single()).isEqualTo("<dotsig-logo>")

        val pdf = OutgoingAttachment("a.pdf", "application/pdf", byteArrayOf(9))
        val withFile = Mime.build(OutgoingMail(Address("a@example.org"), listOf(Address("b@example.org")), subject = "s", text = "Hi", html = html, attachments = listOf(logo, pdf)))
        assertThat(withFile.contentType).startsWith("multipart/mixed")
        val mixed = withFile.content as jakarta.mail.internet.MimeMultipart
        assertThat(mixed.count).isEqualTo(2)
        assertThat(mixed.getBodyPart(0).contentType).startsWith("multipart/related")
    }
}
