package app.dot.mail.engine.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [EmailHtml]: editor HTML -> email-safe document, HTML -> text/plain alternative,
 * plain text -> minimal editor HTML.
 */
class EmailHtmlTest {

    private fun doc(content: String) =
        "<!DOCTYPE html><html><head>" +
            "<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
            "</head><body style=\"font-family: -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; font-size: 15px; line-height: 1.5\">" +
            content + "</body></html>"

    // ----------------------------------------------------------------- compose

    @Test
    fun `wraps content in a complete document`() {
        assertThat(EmailHtml.compose("<p>Hi</p>"))
            .isEqualTo(doc("<p style=\"margin: 0 0 1em\">Hi</p>"))
    }

    @Test
    fun `empty editor html still yields a valid document`() {
        assertThat(EmailHtml.compose("")).isEqualTo(doc(""))
    }

    @Test
    fun `editor supplied as a full document keeps only the body content`() {
        val out = EmailHtml.compose("<html><head><style>.x{color:red}</style></head><body><p>Hi</p></body></html>")
        assertThat(out).doesNotContain("<style")
        assertThat(out).doesNotContain("color:red")
        assertThat(out).contains("<p style=\"margin: 0 0 1em\">Hi</p>")
    }

    @Test
    fun `paragraphs get the required margin`() {
        val out = EmailHtml.compose("<p>a</p><div>b</div>")
        assertThat(out).contains("<p style=\"margin: 0 0 1em\">a</p>")
        assertThat(out).contains("<div style=\"margin: 0 0 1em\">b</div>")
    }

    @Test
    fun `editor margin on paragraphs is overridden`() {
        val out = EmailHtml.compose("<p style=\"margin: 0 0 99px\">x</p>")
        assertThat(out).contains("<p style=\"margin: 0 0 1em\">x</p>")
    }

    @Test
    fun `safe editor styles survive next to the forced ones`() {
        val out = EmailHtml.compose("<p style=\"color: #ff0000; margin: 9px\">x</p>")
        assertThat(out).contains("<p style=\"margin: 0 0 1em; color: #ff0000\">x</p>")
    }

    @Test
    fun `headings get inline styles`() {
        val out = EmailHtml.compose("<h1>T</h1><h2>S</h2><h3>U</h3><h4>V</h4>")
        assertThat(out).contains("<h1 style=\"margin: 0 0 1em; font-size: 1.6em; font-weight: bold\">T</h1>")
        assertThat(out).contains("<h2 style=\"margin: 0 0 1em; font-size: 1.4em; font-weight: bold\">S</h2>")
        assertThat(out).contains("<h3 style=\"margin: 0 0 1em; font-size: 1.2em; font-weight: bold\">U</h3>")
        assertThat(out).contains("<h4 style=\"margin: 0 0 1em; font-weight: bold\">V</h4>")
    }

    @Test
    fun `lists get sane padding`() {
        val out = EmailHtml.compose("<ul><li>a</li><li>b</li></ul><ol><li>c</li></ol>")
        assertThat(out).contains("<ul style=\"margin: 0 0 1em; padding-left: 1.5em\"><li>a</li><li>b</li></ul>")
        assertThat(out).contains("<ol style=\"margin: 0 0 1em; padding-left: 1.5em\"><li>c</li></ol>")
    }

    @Test
    fun `blockquote and code get inline styles`() {
        val out = EmailHtml.compose("<blockquote>q</blockquote><code>x=1</code><pre>y\nz</pre>")
        assertThat(out).contains("<blockquote style=\"margin: 0 0 1em; padding-left: 1em\">q</blockquote>")
        assertThat(out).contains("<code style=\"font-family: monospace\">x=1</code>")
        assertThat(out).contains("<pre style=\"font-family: monospace; white-space: pre-wrap\">y")
    }

    @Test
    fun `class and id attributes are dropped`() {
        val out = EmailHtml.compose("<p class=\"lead\" id=\"top\" data-x=\"1\">x</p>")
        assertThat(out).doesNotContain("class")
        assertThat(out).doesNotContain("id=")
        assertThat(out).doesNotContain("data-x")
        assertThat(out).contains(">x</p>")
    }

    @Test
    fun `on attributes are dropped`() {
        val out = EmailHtml.compose("<p onmouseover=\"alert(1)\" onclick=\"x()\">Hi</p>")
        assertThat(out).doesNotContain("onmouseover")
        assertThat(out).doesNotContain("onclick")
        assertThat(out).contains(">Hi</p>")
    }

    @Test
    fun `style containing url is dropped`() {
        val out = EmailHtml.compose("<p style=\"background: url(http://evil.ch/t.png)\">Hi</p>")
        assertThat(out).doesNotContain("url(")
        assertThat(out).contains("<p style=\"margin: 0 0 1em\">Hi</p>")
    }

    @Test
    fun `style containing expression is dropped`() {
        val out = EmailHtml.compose("<p style=\"width: expression(alert(1))\">x</p>")
        assertThat(out).doesNotContain("expression")
    }

    @Test
    fun `style containing an at-rule is cleared entirely`() {
        val out = EmailHtml.compose("<p style=\"@media screen { color: red }\">x</p>")
        assertThat(out).contains("<p style=\"margin: 0 0 1em\">x</p>")
    }

    @Test
    fun `style containing an escape is cleared entirely`() {
        val out = EmailHtml.compose("<p style=\"color: #ff\\0red\">x</p>")
        assertThat(out).contains("<p style=\"margin: 0 0 1em\">x</p>")
    }

    @Test
    fun `javascript href is dropped and the text kept`() {
        val out = EmailHtml.compose("<p><a href=\"javascript:alert(1)\">x</a></p>")
        assertThat(out).isEqualTo(doc("<p style=\"margin: 0 0 1em\">x</p>"))
        assertThat(out).doesNotContain("javascript")
    }

    @Test
    fun `unsafe href schemes are dropped`() {
        val out = EmailHtml.compose("<a href=\"data:text/html;base64,AA\">d</a> <a href=\"ftp://x\">f</a>")
        assertThat(out).doesNotContain("data:")
        assertThat(out).doesNotContain("ftp:")
        assertThat(out).doesNotContain("<a ")
        assertThat(out).contains("d f")
    }

    @Test
    fun `http https mailto tel hrefs are kept`() {
        val out = EmailHtml.compose(
            "<a href=\"https://a.ch\">s</a> <a href=\"http://a.ch\">c</a> " +
                "<a href=\"mailto:a@b.ch\">m</a> <a href=\"tel:+41445556677\">t</a>"
        )
        assertThat(out).contains("href=\"https://a.ch\"")
        assertThat(out).contains("href=\"http://a.ch\"")
        assertThat(out).contains("href=\"mailto:a@b.ch\"")
        assertThat(out).contains("href=\"tel:+41445556677\"")
    }

    @Test
    fun `script and iframe are removed with their content`() {
        val out = EmailHtml.compose("<script>bad()</script><iframe src=\"https://evil.ch\">evil</iframe><p>a</p>")
        assertThat(out).doesNotContain("bad()")
        assertThat(out).doesNotContain("iframe")
        assertThat(out).doesNotContain("evil")
        assertThat(out).contains("<p style=\"margin: 0 0 1em\">a</p>")
    }

    @Test
    fun `tables are unwrapped but their text is kept`() {
        assertThat(EmailHtml.compose("<table><tr><td>cell</td></tr></table>")).isEqualTo(doc("cell"))
    }

    @Test
    fun `unknown tags are unwrapped keeping their text`() {
        assertThat(EmailHtml.compose("<marquee>moved</marquee>")).isEqualTo(doc("moved"))
    }

    @Test
    fun `image data uri is removed, remote http image is kept with safe attributes`() {
        val out = EmailHtml.compose("<p>x</p><img src=\"data:image/png;base64,AAA\">")
        assertThat(out).doesNotContain("data:")
        val out2 = EmailHtml.compose("<img src=\"https://x.ch/l.png\" alt=\"Logo\" width=\"120\" onerror=\"x()\">")
        assertThat(out2).contains("<img src=\"https://x.ch/l.png\" alt=\"Logo\" width=\"120\">")
        assertThat(out2).doesNotContain("onerror")
    }

    @Test
    fun `safe colors are kept, unsafe colors are dropped`() {
        val out = EmailHtml.compose(
            "<span style=\"color: #ff0000\">a</span>" +
                "<span style=\"color: red\">b</span>" +
                "<span style=\"color: rgb(255, 0, 0)\">c</span>" +
                "<span style=\"color: hsl(0, 0%, 0%)\">d</span>" +
                "<span style=\"color: rgb(300, 0, 0)\">e</span>"
        )
        assertThat(out).contains("<span style=\"color: #ff0000\">a</span>")
        assertThat(out).contains("<span style=\"color: red\">b</span>")
        assertThat(out).contains("<span style=\"color: rgb(255, 0, 0)\">c</span>")
        assertThat(out).contains("<span>d</span>")
        assertThat(out).contains("<span>e</span>")
    }

    @Test
    fun `signature is appended after the separator`() {
        val out = EmailHtml.compose("<p>Hi</p>", signatureHtml = "<p>Bob</p>")
        assertThat(out).contains("Hi</p><br>-- <br><p style=\"margin: 0 0 1em\">Bob</p></body>")
    }

    @Test
    fun `blank signature is not appended`() {
        for (sig in listOf(null, "", "   ", "<br>", "<p>  </p>")) {
            val out = EmailHtml.compose("<p>Hi</p>", signatureHtml = sig)
            assertThat(out).doesNotContain("-- <br>")
        }
    }

    @Test
    fun `image-only signature is appended`() {
        val out = EmailHtml.compose("<p>Hi</p>", signatureHtml = "<img src=\"https://x.ch/sig.png\" alt=\"sig\">")
        assertThat(out).contains("<br>-- <br><img src=\"https://x.ch/sig.png\" alt=\"sig\">")
    }

    @Test
    fun `quote is appended with attribution line and cite blockquote`() {
        val out = EmailHtml.compose(
            "<p>Hi</p>",
            quote = EmailHtml.Quote("On Tue, 4 Nov 2026 at 10:12, Anna <a@b.ch> wrote:", "<p>old</p>"),
        )
        assertThat(out).contains(
            "<p style=\"margin: 0 0 0.5em; font-size: 0.9em; color: #555\">On Tue, 4 Nov 2026 at 10:12, Anna &lt;a@b.ch&gt; wrote:</p>"
        )
        assertThat(out).contains(
            "<blockquote type=\"cite\" style=\"margin:0 0 0 .8ex;border-left:1px solid #ccc;padding-left:1ex\">" +
                "<p style=\"margin: 0 0 1em\">old</p></blockquote></body>"
        )
    }

    @Test
    fun `quote html is sanitised too`() {
        val out = EmailHtml.compose("<p>Hi</p>", quote = EmailHtml.Quote("wrote:", "<p onmouseover=\"x()\">old<script>bad()</script></p>"))
        assertThat(out).doesNotContain("onmouseover")
        assertThat(out).doesNotContain("bad()")
        assertThat(out).contains("<p style=\"margin: 0 0 1em\">old</p></blockquote>")
    }

    @Test
    fun `signature and quote appear in order`() {
        val out = EmailHtml.compose("<p>Hi</p>", signatureHtml = "<p>Sig</p>", quote = EmailHtml.Quote("wrote:", "<p>q</p>"))
        assertThat(out.indexOf("Hi</p>").coerceAtLeast(0))
            .isLessThan(out.indexOf("<br>-- <br>"))
        assertThat(out.indexOf("<br>-- <br>")).isLessThan(out.indexOf("Sig</p>"))
        assertThat(out.indexOf("Sig</p>")).isLessThan(out.indexOf("wrote:"))
        assertThat(out.indexOf("wrote:")).isLessThan(out.indexOf("type=\"cite\""))
    }

    @Test
    fun `huge input completes and keeps both ends`() {
        val big = (0 until 4000).joinToString("") { "<p>para $it</p>" }
        val out = EmailHtml.compose(big)
        assertThat(out).contains("para 0")
        assertThat(out).contains("para 3999")
        assertThat(out).startsWith("<!DOCTYPE html>")
    }

    // ------------------------------------------------------------- toPlainText

    @Test
    fun `paragraphs are separated by a blank line`() {
        assertThat(EmailHtml.toPlainText("<p>A</p><p>B</p>")).isEqualTo("A\n\nB")
    }

    @Test
    fun `br becomes a newline without a blank line`() {
        assertThat(EmailHtml.toPlainText("<p>A<br>B</p>")).isEqualTo("A\nB")
    }

    @Test
    fun `inline tags are flattened`() {
        assertThat(EmailHtml.toPlainText("<p><b>x</b> <i>y</i> <u>z</u></p>")).isEqualTo("x y z")
    }

    @Test
    fun `unordered list renders bullets`() {
        assertThat(EmailHtml.toPlainText("<ul><li>a</li><li>b</li></ul>")).isEqualTo("• a\n• b")
    }

    @Test
    fun `ordered list renders numbers`() {
        assertThat(EmailHtml.toPlainText("<ol><li>a</li><li>b</li></ol>")).isEqualTo("1. a\n2. b")
    }

    @Test
    fun `nested lists are indented and counters stay per list`() {
        val html = "<ol><li>outer<ul><li>i1</li><li>i2</li></ul></li><li>two</li></ol>"
        assertThat(EmailHtml.toPlainText(html)).isEqualTo("1. outer\n  • i1\n  • i2\n2. two")
    }

    @Test
    fun `list after a paragraph is separated by a blank line`() {
        assertThat(EmailHtml.toPlainText("<p>A</p><ul><li>x</li></ul>")).isEqualTo("A\n\n• x")
    }

    @Test
    fun `links render as text with url`() {
        assertThat(EmailHtml.toPlainText("<p>Visit <a href=\"https://x.ch\">site</a> now</p>"))
            .isEqualTo("Visit site <https://x.ch> now")
    }

    @Test
    fun `link with text equal to the url shows the url once`() {
        assertThat(EmailHtml.toPlainText("<a href=\"https://x.ch\">https://x.ch</a>")).isEqualTo("https://x.ch")
    }

    @Test
    fun `link with unsafe scheme shows only the text`() {
        assertThat(EmailHtml.toPlainText("<a href=\"javascript:alert(1)\">x</a>")).isEqualTo("x")
    }

    @Test
    fun `blockquote lines are prefixed`() {
        assertThat(EmailHtml.toPlainText("<blockquote><p>q1</p><p>q2</p></blockquote>"))
            .isEqualTo("> q1\n>\n> q2")
    }

    @Test
    fun `nested blockquotes get double prefix`() {
        val html = "<blockquote><p>a</p><blockquote><p>b</p></blockquote></blockquote>"
        assertThat(EmailHtml.toPlainText(html)).isEqualTo("> a\n>\n>> b")
    }

    @Test
    fun `headings stay on their own line`() {
        assertThat(EmailHtml.toPlainText("<h2>Hi</h2><p>body</p>")).isEqualTo("Hi\n\nbody")
    }

    @Test
    fun `entities are decoded`() {
        assertThat(EmailHtml.toPlainText("<p>&amp; &lt; done</p>")).isEqualTo("& < done")
    }

    @Test
    fun `nbsp is collapsed to a space`() {
        assertThat(EmailHtml.toPlainText("<p>Hello&#160;&#160;world</p>")).isEqualTo("Hello world")
    }

    @Test
    fun `image alt text is kept, anonymous images are dropped`() {
        assertThat(EmailHtml.toPlainText("<p><img alt=\"logo\" src=\"https://x/y.png\"> <b>t</b></p>"))
            .isEqualTo("logo t")
        assertThat(EmailHtml.toPlainText("<p><img src=\"https://x/y.png\">t</p>")).isEqualTo("t")
    }

    @Test
    fun `pre keeps its newlines`() {
        assertThat(EmailHtml.toPlainText("<pre>a\nb</pre>")).isEqualTo("a\nb")
    }

    @Test
    fun `script and style are ignored`() {
        assertThat(EmailHtml.toPlainText("<script>var x=\"no\"</script><style>.no{}</style><p>ok</p>"))
            .isEqualTo("ok")
    }

    @Test
    fun `no trailing spaces and at most one blank line`() {
        val html = "<div><div><div><p>X</p></div></div></div><div><p>Y</p></div>"
        assertThat(EmailHtml.toPlainText(html)).isEqualTo("X\n\nY")
    }

    @Test
    fun `empty html yields empty text`() {
        assertThat(EmailHtml.toPlainText("")).isEqualTo("")
        assertThat(EmailHtml.toPlainText("<p></p>")).isEqualTo("")
    }

    // ---------------------------------------------------------- fromPlainText

    @Test
    fun `blank line separates paragraphs`() {
        assertThat(EmailHtml.fromPlainText("A\n\nB")).isEqualTo("<p>A</p><p>B</p>")
    }

    @Test
    fun `single newline becomes br`() {
        assertThat(EmailHtml.fromPlainText("A\nB")).isEqualTo("<p>A<br>B</p>")
    }

    @Test
    fun `quote lines become blockquote`() {
        assertThat(EmailHtml.fromPlainText("> x\n> y")).isEqualTo("<blockquote><p>x<br>y</p></blockquote>")
    }

    @Test
    fun `nested quotes become nested blockquotes`() {
        assertThat(EmailHtml.fromPlainText(">> z")).isEqualTo("<blockquote><blockquote><p>z</p></blockquote></blockquote>")
    }

    @Test
    fun `urls are autolinked`() {
        assertThat(EmailHtml.fromPlainText("see https://a.ch/x now"))
            .isEqualTo("<p>see <a href=\"https://a.ch/x\">https://a.ch/x</a> now</p>")
    }

    @Test
    fun `email addresses are autolinked as mailto`() {
        assertThat(EmailHtml.fromPlainText("mail me at bob@example.ch"))
            .isEqualTo("<p>mail me at <a href=\"mailto:bob@example.ch\">bob@example.ch</a></p>")
    }

    @Test
    fun `trailing punctuation is not part of the url`() {
        assertThat(EmailHtml.fromPlainText("check https://a.ch/x."))
            .isEqualTo("<p>check <a href=\"https://a.ch/x\">https://a.ch/x</a>.</p>")
    }

    @Test
    fun `url in parentheses is trimmed correctly`() {
        assertThat(EmailHtml.fromPlainText("(https://a.ch/x)"))
            .isEqualTo("<p>(<a href=\"https://a.ch/x\">https://a.ch/x</a>)</p>")
    }

    @Test
    fun `mailto text is not linked`() {
        assertThat(EmailHtml.fromPlainText("mailto:a@b.c")).isEqualTo("<p>mailto:a@b.c</p>")
    }

    @Test
    fun `html characters are escaped`() {
        assertThat(EmailHtml.fromPlainText("a < b & c")).isEqualTo("<p>a &lt; b &amp; c</p>")
    }

    @Test
    fun `mixed text and quote`() {
        assertThat(EmailHtml.fromPlainText("text\n\n> q"))
            .isEqualTo("<p>text</p><blockquote><p>q</p></blockquote>")
    }

    @Test
    fun `trailing newlines produce no empty paragraphs`() {
        assertThat(EmailHtml.fromPlainText("A\n\n\n")).isEqualTo("<p>A</p>")
        assertThat(EmailHtml.fromPlainText("\n\nA")).isEqualTo("<p>A</p>")
    }

    @Test
    fun `empty input yields empty output`() {
        assertThat(EmailHtml.fromPlainText("")).isEqualTo("")
        assertThat(EmailHtml.fromPlainText("   \n  ")).isEqualTo("")
    }

    @Test
    fun `plain bullet characters stay text`() {
        assertThat(EmailHtml.fromPlainText("• a")).isEqualTo("<p>• a</p>")
    }

    @Test
    fun `url and email on one line are both linked`() {
        assertThat(EmailHtml.fromPlainText("see https://a.ch and bob@example.ch"))
            .isEqualTo("<p>see <a href=\"https://a.ch\">https://a.ch</a> and <a href=\"mailto:bob@example.ch\">bob@example.ch</a></p>")
    }

    // ---------------------------------------------------------------- round trip

    @Test
    fun `round trip paragraphs`() {
        val html = "<p>Hello</p><p>World</p>"
        assertThat(EmailHtml.fromPlainText(EmailHtml.toPlainText(html))).isEqualTo(html)
    }

    @Test
    fun `round trip with quote`() {
        val text = "a\n\n> b"
        assertThat(EmailHtml.toPlainText(EmailHtml.fromPlainText(text))).isEqualTo(text)
    }

    @Test
    fun `round trip list and paragraph`() {
        val text = "intro\n\n• x\n• y"
        val html = EmailHtml.fromPlainText(text)
        assertThat(html).isEqualTo("<p>intro</p><p>• x<br>• y</p>")
        assertThat(EmailHtml.toPlainText(html)).isEqualTo(text)
    }

    @Test fun `compose keeps our cid logo but still drops remote and script images`() {
        val out = EmailHtml.compose("<p>x</p><img src=\"cid:dotsig-logo\"><img src=\"javascript:alert(1)\"><img src=\"data:text/html,x\">")
        assertThat(out).contains("cid:dotsig-logo")
        assertThat(out).doesNotContain("javascript")
        assertThat(out).doesNotContain("data:text")
    }

    @Test fun `sanitizeSignature strips unsafe markup but keeps formatting and links`() {
        val out = EmailHtml.sanitizeSignature(
            "<div onclick=\"x()\"><b>Alice</b><script>alert(1)</script><br><a href=\"javascript:evil()\">bad</a> " +
                "<a href=\"https://example.org\" onclick=\"x()\">site</a><img src=\"https://t.example/p.gif\"><form><input></form></div>",
        )
        assertThat(out).contains("<b>Alice</b>")
        assertThat(out).contains("href=\"https://example.org\"")
        assertThat(out).doesNotContain("script")
        assertThat(out).doesNotContain("javascript")
        assertThat(out).doesNotContain("onclick")
        assertThat(out).doesNotContain("<img")
        assertThat(out).doesNotContain("<div")
        assertThat(EmailHtml.sanitizeSignature("<script>x</script>  ")).isEmpty()
    }
}
