package app.dot.mail.ui.settings

import app.dot.design.ThemeMode
import app.dot.mail.AfterAction
import app.dot.mail.ComposeFormat
import app.dot.mail.Density
import app.dot.mail.MarkRead
import app.dot.mail.SwipeAction
import app.dot.mail.UiPrefs
import app.dot.mail.engine.text.EmailTemplate
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class SettingsBackupTest {
    private val emails = mapOf(1L to "alice@example.org", 2L to "work@example.org")
    private val ids = emails.entries.associate { it.value to it.key }

    private val custom = UiPrefs(
        theme = ThemeMode.DARK, density = Density.COMPACT, swipeStart = SwipeAction.SNOOZE, swipeEnd = SwipeAction.NONE,
        loadRemoteImages = true, appLock = true, secureScreen = true, undoSendSeconds = 20, confirmLinks = false, defaultAccountId = 2,
        confirmDelete = true, afterAction = AfterAction.NEXT, markRead = MarkRead.AFTER_2S, conversationView = false, avatarColors = false,
        contactPhotos = true, gravatar = true, brandLogos = true, websiteIcons = true, readingTextPercent = 120, quotedExpanded = true,
        replyAllByDefault = true, swipeBetweenMessages = true, includeOriginal = false, defaultFormat = ComposeFormat.PLAIN, lockAfterMinutes = 5,
    )

    @Test fun `every preference is exported unless deliberately excluded`() {
        val json = SettingsBackup.encodePrefs(custom) { emails[it] }
        val fields = UiPrefs::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }.map { it.name }.toSet()
        val exported = json.keys.map { if (it == "defaultAccount") "defaultAccountId" else it }.toSet()
        assertThat(fields - exported).containsExactlyElementsIn(SettingsBackup.EXCLUDED_PREFS)
        assertThat(exported - fields).isEmpty()
    }

    @Test fun `preferences round-trip, except the device-bound switches`() {
        val json = SettingsBackup.encodePrefs(custom) { emails[it] }
        val restored = SettingsBackup.decodePrefs(json, UiPrefs()) { ids[it] }
        assertThat(restored).isEqualTo(custom.copy(appLock = false, contactPhotos = false)) // never restored: would lock out / need a permission
        assertThat(json.toString()).doesNotContain("appLock")
        assertThat(json["defaultAccount"].toString()).contains("work@example.org") // a local id isn't portable: the address is
    }

    @Test fun `bad values are ignored and an unknown default account keeps the current one`() {
        val o = Json.parseToJsonElement("""{"theme":"PURPLE","density":5,"readingTextPercent":500,"undoSendSeconds":-1,"defaultAccount":"ghost@example.org","gravatar":"yes","swipeStart":"SNOOZE","extra":1}""") as JsonObject
        val base = UiPrefs(defaultAccountId = 1, theme = ThemeMode.LIGHT)
        val r = SettingsBackup.decodePrefs(o, base) { ids[it] }
        assertThat(r.theme).isEqualTo(ThemeMode.LIGHT)
        assertThat(r.density).isEqualTo(base.density)
        assertThat(r.readingTextPercent).isEqualTo(100)
        assertThat(r.undoSendSeconds).isEqualTo(base.undoSendSeconds)
        assertThat(r.defaultAccountId).isEqualTo(1)
        assertThat(r.gravatar).isFalse()
        assertThat(r.swipeStart).isEqualTo(SwipeAction.SNOOZE)
    }

    @Test fun `remote image rules are keyed by account address, not local id`() {
        val rules = listOf(RemoteImageRule(false, "shop@example.org", 0), RemoteImageRule(true, "example.org", 2), RemoteImageRule(true, "gone.example", 99))
        val json = SettingsBackup.encodeRules(rules) { emails[it] }
        assertThat(json.toString()).doesNotContain("gone.example") // account no longer exists
        val newIds = mapOf("work@example.org" to 7L)
        val (decoded, dropped) = SettingsBackup.decodeRules(json, newIds::get)
        assertThat(decoded).containsExactly(RemoteImageRule(false, "shop@example.org", 0), RemoteImageRule(true, "example.org", 7))
        assertThat(dropped).isEqualTo(0)
        val (onlyGlobal, droppedHere) = SettingsBackup.decodeRules(json) { null }
        assertThat(onlyGlobal).containsExactly(RemoteImageRule(false, "shop@example.org", 0))
        assertThat(droppedHere).isEqualTo(1)
    }

    @Test fun `hostile rule values are re-validated`() {
        val bad = Json.parseToJsonElement("""[{"type":"domain","value":"com"},{"type":"address","value":"no at sign"},{"type":"domain","value":"a b.org"}]""") as JsonArray
        assertThat(SettingsBackup.decodeRules(bad) { null }.first).isEmpty()
    }

    @Test fun `templates merge by name`() {
        val mine = listOf(EmailTemplate(1, "Thanks", null, "Thank you", null))
        val json = SettingsBackup.encodeTemplates(listOf(EmailTemplate(5, "thanks", null, "other", null), EmailTemplate(6, "Away", "Out of office", "I am away", "<p>I am away</p>")))
        val incoming = SettingsBackup.decodeTemplates(json)
        assertThat(incoming.map { it.name }).containsExactly("thanks", "Away").inOrder()
        val added = SettingsBackup.newTemplates(mine, incoming)
        assertThat(added.map { it.name }).containsExactly("Away")
        assertThat(added.single().subject).isEqualTo("Out of office")
        assertThat(added.single().bodyHtml).isEqualTo("<p>I am away</p>")
    }

    @Test fun `the file wraps the sections and is versioned`() {
        val data = buildJsonObject { put("accounts", buildJsonArray { }) }.toString()
        val text = SettingsBackup.build(data, JsonObject(emptyMap()), JsonArray(emptyList()), JsonArray(emptyList()), "0.1.0", "2026-11-08T10:00:00Z")
        val (parsed, error) = SettingsBackup.parse(text)
        assertThat(error).isNull()
        assertThat(parsed!!.dataSection).contains("accounts")
        assertThat(text).contains("\"format\": 1")
    }

    @Test fun `foreign, newer and oversized files are refused with a reason`() {
        assertThat(SettingsBackup.parse("hello").second).contains("isn't a Dot Mail backup")
        assertThat(SettingsBackup.parse("""{"app":"other","format":1}""").second).contains("isn't a Dot Mail backup")
        assertThat(SettingsBackup.parse("""{"app":"dot-mail"}""").second).contains("no version")
        assertThat(SettingsBackup.parse("""{"app":"dot-mail","format":99}""").second).contains("newer version")
        assertThat(SettingsBackup.parse("""{"app":"dot-mail","format":0}""").second).contains("not supported")
        assertThat(SettingsBackup.parse("x".repeat(2_000_001)).second).contains("too large")
        // Missing sections are fine: a file with fewer parts still imports what it has.
        val (parsed, error) = SettingsBackup.parse("""{"app":"dot-mail","format":1,"futureSection":{"a":1}}""")
        assertThat(error).isNull(); assertThat(parsed!!.prefs).isNull(); assertThat(parsed.dataSection).isEqualTo("{}")
    }
}
