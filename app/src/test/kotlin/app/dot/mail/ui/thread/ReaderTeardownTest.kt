package app.dot.mail.ui.thread

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.dot.design.DotTheme
import app.dot.design.ThemeMode
import app.dot.mail.ui.pdf.PdfGate
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Leaving an open message (Back, next conversation, theme flip) must tear its views down without crashing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h800dp")
class ReaderTeardownTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `leaving a loaded html message disposes its WebView without throwing`() {
        var show by mutableStateOf(true)
        compose.setContent {
            DotTheme(ThemeMode.LIGHT) {
                if (show) HtmlMessageView(html = "<p>Hello <a href=\"https://example.org\">link</a></p>", dark = false, allowRemote = false, textColor = 0, linkColor = 0, onLink = {})
            }
        }
        compose.waitForIdle()
        show = false
        compose.waitForIdle()
        show = true // and straight back, as with swipe-to-next on the same screen
        compose.waitForIdle()
        show = false
        compose.waitForIdle()
    }

    @Test fun `flipping the theme while the message is open recreates the WebView safely`() {
        var dark by mutableStateOf(false)
        compose.setContent {
            DotTheme(ThemeMode.LIGHT) { HtmlMessageView(html = "<p>x</p>", dark = dark, allowRemote = false, textColor = 0, linkColor = 0, onLink = {}) }
        }
        compose.waitForIdle()
        dark = true
        compose.waitForIdle()
    }

    @Test fun `releasing a WebView mid-load is safe even twice and silences its link handler`() {
        val web = WebView(ApplicationProvider.getApplicationContext())
        web.loadDataWithBaseURL(null, "<p>loading</p>", "text/html", "utf-8", null)
        releaseWebView(web)
        releaseWebView(web)
        assertThat(web.webViewClient).isNotNull()
    }

    @Test fun `closing a pdf waits for the page being rendered and nothing renders afterwards`() = runBlocking {
        val closed = AtomicInteger()
        val gate = PdfGate { closed.incrementAndGet() }
        val renderStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val inFlight = async(Dispatchers.IO) {
            gate.render { renderStarted.complete(Unit); runBlocking { release.await() }; "page" }
        }
        renderStarted.await()
        gate.close(CoroutineScope(Dispatchers.IO))
        delay(100)
        assertThat(closed.get()).isEqualTo(0) // still rendering: the renderer must stay open
        release.complete(Unit)
        assertThat(inFlight.await()).isEqualTo("page")
        withTimeout(2_000) { while (closed.get() == 0) delay(10) }
        assertThat(gate.render { "late" }).isNull() // a render that starts after closing does nothing
        gate.close(CoroutineScope(Dispatchers.IO)); delay(100)
        assertThat(closed.get()).isEqualTo(1)
    }
}
