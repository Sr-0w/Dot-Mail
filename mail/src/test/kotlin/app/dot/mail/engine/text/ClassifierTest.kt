package app.dot.mail.engine.text

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ClassifierTest {

    private fun input(
        from: String = "friend@example.com",
        fromName: String = "Friend",
        subject: String = "",
        snippet: String = "",
        headers: Map<String, List<String>> = emptyMap(),
        pdf: Boolean = false,
    ): ClassifierInput = ClassifierInput(from, fromName, subject, snippet, headers, pdf)

    private fun rule(
        id: String,
        field: Field = Field.SUBJECT,
        headerName: String? = null,
        op: Op = Op.CONTAINS,
        value: String = "x",
        category: Category = Category.BILL,
        enabled: Boolean = true,
    ): Rule = Rule(id, field, headerName, op, value, category, enabled)

    // ----------------------------------------------------- rule matching

    @Test
    fun `user rules are evaluated first and the first match wins`() {
        val c = Classifier.classify(
            input(subject = "hello there"),
            listOf(
                rule("r1", value = "hello", category = Category.BILL),
                rule("r2", value = "hello", category = Category.PURCHASE),
            ),
        )
        assertThat(c.category).isEqualTo(Category.BILL)
        assertThat(c.ruleId).isEqualTo("r1")
        assertThat(c.reason).contains("r1")
    }

    @Test
    fun `disabled rules are skipped`() {
        val c = Classifier.classify(
            input(subject = "hello there"),
            listOf(
                rule("r1", value = "hello", enabled = false, category = Category.BILL),
                rule("r2", value = "hello", category = Category.PURCHASE),
            ),
        )
        assertThat(c.category).isEqualTo(Category.PURCHASE)
        assertThat(c.ruleId).isEqualTo("r2")
    }

    @Test
    fun `equals compares case insensitively`() {
        val c = Classifier.classify(
            input(from = "news@example.com"),
            listOf(rule("r1", Field.FROM_ADDRESS, op = Op.EQUALS, value = "News@EXAMPLE.com")),
        )
        assertThat(c.ruleId).isEqualTo("r1")
    }

    @Test
    fun `from domain field matches the domain part`() {
        val c = Classifier.classify(
            input(from = "user@mail.example.com"),
            listOf(rule("r1", Field.FROM_DOMAIN, value = "example.com")),
        )
        assertThat(c.ruleId).isEqualTo("r1")
        val miss = Classifier.classify(
            input(from = "user@other.com"),
            listOf(rule("r1", Field.FROM_DOMAIN, op = Op.EQUALS, value = "example.com")),
        )
        assertThat(miss.ruleId).isNull()
    }

    @Test
    fun `ends with compares the tail case insensitively`() {
        val c = Classifier.classify(
            input(subject = "Your Invoice #1"),
            listOf(rule("r1", op = Op.ENDS_WITH, value = "invoice #1")),
        )
        assertThat(c.ruleId).isEqualTo("r1")
    }

    @Test
    fun `header rules look the header up case insensitively`() {
        val c = Classifier.classify(
            input(headers = mapOf("x-custom-tag" to listOf("VIP"))),
            listOf(rule("r1", Field.HEADER, headerName = "X-Custom-Tag", op = Op.EQUALS, value = "vip")),
        )
        assertThat(c.ruleId).isEqualTo("r1")
    }

    @Test
    fun `list id rules use the raw list id header value`() {
        val c = Classifier.classify(
            input(headers = mapOf("List-Id" to listOf("News <news@example.org>"))),
            listOf(rule("r1", Field.LIST_ID, value = "news@example.org")),
        )
        assertThat(c.ruleId).isEqualTo("r1")
    }

    @Test
    fun `regex matches with find semantics`() {
        val c = Classifier.classify(
            input(subject = "your order #42 is here"),
            listOf(rule("r1", op = Op.REGEX, value = "order #\\d+")),
        )
        assertThat(c.ruleId).isEqualTo("r1")
    }

    @Test
    fun `an invalid regex simply does not match`() {
        val c = Classifier.classify(
            input(subject = "anything at all"),
            listOf(rule("r1", op = Op.REGEX, value = "(")),
        )
        assertThat(c.ruleId).isNull()
        assertThat(c.category).isEqualTo(Category.PERSONAL)
    }

    @Test
    fun `regex patterns longer than 200 chars are rejected`() {
        val longPattern = "(?:" + "a".repeat(201) + ")|aaa"
        val c = Classifier.classify(
            input(subject = "aaa"),
            listOf(rule("r1", op = Op.REGEX, value = longPattern)),
        )
        assertThat(c.ruleId).isNull()
    }

    @Test
    fun `regex input is truncated to 2000 chars`() {
        val far = "x".repeat(2400) + "MARKER"
        val cFar = Classifier.classify(input(subject = far), listOf(rule("r1", op = Op.REGEX, value = "MARKER")))
        assertThat(cFar.ruleId).isNull()
        val near = "MARKER" + "x".repeat(2400)
        val cNear = Classifier.classify(input(subject = near), listOf(rule("r1", op = Op.REGEX, value = "MARKER")))
        assertThat(cNear.ruleId).isEqualTo("r1")
    }

    @Test
    fun `an empty rule value never matches`() {
        val c = Classifier.classify(
            input(subject = "anything"),
            listOf(rule("r1", value = "")),
        )
        assertThat(c.ruleId).isNull()
    }

    @Test
    fun `with no rules the built in heuristics run`() {
        val c = Classifier.classify(
            input(headers = mapOf("List-Unsubscribe" to listOf("<https://ex.com/u>"))),
            emptyList(),
        )
        assertThat(c.category).isEqualTo(Category.NEWSLETTER)
        assertThat(c.ruleId).isNull()
    }

    // ------------------------------------------------- built-in heuristics

    @Test
    fun `list unsubscribe means newsletter`() {
        val c = Classifier.classify(input(headers = mapOf("List-Unsubscribe" to listOf("<https://ex.com/u>"))))
        assertThat(c.category).isEqualTo(Category.NEWSLETTER)
        assertThat(c.reason).contains("List-Unsubscribe")
    }

    @Test
    fun `list id alone means newsletter`() {
        val c = Classifier.classify(input(headers = mapOf("List-Id" to listOf("News <news@example.org>"))))
        assertThat(c.category).isEqualTo(Category.NEWSLETTER)
        assertThat(c.reason).contains("List-Id")
    }

    @Test
    fun `precedence bulk or list means newsletter`() {
        assertThat(Classifier.classify(input(headers = mapOf("Precedence" to listOf("bulk")))).category)
            .isEqualTo(Category.NEWSLETTER)
        assertThat(Classifier.classify(input(headers = mapOf("Precedence" to listOf("list")))).category)
            .isEqualTo(Category.NEWSLETTER)
    }

    @Test
    fun `mailchimp headers mean newsletter`() {
        val c = Classifier.classify(
            input(headers = mapOf("X-Mailchimp-Feedback" to listOf("id=xyz,username=abc"))),
        )
        assertThat(c.category).isEqualTo(Category.NEWSLETTER)
        assertThat(c.reason).contains("Mailchimp")
    }

    @Test
    fun `campaign headers mean newsletter`() {
        val c = Classifier.classify(input(headers = mapOf("X-Campaign-ID" to listOf("12345"))))
        assertThat(c.category).isEqualTo(Category.NEWSLETTER)
        assertThat(c.reason).contains("Campaign")
    }

    @Test
    fun `a campaign like feedback id means newsletter`() {
        val c = Classifier.classify(input(headers = mapOf("Feedback-ID" to listOf("ex.com:newsletter:abc"))))
        assertThat(c.category).isEqualTo(Category.NEWSLETTER)
    }

    @Test
    fun `github headers mean notification`() {
        val c = Classifier.classify(input(headers = mapOf("X-GitHub-Id" to listOf("1"))))
        assertThat(c.category).isEqualTo(Category.NOTIFICATION)
        assertThat(c.reason).contains("X-GitHub")
    }

    @Test
    fun `auto submitted means notification`() {
        val c = Classifier.classify(input(headers = mapOf("Auto-Submitted" to listOf("auto-generated"))))
        assertThat(c.category).isEqualTo(Category.NOTIFICATION)
    }

    @Test
    fun `a no reply sender without list headers means notification`() {
        val c = Classifier.classify(input(from = "no-reply@service.io", subject = "Account update"))
        assertThat(c.category).isEqualTo(Category.NOTIFICATION)
        assertThat(c.reason).contains("no-reply")
    }

    @Test
    fun `a no reply sender with list unsubscribe is a newsletter instead`() {
        val c = Classifier.classify(
            input(
                from = "no-reply@service.io",
                subject = "Weekly digest",
                headers = mapOf("List-Unsubscribe" to listOf("<https://service.io/unsub>")),
            ),
        )
        assertThat(c.category).isEqualTo(Category.NEWSLETTER)
    }

    @Test
    fun `security wording from a no reply sender means notification`() {
        val c = Classifier.classify(
            input(from = "notify@bank.io", subject = "Security Alert: new sign-in detected"),
        )
        assertThat(c.category).isEqualTo(Category.NOTIFICATION)
        assertThat(c.reason).contains("security")
    }

    @Test
    fun `an invoice subject means bill`() {
        val c = Classifier.classify(input(subject = "Invoice #2026-114"))
        assertThat(c.category).isEqualTo(Category.BILL)
        assertThat(c.reason).contains("invoice")
    }

    @Test
    fun `facture and rechnung mean bill`() {
        assertThat(Classifier.classify(input(subject = "Votre facture n° 12")).category)
            .isEqualTo(Category.BILL)
        assertThat(Classifier.classify(input(subject = "Ihre Rechnung ist bereit")).category)
            .isEqualTo(Category.BILL)
    }

    @Test
    fun `a weak bill keyword with an amount means bill`() {
        val c = Classifier.classify(input(subject = "Your statement is due", snippet = "Total: $120.00"))
        assertThat(c.category).isEqualTo(Category.BILL)
        assertThat(c.reason).contains("due")
    }

    @Test
    fun `a weak bill keyword with a pdf attachment means bill`() {
        val c = Classifier.classify(input(subject = "Your statement", pdf = true))
        assertThat(c.category).isEqualTo(Category.BILL)
    }

    @Test
    fun `a weak bill keyword alone does not mean bill`() {
        val c = Classifier.classify(input(subject = "due"))
        assertThat(c.category).isEqualTo(Category.PERSONAL)
    }

    @Test
    fun `an order keyword means purchase`() {
        val c = Classifier.classify(input(subject = "Order #42 confirmed"))
        assertThat(c.category).isEqualTo(Category.PURCHASE)
        assertThat(c.reason).contains("order")
    }

    @Test
    fun `shipping and livraison mean purchase`() {
        assertThat(Classifier.classify(input(subject = "Your package is shipping")).category)
            .isEqualTo(Category.PURCHASE)
        assertThat(Classifier.classify(input(subject = "Livraison de votre colis")).category)
            .isEqualTo(Category.PURCHASE)
    }

    @Test
    fun `bill wins over purchase`() {
        val c = Classifier.classify(input(subject = "Invoice for your order #42"))
        assertThat(c.category).isEqualTo(Category.BILL)
    }

    @Test
    fun `social sender domains mean social`() {
        assertThat(Classifier.classify(input(from = "mail@facebookmail.com")).category)
            .isEqualTo(Category.SOCIAL)
        assertThat(Classifier.classify(input(from = "update@linkedin.com")).category)
            .isEqualTo(Category.SOCIAL)
        assertThat(Classifier.classify(input(from = "post@x.com")).category)
            .isEqualTo(Category.SOCIAL)
        assertThat(Classifier.classify(input(from = "news@twitter.com")).category)
            .isEqualTo(Category.SOCIAL)
        assertThat(Classifier.classify(input(from = "photo@instagram.com")).category)
            .isEqualTo(Category.SOCIAL)
    }

    @Test
    fun `sub domains of x com mean social`() {
        val c = Classifier.classify(input(from = "post@sub.x.com"))
        assertThat(c.category).isEqualTo(Category.SOCIAL)
        // but a lookalike domain must not
        val miss = Classifier.classify(input(from = "a@notx.com", subject = "Dinner plans"))
        assertThat(miss.category).isEqualTo(Category.PERSONAL)
    }

    @Test
    fun `mastodon style headers mean social`() {
        val c = Classifier.classify(
            input(from = "user@toot.example", headers = mapOf("X-Mastodon-Server" to listOf("https://toot.example"))),
        )
        assertThat(c.category).isEqualTo(Category.SOCIAL)
        assertThat(c.reason).contains("Mastodon")
    }

    @Test
    fun `accented keywords match without accents`() {
        assertThat(Classifier.classify(input(subject = "Votre échéance de 50€")).category)
            .isEqualTo(Category.BILL)
        assertThat(Classifier.classify(input(subject = "Merci pour votre reçu")).category)
            .isEqualTo(Category.PURCHASE)
        assertThat(Classifier.classify(input(subject = "Bestellung bestätigt")).category)
            .isEqualTo(Category.PURCHASE)
    }

    @Test
    fun `plain mail is personal`() {
        val c = Classifier.classify(input(subject = "Dinner on Saturday?"))
        assertThat(c.category).isEqualTo(Category.PERSONAL)
        assertThat(c.reason).contains("no matching signal")
        assertThat(c.ruleId).isNull()
    }

    // ------------------------------------------------------------ codec

    @Test
    fun `codec round trip preserves every rule field`() {
        val rules = listOf(
            Rule("r1", Field.FROM_ADDRESS, null, Op.EQUALS, "a@b.com", Category.BILL, true),
            Rule("r2", Field.HEADER, "X-Custom", Op.CONTAINS, "he said \"hi\"\n\\end", Category.NEWSLETTER, false),
            Rule("r3", Field.FROM_DOMAIN, null, Op.ENDS_WITH, "ex.co.uk", Category.SOCIAL, true),
            Rule("r4", Field.SUBJECT, null, Op.REGEX, "ord\\d", Category.PURCHASE, false),
            Rule("r5", Field.LIST_ID, null, Op.CONTAINS, "caf\u00e9", Category.NOTIFICATION, true),
        )
        assertThat(Classifier.decodeRules(Classifier.encodeRules(rules))).isEqualTo(rules)
    }

    @Test
    fun `encoding is deterministic compact json`() {
        val json = Classifier.encodeRules(
            listOf(Rule("a", Field.FROM_ADDRESS, null, Op.EQUALS, "x", Category.BILL, true)),
        )
        assertThat(json).isEqualTo(
            "[{\"id\":\"a\",\"field\":\"FROM_ADDRESS\",\"headerName\":null,\"op\":\"EQUALS\"," +
                "\"value\":\"x\",\"category\":\"BILL\",\"enabled\":true}]",
        )
    }

    @Test
    fun `decoding tolerates pretty printing and free key order`() {
        val json = """
            [
              {
                "enabled": true,
                "id": "a",
                "field": "FROM_ADDRESS",
                "headerName": null,
                "op": "EQUALS",
                "value": "x",
                "category": "BILL"
              }
            ]
        """.trimIndent()
        assertThat(Classifier.decodeRules(json))
            .isEqualTo(listOf(Rule("a", Field.FROM_ADDRESS, null, Op.EQUALS, "x", Category.BILL, true)))
    }

    @Test
    fun `decoding rejects invalid json`() {
        assertThrows(IllegalArgumentException::class.java) { Classifier.decodeRules("not json") }
        assertThrows(IllegalArgumentException::class.java) { Classifier.decodeRules("[{]") }
        assertThrows(IllegalArgumentException::class.java) { Classifier.decodeRules("[{} x]") }
    }

    @Test
    fun `decoding rejects unknown enum values`() {
        val json = """[{"id":"a","field":"BOGUS","headerName":null,"op":"EQUALS","value":"x","category":"BILL","enabled":true}]"""
        assertThrows(IllegalArgumentException::class.java) { Classifier.decodeRules(json) }
    }

    @Test
    fun `decoding rejects missing required keys`() {
        val json = """[{"id":"a","field":"FROM_ADDRESS","headerName":null,"op":"EQUALS","category":"BILL","enabled":true}]"""
        assertThrows(IllegalArgumentException::class.java) { Classifier.decodeRules(json) }
    }

    @Test
    fun `decoding an empty array yields no rules`() {
        assertThat(Classifier.decodeRules("[]")).isEmpty()
    }

    @Test fun `matchRules ignores the built-in heuristics and honours order and enabled`() {
        val input = ClassifierInput("promo@shop.example", "Shop", "Your order has shipped", "", mapOf("List-Id" to listOf("<deals.shop.example>")), false)
        assertThat(Classifier.matchRules(input, emptyList())).isNull() // built-in would say PURCHASE, but matchRules never does
        val off = Rule("a", Field.FROM_DOMAIN, null, Op.EQUALS, "shop.example", Category.SOCIAL, enabled = false)
        val listRule = Rule("b", Field.LIST_ID, null, Op.CONTAINS, "deals", Category.NEWSLETTER, enabled = true)
        val later = Rule("c", Field.SUBJECT, null, Op.CONTAINS, "order", Category.BILL, enabled = true)
        assertThat(Classifier.matchRules(input, listOf(off, listRule, later))!!.category).isEqualTo(Category.NEWSLETTER)
        assertThat(Classifier.classify(input, listOf(off)).category).isEqualTo(Category.PURCHASE)
    }
}
