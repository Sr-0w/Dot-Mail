package app.dot.mail.engine.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LinksTest {

    // ------------------------------------------------------- stripTracking

    @Test
    fun `strips the utm family and keeps remaining params in order`() {
        assertThat(Links.stripTracking("https://ex.com/p?b=2&utm_source=x&a=1&utm_medium=y"))
            .isEqualTo("https://ex.com/p?b=2&a=1")
    }

    @Test
    fun `strips known ids case insensitively`() {
        assertThat(Links.stripTracking("https://ex.com/?UTM_SOURCE=x&Fbclid=y&GCLID=z&keep=1"))
            .isEqualTo("https://ex.com/?keep=1")
    }

    @Test
    fun `drops the question mark when every param is tracking`() {
        assertThat(Links.stripTracking("https://ex.com/x?gclid=abc&fbclid=def"))
            .isEqualTo("https://ex.com/x")
    }

    @Test
    fun `preserves the fragment with remaining params`() {
        assertThat(Links.stripTracking("https://ex.com/x?keep=1&utm_z=a#section"))
            .isEqualTo("https://ex.com/x?keep=1#section")
    }

    @Test
    fun `preserves the fragment when all params are stripped`() {
        assertThat(Links.stripTracking("https://ex.com/x?utm_source=a#top"))
            .isEqualTo("https://ex.com/x#top")
    }

    @Test
    fun `keeps a param whose value merely mentions utm`() {
        assertThat(Links.stripTracking("https://ex.com/?q=utm_source&f=1"))
            .isEqualTo("https://ex.com/?q=utm_source&f=1")
    }

    @Test
    fun `keeps valueless params and equals signs inside values`() {
        assertThat(Links.stripTracking("https://ex.com/?fbclid&a=b=c&msclkid=1"))
            .isEqualTo("https://ex.com/?a=b=c")
    }

    @Test
    fun `leaves non http urls untouched`() {
        assertThat(Links.stripTracking("mailto:a@b.c?subject=x&utm_source=y"))
            .isEqualTo("mailto:a@b.c?subject=x&utm_source=y")
        assertThat(Links.stripTracking("ftp://files.example.com/?gclid=1"))
            .isEqualTo("ftp://files.example.com/?gclid=1")
    }

    @Test
    fun `returns the url unchanged when no tracking params are present`() {
        assertThat(Links.stripTracking("https://ex.com/a/b?nope=1&x=2"))
            .isEqualTo("https://ex.com/a/b?nope=1&x=2")
        assertThat(Links.stripTracking("https://ex.com/a#only-a-fragment"))
            .isEqualTo("https://ex.com/a#only-a-fragment")
    }

    @Test
    fun `drops a trailing ampersand once its param is stripped`() {
        assertThat(Links.stripTracking("https://ex.com/?utm_x=1&"))
            .isEqualTo("https://ex.com/")
        assertThat(Links.stripTracking("https://ex.com/?a=1&"))
            .isEqualTo("https://ex.com/?a=1")
    }

    @Test
    fun `handles an uppercase scheme`() {
        assertThat(Links.stripTracking("HTTPS://EX.COM/?UTM_A=b&c=1"))
            .isEqualTo("HTTPS://EX.COM/?c=1")
    }

    @Test
    fun `strips the whole known list in one go`() {
        val url = "https://ex.com/?fbclid=a&gclid=b&dclid=c&gbraid=d&wbraid=e&msclkid=f" +
            "&mc_cid=g&mc_eid=h&_hsenc=i&_hsmi=j&mkt_tok=k&igshid=l&yclid=m" +
            "&oly_anon_id=n&oly_enc_id=o&vero_id=p&__s=q&ref_src=r&keep=1"
        assertThat(Links.stripTracking(url)).isEqualTo("https://ex.com/?keep=1")
    }

    @Test
    fun `preserves the percent encoding of surviving params`() {
        assertThat(Links.stripTracking("https://ex.com/?keep=h%C3%A9llo%20w&utm_z=1"))
            .isEqualTo("https://ex.com/?keep=h%C3%A9llo%20w")
    }

    // ------------------------------------------------------------ describe

    @Test
    fun `describes a plain https link`() {
        assertThat(Links.describe("https://example.com/page"))
            .isEqualTo(LinkInfo("example.com", false, true))
    }

    @Test
    fun `describes a plain http link as not https`() {
        assertThat(Links.describe("http://example.com"))
            .isEqualTo(LinkInfo("example.com", false, false))
    }

    @Test
    fun `returns null for non url inputs`() {
        assertThat(Links.describe("")).isNull()
        assertThat(Links.describe("hello world")).isNull()
        assertThat(Links.describe("mailto:a@b.c")).isNull()
        assertThat(Links.describe("ftp://files.example.com/x")).isNull()
    }

    @Test
    fun `decodes a punycode label into the display host`() {
        // xn--p1ai is the .рф TLD
        assertThat(Links.describe("https://mail.xn--p1ai/"))
            .isEqualTo(LinkInfo("mail.\u0440\u0444", true, true))
    }

    @Test
    fun `flags a punycode host that decodes to pure non latin`() {
        assertThat(Links.describe("https://xn--p1ai/"))
            .isEqualTo(LinkInfo("\u0440\u0444", true, true))
    }

    @Test
    fun `flags latin cyrillic mixing in a unicode host`() {
        // "еxample.com" — first letter is the Cyrillic е
        assertThat(Links.describe("https://\u0435xample.com/"))
            .isEqualTo(LinkInfo("\u0435xample.com", true, true))
    }

    @Test
    fun `flags latin greek mixing in a unicode host`() {
        // "εxample.com" — first letter is the Greek epsilon
        assertThat(Links.describe("https://\u03b5xample.com/"))
            .isEqualTo(LinkInfo("\u03b5xample.com", true, true))
    }

    @Test
    fun `a pure ascii host is not a risk`() {
        assertThat(Links.describe("https://a.b.example.com"))
            .isEqualTo(LinkInfo("a.b.example.com", false, true))
    }

    @Test
    fun `a pure cyrillic unicode host without punycode is not mixed`() {
        // "пример.рф" is entirely Cyrillic: no Latin letters and no punycode
        assertThat(Links.describe("https://\u043f\u0440\u0438\u043c\u0435\u0440.\u0440\u0444/"))
            .isEqualTo(LinkInfo("\u043f\u0440\u0438\u043c\u0435\u0440.\u0440\u0444", false, true))
    }

    @Test
    fun `strips userinfo and port before describing`() {
        assertThat(Links.describe("https://user:pw@Example.com:8443/x"))
            .isEqualTo(LinkInfo("Example.com", false, true))
    }

    @Test
    fun `an undecodable punycode label is suspicious`() {
        val info = Links.describe("https://xn--.com/")
        assertThat(info).isNotNull()
        assertThat(info!!.displayHost).isEqualTo("xn--.com")
        assertThat(info.isIdnHomographRisk).isTrue()
    }

    // -------------------------------------------------------- textMismatch

    @Test
    fun `matching text and href hosts do not mismatch`() {
        assertThat(Links.textMismatch("example.com", "https://example.com/x")).isFalse()
        assertThat(Links.textMismatch("https://example.com/deal", "https://example.com/deal")).isFalse()
    }

    @Test
    fun `different registrable hosts mismatch`() {
        assertThat(Links.textMismatch("evil.com", "https://example.com/")).isTrue()
    }

    @Test
    fun `subdomain text matches an apex href`() {
        assertThat(Links.textMismatch("shop.example.com", "https://example.com/")).isFalse()
    }

    @Test
    fun `different subdomains of the same registrable do not mismatch`() {
        // Registrable hosts are compared: a.example.com and b.example.com both
        // register as example.com.
        assertThat(Links.textMismatch("a.example.com", "https://b.example.com/")).isFalse()
        assertThat(Links.textMismatch("a.example.com", "https://sub.evil.com/")).isTrue()
    }

    @Test
    fun `co uk is treated as a two level suffix`() {
        assertThat(Links.textMismatch("example.co.uk", "https://mail.example.co.uk/")).isFalse()
        assertThat(Links.textMismatch("other.co.uk", "https://example.co.uk/")).isTrue()
    }

    @Test
    fun `com au is treated as a two level suffix`() {
        assertThat(Links.textMismatch("bank.com.au", "https://x.bank.com.au/")).isFalse()
        assertThat(Links.textMismatch("bank.com.au", "https://evil.com.au/")).isTrue()
    }

    @Test
    fun `text as a full url is compared by host`() {
        assertThat(Links.textMismatch("https://accounts.example.com", "https://evil.com/")).isTrue()
        assertThat(Links.textMismatch("http://example.com:80/x", "https://example.com/y")).isFalse()
    }

    @Test
    fun `prose text never mismatches`() {
        assertThat(Links.textMismatch("Click here", "https://example.com/")).isFalse()
        assertThat(Links.textMismatch("Visit us today", "https://example.com/")).isFalse()
        assertThat(Links.textMismatch("", "https://example.com/")).isFalse()
    }

    @Test
    fun `a port in the text is ignored`() {
        assertThat(Links.textMismatch("example.com:8080", "https://example.com/")).isFalse()
    }

    @Test
    fun `comparison is case insensitive`() {
        assertThat(Links.textMismatch("EXAMPLE.COM", "https://example.com")).isFalse()
        assertThat(Links.textMismatch("Evil.COM", "https://example.com")).isTrue()
    }

    @Test
    fun `a trailing dot is ignored`() {
        assertThat(Links.textMismatch("example.com.", "https://example.com")).isFalse()
    }

    @Test
    fun `an unparsable href never mismatches`() {
        assertThat(Links.textMismatch("example.com", "not a url")).isFalse()
        assertThat(Links.textMismatch("example.com", "mailto:a@b.c")).isFalse()
    }

    @Test
    fun `a unicode text host differing from an ascii href mismatches`() {
        assertThat(Links.textMismatch("\u0435xample.com", "https://example.com/")).isTrue()
    }
}
