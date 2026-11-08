package app.dot.mail

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dot.design.ThemeMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Screenshots contain synthetic demo mail only; the real mailbox/preferences are never used. */
@RunWith(AndroidJUnit4::class)
class FrostedHeaderUiTest {
    private val demo=DemoRule()
    private val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val chain:RuleChain=RuleChain.outerRule(demo).around(compose)

    @Test fun scrollingHeadersRemainReadableAndClickableInLightDarkAndOled() {
        compose.waitForText(HIKE)
        for(theme in listOf(ThemeMode.LIGHT,ThemeMode.DARK,ThemeMode.OLED)) {
            compose.runOnIdle { demo.app.settings.update { it.copy(theme=theme) } }
            compose.waitForIdle()
            capture("${theme.name.lowercase()}-inbox")
            compose.openDrawer()
            compose.onNodeWithText("Settings").performClick()
            compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
            compose.onNodeWithText("STORAGE").performScrollTo()
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            capture("${theme.name.lowercase()}-settings")
            compose.onNodeWithContentDescription("Back").performClick()
            compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
            compose.onNodeWithContentDescription("Compose").performClick()
            compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
            try { compose.waitForText("New message") } catch(e:Throwable) { capture("compose-probe"); throw e }
            compose.onNodeWithContentDescription("Close").assertIsDisplayed()
            capture("${theme.name.lowercase()}-compose")
            compose.onNodeWithContentDescription("Close").performClick()
            compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithContentDescription("Compose").fetchSemanticsNodes().isNotEmpty() }
        }
    }
    private fun capture(name:String) {
        captureDemoFrame(compose.activity,name)
        File(compose.activity.cacheDir,"dotmail-ui-tests/$name-tree.txt").writeText(compose.onRoot().printToString())
    }
}

internal fun captureDemoFrame(activity:MainActivity,name:String) {
        Thread.sleep(500)
        val view=activity.window.decorView
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        val latch=CountDownLatch(1); var result=-1
        PixelCopy.request(activity.window,image,{ result=it; latch.countDown() },Handler(Looper.getMainLooper()))
        assertTrue(latch.await(3,TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS,result)
        val folder=File(activity.cacheDir,"dotmail-ui-tests").apply { mkdirs() }
        File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }
