package app.dot.mail.engine.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SnippetsTest {

    // ------------------------------------------------------------- plain text

    @Test
    fun `collapses whitespace`() {
        assertThat(Snippets.fromPlainText("Hello   world\n\nThis is a test.", max = 160))
            .isEqualTo("Hello world This is a test.")
    }

    @Test
    fun `no ellipsis when the text fits`() {
        assertThat(Snippets.fromPlainText("Short", max = 160)).isEqualTo("Short")
    }

    @Test
    fun `truncates with ellipsis at most max chars`() {
        val snippet = Snippets.fromPlainText("aa bb cc dd ee ff", max = 10)
        assertThat(snippet).isEqualTo("aa bb cc…")
        assertThat(snippet).hasLength(9)
    }

    @Test
    fun `truncation never exceeds max`() {
        val snippet = Snippets.fromPlainText("word ".repeat(50).trim(), max = 20)
        assertThat(snippet).hasLength(20)
        assertThat(snippet).endsWith("…")
    }

    @Test
    fun `drops the quoted tail`() {
        val text = "Hello Alice,\n\nYes, I can do it.\n\n-- \nBob\n\n" +
            "On Wed, Nov 5, 2026 at 9:15 AM alice@example.com wrote:\n> Hello Bob,\n> question?"
        assertThat(Snippets.fromPlainText(text, max = 160))
            .isEqualTo("Hello Alice, Yes, I can do it. -- Bob")
    }

    @Test
    fun `empty and blank input`() {
        assertThat(Snippets.fromPlainText("")).isEqualTo("")
        assertThat(Snippets.fromPlainText("   \n  ")).isEqualTo("")
    }

    @Test
    fun `non positive max yields empty`() {
        assertThat(Snippets.fromPlainText("anything", max = 0)).isEqualTo("")
        assertThat(Snippets.fromPlainText("anything", max = -5)).isEqualTo("")
    }

    @Test
    fun `fully quoted input is still shown`() {
        assertThat(Snippets.fromPlainText("> only a quote", max = 160)).isEqualTo("> only a quote")
    }

    // ------------------------------------------------------------------- html

    @Test
    fun `ignores head style and script`() {
        val html = """
            <html>
              <head>
                <title>Some title</title>
                <style>body { color: red; }</style>
                <script>var x = 1;</script>
              </head>
              <body>
                <div>First <b>bold</b> line</div>
                <p>Second line</p>
              </body>
            </html>
        """.trimIndent()
        assertThat(Snippets.fromHtml(html, max = 200)).isEqualTo("First bold line Second line")
    }

    @Test
    fun `drops hidden preheader by inline style`() {
        val html = """
            <div style="display:none">PREHEADER JUNK ONLY</div>
            <div style="visibility: hidden">Also hidden</div>
            <p>Visible content</p>
        """.trimIndent()
        assertThat(Snippets.fromHtml(html, max = 200)).isEqualTo("Visible content")
    }

    @Test
    fun `drops hidden preheader by mso-hide and font size`() {
        val html = """
            <div style="mso-hide: clip">Outlook-only text</div>
            <span style="font-size:0px">Tiny tracker</span>
            <p>Real content</p>
        """.trimIndent()
        assertThat(Snippets.fromHtml(html, max = 200)).isEqualTo("Real content")
    }

    @Test
    fun `drops hidden preheader by class`() {
        val html = "<div class=\"preheader\">HIDDEN</div><p>Shown</p>"
        assertThat(Snippets.fromHtml(html, max = 200)).isEqualTo("Shown")
    }

    @Test
    fun `keeps link text but never the href`() {
        val html = "<p>See <a href=\"https://example.com/secret-page\">example site</a> for details.</p>"
        assertThat(Snippets.fromHtml(html, max = 200)).isEqualTo("See example site for details.")
    }

    @Test
    fun `drops the quoted tail from html`() {
        val html = """
            <p>Reply text</p>
            <blockquote>
              <p>&gt; old line one</p>
              <p>&gt; old line two</p>
            </blockquote>
        """.trimIndent()
        assertThat(Snippets.fromHtml(html, max = 200)).isEqualTo("Reply text")
    }

    @Test
    fun `no-break spaces become regular spaces`() {
        assertThat(Snippets.fromHtml("<p>Hello&#160;&#160;world</p>", max = 200)).isEqualTo("Hello world")
    }

    @Test
    fun `html truncated to max`() {
        val html = "<p>" + "word ".repeat(50).trim() + "</p>"
        val snippet = Snippets.fromHtml(html, max = 20)
        assertThat(snippet).hasLength(20)
        assertThat(snippet).endsWith("…")
    }

    @Test
    fun `html blank input`() {
        assertThat(Snippets.fromHtml("")).isEqualTo("")
        assertThat(Snippets.fromHtml("   ")).isEqualTo("")
    }

    @Test
    fun `tables and lists produce readable text`() {
        val html = "<table><tr><td>Alpha</td><td>Beta</td></tr></table><ul><li>One</li><li>Two</li></ul>"
        assertThat(Snippets.fromHtml(html, max = 200)).isEqualTo("Alpha Beta One Two")
    }
}
