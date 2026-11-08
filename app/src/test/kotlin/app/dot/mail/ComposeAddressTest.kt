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
        assertEquals("<div data-dot-sig=\"1\"><p>-- <br>Best,<br>Alice &lt;A&amp;B&gt;</p></div>", ComposeViewModel.richSignatureBlock(plainOnly))

        val richSender = plainOnly.copy(signatureHtml = "<i> Alice </i>")
        assertEquals("<div data-dot-sig=\"1\"><p>-- <br><i> Alice </i></p></div>", ComposeViewModel.richSignatureBlock(richSender))

        val withLogo = richSender.copy(signatureLogo = "logo.png")
        assertTrue(ComposeViewModel.richSignatureBlock(withLogo)!!.contains("<img src=\"cid:dotsig-logo\""))
        assertEquals(null, ComposeViewModel.richSignatureBlock(plainOnly.copy(signature = null)))
    }

    private val alice = app.dot.mail.ui.compose.Sender(1L, 1L, "Alice", "a@x.org", signatureHtml = "<b>A</b>")

    @Test
    fun `signature block sits between the editor text and the quote and is recoverable`() {
        val block = ComposeViewModel.richSignatureBlock(alice)
        val combined = ComposeViewModel.combineBodyHtml("<p>Hi</p>", "On Mon, B wrote:\n> yo", block)
        assertTrue(combined.indexOf("Hi") < combined.indexOf("data-dot-sig"))
        assertTrue(combined.indexOf("data-dot-sig") < combined.indexOf("B wrote"))
        val editable = ComposeViewModel.extractEditableHtml(combined, "On Mon, B wrote:\n> yo")
        val (owner, rest) = ComposeViewModel.splitSignature(editable)
        assertEquals(1L, owner)
        assertEquals("<p>Hi</p>", rest)
    }

    @Test
    fun `splitSignature leaves html without a marker alone and never eats user text`() {
        val (none, same) = ComposeViewModel.splitSignature("<p>-- <br>Alice</p>")
        assertEquals(null, none)
        assertEquals("<p>-- <br>Alice</p>", same)
        val (owner, rest) = ComposeViewModel.splitSignature("<p>a</p>" + ComposeViewModel.richSignatureBlock(alice) + "<p>after</p>")
        assertEquals(1L, owner)
        assertEquals("<p>a</p><p>after</p>", rest)
    }

    @Test
    fun `plain body appends the signature before the quote`() {
        val plain = ComposeViewModel.plainSignatureBlock(alice.copy(signature = "Alice", signatureHtml = null))
        assertEquals("hello\n\n-- \nAlice\n\n> q", ComposeViewModel.combineBodyText("hello", "> q", plain))
        assertEquals("\n\n-- \nAlice", ComposeViewModel.combineBodyText("", null, plain))
    }

    @Test
    fun `new and reply defaults are independent`() {
        val s = alice.copy(signatureOnNew = true, signatureOnReply = false)
        assertTrue(ComposeViewModel.signatureDefault(s, isReply = false))
        assertFalse(ComposeViewModel.signatureDefault(s, isReply = true))
        assertFalse(ComposeViewModel.signatureDefault(s.copy(signatureHtml = null), isReply = false)) // nothing to insert
    }

    @Test
    fun `an untouched signature does not make an otherwise empty draft worth saving`() {
        val draft = app.dot.mail.data.model.Draft(accountId = 1L, identityId = 1L, bodyText = "\n\n-- \nAlice")
        val st = app.dot.mail.ui.compose.ComposeState(loaded = true, draft = draft, senders = listOf(alice), signatureEnabled = true)
        assertTrue(st.isEmpty)
        assertEquals(alice, st.signatureSender)
        assertEquals(null, st.copy(signatureEnabled = false).signatureSender)
        val other = alice.copy(identityId = 2L, signatureHtml = "<i>O</i>")
        assertEquals(other, st.copy(senders = listOf(alice, other), signatureSourceKey = 2L).signatureSender)
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
