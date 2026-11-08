package app.dot.mail

import app.dot.design.initials
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the pure [initials] helper from the design system
 * (designsystem/src/main/kotlin/app/dot/design/components/Basics.kt).
 *
 * Hosted under :app because :designsystem has no testImplementation
 * dependencies yet; the function itself is pure JVM (no android.*).
 */
class InitialsTest {

    private fun i(name: String) = initials(name)

    @Test
    fun `two words produce first and last initial`() {
        assertEquals("AS", i("Alice Smith"))
    }

    @Test
    fun `lowercase is uppercased`() {
        assertEquals("AS", i("alice smith"))
    }

    @Test
    fun `already uppercase is unchanged`() {
        assertEquals("AS", i("ALICE SMITH"))
    }

    @Test
    fun `single word takes the first letter`() {
        assertEquals("P", i("Plato"))
    }

    @Test
    fun `single letter`() {
        assertEquals("A", i("a"))
    }

    @Test
    fun `three words use first and last not first and second`() {
        assertEquals("ML", i("Mary Ann Lee"))
    }

    @Test
    fun `dot separated parts`() {
        assertEquals("JS", i("J. Smith"))
        assertEquals("AC", i("A.B.C")) // first and last of [A, B, C]
    }

    @Test
    fun `hyphen separated parts`() {
        assertEquals("JD", i("Jean-Dupont"))
    }

    @Test
    fun `underscore separated parts`() {
        assertEquals("AS", i("Alice_Smith"))
    }

    @Test
    fun `email address uses the local part`() {
        assertEquals("U", i("user@example.com"))
    }

    @Test
    fun `BUG display name with embedded address`() {
        // A raw "Name <addr>" string is a realistic input for a pure initials helper
        // whose own contract already special-cases '@' (email addresses).
        assertEquals("AS", i("Alice Smith <a@b.co>"))
    }

    @Test
    fun `empty name falls back to the middle dot`() {
        assertEquals("·", i(""))
    }

    @Test
    fun `whitespace only falls back to the middle dot`() {
        assertEquals("·", i("   "))
    }

    @Test
    fun `punctuation only falls back to the middle dot`() {
        assertEquals("·", i("!!!"))
    }

    @Test
    fun `digits are kept`() {
        assertEquals("AB", i("a1 b2"))
    }

    @Test
    fun `unicode letters are supported`() {
        assertEquals("ЖД", i("Жан Дюбуа"))
        assertEquals("SM", i("Søren Møller"))
    }

    @Test
    fun `apostrophes split words`() {
        assertEquals("ON", i("O'Neil"))
    }
}
