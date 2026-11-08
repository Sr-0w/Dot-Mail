package app.dot.mail

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dot.design.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DotMailUiTest {
    private val demo = DemoRule()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val chain: RuleChain = RuleChain.outerRule(demo).around(compose)

    @Before fun inboxReady() = compose.waitForText(HIKE)

    private fun openThread(subject: String) {
        compose.onNodeWithText(subject).performClick()
        compose.waitForText(subject) // subject heading in the thread
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("Archive", useUnmergedTree = false).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun inbox_showsUnifiedDemoThreads() {
        compose.onNodeWithText(HIKE).assertExists()
        compose.onNodeWithText(PHOTOS).assertExists()
        compose.onNodeWithText(MOODBOARD).assertExists() // second account → unified inbox
        compose.onNodeWithText("Order #48213 confirmed").assertDoesNotExist() // custom folder, not inbox
        compose.onNodeWithContentDescription("Compose").assertExists()
    }

    @Test fun thread_showsSubjectAndBody() {
        openThread(HIKE)
        compose.waitForText("platform 7", substring = true)
        compose.onNodeWithText(HIKE).assertExists()
        compose.onNodeWithContentDescription("Archive").assertExists()
    }

    @Test fun thread_markUnread_returnsToInboxAsUnread() {
        openThread(PHOTOS)
        compose.onNodeWithContentDescription("Mark as unread").performClick()
        compose.waitForText(HIKE) // back on the inbox
        // Row state is exposed through stateDescription.
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(hasText(PHOTOS) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unread. Has attachments. "))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun thread_archive_removesItFromInbox() {
        openThread(HIKE)
        compose.onNodeWithContentDescription("Archive").performClick()
        compose.waitForTextGone(HIKE)
        compose.onNodeWithText(PHOTOS).assertExists()
    }

    @Test fun drawer_listsFolders_andArchiveFolderWorks() {
        // Archive one thread, then find it in the Archive folder via the drawer.
        openThread(HIKE)
        compose.onNodeWithContentDescription("Archive").performClick()
        compose.waitForTextGone(HIKE)

        compose.openDrawer()
        listOf("All inboxes", "Inbox", "Sent", "Drafts", "Archive", "Spam", "Trash", "Settings", "Add account").forEach {
            compose.onAllNodesWithText(it).fetchSemanticsNodes().let { n -> check(n.isNotEmpty()) { "Drawer is missing \"$it\"" } }
        }
        compose.onNodeWithText("Archive").performClick()
        compose.waitForText(HIKE)
        compose.onNodeWithText(PHOTOS).assertDoesNotExist()
    }

    @Test fun compose_addsRecipientChip_andRejectsInvalid() {
        compose.onNodeWithContentDescription("Compose").performClick()
        compose.waitForText("New message")
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()

        compose.textFields()[0].performTextInput("a@b.co,")
        compose.waitForText("a@b.co")
        compose.onNodeWithContentDescription("Send").assertIsEnabled()

        compose.textFields()[0].performTextInput("not-an-address,")
        compose.waitForText("isn't a valid address", substring = true)
    }

    @Test fun search_findsHike() {
        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitForText("Search by sender", substring = true)
        compose.textFields()[0].performTextInput("hike")
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(HIKE).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(PHOTOS).assertDoesNotExist()
    }

    @Test fun settings_themeToggle() {
        compose.openDrawer()
        compose.onNodeWithText("Settings").performClick()
        compose.waitForText("APPEARANCE") // SectionLabel upper-cases
        assertEquals(ThemeMode.LIGHT, demo.app.settings.ui.value.theme)
        compose.onNodeWithText("Dark").performScrollTo().performClick()
        compose.waitUntil(WAIT_MS) { demo.app.settings.ui.value.theme == ThemeMode.DARK }
        compose.onNodeWithText("System").performClick()
        compose.waitUntil(WAIT_MS) { demo.app.settings.ui.value.theme == ThemeMode.SYSTEM }
    }
}
