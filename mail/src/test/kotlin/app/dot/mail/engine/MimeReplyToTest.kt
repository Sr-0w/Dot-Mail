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
}
