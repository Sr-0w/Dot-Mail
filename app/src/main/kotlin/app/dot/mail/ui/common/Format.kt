package app.dot.mail.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.AllInbox
import androidx.compose.ui.graphics.vector.ImageVector
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
            day == today.minusDays(1) -> "Yesterday"
            ChronoUnit.DAYS.between(day, today) in 2..6 -> then.format(DateTimeFormatter.ofPattern("EEE", locale))
            day.year == today.year -> then.format(DateTimeFormatter.ofPattern(bestPattern(locale, "d MMM"), locale))
            else -> then.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
        }
    }

    /** Message header timestamps: "Today, 14:32" / "8 Nov 2026, 14:32". */
    fun messageDate(epochMs: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val then = Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (then.toLocalDate() == today) "Today, " + then.format(time.withLocale(locale))
        else then.format(fullDate.withLocale(locale))
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes / 1024.0).let { if (it < 10) "%.1f".format(it) else it.toInt().toString() }} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    fun roleName(role: FolderRole): String = when (role) {
        FolderRole.INBOX -> "Inbox"
        FolderRole.SENT -> "Sent"
        FolderRole.DRAFTS -> "Drafts"
        FolderRole.ARCHIVE -> "Archive"
        FolderRole.TRASH -> "Trash"
        FolderRole.SPAM -> "Spam"
        FolderRole.ALL_MAIL -> "All mail"
        FolderRole.STARRED -> "Starred"
        FolderRole.CUSTOM -> "Folder"
        FolderRole.SNOOZED -> "Snoozed"
    }

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
    }

    /** Display order of system folders in the drawer. */
    val roleOrder = listOf(
        FolderRole.INBOX, FolderRole.STARRED, FolderRole.SENT, FolderRole.DRAFTS,
        FolderRole.ARCHIVE, FolderRole.ALL_MAIL, FolderRole.SPAM, FolderRole.TRASH,
    )

    private fun bestPattern(locale: Locale, skeleton: String): String =
        android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
}
