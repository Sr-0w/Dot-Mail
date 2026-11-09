package app.dot.mail.ui.shapes

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.dot.design.DotTheme
import app.dot.design.ThemeMode
import app.dot.design.components.DotCheckbox
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** FR-56: Material progress indicators and check boxes are replaced by the Quiet spinner / check; exceptions are listed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class QuietRemnantsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `the quiet check box is purely visual and its row carries the toggle semantics`() {
        var on by mutableStateOf(false)
        compose.setContent {
            DotTheme(ThemeMode.LIGHT) {
                Row(Modifier.testTag("row").toggleable(on, role = Role.Checkbox, onValueChange = { on = it })) { DotCheckbox(on) }
            }
        }
        compose.onNodeWithTag("row").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("row").performClick().assertIsOff()
    }

    @Test fun `no Material progress indicator or check box is left in app or design system sources`() {
        val roots = listOf("src/main", "../designsystem/src/main").map { File(it) }.filter { it.exists() }
        assertThat(roots).isNotEmpty()
        val offenders = roots.flatMap { r -> r.walkTopDown().filter { it.extension == "kt" }.toList() }
            .filter { f -> f.readText().let { "CircularProgressIndicator" in it || "LinearProgressIndicator" in it || "material3.Checkbox" in it } }
            .map { it.name }
        assertThat(offenders).isEmpty()
    }
}
