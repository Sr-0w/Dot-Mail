package app.dot.mail

import app.dot.mail.data.model.EmailAddress
import app.dot.mail.ui.compose.ComposeViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the [ComposeViewModel] companion helpers
 * ([ComposeViewModel.parseAddress] / [ComposeViewModel.isValidEmail]).
 * JUnit4 assertions: Truth is not declared in :app's testImplementation.
 */
class ComposeAddressTest {

    private fun parse(raw: String) = ComposeViewModel.parseAddress(raw)

    // ------------------------------------------------------------ parseAddress

    @Test
    fun `plain address`() {
        assertEquals(EmailAddress("a@b.co"), parse("a@b.co"))
    }

    @Test
    fun `name with address`() {
        val e = parse("Alice <a@b.co>")
        assertEquals("a@b.co", e.email)
        assertEquals("Alice", e.name)
    }

    @Test
    fun `quoted name is unquoted`() {
        val e = parse("\"Alice\" <a@b.co>")
        assertEquals("a@b.co", e.email)
        assertEquals("Alice", e.name)
    }

    @Test
    fun `name containing angle brackets keeps them`() {
        val e = parse("A <b> <a@b.co>")
        assertEquals("a@b.co", e.email)
        assertEquals("A <b>", e.name)
    }

    @Test
    fun `brackets without a name yield null name`() {
        val e = parse("<a@b.co>")
        assertEquals("a@b.co", e.email)
        assertNull(e.name)
    }

    @Test
    fun `surrounding and inner whitespace is trimmed`() {
        val e = parse("  Alice   <  a@b.co  >  ")
        assertEquals("a@b.co", e.email)
        assertEquals("Alice", e.name)
    }

    @Test
    fun `stray closing bracket is trimmed`() {
        assertEquals(EmailAddress("a@b.co"), parse("a@b.co>"))
    }

    @Test
    fun `stray opening bracket is trimmed`() {
        assertEquals(EmailAddress("a@b.co"), parse("<a@b.co"))
    }

    @Test
    fun `displayName falls back to the address`() {
        assertEquals("Alice", EmailAddress("a@b.co", "Alice").displayName)
        assertEquals("a@b.co", EmailAddress("a@b.co").displayName)
        assertEquals("a@b.co", EmailAddress("a@b.co", "   ").displayName)
    }

    // ------------------------------------------------------------- isValidEmail

    private fun valid(s: String) = assertTrue("expected valid: $s", ComposeViewModel.isValidEmail(s))
    private fun invalid(s: String) = assertFalse("expected invalid: $s", ComposeViewModel.isValidEmail(s))

    @Test
    fun `valid addresses are accepted`() {
        listOf(
            "alice@example.com",
            "a+b@example.co",
            "a.b@example.com",
            "user@sub.domain.org",
            "jürgen@example.de",
            "a@xn--mnch-0ra.de",
        ).forEach(::valid)
    }

    @Test
    fun `whitespace is trimmed before matching`() {
        valid("  a@b.co  ")
    }

    @Test
    fun `rejects missing tld dot`() {
        invalid("a@b")
    }

    @Test
    fun `rejects single character tld`() {
        invalid("a@b.c") // no one-character TLD exists
    }

    @Test
    fun `rejects spaces in local part`() {
        invalid("a b@c.de")
    }

    @Test
    fun `rejects empty local part`() {
        invalid("@b.co")
    }

    @Test
    fun `rejects empty domain`() {
        invalid("a@")
    }

    @Test
    fun `rejects double at sign`() {
        invalid("a@@b.co")
    }

    @Test
    fun `rejects angle brackets`() {
        invalid("a<b@c.de")
        invalid("a<b@c.de>")
    }

    @Test
    fun `rejects empty string`() {
        invalid("")
        invalid("   ")
    }

    @Test
    fun `BUG consecutive dots in domain must be rejected`() {
        invalid("a@b..co")
    }

    @Test
    fun `BUG trailing dot in domain must be rejected`() {
        invalid("a@b.co.")
    }

    // ------------------------------------------------------------- bodyHtml & signatures

    @Test
    fun `combineBodyHtml and extractEditableHtml round-trip editor html with quoted tail`() {
        val editor = "<p>Hello <b>world</b></p>"
        val quote = "On Mon, Alice wrote:\n> hi there"
        val combined = ComposeViewModel.combineBodyHtml(editor, quote)
        assertTrue(combined.startsWith(editor))
        assertTrue(combined.contains("Alice wrote:"))
        assertEquals(editor, ComposeViewModel.extractEditableHtml(combined, quote))
    }

    @Test
    fun `signature blocks format plain and rich signatures`() {
        val plainOnly = app.dot.mail.ui.compose.Sender(
            accountId = 1L,
            identityId = 1L,
            name = "Alice",
            email = "alice@example.org",
            signature = "Best,\nAlice <A&B>",
            signatureHtml = null,
            isDefault = true,
        )
        assertEquals("-- \nBest,\nAlice <A&B>", ComposeViewModel.plainSignatureBlock(plainOnly))
        assertEquals("<p>-- <br>Best,<br>Alice &lt;A&amp;B&gt;</p>", ComposeViewModel.richSignatureBlock(plainOnly))

        val richSender = plainOnly.copy(signatureHtml = "<i> Alice </i>")
        assertEquals("<p>-- <br><i> Alice </i></p>", ComposeViewModel.richSignatureBlock(richSender))
    }

    @Test
    fun `canCollapseCcBcc is true only when both cc and bcc chips and pending text are empty`() {
        val emptyDraft = app.dot.mail.data.model.Draft(accountId = 1L, identityId = null)
        val base = app.dot.mail.ui.compose.ComposeState(loaded = true, draft = emptyDraft, showCcBcc = true)
        assertTrue(base.canCollapseCcBcc)

        val withCcChip = base.copy(draft = emptyDraft.copy(cc = listOf(EmailAddress("cc@example.org"))))
        assertFalse(withCcChip.canCollapseCcBcc)

        val withBccChip = base.copy(draft = emptyDraft.copy(bcc = listOf(EmailAddress("bcc@example.org"))))
        assertFalse(withBccChip.canCollapseCcBcc)

        val withPendingCc = base.copy(pending = mapOf(app.dot.mail.ui.compose.RecipientField.CC to "typing"))
        assertFalse(withPendingCc.canCollapseCcBcc)

        val withPendingToOnly = base.copy(pending = mapOf(app.dot.mail.ui.compose.RecipientField.TO to "to@example.org"))
        assertTrue(withPendingToOnly.canCollapseCcBcc)
    }
}
