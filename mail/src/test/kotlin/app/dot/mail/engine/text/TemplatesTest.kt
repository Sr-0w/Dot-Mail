package app.dot.mail.engine.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TemplatesTest {

    private fun template(
        id: Long = 1,
        name: String = "T",
        subject: String? = null,
        bodyText: String = "",
        bodyHtml: String? = null,
    ): EmailTemplate = EmailTemplate(id, name, subject, bodyText, bodyHtml)

    private val vars = mapOf(
        "first_name" to "Bob",
        "name" to "Robert",
        "email" to "bob@example.com",
        "date" to "12 Jun 2026",
        "my_name" to "Ada",
    )

    // ---------------------------------------------------------------- render

    @Test
    fun `render fills every known placeholder in the body text without escaping`() {
        val t = template(
            bodyText = "Hi {{first_name}}, your email {{email}} was noted on {{date}}. Signed, {{my_name}} ({{name}}).",
        )
        assertThat(render(t, vars).bodyText)
            .isEqualTo("Hi Bob, your email bob@example.com was noted on 12 Jun 2026. Signed, Ada (Robert).")
    }

    @Test
    fun `render html-escapes values only in the html body`() {
        val t = template(
            subject = "Hello {{name}}",
            bodyText = "Plain: {{name}}",
            bodyHtml = "<p>Hello {{name}} &amp; friends</p>",
        )
        val out = render(t, mapOf("name" to """Bob "the builder" <b>x</b> & co"""))
        assertThat(out.subject).isEqualTo("Hello Bob \"the builder\" <b>x</b> & co")
        assertThat(out.bodyText).isEqualTo("Plain: Bob \"the builder\" <b>x</b> & co")
        assertThat(out.bodyHtml)
            .isEqualTo("<p>Hello Bob &quot;the builder&quot; &lt;b&gt;x&lt;/b&gt; &amp; co &amp; friends</p>")
    }

    @Test
    fun `render fills the subject without escaping`() {
        val out = render(template(subject = "See {{name}}"), mapOf("name" to "<img>"))
        assertThat(out.subject).isEqualTo("See <img>")
    }

    @Test
    fun `unknown placeholders are left as-is`() {
        val out = render(template(bodyText = "{{foo}} and {{first_name}}"), vars)
        assertThat(out.bodyText).isEqualTo("{{foo}} and Bob")
    }

    @Test
    fun `whitespace inside the braces is tolerated`() {
        val out = render(template(bodyText = "{{ name }} and {{  email  }}"), vars)
        assertThat(out.bodyText).isEqualTo("Robert and bob@example.com")
    }

    @Test
    fun `a known placeholder with no var value is left as-is`() {
        val out = render(template(bodyText = "On {{date}} see {{first_name}}"), mapOf("first_name" to "Bob"))
        assertThat(out.bodyText).isEqualTo("On {{date}} see Bob")
    }

    @Test
    fun `every occurrence of a placeholder is replaced`() {
        val out = render(template(bodyText = "{{name}}/{{name}}/{{name}}"), mapOf("name" to "x"))
        assertThat(out.bodyText).isEqualTo("x/x/x")
    }

    @Test
    fun `render preserves id and name`() {
        val t = template(id = 42, name = "My template", bodyText = "{{name}}")
        assertThat(render(t, vars).id).isEqualTo(42)
        assertThat(render(t, vars).name).isEqualTo("My template")
    }

    @Test
    fun `null subject and html stay null`() {
        val out = render(template(bodyText = "Hi {{first_name}}"), vars)
        assertThat(out.subject).isNull()
        assertThat(out.bodyHtml).isNull()
        assertThat(out.bodyText).isEqualTo("Hi Bob")
    }

    @Test
    fun `values with dollar signs and percent are safe`() {
        val out = render(
            template(bodyText = "Price: {{name}}", bodyHtml = "<i>{{name}}</i>"),
            mapOf("name" to "50% off for \$100 & change"),
        )
        assertThat(out.bodyText).isEqualTo("Price: 50% off for \$100 & change")
        assertThat(out.bodyHtml).isEqualTo("<i>50% off for \$100 &amp; change</i>")
    }

    // ---------------------------------------------------------- placeholders

    @Test
    fun `placeholders scans all fields and dedupes with trimmed names`() {
        val t = template(
            subject = "{{name}}",
            bodyText = "{{name}} {{ first_name }} {{unknown_one}}",
            bodyHtml = "{{email}}",
        )
        assertThat(placeholders(t)).containsExactly("name", "first_name", "unknown_one", "email")
    }

    @Test
    fun `placeholders is empty when there are none`() {
        assertThat(placeholders(template(bodyText = "plain text"))).isEmpty()
        assertThat(placeholders(template())).isEmpty()
    }

    @Test
    fun `placeholder tokens in the html body are reported`() {
        assertThat(placeholders(template(bodyHtml = "<a>{{my_name}}</a>")))
            .containsExactly("my_name")
    }

    // ------------------------------------------------------------- escaping

    @Test
    fun `escapeHtml escapes the five significant characters`() {
        assertThat(escapeHtml("a & b <c> \"d\" 'e'"))
            .isEqualTo("a &amp; b &lt;c&gt; &quot;d&quot; &#39;e&#39;")
    }

    @Test
    fun `escapeHtml is a no-op for plain text`() {
        assertThat(escapeHtml("hello 123")).isEqualTo("hello 123")
    }

    @Test
    fun `the known placeholder set is exposed`() {
        assertThat(KNOWN_PLACEHOLDERS)
            .containsExactly("first_name", "name", "email", "date", "my_name")
    }
}
