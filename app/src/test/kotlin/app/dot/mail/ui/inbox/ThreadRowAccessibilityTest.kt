package app.dot.mail.ui.inbox

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density as UiDensity
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import app.dot.design.DotTheme
import app.dot.design.ThemeMode
import app.dot.mail.AppSettings
import app.dot.mail.Density
import app.dot.mail.LocalSettings
import app.dot.mail.data.model.EmailAddress
import app.dot.mail.data.model.ThreadSummary
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h800dp")
class ThreadRowAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    private val thread = ThreadSummary(
        threadId = 1, accountId = 1, subject = "Quarterly planning meeting moved to Thursday afternoon",
        participants = listOf(EmailAddress("anna@example.org", "Anna Müller-Lindqvist")),
        snippet = "Hi all, we have moved the quarterly planning meeting to Thursday at 15:00 in the large room.",
        lastMessageAt = 1_700_000_000_000, messageCount = 3, unreadCount = 1, isStarred = true, hasAttachments = true,
    )

    private fun show(density: Density, fontScale: Float, actions: Boolean = true) {
        val settings = AppSettings(ApplicationProvider.getApplicationContext<Context>())
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalSettings provides settings, LocalDensity provides UiDensity(base.density, fontScale)) {
                DotTheme(ThemeMode.LIGHT) {
                    Box(Modifier.fillMaxWidth().testTag("root")) {
                        ThreadRow(
                            thread, selected = false, selectionMode = false, density = density, onClick = {}, onLongClick = {},
                            onAction = if (actions) ({}) else null, modifier = Modifier.testTag("row"),
                        )
                    }
                }
            }
        }
    }

    private fun rowHeight(density: Density, scale: Float): Float {
        show(density, scale)
        return compose.onNodeWithTag("row").getUnclippedBoundsInRoot().height.value
    }

    @Test fun `density changes real row height`(): Unit {
        val compact = rowHeight(Density.COMPACT, 1f)
        assertThat(compact).isGreaterThan(40f)
        assertThat(RowMetrics.of(Density.COMPACT).vPad).isLessThan(RowMetrics.of(Density.COMFORTABLE).vPad)
        assertThat(RowMetrics.of(Density.COMFORTABLE).vPad).isLessThan(RowMetrics.of(Density.SPACIOUS).vPad)
        assertThat(RowMetrics.of(Density.COMPACT).snippetLines).isEqualTo(0)
        assertThat(RowMetrics.of(Density.SPACIOUS).snippetLines).isEqualTo(2)
    }

    @Test fun `row stays inside the screen and shows its text at 200 percent font scale`(): Unit {
        show(Density.SPACIOUS, 2f)
        compose.onNodeWithText("Quarterly planning meeting moved to Thursday afternoon").assertIsDisplayed()
        val root = compose.onNodeWithTag("root").getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag("row").getUnclippedBoundsInRoot()
        assertThat(row.width.value).isAtMost(root.width.value)
        assertThat(row.left.value).isAtLeast(0f)
        compose.onNodeWithText("Anna Müller-Lindqvist").assertIsDisplayed()
    }

    @Test fun `larger font scale makes a spacious row taller instead of clipping it`(): Unit {
        val settings = AppSettings(ApplicationProvider.getApplicationContext<Context>())
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalSettings provides settings) {
                DotTheme(ThemeMode.LIGHT) {
                    androidx.compose.foundation.layout.Column {
                        for ((tag, scale) in listOf("normal" to 1f, "big" to 2f)) {
                            CompositionLocalProvider(LocalDensity provides UiDensity(base.density, scale)) {
                                ThreadRow(thread, selected = false, selectionMode = false, density = Density.SPACIOUS, onClick = {}, onLongClick = {}, modifier = Modifier.testTag(tag))
                            }
                        }
                    }
                }
            }
        }
        val normal = compose.onNodeWithTag("normal").getUnclippedBoundsInRoot().height.value
        val big = compose.onNodeWithTag("big").getUnclippedBoundsInRoot().height.value
        assertThat(big).isGreaterThan(normal)
    }

    @Test fun `rows expose archive delete star read and snooze as accessibility actions`(): Unit {
        show(Density.COMFORTABLE, 1f)
        val node = compose.onNodeWithTag("row").fetchSemanticsNode()
        val labels = node.config[SemanticsActions.CustomActions].map { it.label }
        assertThat(labels).containsExactly("Archive", "Delete", "Remove star", "Mark as read", "Snooze").inOrder()
    }

    @Test fun `without a handler no custom actions are exposed`(): Unit {
        show(Density.COMFORTABLE, 1f, actions = false)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).assertDoesNotExist()
    }
}
