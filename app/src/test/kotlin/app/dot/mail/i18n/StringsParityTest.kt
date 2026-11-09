package app.dot.mail.i18n

import app.dot.mail.ui.account.SetupHints
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** EN is the source of truth: FR and DE must have the same keys and placeholders, and nothing may promise Microsoft sign-in. */
class StringsParityTest {
    private fun load(folder: String): Map<String, String> {
        val file = File("src/main/res/$folder/strings.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, String>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            when (n.nodeName) {
                "string" -> out[n.attributes.getNamedItem("name").nodeValue] = n.textContent
                "plurals" -> for (j in 0 until n.childNodes.length) {
                    val item = n.childNodes.item(j)
                    if (item.nodeName == "item") out[n.attributes.getNamedItem("name").nodeValue + "#" + item.attributes.getNamedItem("quantity").nodeValue] = item.textContent
                }
            }
        }
        return out
    }
    private val en = load("values")
    private val fr = load("values-fr")
    private val de = load("values-de")
    private val placeholder = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxXeEgGcb]")
    private fun placeholders(s: String) = placeholder.findAll(s).map { it.value }.sorted().toList()

    @Test fun `FR and DE have exactly the keys of EN`() {
        for ((name, t) in listOf("fr" to fr, "de" to de)) {
            assertWithMessage("missing in $name").that(en.keys - t.keys).isEmpty()
            assertWithMessage("extra in $name").that(t.keys - en.keys).isEmpty()
        }
    }

    @Test fun `placeholders match EN in FR and DE`() {
        for ((name, t) in listOf("fr" to fr, "de" to de)) {
            // The singular form of a plural may omit the number in one language and keep it in another (FR "one" also covers 0).
            val bad = en.filter { (k, v) -> !k.endsWith("#one") && t[k] != null && placeholders(v) != placeholders(t.getValue(k)) }.keys
            assertWithMessage("placeholder mismatch in $name").that(bad).isEmpty()
        }
    }

    @Test fun `no translation is left identical to a long English sentence`() {
        val allowed = setOf("compose_link_url_placeholder")
        for ((name, t) in listOf("fr" to fr, "de" to de)) {
            val same = en.filter { (k, v) -> k !in allowed && v.length > 14 && v.any { it.isLetter() } && t[k] == v }.keys
            assertWithMessage("untranslated in $name").that(same).isEmpty()
        }
    }

    @Test fun `FR uses typographic apostrophes and DE the formal Sie throughout`() {
        assertThat(fr.filterValues { "'" in it }.keys).isEmpty()
        assertThat(de.filterValues { Regex("\\b(du|dein|deine|deinen|deinem|dich|dir)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }.keys).isEmpty()
    }

    @Test fun `no string promises Microsoft or Outlook sign-in`() {
        val promise = Regex("(?i)(sign|log)[ -]?in.{0,40}(microsoft|outlook|hotmail|office ?365)|(microsoft|outlook|hotmail|office ?365).{0,40}(sign|log)[ -]?in|(connexion|connecter).{0,40}(microsoft|outlook|hotmail)|(microsoft|outlook|hotmail).{0,40}(connexion|connecter)|(anmeld).{0,40}(microsoft|outlook|hotmail)|(microsoft|outlook|hotmail).{0,40}anmeld")
        for ((name, t) in listOf("en" to en, "fr" to fr, "de" to de)) {
            val offenders = t.filter { (k, v) -> promise.containsMatchIn(v) && !k.startsWith("setup_hint_microsoft") }.keys
            assertWithMessage("Microsoft sign-in wording in $name").that(offenders).isEmpty()
        }
        // The one place that names Microsoft in setup says it is NOT supported.
        assertThat(en.getValue("setup_hint_microsoft")).contains("be added")
    }

    @Test fun `every engine provider hint maps to a translated string`() {
        val engine = listOf(
            "Gmail: use an app password (Google Account → Security → App passwords).",
            "Yahoo: use an app password (Yahoo Account → Account security → App passwords).",
            "iCloud: use an App-Specific Password (appleid.apple.com → Sign-In & Security → App-Specific Passwords).",
            "Proton Mail doesn't offer IMAP access on Android (Proton Mail Bridge is desktop-only). Use the Proton Mail app instead.",
            "Outlook/Hotmail/Live/MSN: Microsoft disabled password sign-in for personal accounts — use OAuth2.",
            "Microsoft 365: password sign-in is usually disabled — use OAuth2.",
            "OAuth sign-in is not available in this build",
            app.dot.mail.engine.autoconfig.AutoDiscovery.INSECURE_PLAIN_HINT,
        )
        for (h in engine) assertWithMessage(h).that(SetupHints.resFor(h)).isNotNull()
        assertThat(SetupHints.resFor("some other hint")).isNull()
        assertThat(SetupHints.localize("some other hint") { "x" }).isEqualTo("some other hint")
    }
}
