package app.dot.mail.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsMatcherTest {
    private fun hit(title: String, keywords: String = "", subtitle: String? = null, crumb: String = "Appearance") =
        SettingsHit(title, subtitle, crumb, keywords, SettingsTarget.Section(SettingsSection.APPEARANCE))

    private val hits = listOf(
        hit("Thème", keywords = "sombre clair nuit dark"),
        hit("Density", keywords = "compact spacing"),
        hit("Text size in messages", keywords = "font zoom"),
        hit("App lock", subtitle = "Fingerprint or screen lock", crumb = "Privacy"),
    )

    @Test fun `accents and case are ignored`() {
        assertThat(SettingsMatcher.match("THEME", hits).map { it.title }).containsExactly("Thème")
    }

    @Test fun `synonyms find the setting`() {
        assertThat(SettingsMatcher.match("dark", hits).map { it.title }).containsExactly("Thème")
        assertThat(SettingsMatcher.match("font", hits).map { it.title }).containsExactly("Text size in messages")
    }

    @Test fun `every word must match, anywhere`() {
        assertThat(SettingsMatcher.match("privacy fingerprint", hits).map { it.title }).containsExactly("App lock")
        assertThat(SettingsMatcher.match("privacy dark", hits)).isEmpty()
    }

    @Test fun `title matches rank before keyword matches`() {
        val list = listOf(hit("Compose format", keywords = "x"), hit("Density", keywords = "compact"))
        assertThat(SettingsMatcher.match("comp", list).map { it.title }).containsExactly("Compose format", "Density").inOrder()
    }

    @Test fun `blank query gives nothing`() {
        assertThat(SettingsMatcher.match("   ", hits)).isEmpty()
    }

    @Test fun `aliases are discoverable from settings search`() {
        assertThat(SettingsIndex.entries.any { it.title == app.dot.mail.R.string.aliases_title && it.target == SettingsTarget.Aliases }).isTrue()
    }
}
