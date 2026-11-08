package app.dot.mail.data

import app.dot.mail.data.model.Draft
import app.dot.mail.data.model.EmailAddress
import app.dot.mail.engine.Address
import app.dot.mail.engine.Mime
import app.dot.mail.engine.OutgoingMail
import app.dot.mail.engine.text.EmailHtml
import com.google.common.truth.Truth.assertThat
import jakarta.mail.internet.MimeMultipart
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RichDraftTest {
    @Test fun codecKeepsHtmlAndOldDraftsDecodeAsPlain() {
        val d = Draft(localId = 7, accountId = 1, identityId = 1, to = listOf(EmailAddress("a@b.co")), subject = "Hi", bodyText = "Hello world", bodyHtml = "<p>Hello <b>world</b></p>")
        val back = Codec.draft(Codec.draft(d))
        assertThat(back.bodyHtml).isEqualTo(d.bodyHtml)
        assertThat(back.bodyText).isEqualTo("Hello world")
        val legacy = Codec.draft(Codec.draft(d.copy(bodyHtml = null)))
        assertThat(legacy.bodyHtml).isNull()
    }

    @Test fun formattedDraftIsSentAsMultipartAlternative() {
        val html = EmailHtml.compose("<p>Hello <b>world</b></p>")
        val mime = Mime.build(OutgoingMail(Address("me@b.co"), listOf(Address("you@b.co")), subject = "S", text = EmailHtml.toPlainText(html), html = html))
        mime.saveChanges()
        val content = mime.content
        val alt = if (content is MimeMultipart && content.contentType.startsWith("multipart/alternative", true)) content
            else ((content as MimeMultipart).getBodyPart(0).content as MimeMultipart)
        assertThat(alt.contentType.lowercase()).startsWith("multipart/alternative")
        val types = (0 until alt.count).map { alt.getBodyPart(it).contentType.lowercase() }
        assertThat(types.any { it.startsWith("text/plain") }).isTrue()
        assertThat(types.any { it.startsWith("text/html") }).isTrue()
        assertThat(alt.getBodyPart(types.indexOfFirst { it.startsWith("text/plain") }).content.toString()).contains("Hello world")
    }
}
