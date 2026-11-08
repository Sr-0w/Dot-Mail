package app.dot.mail.engine.autoconfig

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoconfigXmlTest {

    private val email = "alice@corp.example"
    private val local = "alice"
    private val domain = "corp.example"

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/autoconfig/$name")) { "missing fixture $name" }
            .bufferedReader().readText()

    private fun parse(name: String): ParsedServers? = AutoconfigXml.parse(fixture(name), email, local, domain)

    @Test
    fun `parses a realistic provider document`() {
        val p = parse("simple.xml")!!
        assertThat(p.imap.host).isEqualTo("imap.corp.example")
        assertThat(p.imap.port).isEqualTo(993)
        assertThat(p.imap.socket).isEqualTo(SocketType.SSL)
        assertThat(p.imap.username).isEqualTo(email)
        assertThat(p.smtp.host).isEqualTo("smtp.corp.example")
        assertThat(p.smtp.port).isEqualTo(587)
        assertThat(p.smtp.socket).isEqualTo(SocketType.STARTTLS)
        assertThat(p.smtp.username).isEqualTo(local)
        assertThat(p.providerName).isEqualTo("Corp Mail")
    }

    @Test
    fun `prefers imap ssl over pop3 and starttls`() {
        val p = parse("multiple-variants.xml")!!
        assertThat(p.imap.host).isEqualTo("imap-secure.multi.example")
        assertThat(p.imap.port).isEqualTo(993)
        assertThat(p.imap.socket).isEqualTo(SocketType.SSL)
        assertThat(p.smtp.host).isEqualTo("smtp.multi.example")
        assertThat(p.smtp.port).isEqualTo(465)
        assertThat(p.smtp.socket).isEqualTo(SocketType.SSL)
        assertThat(p.providerName).isEqualTo("Multi Variant")
    }

    @Test
    fun `pop3 is accepted only when no imap exists`() {
        val p = parse("pop3-only.xml")!!
        assertThat(p.imap.host).isEqualTo("pop.corp.example")
        assertThat(p.imap.socket).isEqualTo(SocketType.SSL)
        assertThat(p.smtp.host).isEqualTo("smtp.corp.example")
    }

    @Test
    fun `ispdb style document parses`() {
        val p = parse("ispdb-posteo.xml")!!
        assertThat(p.imap.host).isEqualTo("mailposteo-prod.mail.emailrelay.org")
        assertThat(p.imap.port).isEqualTo(993)
        assertThat(p.smtp.port).isEqualTo(465)
        assertThat(p.providerName).isEqualTo("Posteo")
    }

    @Test
    fun `all three placeholders are substituted`() {
        val p = parse("placeholders.xml")!!
        assertThat(p.imap.host).isEqualTo("imap.corp.example")
        assertThat(p.imap.username).isEqualTo(local)
        assertThat(p.smtp.host).isEqualTo("corp.example-smtp")
        assertThat(p.smtp.username).isEqualTo(email)
    }

    @Test
    fun `missing port gets a sensible default per socket type`() {
        val p = parse("missing-port.xml")!!
        assertThat(p.imap.socket).isEqualTo(SocketType.SSL)
        assertThat(p.imap.port).isEqualTo(993)
        assertThat(p.smtp.socket).isEqualTo(SocketType.STARTTLS)
        assertThat(p.smtp.port).isEqualTo(587)
    }

    @Test
    fun `missing username defaults to the full email`() {
        val xml = fixture("simple.xml").replace("<username>%EMAILLOCALPART%</username>", "")
        val p = AutoconfigXml.parse(xml, email, local, domain)!!
        assertThat(p.smtp.username).isEqualTo(email)
    }

    @Test
    fun `malformed xml yields null`() {
        assertThat(parse("malformed.xml")).isNull()
    }

    @Test
    fun `document without servers yields null`() {
        assertThat(parse("no-servers.xml")).isNull()
    }

    @Test
    fun `blank input yields null`() {
        assertThat(AutoconfigXml.parse("   ", email, local, domain)).isNull()
    }

    @Test
    fun `xxe document is rejected instead of expanded`() {
        val p = parse("xxe.xml")
        assertThat(p).isNull() // DOCTYPE with entities is disallowed entirely
    }

    @Test
    fun `external dtd reference is rejected`() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE clientInfo SYSTEM "http://evil.example/evil.dtd">
            <clientInfo>
              <incomingServer type="imap">
                <hostname>imap.corp.example</hostname>
                <port>993</port>
                <socketType>SSL</socketType>
                <username>%EMAILADDRESS%</username>
              </incomingServer>
              <outgoingServer type="smtp">
                <hostname>smtp.corp.example</hostname>
                <port>465</port>
                <socketType>SSL</socketType>
                <username>%EMAILADDRESS%</username>
              </outgoingServer>
            </clientInfo>
        """.trimIndent()
        assertThat(AutoconfigXml.parse(xml, email, local, domain)).isNull()
    }

    @Test
    fun `internal entity without doctype is inert`() {
        // No DOCTYPE -> no entity declarations possible; a raw & must be escaped XML.
        val xml = """
            <clientInfo>
              <emailProvider id="x" domain="x">
                <incomingServer type="imap">
                  <hostname>imap.corp.example</hostname>
                  <port>993</port>
                  <socketType>SSL</socketType>
                  <username>%EMAILADDRESS%</username>
                </incomingServer>
                <outgoingServer type="smtp">
                  <hostname>smtp.corp.example</hostname>
                  <port>465</port>
                  <socketType>SSL</socketType>
                  <username>%EMAILADDRESS%</username>
                </outgoingServer>
              </emailProvider>
            </clientInfo>
        """.trimIndent()
        val p = AutoconfigXml.parse(xml, email, local, domain)
        assertThat(p?.imap?.host).isEqualTo("imap.corp.example")
    }

    @Test
    fun `invalid port is dropped and not trusted`() {
        val xml = fixture("simple.xml").replace("<port>993</port>", "<port>99999</port>")
        assertThat(AutoconfigXml.parse(xml, email, local, domain)).isNull()
    }
}
