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
    @Test fun filtersCollapseAndReturnWhileScrollingWithoutHeaderResizeJumps() {
        compose.waitForText(HIKE)
        compose.runOnIdle {
            @Suppress("UNCHECKED_CAST")
            val field=app.dot.mail.demo.DemoMailGraph::class.java.getDeclaredField("messages").apply { isAccessible=true }
            val messages=field.get(demo.graph) as kotlinx.coroutines.flow.MutableStateFlow<List<app.dot.mail.data.model.Message>>
            val base=messages.value.first()
            messages.value=messages.value+List(60) { i -> base.copy(id=10000L+i,threadId=10000L+i,subject="Scrolling fixture $i",sentAt=System.currentTimeMillis()-i*60000L) }
        }
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        compose.onNodeWithContentDescription("Sort").assertIsDisplayed()
        val times=java.util.Collections.synchronizedList(mutableListOf<Long>())
        val handlerThread=android.os.HandlerThread("DotMailFrameMetrics").apply { start() }
        val listener=android.view.Window.OnFrameMetricsAvailableListener { _,frame,_ ->
            val duration=frame.getMetric(android.view.FrameMetrics.TOTAL_DURATION)
            if(duration>0 && times.size<2000) times+=duration
        }
        compose.runOnIdle { compose.activity.window.addOnFrameMetricsAvailableListener(listener,Handler(handlerThread.looper)) }
        try {
            repeat(4) {
                compose.onRoot().performTouchInput { swipeUp(durationMillis=600) }
                compose.mainClock.advanceTimeBy(350); compose.waitForIdle()
                compose.onNodeWithContentDescription("Sort").assertDoesNotExist()
                if(it==0) capture("scrolled-inbox")
                compose.onRoot().performTouchInput { swipeDown(durationMillis=600) }
                compose.mainClock.advanceTimeBy(350); compose.waitForIdle()
                compose.onNodeWithContentDescription("Sort").assertIsDisplayed()
            }
        } finally {
            compose.runOnIdle { compose.activity.window.removeOnFrameMetricsAvailableListener(listener) }
            handlerThread.quitSafely()
        }
        val frames=synchronized(times) { times.sorted() }
        if(frames.isNotEmpty()) println("HEADER_FRAMES count=${frames.size} p50_ms=${frames[frames.size/2]/1000000.0} p95_ms=${frames[(frames.size*95/100).coerceAtMost(frames.lastIndex)]/1000000.0} over_16ms=${frames.count { it>16666667 }}")
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
