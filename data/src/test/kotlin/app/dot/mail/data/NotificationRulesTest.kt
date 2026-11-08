package app.dot.mail.data

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.AccountRow
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class NotificationRulesPureTest {
    @Test fun `inbox notifies by default and other folders do not`() {
        assertThat(NotificationRules.defaultNotify("INBOX")).isTrue()
        for (role in listOf("SENT", "DRAFTS", "ARCHIVE", "TRASH", "SPAM", "CUSTOM")) assertThat(NotificationRules.defaultNotify(role)).isFalse()
        assertThat(NotificationRules.effective(emptyMap(), 1, "INBOX")).isTrue()
        assertThat(NotificationRules.effective(mapOf(1L to false), 1, "INBOX")).isFalse()
        assertThat(NotificationRules.effective(mapOf(2L to true), 2, "CUSTOM")).isTrue()
    }

    @Test fun `stored formats tolerate garbage`() {
        assertThat(NotificationRules.decodeIds("1,2,x,,3")).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(NotificationRules.decodeFolders("5:1,6:0,7:9,bad,8")).containsExactly(5L, true, 6L, false)
        assertThat(NotificationRules.decodeActions("DELETE,REPLY")).containsExactly(NotificationAction.DELETE, NotificationAction.REPLY).inOrder()
        assertThat(NotificationRules.decodeActions("DELETE,DELETE")).isEqualTo(NotificationRules.DEFAULT_ACTIONS)
        assertThat(NotificationRules.decodeActions("nonsense")).isEqualTo(NotificationRules.DEFAULT_ACTIONS)
        assertThat(NotificationRules.decodeActions(null)).isEqualTo(NotificationRules.DEFAULT_ACTIONS)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationRulesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val account = AccountRow(id = 1, email = "alice@example.org", name = "Alice", color = 0, config = "{}", secret = "")

    @Before fun reset() {
        NotificationRules.resetForTest(); AccountSettingsStore.get(context).forget(1)
        // getLaunchIntentForPackage needs a launcher activity to exist.
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        val info = android.content.pm.ResolveInfo().apply { activityInfo = android.content.pm.ActivityInfo().apply { packageName = context.packageName; name = "app.dot.mail.MainActivity" } }
        shadowOf(context.packageManager).addResolveInfoForIntent(launcher, info)
    }

    private fun notifications() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    @Test fun `mute, folder switches and actions persist`() {
        val rules = NotificationRules.get(context)
        rules.setMuted(listOf(5, 6, -1), true) // drafts (negative ids) are ignored
        rules.setFolderNotify(10, "CUSTOM", true); rules.setFolderNotify(11, "INBOX", false); rules.setFolderNotify(12, "SENT", false)
        rules.setActions(NotificationAction.REPLY, NotificationAction.REPLY) // equal → second replaced
        NotificationRules.resetForTest()
        val again = NotificationRules.get(context)
        assertThat(again.muted.value).containsExactly(5L, 6L)
        assertThat(again.folderOverrides.value).containsExactly(10L, true, 11L, false) // 12 equals its default: no entry
        assertThat(again.actions.value[0]).isEqualTo(NotificationAction.REPLY)
        assertThat(again.actions.value[1]).isNotEqualTo(NotificationAction.REPLY)
        again.setMuted(listOf(5), false)
        assertThat(again.isMuted(5)).isFalse(); assertThat(again.isMuted(6)).isTrue()
    }

    @Test fun `a new-mail notification carries the two chosen buttons`() {
        val rules = NotificationRules.get(context)
        MailNotifications.newMail(context, account, 7, "Hello")
        val first = notifications().single()
        assertThat(first.actions.map { it.title.toString() }).containsExactly("Mark read", "Archive").inOrder() // defaults

        rules.setActions(NotificationAction.DELETE, NotificationAction.REPLY)
        MailNotifications.newMail(context, account, 8, "Another")
        val second = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification("8", 1)
        assertThat(second.actions.map { it.title.toString() }).containsExactly("Delete", "Reply").inOrder()
    }

    @Test fun `a muted conversation never notifies`() {
        NotificationRules.get(context).setMuted(listOf(7), true)
        MailNotifications.newMail(context, account, 7, "Hello")
        assertThat(notifications()).isEmpty()
        MailNotifications.newMail(context, account, 8, "Not muted")
        assertThat(notifications()).hasSize(1)
        NotificationRules.get(context).setMuted(listOf(7), false)
        MailNotifications.newMail(context, account, 7, "Hello again")
        assertThat(notifications()).hasSize(2)
    }

    @Test fun `the account switch still wins`() {
        AccountSettingsStore.get(context).update(1) { it.copy(notificationsEnabled = false) }
        MailNotifications.newMail(context, account, 9, "Silent account")
        assertThat(notifications()).isEmpty()
    }
}
