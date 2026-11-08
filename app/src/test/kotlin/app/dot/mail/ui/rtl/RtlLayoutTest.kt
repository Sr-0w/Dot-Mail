package app.dot.mail.ui.rtl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import app.dot.design.ThemeMode
import app.dot.design.DotTheme
import app.dot.design.components.BackButton
import app.dot.design.components.DotFilterChip
import app.dot.design.components.DotTopBar
import app.dot.design.components.ListRow
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Layout mirrors under right-to-left languages (Arabic, Hebrew, Persian…): leading things move to the right edge. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h800dp")
class RtlLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun content(direction: LayoutDirection, body: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                DotTheme(ThemeMode.LIGHT) { Box(Modifier.fillMaxWidth().testTag("root")) { body() } }
            }
        }
    }

    private fun backButtonCentreVsMiddle(direction: LayoutDirection): Float {
        content(direction) { DotTopBar(title = "Title", navigation = { BackButton {} }) }
        val back = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getString(app.dot.design.R.string.ds_back)
        val root = compose.onNodeWithTag("root").getUnclippedBoundsInRoot()
        val button = compose.onNodeWithContentDescription(back).getUnclippedBoundsInRoot()
        return (button.left.value + button.right.value) / 2 - root.width.value / 2
    }

    @Test fun `the back button is on the left in LTR`(): Unit { assertThat(backButtonCentreVsMiddle(LayoutDirection.Ltr)).isLessThan(0f) }
    @Test fun `the back button is on the right in RTL`(): Unit { assertThat(backButtonCentreVsMiddle(LayoutDirection.Rtl)).isGreaterThan(0f) }

    @Test fun `list row text starts at the right in RTL and keeps trailing content at the left`(): Unit {
        content(LayoutDirection.Rtl) { ListRow(title = "Sujet", trailing = { Text("›", Modifier.testTag("trailing")) }) }
        val root = compose.onNodeWithTag("root").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("Sujet").getUnclippedBoundsInRoot()
        val trailing = compose.onNodeWithTag("trailing").getUnclippedBoundsInRoot()
        assertThat(title.right.value).isGreaterThan(root.width.value / 2)
        assertThat(trailing.left.value).isLessThan(root.width.value / 2)
    }

    @Test fun `filter chips render and stay inside the screen under RTL`(): Unit {
        content(LayoutDirection.Rtl) { DotFilterChip("غير مقروء", selected = true, onClick = {}, modifier = Modifier.testTag("chip")) }
        compose.onNodeWithTag("chip").assertIsDisplayed()
        val chip = compose.onNodeWithTag("chip").getUnclippedBoundsInRoot()
        assertThat(chip.left.value).isAtLeast(0f)
        assertThat(chip.right).isAtMost(400.dp)
    }

    @Test fun `directional icons are auto-mirrored`(): Unit {
        assertThat(Icons.AutoMirrored.Outlined.Send.autoMirror).isTrue()
        content(LayoutDirection.Rtl) { Icon(Icons.AutoMirrored.Outlined.Send, "send") }
        compose.onNodeWithContentDescription("send").assertIsDisplayed()
    }
}
