package app.dot.mail.engine.text

import java.text.Normalizer
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Message categories the local classifier can produce. */
enum class Category { PERSONAL, NEWSLETTER, PURCHASE, BILL, NOTIFICATION, SOCIAL }

/** Which field a user [Rule] is evaluated against. */
enum class Field { FROM_ADDRESS, FROM_DOMAIN, SUBJECT, HEADER, LIST_ID }

/** How a user [Rule] compares its [Rule.value] against the field. */
enum class Op { EQUALS, CONTAINS, ENDS_WITH, REGEX }

/**
 * Everything the classifier looks at for one message. [headers] may use any
 * casing for the names; lookups through [firstHeader]/[hasHeaderKeyStarting]/
 * [hasHeaderKeyContaining] are case-insensitive.
 */
data class ClassifierInput(
    val fromAddress: String,
    val fromName: String,
    val subject: String,
    val snippet: String,
    val headers: Map<String, List<String>>,
    val hasAttachmentPdf: Boolean,
) {
    /** First value of the header named [name], case-insensitively; null when absent. */
    fun firstHeader(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    /** True when any header name starts with [prefix] (case-insensitive). */
    fun hasHeaderKeyStarting(prefix: String): Boolean =
        headers.keys.any { it.startsWith(prefix, ignoreCase = true) }

    /** True when any header name contains [fragment] (case-insensitive). */
    fun hasHeaderKeyContaining(fragment: String): Boolean =
        headers.keys.any { it.contains(fragment, ignoreCase = true) }
}

/**
 * A user-editable classification rule. [headerName] is only used with
 * [Field.HEADER] (ignored for the other fields). Rules are matched in list
 * order, first match wins.
 */
data class Rule(
    val id: String,
    val field: Field,
    val headerName: String?,
    val op: Op,
    val value: String,
    val category: Category,
    val enabled: Boolean,
)

/** Result of [Classifier.classify]; [ruleId] is set only for rule matches. */
data class Classification(val category: Category, val reason: String, val ruleId: String?)

/**
 * Local, rules-first message classification (pure JVM, no dependencies).
 *
 * Order: enabled user [Rule]s in list order (first match wins), then the
 * built-in heuristics in this priority:
 *
 * 1. BILL — strong billing keywords (invoice/facture/Rechnung/...), or weak
 *    ones (due/statement/échéance/...) combined with an amount pattern or a
 *    PDF attachment.
 * 2. PURCHASE — order/shipping/tracking keywords (EN/FR/DE).
 * 3. SOCIAL — known social sender domains or Mastodon-style headers.
 * 4. NOTIFICATION — X-GitHub-* headers, Auto-Submitted: auto-generated,
 *    no-reply-style senders (plain no-reply WITH a List-Unsubscribe/List-Id
 *    falls through to NEWSLETTER), or security-alert wording.
 * 5. NEWSLETTER — List-Unsubscribe/List-Id, Precedence: bulk|list,
 *    X-Mailchimp-* or X-Campaign* headers, or a campaign-like Feedback-ID.
 * 6. PERSONAL — default.
 *
 * Multilingual keywords are matched lowercase and accent-insensitively
 * (é/E → e). User rules compare case-insensitively for EQUALS/CONTAINS/
 * ENDS_WITH; REGEX is applied verbatim to the field value truncated to
 * [MAX_INPUT_LENGTH] chars, patterns longer than [MAX_PATTERN_LENGTH] are
 * rejected, and syntax errors make the rule simply not match.
 */
object Classifier {

    private const val MAX_PATTERN_LENGTH = 200
    private const val MAX_INPUT_LENGTH = 2000

    fun classify(input: ClassifierInput, rules: List<Rule> = emptyList()): Classification =
        matchRules(input, rules) ?: builtIn(input)

    /** The first enabled user rule that matches [input], or null (the built-in heuristics are not consulted). */
    fun matchRules(input: ClassifierInput, rules: List<Rule>): Classification? {
        for (rule in rules) {
            if (!rule.enabled) continue
            if (ruleMatches(rule, input)) {
                return Classification(rule.category, "Rule '${rule.id}' matched", rule.id)
            }
        }
        return null
    }

    // ------------------------------------------------------------- rules

    private fun ruleMatches(rule: Rule, input: ClassifierInput): Boolean {
        if (rule.value.isEmpty()) return false // empty value would match everything: treat as invalid
        val target = when (rule.field) {
            Field.FROM_ADDRESS -> input.fromAddress
            Field.FROM_DOMAIN -> domainOf(input.fromAddress)
            Field.SUBJECT -> input.subject
            Field.HEADER -> rule.headerName?.let(input::firstHeader) ?: return false
            Field.LIST_ID -> input.firstHeader("List-Id")
        } ?: return false
        return when (rule.op) {
            Op.EQUALS -> target.equals(rule.value, ignoreCase = true)
            Op.CONTAINS -> target.contains(rule.value, ignoreCase = true)
            Op.ENDS_WITH -> target.endsWith(rule.value, ignoreCase = true)
            Op.REGEX -> regexMatch(target, rule.value)
        }
    }

    private fun regexMatch(target: String, pattern: String): Boolean {
        if (pattern.length > MAX_PATTERN_LENGTH) return false
        val compiled = try {
            Pattern.compile(pattern)
        } catch (e: PatternSyntaxException) {
            return false
        }
        val text = if (target.length > MAX_INPUT_LENGTH) target.substring(0, MAX_INPUT_LENGTH) else target
        return compiled.matcher(text).find()
    }

    private fun domainOf(address: String): String =
        address.trim().substringAfterLast('@')

    // ------------------------------------------------------- built-in sets

    private val BILL_STRONG = setOf(
        "invoice", "facture", "rechnung", "payment due", "paiement du", "faellig", "rechnungsbetrag",
    )

    private val BILL_WEAK = setOf(
        "due", "statement", "echeance", "montant", "rechnungsnr", "balance", "paiement",
    )

    private val PURCHASE = setOf(
        "order", "commande", "bestellung", "receipt", "recu", "quittung",
        "shipping", "livraison", "versand", "tracking", "envoi",
    )

    private val SOCIAL_DOMAINS = setOf(
        "facebook.com", "facebookmail.com", "linkedin.com", "x.com", "twitter.com", "instagram.com",
    )

    private val NO_REPLY_LOCALS = setOf(
        "no-reply", "noreply", "no.reply", "no_reply", "do-not-reply", "donotreply",
        "notifications", "notify",
    )

    private val SECURITY = setOf(
        "security alert", "security notification", "new sign-in", "new sign in", "new login",
        "password change", "password reset", "sicherheitswarnung", "alerte securite", "nouvelle connexion",
    )

    private val CURRENCY_BEFORE = Regex("""[\$€£]|(usd|eur|chf|frs|fr|kr|sek|nkr|dkk)\s?\d""")
    private val CURRENCY_AFTER = Regex("""\d\s?([\$€£]|usd|eur|chf|frs|fr|kr|sek|nkr|dkk)\b""")

    // -------------------------------------------------------- built-in run

    private fun builtIn(input: ClassifierInput): Classification {
        val text = fold(input.subject + "\n" + input.snippet)
        val lower = (input.subject + "\n" + input.snippet).lowercase()
        val domain = domainOf(input.fromAddress).lowercase()
        val local = input.fromAddress.substringBefore('@').lowercase().trim()

        // 1. BILL
        BILL_STRONG.firstOrNull { text.contains(it) }?.let {
            return Classification(Category.BILL, "billing keyword '$it'", null)
        }
        BILL_WEAK.firstOrNull { text.contains(it) }?.let { weak ->
            if (hasAmount(lower) || input.hasAttachmentPdf) {
                return Classification(Category.BILL, "billing keyword '$weak' with amount or pdf", null)
            }
        }

        // 2. PURCHASE
        PURCHASE.firstOrNull { text.contains(it) }?.let {
            return Classification(Category.PURCHASE, "purchase keyword '$it'", null)
        }

        // 3. SOCIAL
        SOCIAL_DOMAINS.firstOrNull { d -> domain == d || domain.endsWith(".$d") }?.let {
            return Classification(Category.SOCIAL, "social sender domain '$it'", null)
        }
        if (input.hasHeaderKeyContaining("mastodon")) {
            return Classification(Category.SOCIAL, "Mastodon-style headers", null)
        }

        // 4. NOTIFICATION
        if (input.hasHeaderKeyStarting("x-github-")) {
            return Classification(Category.NOTIFICATION, "X-GitHub-* headers", null)
        }
        if (input.firstHeader("Auto-Submitted")?.contains("auto-generated", ignoreCase = true) == true) {
            return Classification(Category.NOTIFICATION, "Auto-Submitted: auto-generated", null)
        }
        if (local in NO_REPLY_LOCALS) {
            SECURITY.firstOrNull { text.contains(it) }?.let {
                return Classification(Category.NOTIFICATION, "security wording from no-reply sender", null)
            }
            val hasList = input.firstHeader("List-Unsubscribe") != null || input.firstHeader("List-Id") != null
            if (!hasList) {
                return Classification(Category.NOTIFICATION, "no-reply sender", null)
            }
        }

        // 5. NEWSLETTER
        if (input.firstHeader("List-Unsubscribe") != null) {
            return Classification(Category.NEWSLETTER, "List-Unsubscribe header", null)
        }
        if (input.firstHeader("List-Id") != null) {
            return Classification(Category.NEWSLETTER, "List-Id header", null)
        }
        val precedence = input.firstHeader("Precedence")
        if (precedence != null &&
            (precedence.contains("bulk", true) || precedence.contains("list", true))
        ) {
            return Classification(Category.NEWSLETTER, "Precedence: bulk/list", null)
        }
        if (input.hasHeaderKeyStarting("x-mailchimp")) {
            return Classification(Category.NEWSLETTER, "Mailchimp headers", null)
        }
        if (input.hasHeaderKeyStarting("x-campaign")) {
            return Classification(Category.NEWSLETTER, "Campaign headers", null)
        }
        val feedbackId = input.firstHeader("Feedback-Id")
        if (feedbackId != null &&
            (feedbackId.contains("campaign", true) ||
                feedbackId.contains("newsletter", true) ||
                feedbackId.contains("list", true))
        ) {
            return Classification(Category.NEWSLETTER, "campaign-like Feedback-Id", null)
        }

        // 6. PERSONAL
        return Classification(Category.PERSONAL, "no matching signal", null)
    }

    private fun hasAmount(text: String): Boolean =
        CURRENCY_BEFORE.containsMatchIn(text) || CURRENCY_AFTER.containsMatchIn(text)

    /** Lowercases and strips accents (é/è/e → e) for keyword matching. */
    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}"), "")

    // -------------------------------------------------------------- codec

    /**
     * Encodes [rules] as a compact JSON array of flat objects (string/bool/null
     * values only), in list order, with the fixed key order
     * id, field, headerName, op, value, category, enabled.
     */
    fun encodeRules(rules: List<Rule>): String {
        val sb = StringBuilder("[")
        rules.forEachIndexed { idx, r ->
            if (idx > 0) sb.append(',')
            sb.append("{\"id\":")
            sb.append(quote(r.id))
            sb.append(",\"field\":\"").append(r.field.name).append('"')
            sb.append(",\"headerName\":")
            sb.append(if (r.headerName == null) "null" else quote(r.headerName))
            sb.append(",\"op\":\"").append(r.op.name).append('"')
            sb.append(",\"value\":")
            sb.append(quote(r.value))
            sb.append(",\"category\":\"").append(r.category.name).append('"')
            sb.append(",\"enabled\":").append(r.enabled)
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /**
     * Decodes the array produced by [encodeRules] (any valid JSON layout with
     * the same seven keys is accepted). Throws [IllegalArgumentException] for
     * malformed JSON or unknown/missing field values.
     */
    fun decodeRules(json: String): List<Rule> {
        val reader = JsonReader(json)
        val rules = reader.ruleArray()
        reader.expectEnd()
        return rules
    }

    private fun quote(s: String): String {
        val sb = StringBuilder().append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }

    /** Minimal JSON reader: an array of flat objects with string/bool/null values. */
    private class JsonReader(private val text: String) {
        private var i = 0

        fun ruleArray(): List<Rule> {
            ws()
            val rules = mutableListOf<Rule>()
            expect('[')
            ws()
            if (peek() == ']') {
                i++
                return rules
            }
            while (true) {
                ws()
                rules += ruleObject()
                ws()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return rules
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at offset $i")
                }
            }
        }

        private fun ruleObject(): Rule {
            expect('{')
            val f = mutableMapOf<String, Any?>()
            ws()
            if (peek() == '}') {
                i++
            } else {
                while (true) {
                    ws()
                    val key = string()
                    ws()
                    expect(':')
                    ws()
                    f[key] = jsonValue()
                    ws()
                    when (val c = peek()) {
                        ',' -> i++
                        '}' -> {
                            i++
                            break
                        }
                        else -> throw IllegalArgumentException("Expected ',' or '}' at offset $i")
                    }
                }
            }
            val id = f["id"] as? String ?: throw IllegalArgumentException("Rule missing string 'id'")
            val field = f["field"] as? String ?: throw IllegalArgumentException("Rule missing string 'field'")
            val op = f["op"] as? String ?: throw IllegalArgumentException("Rule missing string 'op'")
            val value = f["value"] as? String ?: throw IllegalArgumentException("Rule missing string 'value'")
            val category = f["category"] as? String
                ?: throw IllegalArgumentException("Rule missing string 'category'")
            val enabled = f["enabled"] as? Boolean
                ?: throw IllegalArgumentException("Rule missing boolean 'enabled'")
            return Rule(
                id = id,
                field = runCatching { Field.valueOf(field) }
                    .getOrElse { throw IllegalArgumentException("Unknown 'field' value '$field'") },
                headerName = f["headerName"] as? String,
                op = runCatching { Op.valueOf(op) }
                    .getOrElse { throw IllegalArgumentException("Unknown 'op' value '$op'") },
                value = value,
                category = runCatching { Category.valueOf(category) }
                    .getOrElse { throw IllegalArgumentException("Unknown 'category' value '$category'") },
                enabled = enabled,
            )
        }

        private fun jsonValue(): Any? = when (val c = peek()) {
            '"' -> string()
            'n' -> {
                literal("null")
                null
            }
            't' -> {
                literal("true")
                true
            }
            'f' -> {
                literal("false")
                false
            }
            else -> throw IllegalArgumentException("Unsupported value at offset $i")
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= text.length) throw IllegalArgumentException("Unterminated string")
                when (val c = text[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= text.length) throw IllegalArgumentException("Bad escape")
                        when (val e = text[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > text.length) throw IllegalArgumentException("Bad \\u escape")
                                val hex = text.substring(i, i + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Bad \\u escape '$hex'")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun expectEnd() {
            ws()
            if (i != text.length) throw IllegalArgumentException("Trailing content at offset $i")
        }

        private fun literal(s: String) {
            if (!text.startsWith(s, i)) throw IllegalArgumentException("Bad literal at offset $i")
            i += s.length
        }

        private fun peek(): Char {
            if (i >= text.length) throw IllegalArgumentException("Unexpected end of input")
            return text[i]
        }

        private fun expect(c: Char) {
            if (peek() != c) throw IllegalArgumentException("Expected '$c' at offset $i")
            i++
        }

        private fun ws() {
            while (i < text.length && text[i].isWhitespace()) i++
        }
    }
}
