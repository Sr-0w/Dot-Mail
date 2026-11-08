package app.dot.mail.engine.autoconfig

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AutoDiscoveryTest {

    /** Records every requested URL; returns canned bodies, or throws when [failOnFetch]. */
    private class FakeFetcher(
        private val responses: Map<String, String> = emptyMap(),
    ) : ConfigFetcher {
        val requested = mutableListOf<String>()
        var failOnFetch = false

        override fun fetch(url: String): String? {
            requested.add(url)
            if (failOnFetch) throw IOException("simulated network failure")
            return responses[url]
        }
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/autoconfig/$name")) { "missing fixture $name" }
            .bufferedReader().readText()

    // ------------------------------------------------------------------ built-in

    @Test
    fun `gmail is built in`() = runTest {
        val c = AutoDiscovery(FakeFetcher()).discover("alice@gmail.com")!!
        assertThat(c.source).isEqualTo(Source.BUILT_IN)
        assertThat(c.imap.host).isEqualTo("imap.gmail.com")
        assertThat(c.imap.port).isEqualTo(993)
        assertThat(c.imap.socket).isEqualTo(SocketType.SSL)
        assertThat(c.smtp.host).isEqualTo("smtp.gmail.com")
        assertThat(c.smtp.port).isEqualTo(465)
        assertThat(c.imap.username).isEqualTo("alice@gmail.com")
        assertThat(c.smtp.username).isEqualTo("alice@gmail.com")
        assertThat(c.providerName).isEqualTo("Gmail")
        assertThat(c.oauthRequired).isFalse()
        assertThat(c.hint).contains("app password")
    }

    @Test
    fun `googlemail aliases match gmail`() = runTest {
        val f = FakeFetcher()
        for (d in listOf("googlemail.com", "googlemail.de", "googlemail.co.uk")) {
            val c = AutoDiscovery(f).discover("u@$d")!!
            assertThat(c.imap.host).isEqualTo("imap.gmail.com")
            assertThat(c.providerName).isEqualTo("Gmail")
        }
        assertThat(f.requested).isEmpty()
    }

    @Test
    fun `outlook requires oauth`() = runTest {
        val f = FakeFetcher()
        val domains = listOf("outlook.com", "outlook.fr", "hotmail.com", "hotmail.co.uk", "hotmail.de", "hotmail.es", "hotmail.it", "live.com", "msn.com")
        for (d in domains) {
            val c = AutoDiscovery(f).discover("bob@$d")!!
            assertThat(c.source).isEqualTo(Source.BUILT_IN)
            assertThat(c.oauthRequired).isTrue()
            assertThat(c.imap.host).isEqualTo("outlook.office365.com")
            assertThat(c.imap.port).isEqualTo(993)
            assertThat(c.smtp.host).isEqualTo("smtp-mail.outlook.com")
            assertThat(c.smtp.port).isEqualTo(587)
            assertThat(c.smtp.socket).isEqualTo(SocketType.STARTTLS)
            assertThat(c.providerName).isEqualTo("Outlook")
        }
        assertThat(f.requested).isEmpty()
    }

    @Test
    fun `yahoo is built in`() = runTest {
        val c = AutoDiscovery(FakeFetcher()).discover("c@yahoo.fr")!!
        assertThat(c.imap.host).isEqualTo("imap.mail.yahoo.com")
        assertThat(c.imap.port).isEqualTo(993)
        assertThat(c.smtp.host).isEqualTo("smtp.mail.yahoo.com")
        assertThat(c.providerName).isEqualTo("Yahoo")
        assertThat(c.oauthRequired).isFalse()
        assertThat(c.hint).contains("app password")
    }

    @Test
    fun `icloud domains`() = runTest {
        val f = FakeFetcher()
        for (d in listOf("icloud.com", "me.com", "mac.com")) {
            val c = AutoDiscovery(f).discover("c@$d")!!
            assertThat(c.imap.host).isEqualTo("imap.mail.me.com")
            assertThat(c.imap.port).isEqualTo(993)
            assertThat(c.imap.socket).isEqualTo(SocketType.SSL)
            assertThat(c.smtp.host).isEqualTo("smtp.mail.me.com")
            assertThat(c.providerName).isEqualTo("iCloud")
        }
        assertThat(f.requested).isEmpty()
    }

    @Test
    fun `infomaniak domains`() = runTest {
        val f = FakeFetcher()
        for (d in listOf("infomaniak.com", "ik.me", "ikmail.com", "etik.com", "ik.io")) {
            val c = AutoDiscovery(f).discover("d@$d")!!
            assertThat(c.imap.host).isEqualTo("mail.infomaniak.com")
            assertThat(c.imap.port).isEqualTo(993)
            assertThat(c.smtp.host).isEqualTo("mail.infomaniak.com")
            assertThat(c.smtp.port).isEqualTo(465)
            assertThat(c.providerName).isEqualTo("Infomaniak")
        }
        assertThat(f.requested).isEmpty()
    }

    @Test
    fun `fastmail domains`() = runTest {
        val f = FakeFetcher()
        for (d in listOf("fastmail.com", "fastmail.co.uk", "fastmail.eu", "fastmail.net", "fastmail.com.au", "fastmail.co.nz")) {
            val c = AutoDiscovery(f).discover("e@$d")!!
            assertThat(c.imap.host).isEqualTo("imap.fastmail.com")
            assertThat(c.smtp.host).isEqualTo("smtp.fastmail.com")
            assertThat(c.providerName).isEqualTo("Fastmail")
        }
        assertThat(f.requested).isEmpty()
    }

    @Test
    fun `gmx net and de use gmx net servers`() = runTest {
        val f = FakeFetcher()
        for (d in listOf("gmx.net", "gmx.de")) {
            val c = AutoDiscovery(f).discover("f@$d")!!
            assertThat(c.imap.host).isEqualTo("imap.gmx.net")
            assertThat(c.imap.port).isEqualTo(993)
            assertThat(c.smtp.host).isEqualTo("mail.gmx.net")
            assertThat(c.smtp.port).isEqualTo(465)
            assertThat(c.providerName).isEqualTo("GMX")
        }
        assertThat(f.requested).isEmpty()
    }

    @Test
    fun `gmx com uses its own servers`() = runTest {
        val c = AutoDiscovery(FakeFetcher()).discover("f@gmx.com")!!
        assertThat(c.imap.host).isEqualTo("imap.gmx.com")
        assertThat(c.smtp.host).isEqualTo("mail.gmx.com")
        assertThat(c.providerName).isEqualTo("GMX")
    }

    @Test
    fun `other built-in providers`() = runTest {
        suspend fun check(email: String, imapHost: String, smtpHost: String, name: String) {
            val c = AutoDiscovery(FakeFetcher()).discover(email)!!
            assertThat(c.imap.host).isEqualTo(imapHost)
            assertThat(c.imap.port).isEqualTo(993)
            assertThat(c.imap.socket).isEqualTo(SocketType.SSL)
            assertThat(c.smtp.host).isEqualTo(smtpHost)
            assertThat(c.smtp.socket).isEqualTo(SocketType.SSL)
            assertThat(c.providerName).isEqualTo(name)
            assertThat(c.source).isEqualTo(Source.BUILT_IN)
        }
        check("x@web.de", "imap.web.de", "smtp.web.de", "web.de")
        check("x@mailbox.org", "imap.mailbox.org", "smtp.mailbox.org", "mailbox.org")
        check("x@posteo.de", "posteo.de", "posteo.de", "Posteo")
        check("x@posteo.net", "posteo.de", "posteo.de", "Posteo")
        check("x@zoho.com", "imap.zoho.com", "smtp.zoho.com", "Zoho")
        check("x@zoho.eu", "imap.zoho.eu", "smtp.zoho.eu", "Zoho")
        check("x@aol.com", "imap.aol.com", "smtp.aol.com", "AOL")
    }

    @Test
    fun `web de wants the local part as username`() = runTest {
        val c = AutoDiscovery(FakeFetcher()).discover("werner@web.de")!!
        assertThat(c.imap.username).isEqualTo("werner")
        assertThat(c.smtp.username).isEqualTo("werner")
    }

    @Test
    fun `proton is surfaced as not usable on android`() = runTest {
        val f = FakeFetcher()
        for (d in listOf("proton.me", "protonmail.com", "pm.me")) {
            val c = AutoDiscovery(f).discover("g@$d")!!
            assertThat(c.source).isEqualTo(Source.BUILT_IN)
            assertThat(c.providerName).isEqualTo("Proton")
            // Proton Mail Bridge is desktop-only, so a password flow can never succeed:
            // signal that via oauthRequired and explain in the hint.
            assertThat(c.oauthRequired).isTrue()
            assertThat(c.hint).contains("doesn't offer IMAP access on Android")
            assertThat(c.imap.host).isEqualTo("mail.proton.me")
            assertThat(c.smtp.host).isEqualTo("mail.proton.me")
        }
        assertThat(f.requested).isEmpty()
    }

    @Test
    fun `built-in wins without any network call`() = runTest {
        val f = FakeFetcher()
        AutoDiscovery(f).discover("alice@gmail.com")
        assertThat(f.requested).isEmpty()
    }

    @Test
    fun `domain case is normalised for lookups`() = runTest {
        val c = AutoDiscovery(FakeFetcher()).discover("User@Gmail.COM")!!
        assertThat(c.providerName).isEqualTo("Gmail")
        assertThat(c.imap.username).isEqualTo("User@gmail.com")
    }

    // ------------------------------------------------------- autoconfig / ISPDB

    @Test
    fun `provider autoconfig first url wins`() = runTest {
        val f = FakeFetcher(mapOf(
            "https://autoconfig.corp.example/mail/config-v1.1.xml" to fixture("simple.xml"),
        ))
        val c = AutoDiscovery(f).discover("alice@corp.example")!!
        assertThat(c.source).isEqualTo(Source.PROVIDER_AUTOCONFIG)
        assertThat(c.imap).isEqualTo(DiscoveredServer("imap.corp.example", 993, SocketType.SSL, "alice@corp.example"))
        assertThat(c.smtp).isEqualTo(DiscoveredServer("smtp.corp.example", 587, SocketType.STARTTLS, "alice"))
        assertThat(c.providerName).isEqualTo("Corp Mail")
        assertThat(f.requested).hasSize(1)
    }

    @Test
    fun `well-known url is second`() = runTest {
        val f = FakeFetcher(mapOf(
            "https://corp.example/.well-known/autoconfig/mail/config-v1.1.xml" to fixture("simple.xml"),
        ))
        val c = AutoDiscovery(f).discover("alice@corp.example")!!
        assertThat(c.source).isEqualTo(Source.PROVIDER_AUTOCONFIG)
        assertThat(f.requested).containsExactly(
            "https://autoconfig.corp.example/mail/config-v1.1.xml",
            "https://corp.example/.well-known/autoconfig/mail/config-v1.1.xml",
        ).inOrder()
    }

    @Test
    fun `ispdb is third`() = runTest {
        val f = FakeFetcher(mapOf(
            "https://autoconfig.thunderbird.net/v1.1/corp.example" to fixture("ispdb-posteo.xml"),
        ))
        val c = AutoDiscovery(f).discover("alice@corp.example")!!
        assertThat(c.source).isEqualTo(Source.ISPDB)
        assertThat(c.imap.host).isEqualTo("mailposteo-prod.mail.emailrelay.org")
        assertThat(c.imap.port).isEqualTo(993)
        assertThat(c.smtp.port).isEqualTo(465)
        assertThat(c.providerName).isEqualTo("Posteo")
        assertThat(f.requested).hasSize(3)
    }

    @Test
    fun `guess is fourth`() = runTest {
        val f = FakeFetcher()
        val c = AutoDiscovery(f).discover("alice@corp.example")!!
        assertThat(c.source).isEqualTo(Source.GUESS)
        assertThat(c.imap).isEqualTo(DiscoveredServer("imap.corp.example", 993, SocketType.SSL, "alice@corp.example"))
        assertThat(c.smtp).isEqualTo(DiscoveredServer("smtp.corp.example", 465, SocketType.SSL, "alice@corp.example"))
        assertThat(c.providerName).isNull()
        assertThat(c.hint).isNull()
        assertThat(f.requested).containsExactly(
            "https://autoconfig.corp.example/mail/config-v1.1.xml",
            "https://corp.example/.well-known/autoconfig/mail/config-v1.1.xml",
            "https://autoconfig.thunderbird.net/v1.1/corp.example",
        ).inOrder()
    }

    @Test
    fun `email address never appears in any url`() = runTest {
        val f = FakeFetcher()
        AutoDiscovery(f).discover("first.last+tag@corp.example")
        assertThat(f.requested).hasSize(3)
        f.requested.forEach { url ->
            assertThat(url).doesNotContain("first.last+tag")
            assertThat(url).doesNotContain("@")
            assertThat(url).contains("corp.example")
        }
    }

    @Test
    fun `malformed autoconfig falls through to guess`() = runTest {
        val f = FakeFetcher(mapOf(
            "https://autoconfig.corp.example/mail/config-v1.1.xml" to fixture("malformed.xml"),
        ))
        val c = AutoDiscovery(f).discover("alice@corp.example")!!
        assertThat(c.source).isEqualTo(Source.GUESS)
        assertThat(f.requested).hasSize(3)
    }

    @Test
    fun `autoconfig without servers falls through to ispdb`() = runTest {
        val f = FakeFetcher(mapOf(
            "https://autoconfig.corp.example/mail/config-v1.1.xml" to fixture("no-servers.xml"),
            "https://autoconfig.thunderbird.net/v1.1/corp.example" to fixture("ispdb-posteo.xml"),
        ))
        val c = AutoDiscovery(f).discover("alice@corp.example")!!
        assertThat(c.source).isEqualTo(Source.ISPDB)
    }

    @Test
    fun `xxe document is rejected and not followed`() = runTest {
        val f = FakeFetcher(mapOf(
            "https://autoconfig.corp.example/mail/config-v1.1.xml" to fixture("xxe.xml"),
        ))
        val c = AutoDiscovery(f).discover("alice@corp.example")!!
        assertThat(c.source).isEqualTo(Source.GUESS)
        assertThat(c.imap.host).doesNotContain("root:")
        f.requested.forEach { url ->
            assertThat(url.startsWith("file://")).isFalse()
            assertThat(url).startsWith("https://")
        }
    }

    @Test
    fun `fetcher exceptions are treated as not found`() = runTest {
        val f = FakeFetcher()
        f.failOnFetch = true
        val c = AutoDiscovery(f).discover("alice@corp.example")!!
        assertThat(c.source).isEqualTo(Source.GUESS)
        assertThat(f.requested).hasSize(3)
    }

    @Test
    fun `ssl is preferred over starttls and pop3`() = runTest {
        val f = FakeFetcher(mapOf(
            "https://autoconfig.multi.example/mail/config-v1.1.xml" to fixture("multiple-variants.xml"),
        ))
        val c = AutoDiscovery(f).discover("alice@multi.example")!!
        assertThat(c.imap.host).isEqualTo("imap-secure.multi.example")
        assertThat(c.imap.port).isEqualTo(993)
        assertThat(c.imap.socket).isEqualTo(SocketType.SSL)
        assertThat(c.smtp.host).isEqualTo("smtp.multi.example")
        assertThat(c.smtp.port).isEqualTo(465)
        assertThat(c.smtp.socket).isEqualTo(SocketType.SSL)
    }

    @Test
    fun `pop3 is used only when no imap exists`() = runTest {
        val f = FakeFetcher(mapOf(
            "https://autoconfig.corp.example/mail/config-v1.1.xml" to fixture("pop3-only.xml"),
        ))
        val c = AutoDiscovery(f).discover("alice@corp.example")!!
        assertThat(c.imap.host).isEqualTo("pop.corp.example")
        assertThat(c.imap.socket).isEqualTo(SocketType.SSL)
        assertThat(c.smtp.host).isEqualTo("smtp.corp.example")
    }

    @Test
    fun `placeholders are substituted in host and username`() = runTest {
        val f = FakeFetcher(mapOf(
            "https://autoconfig.corp.example/mail/config-v1.1.xml" to fixture("placeholders.xml"),
        ))
        val c = AutoDiscovery(f).discover("alice@corp.example")!!
        assertThat(c.imap.host).isEqualTo("imap.corp.example")
        assertThat(c.imap.username).isEqualTo("alice")
        assertThat(c.smtp.host).isEqualTo("corp.example-smtp")
        assertThat(c.smtp.username).isEqualTo("alice@corp.example")
        assertThat(c.providerName).isEqualTo("Placeholder")
    }

    @Test
    fun `missing ports get sensible defaults`() = runTest {
        val f = FakeFetcher(mapOf(
            "https://autoconfig.corp.example/mail/config-v1.1.xml" to fixture("missing-port.xml"),
        ))
        val c = AutoDiscovery(f).discover("alice@corp.example")!!
        assertThat(c.imap.socket).isEqualTo(SocketType.SSL)
        assertThat(c.imap.port).isEqualTo(993)
        assertThat(c.smtp.socket).isEqualTo(SocketType.STARTTLS)
        assertThat(c.smtp.port).isEqualTo(587)
    }

    // ------------------------------------------------------------------- email

    @Test
    fun `malformed emails return null without network`() = runTest {
        val f = FakeFetcher()
        for (bad in listOf("", "   ", "no-at-sign", "a@", "@a", "@", "a b@c.com", "a@b c.com", "a@@c.com", "a@b..c", "a@.com")) {
            assertThat(AutoDiscovery(f).discover(bad)).isNull()
        }
        assertThat(f.requested).isEmpty()
    }

    @Test
    fun `idn domains are normalised to punycode`() = runTest {
        val c = AutoDiscovery(FakeFetcher()).discover("jürgen@münch.de")!!
        assertThat(c.source).isEqualTo(Source.GUESS)
        // java.net.IDN.toASCII("münch.de") == "xn--mnch-0ra.de"
        assertThat(c.imap.host).isEqualTo("imap.xn--mnch-0ra.de")
        assertThat(c.imap.username).isEqualTo("jürgen@xn--mnch-0ra.de")
    }

    // ------------------------------------------------------------------ MX

    private fun mx(vararg hosts: String) = MxResolver { hosts.toList() }

    @Test
    fun `custom domain at infomaniak is found by MX`() = runTest {
        val f = FakeFetcher()
        val c = AutoDiscovery(f, mx("mta-gw.infomaniak.ch")).discover("robin@snyders.xyz")!!
        assertThat(c.source).isEqualTo(Source.MX)
        assertThat(c.providerName).isEqualTo("Infomaniak")
        assertThat(c.imap.host).isEqualTo("mail.infomaniak.com")
        assertThat(c.imap.username).isEqualTo("robin@snyders.xyz")
        assertThat(c.smtp.username).isEqualTo("robin@snyders.xyz")
        // Provider autoconfig is tried first, ISPDB-by-domain never reached.
        assertThat(f.requested).containsExactly(
            "https://autoconfig.snyders.xyz/mail/config-v1.1.xml",
            "https://snyders.xyz/.well-known/autoconfig/mail/config-v1.1.xml",
        ).inOrder()
    }

    @Test
    fun `MX suffix table`() {
        fun p(h: String) = BuiltInProviders.lookupByMx(h)?.providerName
        assertThat(p("mx1.mail.infomaniak.com.")).isEqualTo("Infomaniak")
        assertThat(p("ASPMX.L.GOOGLE.COM")).isEqualTo("Gmail")
        assertThat(p("alt1.aspmx.l.googlemail.com")).isEqualTo("Gmail")
        assertThat(p("x-example-com.mail.protection.outlook.com")).isEqualTo("Microsoft 365")
        assertThat(p("in1-smtp.messagingengine.com")).isEqualTo("Fastmail")
        assertThat(p("mxext1.mailbox.org")).isEqualTo("mailbox.org")
        assertThat(p("mx01.posteo.de")).isEqualTo("Posteo")
        assertThat(p("mx.zoho.com")).isEqualTo("Zoho")
        assertThat(BuiltInProviders.lookupByMx("mx.zoho.eu")!!.imapHost).isEqualTo("imap.zoho.eu")
        assertThat(BuiltInProviders.lookupByMx("mx1.mail.ovh.net")!!.imapHost).isEqualTo("ssl0.ovh.net")
        assertThat(BuiltInProviders.lookupByMx("spool.mail.gandi.net")!!.smtpHost).isEqualTo("mail.gandi.net")
        // whole labels only, and unknown hosts
        assertThat(p("evilinfomaniak.ch")).isNull()
        assertThat(p("mail.example.org")).isNull()
        assertThat(p("")).isNull()
    }

    @Test
    fun `microsoft 365 via MX requires oauth`() = runTest {
        val c = AutoDiscovery(FakeFetcher(), mx("contoso-com.mail.protection.outlook.com")).discover("a@contoso.com")!!
        assertThat(c.source).isEqualTo(Source.MX)
        assertThat(c.oauthRequired).isTrue()
        assertThat(c.smtp.host).isEqualTo("smtp.office365.com")
    }

    @Test
    fun `unknown MX falls back to ISPDB for the MX registrable domain`() = runTest {
        val xml = fixture("simple.xml")
        val f = FakeFetcher(mapOf("https://autoconfig.thunderbird.net/v1.1/hoster.example" to xml))
        val c = AutoDiscovery(f, mx("mx1.eu.hoster.example")).discover("me@mydomain.org")!!
        assertThat(c.source).isEqualTo(Source.MX)
        assertThat(f.requested).contains("https://autoconfig.thunderbird.net/v1.1/hoster.example")
        assertThat(f.requested).doesNotContain("https://autoconfig.thunderbird.net/v1.1/mydomain.org")
    }

    @Test
    fun `MX that leads nowhere continues to ISPDB by domain then guess`() = runTest {
        val f = FakeFetcher()
        val c = AutoDiscovery(f, mx("mx.nowhere.example")).discover("me@mydomain.org")!!
        assertThat(c.source).isEqualTo(Source.GUESS)
        val urls = f.requested
        assertThat(urls.indexOf("https://autoconfig.thunderbird.net/v1.1/nowhere.example"))
            .isLessThan(urls.indexOf("https://autoconfig.thunderbird.net/v1.1/mydomain.org"))
    }

    @Test
    fun `a throwing or empty MX resolver changes nothing`() = runTest {
        val boom = AutoDiscovery(FakeFetcher(), MxResolver { error("dns down") }).discover("me@mydomain.org")!!
        assertThat(boom.source).isEqualTo(Source.GUESS)
        val none = AutoDiscovery(FakeFetcher(), mx()).discover("me@mydomain.org")!!
        assertThat(none.source).isEqualTo(Source.GUESS)
    }

    @Test
    fun `built-in and provider autoconfig win over MX`() = runTest {
        var asked = false
        val r = MxResolver { asked = true; listOf("aspmx.l.google.com") }
        assertThat(AutoDiscovery(FakeFetcher(), r).discover("a@gmail.com")!!.source).isEqualTo(Source.BUILT_IN)
        assertThat(asked).isFalse()
    }

    @Test
    fun `registrable domain heuristic`() {
        assertThat(registrableDomain("mx1.mail.ovh.net.")).isEqualTo("ovh.net")
        assertThat(registrableDomain("mx.example.co.uk")).isEqualTo("example.co.uk")
        assertThat(registrableDomain("hoster.example")).isEqualTo("hoster.example")
        assertThat(registrableDomain("localhost")).isEqualTo("localhost")
    }
}
