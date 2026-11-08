package app.dot.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RFC 6068 `mailto:` parsing for [MailtoParser]. Pure JVM: [MailtoParser] and
 * [ExternalRequest.Compose] have no Android dependencies.
 */
class MailtoParserTest {

    private fun p(mailto: String) = MailtoParser.parse(mailto)

    private fun compose(
        to: List<String> = emptyList(),
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
        subject: String? = null,
        body: String? = null,
    ) = ExternalRequest.Compose(to = to, cc = cc, bcc = bcc, subject = subject, body = body)

    @Test
    fun `simple address`() {
        assertEquals(compose(to = listOf("a@example.com")), p("mailto:a@example.com"))
    }

    @Test
    fun `uppercase scheme MAILTO`() {
        assertEquals(compose(to = listOf("a@example.com")), p("MAILTO:a@example.com"))
    }

    @Test
    fun `BUG mixed-case scheme Mailto must be parsed`() {
        // RFC 3986 section 3.1: the scheme is case-insensitive.
        assertEquals(compose(to = listOf("a@example.com"), subject = "Hi"), p("Mailto:a@example.com?subject=Hi"))
    }

    @Test
    fun `multiple recipients comma separated in address part`() {
        val c = p("mailto:a@x.com,b@y.com")
        assertEquals(listOf("a@x.com", "b@y.com"), c.to)
    }

    @Test
    fun `semicolon separated recipients`() {
        val c = p("mailto:a@x.com;b@y.com")
        assertEquals(listOf("a@x.com", "b@y.com"), c.to)
    }

    @Test
    fun `whitespace and empty entries are dropped`() {
        val c = p("mailto: a@x.com , ,b@y.com, ")
        assertEquals(listOf("a@x.com", "b@y.com"), c.to)
    }

    @Test
    fun `to query parameter is appended after the address part`() {
        val c = p("mailto:a@x.com?to=b@y.com&to=c@z.com")
        assertEquals(listOf("a@x.com", "b@y.com", "c@z.com"), c.to)
    }

    @Test
    fun `cc and bcc query parameters`() {
        val c = p("mailto:a@x.com?cc=b@y.com&bcc=c@z.com")
        assertEquals(listOf("b@y.com"), c.cc)
        assertEquals(listOf("c@z.com"), c.bcc)
    }

    @Test
    fun `cc with multiple addresses and empty address part`() {
        val c = p("mailto:?cc=a@x.com,b@y.com")
        assertEquals(emptyList<String>(), c.to)
        assertEquals(listOf("a@x.com", "b@y.com"), c.cc)
    }

    @Test
    fun `encoded subject`() {
        assertEquals("Hello World", p("mailto:a@x.com?subject=Hello%20World").subject)
    }

    @Test
    fun `encoded body with newline`() {
        assertEquals("First\nSecond", p("mailto:a@x.com?body=First%0ASecond").body)
    }

    @Test
    fun `plus in query is a literal plus not a space`() {
        // RFC 6068: in mailto queries, '+' is a literal character; spaces arrive as %20.
        assertEquals("A+B", p("mailto:a@x.com?subject=A+B").subject)
    }

    @Test
    fun `space only via percent encoding`() {
        assertEquals("A B", p("mailto:a@x.com?subject=A%20B").subject)
    }

    @Test
    fun `encoded plus decodes to plus`() {
        assertEquals("+", p("mailto:a@x.com?subject=%2B").subject)
    }

    @Test
    fun `plus in address part is kept literal`() {
        assertEquals(listOf("john+tag@example.com"), p("mailto:john+tag@example.com").to)
    }

    @Test
    fun `plus in to parameter is kept literal`() {
        val c = p("mailto:a@x.com?to=b+c@y.com")
        assertEquals(listOf("a@x.com", "b+c@y.com"), c.to)
    }

    @Test
    fun `query keys are case-insensitive`() {
        val c = p("mailto:a@x.com?SUBJECT=Hi&Body=yo&CC=b@y.com&BCC=c@z.com")
        assertEquals("Hi", c.subject)
        assertEquals("yo", c.body)
        assertEquals(listOf("b@y.com"), c.cc)
        assertEquals(listOf("c@z.com"), c.bcc)
    }

    @Test
    fun `equals sign inside a value belongs to the value`() {
        assertEquals("x=y", p("mailto:a@x.com?subject=x=y").subject)
    }

    @Test
    fun `encoded ampersand inside subject`() {
        assertEquals("A&B", p("mailto:a@x.com?subject=A%26B").subject)
    }

    @Test
    fun `unknown parameters are ignored`() {
        val c = p("mailto:a@x.com?term=foo&notify=yes&subject=Bar")
        assertEquals("Bar", c.subject)
        assertNull(c.body)
    }

    @Test
    fun `utf8 percent encoded subject`() {
        assertEquals("élève", p("mailto:a@x.com?subject=%C3%A9l%C3%A8ve").subject)
    }

    @Test
    fun `malformed percent encoding falls back to the raw value`() {
        assertEquals("100%", p("mailto:a@x.com?subject=100%").subject)
    }

    @Test
    fun `trailing question mark`() {
        val c = p("mailto:a@x.com?")
        assertEquals(listOf("a@x.com"), c.to)
        assertNull(c.subject)
        assertNull(c.body)
    }

    @Test
    fun `empty mailto yields empty compose`() {
        assertEquals(compose(), p("mailto:"))
    }

    @Test
    fun `missing scheme prefix is accepted`() {
        val c = p("a@x.com?subject=S")
        assertEquals(listOf("a@x.com"), c.to)
        assertEquals("S", c.subject)
    }
}
