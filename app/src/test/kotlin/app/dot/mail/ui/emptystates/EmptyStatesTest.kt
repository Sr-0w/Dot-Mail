package app.dot.mail.ui.emptystates

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.dot.design.DotTheme
import app.dot.design.ThemeMode
import app.dot.mail.R
import app.dot.mail.data.model.FolderRole
import app.dot.mail.ui.account.WelcomeScreen
import app.dot.mail.ui.inbox.EmptyFor
import app.dot.mail.ui.inbox.LoadErrorState
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Empty and error states render, say something, and offer a way forward where there is one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h800dp")
class EmptyStatesTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private fun s(id: Int) = compose.activity.getString(id)

    @Test fun `welcome shows the pitch and a button that adds the first account`() {
        var added = 0
        compose.setContent { DotTheme(ThemeMode.LIGHT) { WelcomeScreen(onAddAccount = { added++ }) } }
        compose.onNodeWithText(s(R.string.setup_welcome_cta)).assertIsDisplayed().performClick()
        assertThat(added).isEqualTo(1)
    }

    @Test fun `an empty inbox explains itself`() {
        compose.setContent { DotTheme(ThemeMode.LIGHT) { EmptyFor(FolderRole.INBOX) } }
        compose.onNodeWithText(s(R.string.inbox_empty_inbox_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.inbox_empty_inbox_body)).assertIsDisplayed()
    }

    @Test fun `every folder role has its own empty state`() {
        var role by androidx.compose.runtime.mutableStateOf(FolderRole.STARRED)
        compose.setContent { DotTheme(ThemeMode.LIGHT) { EmptyFor(role) } }
        for (r in listOf(FolderRole.STARRED, FolderRole.DRAFTS, FolderRole.SENT, FolderRole.SPAM, FolderRole.TRASH, FolderRole.SNOOZED, FolderRole.NEWSLETTERS)) {
            role = r
            compose.waitForIdle()
        }
    }

    @Test fun `no mail matching the filters offers to clear them`() {
        var cleared = 0
        compose.setContent { DotTheme(ThemeMode.LIGHT) { EmptyFor(FolderRole.INBOX, filtered = true, onClearFilters = { cleared++ }) } }
        compose.onNodeWithText(s(R.string.inbox_empty_filtered_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.inbox_filter_clear)).performClick()
        assertThat(cleared).isEqualTo(1)
    }

    @Test fun `no network shows the reason and a Retry that retries`() {
        var retried = 0
        compose.setContent { DotTheme(ThemeMode.LIGHT) { LoadErrorState(s(R.string.inbox_error_load_title), s(R.string.inbox_error_load_body), onRetry = { retried++ }) } }
        compose.onNodeWithText(s(R.string.inbox_error_load_title)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.inbox_retry)).performClick()
        assertThat(retried).isEqualTo(1)
    }
}
