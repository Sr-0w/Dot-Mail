package app.dot.mail.ui.inbox

import android.content.Context
import android.content.res.Configuration
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.R
import app.dot.mail.data.ListPrefs
import app.dot.mail.data.model.MessageAction
import app.dot.mail.data.SmartFolderStore
import app.dot.mail.data.model.EmailAddress
import app.dot.mail.data.model.ThreadSummary
import app.dot.mail.demo.DemoMailGraph
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DraftRowBugsTest {
    private fun resources(tag: String): android.content.res.Resources {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }).resources
    }

    private fun thread(draft: Boolean, recipients: List<EmailAddress> = emptyList(), participants: List<EmailAddress> = emptyList()) =
        ThreadSummary(1, 1, "s", participants, "", 0, 1, 0, false, false, draftId = if (draft) 5L else null, recipients = recipients)

    @Test fun `a draft without recipients has no avatar address so no placeholder letter is drawn`(): Unit {
        assertThat(avatarAddress(thread(draft = true), showRecipients = true)).isNull()
    }

    @Test fun `a draft with recipients shows the first recipient, a received thread its latest sender`(): Unit {
        val bob = EmailAddress("bob@example.org"); val eve = EmailAddress("eve@example.org"); val amy = EmailAddress("amy@example.org")
        assertThat(avatarAddress(thread(true, recipients = listOf(bob, eve)), true)).isEqualTo(bob)
        assertThat(avatarAddress(thread(false, participants = listOf(amy, eve)), false)).isEqualTo(eve)
        assertThat(avatarAddress(thread(false), false)).isNull()
    }

    @Test fun `the sync error line keeps a space after the colon in every language`(): Unit {
        for ((tag, colon) in listOf("en" to ": ", "de" to ": ", "fr" to " : ")) {
            val r = resources(tag)
            assertThat(r.getString(R.string.inbox_failing_line_single, "robin@snyders.xyz", "couldn't synchronize"))
                .isEqualTo("robin@snyders.xyz${colon}couldn't synchronize")
            for (n in listOf(2, 3)) {
                val text = r.getQuantityString(R.plurals.inbox_failing_line_many, n, n, "detail")
                assertThat(text).contains("$n")
                assertThat(text).endsWith("${colon}detail")
            }
        }
    }

    @Test fun `trashing drafts says drafts, in the singular and plural, in every language`(): Unit {
        val expected = mapOf(
            "en" to ("Draft moved to trash" to "3 drafts moved to trash"),
            "fr" to ("Brouillon déplacé dans la corbeille" to "3 brouillons déplacés dans la corbeille"),
            "de" to ("Entwurf in den Papierkorb verschoben" to "3 Entwürfe in den Papierkorb verschoben"),
        )
        for ((tag, texts) in expected) {
            val r = resources(tag)
            assertThat(r.getQuantityString(R.plurals.inbox_draft_trashed_toast, 1, 1)).isEqualTo(texts.first)
            assertThat(r.getQuantityString(R.plurals.inbox_draft_trashed_toast, 3, 3)).isEqualTo(texts.second)
        }
    }

    @Test fun `the confirmation wording follows what was trashed`(): Unit {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = InboxViewModel(DemoMailGraph(), SavedStateHandle(), flowOf(true), context.resources, ListPrefs.get(context), SmartFolderStore.get(context))
        assertThat(model.confirmation(MessageAction.TRASH, 1, inDrafts = true)).isEqualTo("Draft moved to trash")
        assertThat(model.confirmation(MessageAction.TRASH, 1)).isEqualTo("Conversation moved to trash")
        assertThat(model.confirmation(MessageAction.TRASH, 2, inDrafts = true)).isEqualTo("2 drafts moved to trash")
        assertThat(model.confirmation(MessageAction.ARCHIVE, 1, inDrafts = true)).isNotEqualTo("Draft moved to trash") // only trash is reworded
    }
}
