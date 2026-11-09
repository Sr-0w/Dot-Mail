package app.dot.mail.ui.thread

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.dot.design.DotTheme
import app.dot.design.ThemeMode
import app.dot.mail.ui.isOnTop
import app.dot.mail.ui.pdf.PdfViewerDialog
import app.dot.mail.ui.popFrom
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** FR-22: every way of leaving the reader must be safe - no double pops, no use after release, no crash. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w400dp-h800dp")
class LeaveReaderTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun webViews(root: View): List<WebView> =
        if (root is WebView) listOf(root) else (root as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { webViews(g.getChildAt(it)) } } ?: emptyList()

    private fun html(show: () -> Boolean, beforeRelease: () -> Unit) {
        compose.setContent {
            DotTheme(ThemeMode.LIGHT) {
                CompositionLocalProvider(LocalBeforeWebViewRelease provides beforeRelease) {
                    if (show()) HtmlMessageView(html = "<p>" + "text ".repeat(2000) + "</p><img src=\"https://example.org/a.png\">", dark = false, allowRemote = false, textColor = 0, linkColor = 0, onLink = {})
                }
            }
        }
    }

    @Test fun `leaving while the html is still loading clears the backdrop first, then releases once`() {
        var show by mutableStateOf(true)
        val log = mutableListOf<String>()
        html({ show }) {
            // Compose has already detached the view when onRelease runs; the recording must be dropped before destroy().
            log += "clear"
        }
        compose.waitForIdle()
        val web = webViews(compose.activity.window.decorView).single()
        show = false
        compose.waitForIdle()
        assertThat(log).containsExactly("clear") // exactly once, from the release path
        assertThat(web.parent).isNull()
        assertThat(webViews(compose.activity.window.decorView)).isEmpty()
    }

    @Test fun `a backdrop clear that throws never blocks the release`() {
        var show by mutableStateOf(true)
        html({ show }) { throw IllegalStateException("layer already released") }
        compose.waitForIdle()
        val web = webViews(compose.activity.window.decorView).single()
        show = false
        compose.waitForIdle()
        assertThat(web.parent).isNull()
    }

    @Test fun `rapid leave and re-enter of the same message never crashes`() {
        var show by mutableStateOf(true)
        html({ show }) {}
        repeat(5) { show = !show; compose.waitForIdle() }
        show = false
        compose.waitForIdle()
    }

    @Test fun `a released fit view ignores late draw and arm callbacks and forgets its listener`() {
        val web = FitWebView(compose.activity)
        var reported = 0
        web.onContentWidth = { reported++ }
        releaseWebView(web)
        assertThat(web.onContentWidth).isNull()
        web.arm() // onPageCommitVisible arriving after destroy
        web.rearm()
        assertThat(reported).isEqualTo(0)
        releaseWebView(web) // and a second release is harmless
    }

    @Test fun `double Back pops the reader once and never the screen underneath`() {
        lateinit var nav: androidx.navigation.NavHostController
        var popped = 0
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "inbox") {
                composable("inbox") { Text("inbox", Modifier.testTag("inbox").clickable { nav.navigate("thread") }) }
                composable("thread") { entry ->
                    Box(Modifier.fillMaxSize().testTag("thread").clickable { if (nav.popFrom(entry)) popped++ })
                }
            }
        }
        compose.onNodeWithTag("inbox").performClick()
        compose.waitForIdle()
        // Two taps on the back arrow before the first pop has finished animating.
        compose.runOnIdle {
            val entry = nav.currentBackStackEntry!!
            assertThat(nav.popFrom(entry)).isTrue()
            assertThat(nav.popFrom(entry)).isFalse()
            assertThat(nav.isOnTop(entry)).isFalse()
        }
        compose.waitForIdle()
        assertThat(nav.currentDestination?.route).isEqualTo("inbox")
        assertThat(nav.previousBackStackEntry).isNull() // inbox is still the root: nothing was popped under it
    }

    @Test fun `a swipe that finishes after Back has begun cannot reopen the conversation`() {
        lateinit var nav: androidx.navigation.NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "inbox") {
                composable("inbox") { Text("inbox") }
                composable("thread") { Text("thread") }
            }
        }
        compose.runOnIdle { nav.navigate("thread") }
        compose.waitForIdle()
        compose.runOnIdle {
            val entry = nav.currentBackStackEntry!!
            nav.popFrom(entry) // Back begins...
            val openedNext = if (nav.isOnTop(entry)) { nav.navigate("thread"); true } else false // ...the swipe's late callback
            assertThat(openedNext).isFalse()
        }
        compose.waitForIdle()
        assertThat(nav.currentDestination?.route).isEqualTo("inbox")
    }

    @Test fun `closing the attachment viewer with Back dismisses it once and does not crash`() {
        var dismissed = 0
        compose.setContent {
            DotTheme(ThemeMode.LIGHT) { PdfViewerDialog(uri = "content://missing/none.pdf", name = "none.pdf", onDismiss = { dismissed++ }, onShare = {}, onOpenExternally = {}) }
        }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertThat(dismissed).isAtMost(1)
    }
}
