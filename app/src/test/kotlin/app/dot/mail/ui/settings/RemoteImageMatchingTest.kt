package app.dot.mail.ui.settings

import app.dot.mail.data.model.EmailAddress
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RemoteImageMatchingTest {
    private val m = RemoteImageMatching
    private fun reason(
        from: String, global: Boolean = false, approvals: Set<String> = emptySet(),
        rules: List<RemoteImageRule> = emptyList(), account: Long = 1, key: String = m.messageKey(1, "<a@x>", 10),
    ) = m.reason(account, key, EmailAddress(from), global, approvals, rules)

    @Test fun `blocked by default`() {
        assertThat(reason("news@example.org")).isEqualTo(LoadReason.NONE)
    }

    @Test fun `global switch wins and is reported as such`() {
        assertThat(reason("news@example.org", global = true)).isEqualTo(LoadReason.GLOBAL)
    }

    @Test fun `message keys normalise the header and fall back to the local id`() {
        assertThat(m.messageKey(1, "<ABC@Host.Org>", 5)).isEqualTo(m.messageKey(1, " abc@host.org ", 99))
        assertThat(m.messageKey(1, "<abc@host.org>", 5)).isNotEqualTo(m.messageKey(2, "<abc@host.org>", 5)) // account-scoped
        assertThat(m.messageKey(1, null, 5)).isEqualTo("1|local:5")
        assertThat(m.messageKey(1, "  <> ", 5)).isEqualTo("1|local:5")
    }

    @Test fun `a message approval survives a different local id`() {
        val key = m.messageKey(1, "<a@x>", 10)
        assertThat(reason("a@b.org", approvals = setOf(key), key = m.messageKey(1, "<A@X>", 777))).isEqualTo(LoadReason.MESSAGE)
        assertThat(reason("a@b.org", approvals = setOf(key), account = 2, key = m.messageKey(2, "<a@x>", 10))).isEqualTo(LoadReason.NONE)
    }

    @Test fun `address rules need an exact case-insensitive match`() {
        val rules = listOf(RemoteImageRule(false, "shop@example.org"))
        assertThat(reason("Shop@Example.ORG", rules = rules)).isEqualTo(LoadReason.SENDER)
        assertThat(reason("other@example.org", rules = rules)).isEqualTo(LoadReason.NONE)
        assertThat(reason("shop@example.org.evil.com", rules = rules)).isEqualTo(LoadReason.NONE)
    }

    @Test fun `domain rules match the domain and real subdomains only`() {
        val rules = listOf(RemoteImageRule(true, "example.org"))
        assertThat(reason("a@example.org", rules = rules)).isEqualTo(LoadReason.DOMAIN)
        assertThat(reason("a@mail.example.org", rules = rules)).isEqualTo(LoadReason.DOMAIN)
        assertThat(reason("a@evilexample.org", rules = rules)).isEqualTo(LoadReason.NONE)
        assertThat(reason("a@example.org.evil.com", rules = rules)).isEqualTo(LoadReason.NONE)
        assertThat(reason("a@org", rules = rules)).isEqualTo(LoadReason.NONE)
        assertThat(reason("not-an-address", rules = rules)).isEqualTo(LoadReason.NONE)
    }

    @Test fun `rules can be scoped to one account`() {
        val rules = listOf(RemoteImageRule(true, "example.org", accountId = 2))
        assertThat(reason("a@example.org", rules = rules, account = 1)).isEqualTo(LoadReason.NONE)
        assertThat(reason("a@example.org", rules = rules, account = 2)).isEqualTo(LoadReason.DOMAIN)
    }

    @Test fun `domains are validated`() {
        assertThat(m.normaliseDomain("@Example.ORG")).isEqualTo("example.org")
        assertThat(m.normaliseDomain("*.example.org")).isEqualTo("example.org")
        assertThat(m.normaliseDomain("com")).isNull() // a bare TLD would allow everything under it
        assertThat(m.normaliseDomain("a..b")).isNull()
        assertThat(m.normaliseDomain("exa mple.org")).isNull()
        assertThat(m.normaliseDomain("")).isNull()
    }

    @Test fun `rules round-trip through the storage format and bad lines are dropped`() {
        val rules = listOf(RemoteImageRule(false, "a@b.org", 0), RemoteImageRule(true, "example.org", 3))
        assertThat(m.decodeRules(m.encodeRules(rules))).containsExactlyElementsIn(rules).inOrder()
        assertThat(m.decodeRules("x\ty\tz\nd\tcom\t0\na\tnoat\t0\ngarbage")).isEmpty()
        assertThat(m.decodeRules(null)).isEmpty()
    }
}
