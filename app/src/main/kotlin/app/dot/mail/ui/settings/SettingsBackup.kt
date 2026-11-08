package app.dot.mail.ui.settings

import app.dot.design.ThemeMode
import app.dot.mail.AfterAction
import app.dot.mail.ComposeFormat
import app.dot.mail.Density
import app.dot.mail.MarkRead
import app.dot.mail.SwipeAction
import app.dot.mail.UiPrefs
import app.dot.mail.engine.text.EmailTemplate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The app-level part of a settings backup (preferences, remote-image rules, templates) and the file that wraps it with
 * the data layer's account section. Pure Kotlin + JSON so it's unit-tested on the JVM.
 *
 * Never exported: anything credential-like (there is none in these sections) and two device-bound switches:
 * App lock (restoring it onto a phone without a screen lock would lock the owner out) and Contact photos (needs the
 * Contacts permission, which is granted per device).
 */
object SettingsBackup {
    const val FORMAT = 1
    const val APP = "dot-mail"
    private const val MAX_BYTES = 2_000_000
    private const val MAX_TEMPLATES = 200

    /** UiPrefs fields deliberately left out of the file (see above). The unit test keeps this list and the code in step. */
    val EXCLUDED_PREFS = setOf("appLock", "contactPhotos")

    // ---- preferences -------------------------------------------------------------------------------------------

    fun encodePrefs(p: UiPrefs, accountEmailOf: (Long) -> String?): JsonObject = buildJsonObject {
        put("theme", p.theme.name); put("density", p.density.name); put("swipeStart", p.swipeStart.name); put("swipeEnd", p.swipeEnd.name)
        put("loadRemoteImages", p.loadRemoteImages); put("secureScreen", p.secureScreen); put("undoSendSeconds", p.undoSendSeconds)
        put("confirmLinks", p.confirmLinks)
        put("defaultAccount", accountEmailOf(p.defaultAccountId))
        put("confirmDelete", p.confirmDelete); put("afterAction", p.afterAction.name); put("markRead", p.markRead.name)
        put("conversationView", p.conversationView); put("avatarColors", p.avatarColors)
        put("gravatar", p.gravatar); put("brandLogos", p.brandLogos); put("websiteIcons", p.websiteIcons)
        put("readingTextPercent", p.readingTextPercent); put("quotedExpanded", p.quotedExpanded)
        put("replyAllByDefault", p.replyAllByDefault); put("swipeBetweenMessages", p.swipeBetweenMessages)
        put("includeOriginal", p.includeOriginal); put("defaultFormat", p.defaultFormat.name); put("lockAfterMinutes", p.lockAfterMinutes)
    }

    /** Applies known, valid values over [base]; everything else (unknown keys, bad values) is ignored. */
    fun decodePrefs(o: JsonObject, base: UiPrefs, accountIdOf: (String) -> Long?): UiPrefs {
        fun bool(k: String, d: Boolean) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: d
        fun int(k: String, d: Int, range: IntRange) = (o[k] as? JsonPrimitive)?.intOrNull?.takeIf { it in range } ?: d
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        fun <E : Enum<E>> enum(k: String, values: Array<E>, d: E) = values.firstOrNull { it.name == str(k) } ?: d
        return base.copy(
            theme = enum("theme", ThemeMode.entries.toTypedArray(), base.theme),
            density = enum("density", Density.entries.toTypedArray(), base.density),
            swipeStart = enum("swipeStart", SwipeAction.entries.toTypedArray(), base.swipeStart),
            swipeEnd = enum("swipeEnd", SwipeAction.entries.toTypedArray(), base.swipeEnd),
            loadRemoteImages = bool("loadRemoteImages", base.loadRemoteImages), secureScreen = bool("secureScreen", base.secureScreen),
            undoSendSeconds = int("undoSendSeconds", base.undoSendSeconds, 0..30), confirmLinks = bool("confirmLinks", base.confirmLinks),
            defaultAccountId = str("defaultAccount")?.let(accountIdOf) ?: base.defaultAccountId,
            confirmDelete = bool("confirmDelete", base.confirmDelete),
            afterAction = enum("afterAction", AfterAction.entries.toTypedArray(), base.afterAction),
            markRead = enum("markRead", MarkRead.entries.toTypedArray(), base.markRead),
            conversationView = bool("conversationView", base.conversationView), avatarColors = bool("avatarColors", base.avatarColors),
            gravatar = bool("gravatar", base.gravatar), brandLogos = bool("brandLogos", base.brandLogos), websiteIcons = bool("websiteIcons", base.websiteIcons),
            readingTextPercent = int("readingTextPercent", base.readingTextPercent, 90..130), quotedExpanded = bool("quotedExpanded", base.quotedExpanded),
            replyAllByDefault = bool("replyAllByDefault", base.replyAllByDefault), swipeBetweenMessages = bool("swipeBetweenMessages", base.swipeBetweenMessages),
            includeOriginal = bool("includeOriginal", base.includeOriginal), defaultFormat = enum("defaultFormat", ComposeFormat.entries.toTypedArray(), base.defaultFormat),
            lockAfterMinutes = int("lockAfterMinutes", base.lockAfterMinutes, 0..60),
        )
    }

    // ---- remote-image rules --------------------------------------------------------------------------------------

    fun encodeRules(rules: List<RemoteImageRule>, accountEmailOf: (Long) -> String?): JsonArray = buildJsonArray {
        rules.forEach { r ->
            val account = if (r.accountId == 0L) null else accountEmailOf(r.accountId) ?: return@forEach // rule of a removed account: drop
            add(buildJsonObject { put("type", if (r.isDomain) "domain" else "address"); put("value", r.value); put("account", account) })
        }
    }

    /** Rules for accounts that don't exist here are dropped (returned as the second value's count). */
    fun decodeRules(a: JsonArray?, accountIdOf: (String) -> Long?): Pair<List<RemoteImageRule>, Int> {
        var dropped = 0
        val out = a.orEmpty().take(2000).mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val value = (o["value"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val isDomain = (o["type"] as? JsonPrimitive)?.contentOrNull == "domain"
            val email = (o["account"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            val account = if (email == null) 0L else accountIdOf(email) ?: run { dropped++; return@mapNotNull null }
            val clean = if (isDomain) RemoteImageMatching.normaliseDomain(value) else RemoteImageMatching.normaliseAddress(value).takeIf { '@' in it && ' ' !in it && it.length > 3 }
            clean?.let { RemoteImageRule(isDomain, it, account) }
        }.distinct()
        return out to dropped
    }

    // ---- templates -------------------------------------------------------------------------------------------------

    fun encodeTemplates(t: List<EmailTemplate>): JsonArray = buildJsonArray {
        t.forEach { add(buildJsonObject { put("name", it.name); put("subject", it.subject); put("text", it.bodyText); put("html", it.bodyHtml) }) }
    }

    fun decodeTemplates(a: JsonArray?): List<EmailTemplate> = a.orEmpty().take(MAX_TEMPLATES).mapIndexedNotNull { i, el ->
        val o = el as? JsonObject ?: return@mapIndexedNotNull null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
        val name = s("name")?.trim()?.take(200)?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
        EmailTemplate(i + 1L, name, s("subject")?.take(2000), (s("text") ?: "").take(100_000), s("html")?.take(200_000))
    }

    /** Templates are merged by name: a template with an existing name is left alone, new names are added. */
    fun newTemplates(existing: List<EmailTemplate>, incoming: List<EmailTemplate>): List<EmailTemplate> {
        val names = existing.map { it.name.lowercase() }.toMutableSet()
        return incoming.filter { names.add(it.name.lowercase()) }
    }

    // ---- the file --------------------------------------------------------------------------------------------------

    fun build(dataSection: String, prefs: JsonObject, rules: JsonArray, templates: JsonArray, appVersion: String, exportedAt: String): String =
        Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("app", APP); put("format", FORMAT); put("appVersion", appVersion); put("exportedAt", exportedAt)
            put("data", Json.parseToJsonElement(dataSection))
            put("preferences", prefs); put("remoteImageRules", rules); put("templates", templates)
        })

    class Parsed(val dataSection: String, val prefs: JsonObject?, val rules: JsonArray?, val templates: JsonArray?)

    /** Either the sections, or a user-readable reason why the file can't be used. */
    fun parse(text: String): Pair<Parsed?, String?> {
        if (text.length > MAX_BYTES) return null to "This file is too large to be a Dot Mail backup."
        val root = try { Json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }
            ?: return null to "This file isn't a Dot Mail backup."
        if ((root["app"] as? JsonPrimitive)?.contentOrNull != APP) return null to "This file isn't a Dot Mail backup."
        val format = (root["format"] as? JsonPrimitive)?.intOrNull ?: return null to "This backup has no version."
        if (format > FORMAT) return null to "This backup was made by a newer version of Dot Mail. Update the app to import it."
        if (format < 1) return null to "This backup format is not supported."
        return Parsed(
            dataSection = (root["data"] as? JsonObject)?.toString() ?: "{}",
            prefs = root["preferences"] as? JsonObject, rules = root["remoteImageRules"] as? JsonArray, templates = root["templates"] as? JsonArray,
        ) to null
    }
}
