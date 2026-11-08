package app.dot.mail.ui.settings

import androidx.annotation.StringRes
import app.dot.mail.R
import java.text.Normalizer

/** Where a Settings search result leads. */
internal sealed interface SettingsTarget {
    data class Section(val section: SettingsSection) : SettingsTarget
    data class Account(val accountId: Long) : SettingsTarget
    data object SmartFolders : SettingsTarget
    data object Categories : SettingsTarget
    data object RemoteImages : SettingsTarget
}

/** Sections of the Settings page, in display order; each has an anchor the page can scroll to. */
internal enum class SettingsSection(@StringRes val label: Int) {
    ACCOUNTS(R.string.settings_section_accounts),
    GENERAL(R.string.settings_section_general),
    APPEARANCE(R.string.settings_section_appearance),
    SENDER_PICTURES(R.string.settings_section_sender_pictures),
    READING(R.string.settings_section_reading),
    COMPOSING(R.string.settings_section_composing),
    NOTIFICATIONS(R.string.settings_section_notifications),
    PRIVACY(R.string.settings_section_privacy),
    STORAGE(R.string.settings_section_storage),
    BACKUP(R.string.backup_section),
    ABOUT(R.string.settings_section_about),
}

/** One searchable setting. [keywords] holds translated synonyms ("dark" → Theme), space separated. */
internal data class SettingsEntry(
    @StringRes val title: Int,
    @StringRes val subtitle: Int? = null,
    val section: SettingsSection,
    val target: SettingsTarget = SettingsTarget.Section(section),
    @StringRes val keywords: Int? = null,
)

/** A result with its strings already resolved for the current locale (so the matcher is pure and testable). */
internal data class SettingsHit(
    val title: String,
    val subtitle: String?,
    val breadcrumb: String,
    val keywords: String,
    val target: SettingsTarget,
)

internal object SettingsIndex {
    val entries: List<SettingsEntry> = listOf(
        SettingsEntry(R.string.settings_add_account, section = SettingsSection.ACCOUNTS),
        SettingsEntry(R.string.settings_default_account, section = SettingsSection.GENERAL),
        SettingsEntry(R.string.settings_swipe_right, section = SettingsSection.GENERAL, keywords = R.string.settings_kw_swipe),
        SettingsEntry(R.string.settings_swipe_left, section = SettingsSection.GENERAL, keywords = R.string.settings_kw_swipe),
        SettingsEntry(R.string.settings_confirm_delete, R.string.settings_confirm_delete_sub, SettingsSection.GENERAL),
        SettingsEntry(R.string.settings_after_action, section = SettingsSection.GENERAL),
        SettingsEntry(R.string.settings_mark_read, section = SettingsSection.GENERAL),
        SettingsEntry(R.string.settings_theme, section = SettingsSection.APPEARANCE, keywords = R.string.settings_kw_theme),
        SettingsEntry(R.string.settings_group_conversations, R.string.settings_group_conversations_sub, SettingsSection.APPEARANCE, keywords = R.string.settings_kw_threads),
        SettingsEntry(R.string.settings_density, section = SettingsSection.APPEARANCE, keywords = R.string.settings_kw_density),
        SettingsEntry(R.string.settings_reading_size, section = SettingsSection.APPEARANCE, keywords = R.string.settings_kw_text_size),
        SettingsEntry(R.string.settings_avatar_colors, R.string.settings_avatar_colors_sub, SettingsSection.APPEARANCE),
        SettingsEntry(R.string.settings_contact_photos, R.string.settings_contact_photos_sub, SettingsSection.SENDER_PICTURES, keywords = R.string.settings_kw_avatars),
        SettingsEntry(R.string.settings_gravatar, R.string.settings_gravatar_sub, SettingsSection.SENDER_PICTURES, keywords = R.string.settings_kw_avatars),
        SettingsEntry(R.string.settings_libravatar, R.string.settings_libravatar_sub, SettingsSection.SENDER_PICTURES, keywords = R.string.settings_kw_avatars),
        SettingsEntry(R.string.settings_brand_logos, R.string.settings_brand_logos_sub, SettingsSection.SENDER_PICTURES, keywords = R.string.settings_kw_avatars),
        SettingsEntry(R.string.settings_website_icons, R.string.settings_website_icons_sub, SettingsSection.SENDER_PICTURES, keywords = R.string.settings_kw_avatars),
        SettingsEntry(R.string.settings_tracking_blocked, R.string.settings_tracking_blocked_sub, SettingsSection.READING, keywords = R.string.settings_kw_privacy),
        SettingsEntry(R.string.settings_confirm_links, R.string.settings_confirm_links_sub, SettingsSection.READING, keywords = R.string.settings_kw_privacy),
        SettingsEntry(R.string.settings_show_quoted, R.string.settings_show_quoted_sub, SettingsSection.READING),
        SettingsEntry(R.string.settings_swipe_between, R.string.settings_swipe_between_sub, SettingsSection.READING, keywords = R.string.settings_kw_swipe),
        SettingsEntry(R.string.settings_default_format, section = SettingsSection.COMPOSING),
        SettingsEntry(R.string.settings_undo_send, section = SettingsSection.COMPOSING, keywords = R.string.settings_kw_undo),
        SettingsEntry(R.string.settings_reply_all, R.string.settings_reply_all_sub, SettingsSection.COMPOSING),
        SettingsEntry(R.string.settings_include_original, R.string.settings_include_original_sub, SettingsSection.COMPOSING),
        SettingsEntry(R.string.settings_system_notifications, R.string.settings_system_notifications_sub, SettingsSection.NOTIFICATIONS, keywords = R.string.settings_kw_notifications),
        SettingsEntry(R.string.settings_instant_title, R.string.settings_instant_body, SettingsSection.NOTIFICATIONS, keywords = R.string.settings_kw_notifications),
        SettingsEntry(R.string.settings_notif_button_first, section = SettingsSection.NOTIFICATIONS, keywords = R.string.settings_kw_notifications),
        SettingsEntry(R.string.settings_notif_button_second, section = SettingsSection.NOTIFICATIONS, keywords = R.string.settings_kw_notifications),
        SettingsEntry(R.string.settings_smart_folders, R.string.settings_smart_folders_sub, SettingsSection.NOTIFICATIONS, SettingsTarget.SmartFolders, R.string.settings_kw_folders),
        SettingsEntry(R.string.settings_categories, R.string.settings_categories_sub, SettingsSection.NOTIFICATIONS, SettingsTarget.Categories, R.string.settings_kw_folders),
        SettingsEntry(R.string.settings_remote_images, section = SettingsSection.PRIVACY, target = SettingsTarget.RemoteImages, keywords = R.string.settings_kw_privacy),
        SettingsEntry(R.string.settings_app_lock, R.string.settings_app_lock_sub, SettingsSection.PRIVACY, keywords = R.string.settings_kw_lock),
        SettingsEntry(R.string.settings_lock_after, section = SettingsSection.PRIVACY, keywords = R.string.settings_kw_lock),
        SettingsEntry(R.string.settings_hide_recents, R.string.settings_hide_recents_sub, SettingsSection.PRIVACY, keywords = R.string.settings_kw_privacy),
        SettingsEntry(R.string.settings_clear_cache, section = SettingsSection.STORAGE, keywords = R.string.settings_kw_storage),
        SettingsEntry(R.string.backup_export, R.string.backup_export_sub, SettingsSection.BACKUP, keywords = R.string.settings_kw_backup),
        SettingsEntry(R.string.backup_import, R.string.backup_import_sub, SettingsSection.BACKUP, keywords = R.string.settings_kw_backup),
        SettingsEntry(R.string.settings_about, section = SettingsSection.ABOUT),
        SettingsEntry(R.string.settings_source_code, section = SettingsSection.ABOUT),
        SettingsEntry(R.string.settings_licences, section = SettingsSection.ABOUT),
        SettingsEntry(R.string.settings_no_telemetry, R.string.settings_no_telemetry_sub, SettingsSection.ABOUT, keywords = R.string.settings_kw_privacy),
    )
}

internal object SettingsMatcher {
    /** Lower-case, accents removed ("Thème" → "theme"), so French/German labels match unaccented typing. */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    /**
     * Every word of [query] must occur (as a substring) in the title, subtitle, breadcrumb or keywords. Ranked: title
     * starts with the query, then a title word starts with a query word, then title contains, then anything else.
     */
    fun match(query: String, hits: List<SettingsHit>): List<SettingsHit> {
        val words = normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val q = words.joinToString(" ")
        return hits.mapNotNull { h ->
            val title = normalize(h.title)
            val all = listOf(title, normalize(h.subtitle.orEmpty()), normalize(h.breadcrumb), normalize(h.keywords)).joinToString(" ")
            if (!words.all { it in all }) return@mapNotNull null
            val rank = when {
                title.startsWith(q) -> 0
                title.split(Regex("\\W+")).any { w -> words.any { w.startsWith(it) } } -> 1
                q in title -> 2
                else -> 3
            }
            rank to h
        }.sortedWith(compareBy({ it.first }, { it.second.title.length })).map { it.second }.distinctBy { it.title to it.target }
    }
}
