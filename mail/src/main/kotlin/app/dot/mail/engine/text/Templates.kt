package app.dot.mail.engine.text

/**
 * A stored email template. [subject] may be null (no subject template);
 * [bodyHtml] may be null (plain-text-only template). Placeholder tokens are
 * `{{name}}` — see [placeholders] / [render].
 */
data class EmailTemplate(
    val id: Long,
    val name: String,
    val subject: String?,
    val bodyText: String,
    val bodyHtml: String?,
)

/** The placeholders the app knows how to fill. */
val KNOWN_PLACEHOLDERS: Set<String> =
    setOf("first_name", "name", "email", "date", "my_name")

private val PLACEHOLDER_RE = Regex("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}")

/**
 * The distinct placeholder names used anywhere in [template] (subject, body
 * text, body HTML), brace whitespace trimmed. Unknown names are reported too —
 * they simply never get filled by [render].
 */
fun placeholders(template: EmailTemplate): Set<String> {
    val out = mutableSetOf<String>()
    for (field in listOfNotNull(template.subject, template.bodyText, template.bodyHtml)) {
        for (m in PLACEHOLDER_RE.findAll(field)) out += m.groupValues[1]
    }
    return out
}

/**
 * Returns [template] with every known placeholder filled from [vars]:
 * `{{first_name}}`, `{{name}}`, `{{email}}`, `{{date}}`, `{{my_name}}`
 * (whitespace inside the braces is tolerated, e.g. `{{ name }}`).
 *
 * - Values are HTML-escaped in [EmailTemplate.bodyHtml] but left raw in
 *   [EmailTemplate.bodyText] and [EmailTemplate.subject].
 * - Unknown placeholders, and known ones with no value in [vars], are kept
 *   verbatim.
 * - [EmailTemplate.id] and [EmailTemplate.name] are preserved.
 */
fun render(template: EmailTemplate, vars: Map<String, String>): EmailTemplate {
    fun replace(text: String?, html: Boolean): String? = text?.let { t ->
        PLACEHOLDER_RE.replace(t) { m ->
            val key = m.groupValues[1]
            val value = vars[key]
            if (key in KNOWN_PLACEHOLDERS && value != null) {
                if (html) escapeHtml(value) else value
            } else m.value
        }
    }
    return EmailTemplate(
        id = template.id,
        name = template.name,
        subject = replace(template.subject, false),
        bodyText = replace(template.bodyText, false) ?: "",
        bodyHtml = replace(template.bodyHtml, true),
    )
}

/** HTML-escapes the five significant characters. */
fun escapeHtml(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")
