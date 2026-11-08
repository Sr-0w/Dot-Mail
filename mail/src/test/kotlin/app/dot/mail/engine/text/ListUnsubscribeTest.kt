package app.dot.mail.engine.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ListUnsubscribeTest {

    // ------------------------------------------------- real-world shapes

    @Test
    fun `gmail style one click with mailto and https`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("<mailto:owner@example.com?subject=Unsubscribe>, " +
                "<https://mail.example.com/r/u?aid=paid123>"),
            "List-Unsubscribe-Post" to listOf("List-Unsubscribe=One-Click"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.oneClickHttps).isEqualTo("https://mail.example.com/r/u?aid=paid123")
        assertThat(o.mailto).isEqualTo(MailtoTarget("owner@example.com", "Unsubscribe", null))
        assertThat(o.webPage).isNull()
        assertThat(o.listId).isNull()
    }

    @Test
    fun `mailchimp style http page plus mailto without one click`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("<http://list.example.com/u?u=abc123>, " +
                "<mailto:lists-admin@example.com?subject=Unsubscribe>"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.oneClickHttps).isNull()
        assertThat(o.webPage).isEqualTo("http://list.example.com/u?u=abc123")
        assertThat(o.mailto).isEqualTo(MailtoTarget("lists-admin@example.com", "Unsubscribe", null))
    }

    @Test
    fun `substack style one click https only`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("<https://substack.example.com/unsubscribe?token=tok123>"),
            "List-Unsubscribe-Post" to listOf("List-Unsubscribe=One-Click"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.oneClickHttps).isEqualTo("https://substack.example.com/unsubscribe?token=tok123")
        assertThat(o.mailto).isNull()
        assertThat(o.webPage).isNull()
    }

    @Test
    fun `one click requested but only an http uri yields nothing usable`() {
        assertThat(ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("<http://ex.com/u>"),
            "List-Unsubscribe-Post" to listOf("List-Unsubscribe=One-Click"),
        ))).isNull()
    }

    @Test
    fun `NONE with a list id still reports the list`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("NONE"),
            "List-Id" to listOf("News <news@example.org>"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.listId).isEqualTo("news@example.org")
        assertThat(o.oneClickHttps).isNull()
        assertThat(o.mailto).isNull()
        assertThat(o.webPage).isNull()
    }

    // --------------------------------------------------------- null cases

    @Test
    fun `no relevant headers returns null`() {
        assertThat(ListUnsubscribe.parse(mapOf("Subject" to listOf("hi")))).isNull()
        assertThat(ListUnsubscribe.parse(emptyMap())).isNull()
    }

    @Test
    fun `empty or relative entries yield nothing`() {
        assertThat(ListUnsubscribe.parse(mapOf("List-Unsubscribe" to listOf("")))).isNull()
        assertThat(ListUnsubscribe.parse(mapOf("List-Unsubscribe" to listOf("/unsubscribe")))).isNull()
        assertThat(ListUnsubscribe.parse(mapOf("List-Unsubscribe" to listOf("none")))).isNull()
    }

    // ------------------------------------------------- header handling

    @Test
    fun `header names are matched case insensitively`() {
        val o = ListUnsubscribe.parse(mapOf(
            "list-unsubscribe" to listOf("<https://ex.com/u>"),
            "LIST-UNSUBSCRIBE-POST" to listOf("list-unsubscribe=one-click"),
            "List-ID" to listOf("x <x@example.org>"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.oneClickHttps).isEqualTo("https://ex.com/u")
        assertThat(o.listId).isEqualTo("x@example.org")
    }

    @Test
    fun `multiple list unsubscribe headers are merged`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("<mailto:a@example.com>", " , <https://ex.com/u>"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.mailto).isEqualTo(MailtoTarget("a@example.com", null, null))
        assertThat(o.webPage).isEqualTo("https://ex.com/u")
    }

    @Test
    fun `folded header values are unfolded`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("<mailto:a@example.com?subject=Unsubscri\r\n be>, " +
                "<https://ex.com/l\r\n ong>"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.webPage).isEqualTo("https://ex.com/long")
        assertThat(o.mailto).isEqualTo(MailtoTarget("a@example.com", "Unsubscribe", null))
    }

    @Test
    fun `multiple angle bracketed entries keep first of each kind`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf(
                "<mailto:a@example.com?subject=First>, <https://ex.com/a>, <http://ex.com/b>"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.webPage).isEqualTo("https://ex.com/a")
        assertThat(o.mailto).isEqualTo(MailtoTarget("a@example.com", "First", null))
    }

    @Test
    fun `entries without angle brackets split on commas`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("https://ex.com/u, mailto:a@example.com"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.webPage).isEqualTo("https://ex.com/u")
        assertThat(o.mailto).isEqualTo(MailtoTarget("a@example.com", null, null))
    }

    @Test
    fun `comments are stripped from entries`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf(
                "<https://ex.com/u (unsubscribe)> (see docs), <mailto:a@example.com (list owner)>"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.webPage).isEqualTo("https://ex.com/u")
        assertThat(o.mailto).isEqualTo(MailtoTarget("a@example.com", null, null))
    }

    @Test
    fun `junk between angle bracketed entries is ignored`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf(
                "junk text <https://ex.com/u> more junk <mailto:a@example.com> tail"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.webPage).isEqualTo("https://ex.com/u")
        assertThat(o.mailto).isEqualTo(MailtoTarget("a@example.com", null, null))
    }

    @Test
    fun `the web page is the first https or http in header order`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("<http://ex.com/a>, <https://ex.com/b>"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.webPage).isEqualTo("http://ex.com/a")
    }

    @Test
    fun `an undecodable list id form yields no list id`() {
        assertThat(ListUnsubscribe.parse(mapOf("List-Id" to listOf("<>")))).isNull()
        val o = ListUnsubscribe.parse(mapOf(
            "List-Id" to listOf("a <b"),
            "List-Unsubscribe" to listOf("<https://ex.com/u>"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.listId).isNull()
        assertThat(o.webPage).isEqualTo("https://ex.com/u")
    }

    // ------------------------------------------------- one-click handling

    @Test
    fun `one click request body is the exact token`() {
        assertThat(ListUnsubscribe.oneClickRequestBody()).isEqualTo("List-Unsubscribe=One-Click")
    }

    @Test
    fun `one click tolerates whitespace and case`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("<https://ex.com/oc>"),
            "List-Unsubscribe-Post" to listOf("  list-unsubscribe = ONE-CLICK  "),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.oneClickHttps).isEqualTo("https://ex.com/oc")
        assertThat(o.webPage).isNull()
    }

    @Test
    fun `a similar but wrong post value is not one click`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("<https://ex.com/u>"),
            "List-Unsubscribe-Post" to listOf("List-Unsubscribe=One-Clicks"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.webPage).isEqualTo("https://ex.com/u")
        assertThat(o.oneClickHttps).isNull()
    }

    @Test
    fun `multiple post headers, a valid one wins`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("<https://ex.com/oc>"),
            "List-Unsubscribe-Post" to listOf("junk", "List-Unsubscribe=One-Click"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.oneClickHttps).isEqualTo("https://ex.com/oc")
    }

    @Test
    fun `one click with https and http uses the https only`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("<https://ex.com/oc>, <http://ex.com/page>"),
            "List-Unsubscribe-Post" to listOf("List-Unsubscribe=One-Click"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.oneClickHttps).isEqualTo("https://ex.com/oc")
        assertThat(o.webPage).isNull()
    }

    // ----------------------------------------------------- entry rejects

    @Test
    fun `overlong uris are rejected`() {
        val long = "https://ex.com/" + "a".repeat(3000)
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("$long, mailto:keep@example.com"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.webPage).isNull()
        assertThat(o.mailto).isEqualTo(MailtoTarget("keep@example.com", null, null))
    }

    @Test
    fun `non ascii entries are rejected`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("https://ex.com/\u00e9, mailto:a@example.com"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.webPage).isNull()
        assertThat(o.mailto).isEqualTo(MailtoTarget("a@example.com", null, null))
    }

    @Test
    fun `url entries containing whitespace are rejected`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("<https://ex.com/a b>, <https://ok.example.com/x>"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.webPage).isEqualTo("https://ok.example.com/x")
    }

    @Test
    fun `mailto with empty address is rejected`() {
        assertThat(ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("<mailto:?subject=Bye>"),
        ))).isNull()
    }

    @Test
    fun `mailto address containing whitespace is rejected`() {
        assertThat(ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("<mailto:bad address@b.example.com>"),
        ))).isNull()
    }

    // ---------------------------------------------------- mailto details

    @Test
    fun `mailto subject and body are percent decoded`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf(
                "<mailto:a@example.com?subject=Hello%20World&body=Line%20one%0ALine%20two>"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.mailto).isEqualTo(
            MailtoTarget("a@example.com", "Hello World", "Line one\nLine two"))
    }

    @Test
    fun `mailto without query has null subject and body`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("mailto:a@example.com"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.mailto).isEqualTo(MailtoTarget("a@example.com", null, null))
    }

    @Test
    fun `mailto scheme and parameter names are case insensitive`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("<MAILTO:A@B.EXAMPLE.COM?SUBJECT=Hi&BODY=Yo>"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.mailto).isEqualTo(MailtoTarget("A@B.EXAMPLE.COM", "Hi", "Yo"))
    }

    @Test
    fun `unknown mailto params are ignored and first wins`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf(
                "<mailto:a@example.com?x=1&subject=First&subject=Second&body=Yo>"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.mailto).isEqualTo(MailtoTarget("a@example.com", "First", "Yo"))
    }

    @Test
    fun `the first usable mailto wins`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("mailto:a@example.com?subject=1, mailto:d@example.com?subject=2"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.mailto).isEqualTo(MailtoTarget("a@example.com", "1", null))
    }

    @Test
    fun `empty subject and body values are null`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("<mailto:a@example.com?subject=&body=>"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.mailto).isEqualTo(MailtoTarget("a@example.com", null, null))
    }

    @Test
    fun `malformed percent sequences are kept literal`() {
        val o = ListUnsubscribe.parse(mapOf(
            "List-Unsubscribe" to listOf("<mailto:a@example.com?subject=100%zz%20ok>"),
        ))
        assertThat(o).isNotNull()
        assertThat(o!!.mailto?.subject).isEqualTo("100%zz ok")
    }
}
