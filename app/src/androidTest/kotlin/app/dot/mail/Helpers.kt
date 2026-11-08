package app.dot.mail

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick

const val WAIT_MS = 8_000L

/** Waits for paging/animations: the demo graph is instant but navigation transitions are not. */
fun ComposeTestRule.waitForText(text: String, substring: Boolean = false) {
    waitUntil(WAIT_MS) { onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
}

fun ComposeTestRule.waitForTextGone(text: String) {
    waitUntil(WAIT_MS) { onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }
}

fun ComposeTestRule.openDrawer() {
    onNodeWithContentDescription("Open mailboxes").performClick()
    waitForText("Settings") // drawer footer
}

fun SemanticsNodeInteractionsProvider.textFields() = onAllNodes(hasSetTextAction())

const val HIKE = "Saturday hike — final plan"
const val PHOTOS = "Photos from the weekend"
const val MOODBOARD = "Moodboard v3"
