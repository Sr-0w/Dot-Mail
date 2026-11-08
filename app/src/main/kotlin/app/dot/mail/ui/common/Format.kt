package app.dot.mail.ui.common

import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.AllInbox
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.dot.mail.R
import app.dot.mail.data.model.FolderRole
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

object Format {
    /** Set from the system setting (DateFormat.is24HourFormat) at app start / resume; null = locale default. */
    @Volatile var use24h: Boolean? = null
    private val time: DateTimeFormatter
        get() = when (use24h) {
            true -> DateTimeFormatter.ofPattern("HH:mm")
            false -> DateTimeFormatter.ofPattern("h:mm a")
            null -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        }
    private val fullDate = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

    /** List timestamps: "14:32", "Tue", "8 Nov", "08/11/2024". */
    fun listDate(epochMs: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val then = Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = then.toLocalDate()
        return when {
            day == today -> then.format(time.withLocale(locale))
            day == today.minusDays(1) -> relativeDay(locale, yesterday = true)
            ChronoUnit.DAYS.between(day, today) in 2..6 -> then.format(DateTimeFormatter.ofPattern("EEE", locale))
            day.year == today.year -> then.format(DateTimeFormatter.ofPattern(bestPattern(locale, "d MMM"), locale))
            else -> then.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
        }
    }

    /** Message header timestamps: "Today, 14:32" / "8 Nov 2026, 14:32". */
    fun messageDate(epochMs: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val then = Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (then.toLocalDate() == today) relativeDay(locale, yesterday = false) + ", " + then.format(time.withLocale(locale))
        else then.format(fullDate.withLocale(locale))
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes / 1024.0).let { if (it < 10) "%.1f".format(it) else it.toInt().toString() }} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    /** "Yesterday" / "Today" in the user's language, from ICU (no string table to maintain). English if ICU is unavailable (JVM tests). */
    private fun relativeDay(locale: Locale, yesterday: Boolean): String = runCatching {
        val f = android.icu.text.RelativeDateTimeFormatter.getInstance(android.icu.util.ULocale.forLocale(locale), null,
            android.icu.text.RelativeDateTimeFormatter.Style.LONG, android.icu.text.DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE)
        f.format(
            if (yesterday) android.icu.text.RelativeDateTimeFormatter.Direction.LAST else android.icu.text.RelativeDateTimeFormatter.Direction.THIS,
            android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit.DAY,
        )
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: if (yesterday) "Yesterday" else "Today"

    @StringRes fun roleNameRes(role: FolderRole): Int = when (role) {
        FolderRole.INBOX -> R.string.inbox_role_inbox
        FolderRole.SENT -> R.string.inbox_role_sent
        FolderRole.DRAFTS -> R.string.inbox_role_drafts
        FolderRole.ARCHIVE -> R.string.inbox_role_archive
        FolderRole.TRASH -> R.string.inbox_role_trash
        FolderRole.SPAM -> R.string.inbox_role_spam
        FolderRole.ALL_MAIL -> R.string.inbox_role_all_mail
        FolderRole.STARRED -> R.string.inbox_role_starred
        FolderRole.CUSTOM -> R.string.inbox_role_custom
        FolderRole.SNOOZED -> R.string.inbox_role_snoozed
        FolderRole.NEWSLETTERS -> R.string.inbox_role_newsletters
        FolderRole.PURCHASES -> R.string.inbox_role_purchases
        FolderRole.BILLS -> R.string.inbox_role_bills
        FolderRole.NOTIFICATIONS -> R.string.inbox_role_notifications
        FolderRole.SOCIAL -> R.string.inbox_role_social
    }

    /** Name of a system folder role in the current language. */
    @Composable fun roleName(role: FolderRole): String = stringResource(roleNameRes(role))
    fun roleName(resources: android.content.res.Resources, role: FolderRole): String = resources.getString(roleNameRes(role))

    /** What a folder is called in lists: custom folders by path, system folders by their translated role name. */
    @Composable fun folderTitle(role: FolderRole, path: String): String = if (role == FolderRole.CUSTOM) path else roleName(role)

    fun roleIcon(role: FolderRole): ImageVector = when (role) {
        FolderRole.INBOX -> Icons.Outlined.Inbox
        FolderRole.SENT -> Icons.Outlined.Send
        FolderRole.DRAFTS -> Icons.Outlined.Drafts
        FolderRole.ARCHIVE -> Icons.Outlined.Archive
        FolderRole.TRASH -> Icons.Outlined.Delete
        FolderRole.SPAM -> Icons.Outlined.Report
        FolderRole.ALL_MAIL -> Icons.Outlined.AllInbox
        FolderRole.STARRED -> Icons.Outlined.StarOutline
        FolderRole.CUSTOM -> Icons.Outlined.Folder
        FolderRole.SNOOZED -> Icons.Outlined.Snooze
        FolderRole.NEWSLETTERS -> Icons.Outlined.Email
        FolderRole.PURCHASES -> Icons.Outlined.ShoppingBag
        FolderRole.BILLS -> Icons.Outlined.ReceiptLong
        FolderRole.NOTIFICATIONS -> Icons.Outlined.Notifications
        FolderRole.SOCIAL -> Icons.Outlined.Groups
    }

    /** Display order of system folders in the drawer. */
    val roleOrder = listOf(
        FolderRole.INBOX, FolderRole.STARRED, FolderRole.SENT, FolderRole.DRAFTS,
        FolderRole.ARCHIVE, FolderRole.ALL_MAIL, FolderRole.SPAM, FolderRole.TRASH,
    )

    private fun bestPattern(locale: Locale, skeleton: String): String =
        android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
}
