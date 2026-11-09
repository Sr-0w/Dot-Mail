package app.dot.mail.ui.thread

import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import app.dot.design.DotTheme
import app.dot.design.ThemeMode
import app.dot.mail.data.model.EmailAddress
import app.dot.mail.data.model.Message
import app.dot.mail.data.model.MessageBody
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** FR-21: HTML mail flicker/blanking. The WebView must stay out of the glass capture path and keep a plain, layer-free draw. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h800dp")
class HtmlFlickerTest {
    @get:Rule val compose = createComposeRule()

    private fun message(body: MessageBody?) = Message(
        id = 1, accountId = 1, folderId = 1, threadId = 1, from = EmailAddress("a@example.org"), to = emptyList(), cc = emptyList(), bcc = emptyList(),
        replyTo = emptyList(), subject = "s", sentAt = 0, snippet = "", isUnread = false, isStarred = false, body = body, attachments = emptyList(),
        messageIdHeader = null, references = emptyList(), unsubscribe = null,
    )
    private fun body(html: String?, text: String? = "t") = MessageBody(plainText = text, html = html, hasRemoteImages = false)

    @Test fun `glass records the reader only when every message is loaded plain text`() {
        assertThat(glassCanCapture(listOf(message(body(null))), captureHtml = false)).isTrue()
        assertThat(glassCanCapture(listOf(message(body("  "))), captureHtml = false)).isTrue()
    }

    @Test fun `with HTML capture off, html and bodies still loading stay out of the capture`() {
        assertThat(glassCanCapture(listOf(message(body("<p>hi</p>"))), captureHtml = false)).isFalse()
        assertThat(glassCanCapture(listOf(message(body(null)), message(body("<b>x</b>"))), captureHtml = false)).isFalse()
        assertThat(glassCanCapture(listOf(message(null)), captureHtml = false)).isFalse() // not downloaded yet: may turn out to be HTML
        assertThat(glassCanCapture(listOf(message(body(null))), captureHtml = false)).isTrue()
    }

    @Test fun `with HTML capture on every loaded reader is captured and an unloaded one never is`() {
        assertThat(glassCanCapture(listOf(message(body("<p>hi</p>"))), captureHtml = true)).isTrue()
        assertThat(glassCanCapture(null, captureHtml = true)).isFalse()
        assertThat(glassCanCapture(null, captureHtml = false)).isFalse()
    }

    @Test fun `tall-content setup never forces a layer and does not throw`() {
        val web = WebView(ApplicationProvider.getApplicationContext())
        configureForTallContent(web)
        assertThat(web.layerType).isEqualTo(android.view.View.LAYER_TYPE_NONE)
        assertThat(web.overScrollMode).isEqualTo(android.view.View.OVER_SCROLL_NEVER)
    }

    @Test fun `an unzoomed message takes exactly its content height and a zoom grows it`() {
        var zoomTall by mutableFloatStateOf(100f)
        compose.setContent {
            DotTheme(ThemeMode.LIGHT) {
                Column { androidx.compose.foundation.layout.Box(Modifier.testTag("z").fillMaxWidth().pinchToZoom().then(Modifier.height(zoomTall.dp))) }
            }
        }
        compose.waitForIdle()
        assertThat(compose.onNodeWithTag("z").getBoundsInRoot().height.value).isEqualTo(100f)
        zoomTall = 340f
        compose.waitForIdle()
        assertThat(compose.onNodeWithTag("z").getBoundsInRoot().height.value).isEqualTo(340f)
    }
}
