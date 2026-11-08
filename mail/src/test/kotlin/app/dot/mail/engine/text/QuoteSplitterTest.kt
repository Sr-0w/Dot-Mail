package app.dot.mail.engine.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QuoteSplitterTest {

    @Test
    fun `plain text without a quote stays whole`() {
        val split = QuoteSplitter.split("Hello Alice,\n\nHow is the project going?")
        assertThat(split.visible).isEqualTo("Hello Alice,\n\nHow is the project going?")
        assertThat(split.quoted).isNull()
    }

    @Test
    fun `empty text`() {
        val split = QuoteSplitter.split("")
        assertThat(split.visible).isEqualTo("")
        assertThat(split.quoted).isNull()
    }

    @Test
    fun `trailing newlines are trimmed from the visible part`() {
        val split = QuoteSplitter.split("Done.\n\n\n")
        assertThat(split.visible).isEqualTo("Done.")
        assertThat(split.quoted).isNull()
    }

    @Test
    fun `quote markers only, no header`() {
        val split = QuoteSplitter.split("Sure, works for me.\n> original line one\n> original line two")
        assertThat(split.visible).isEqualTo("Sure, works for me.")
        assertThat(split.quoted).isEqualTo("> original line one\n> original line two")
    }

    @Test
    fun `gmail style quote with wrote header and signature`() {
        val text = """
            Hi Alice,

            Thanks, works for me!

            --
            Bob

            On Wed, Nov 5, 2026 at 9:15 AM alice@example.com wrote:
            > Hi Bob,
            > can you confirm the delivery date?
        """.trimIndent()
        val split = QuoteSplitter.split(text)
        assertThat(split.visible).isEqualTo("Hi Alice,\n\nThanks, works for me!\n\n--\nBob")
        assertThat(split.quoted).isEqualTo(
            "On Wed, Nov 5, 2026 at 9:15 AM alice@example.com wrote:\n> Hi Bob,\n> can you confirm the delivery date?"
        )
    }

    @Test
    fun `french wrote header without quote markers`() {
        val text = """
            Bonjour,

            Le mar. 4 nov. 2026 à 15:30, alice <alice@ex.fr> a écrit :
            Bonjour Bob, peux-tu confirmer la date ?
        """.trimIndent()
        val split = QuoteSplitter.split(text)
        assertThat(split.visible).isEqualTo("Bonjour,")
        assertThat(split.quoted)
            .isEqualTo("Le mar. 4 nov. 2026 à 15:30, alice <alice@ex.fr> a écrit :\nBonjour Bob, peux-tu confirmer la date ?")
    }

    @Test
    fun `german wrote header without quote markers`() {
        val text = """
            Hallo,

            Am 04.11.2026 um 15:30 Uhr schrieb alice <alice@ex.de>:
            Hallo Bob, kannst du das bestätigen?
        """.trimIndent()
        val split = QuoteSplitter.split(text)
        assertThat(split.visible).isEqualTo("Hallo,")
        assertThat(split.quoted)
            .isEqualTo("Am 04.11.2026 um 15:30 Uhr schrieb alice <alice@ex.de>:\nHallo Bob, kannst du das bestätigen?")
    }

    @Test
    fun `wrote header without trailing colon`() {
        val text = "ok\nOn 7 Nov 2026, at 16:12, alice@example.com wrote\nbody of the old message"
        val split = QuoteSplitter.split(text)
        assertThat(split.visible).isEqualTo("ok")
        assertThat(split.quoted).isEqualTo("On 7 Nov 2026, at 16:12, alice@example.com wrote\nbody of the old message")
    }

    @Test
    fun `outlook original message block`() {
        val text = """
            Hi, done.

            -----Original Message-----
            From: alice <alice@ex.com>
            Sent: Monday, November 3, 2026 9:15 AM
            To: bob <bob@ex.com>
            Subject: Re: Plan

            Original body here.
        """.trimIndent()
        val split = QuoteSplitter.split(text)
        assertThat(split.visible).isEqualTo("Hi, done.")
        assertThat(split.quoted).isEqualTo(
            """
                -----Original Message-----
                From: alice <alice@ex.com>
                Sent: Monday, November 3, 2026 9:15 AM
                To: bob <bob@ex.com>
                Subject: Re: Plan

                Original body here.
            """.trimIndent()
        )
    }

    @Test
    fun `whole text quoted is never hidden`() {
        val text = "> only a quote\n> nothing new written"
        val split = QuoteSplitter.split(text)
        assertThat(split.visible).isEqualTo(text)
        assertThat(split.quoted).isNull()
    }

    @Test
    fun `blank lines before the quote keep everything visible`() {
        val text = "\n\n> quoted tail"
        val split = QuoteSplitter.split(text)
        assertThat(split.visible).isEqualTo(text)
        assertThat(split.quoted).isNull()
    }

    @Test
    fun `interleaved writing after a quote keeps everything visible`() {
        val text = "> quoted start\nthen I wrote a reply after the quote"
        val split = QuoteSplitter.split(text)
        assertThat(split.visible).isEqualTo(text)
        assertThat(split.quoted).isNull()
    }

    @Test
    fun `nested quotes are kept together`() {
        val split = QuoteSplitter.split("reply\n> a\n>> b")
        assertThat(split.visible).isEqualTo("reply")
        assertThat(split.quoted).isEqualTo("> a\n>> b")
    }

    @Test
    fun `crlf line endings`() {
        val split = QuoteSplitter.split("Hi\r\n\r\n> quoted line")
        assertThat(split.visible).isEqualTo("Hi")
        assertThat(split.quoted).isEqualTo("> quoted line")
    }

    @Test
    fun `signature without a quote stays visible`() {
        val text = "Done.\n--\nBob\nCEO"
        val split = QuoteSplitter.split(text)
        assertThat(split.visible).isEqualTo(text)
        assertThat(split.quoted).isNull()
    }

    @Test
    fun `greater sign mid line is not a quote marker`() {
        val text = "Use > to mean greater than\n> real quote"
        val split = QuoteSplitter.split(text)
        assertThat(split.visible).isEqualTo("Use > to mean greater than")
        assertThat(split.quoted).isEqualTo("> real quote")
    }

    @Test
    fun `signature sits inside the visible part when a quote follows`() {
        val text = "Reply text\n-- \nSignature line\n\nOn 7 Nov 2026, at 16:12, x@example.com wrote:\n> old"
        val split = QuoteSplitter.split(text)
        assertThat(split.visible).isEqualTo("Reply text\n-- \nSignature line")
        assertThat(split.quoted).isNotNull()
    }
}
