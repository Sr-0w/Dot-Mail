package app.dot.mail

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.dot.mail.data.model.Message
import app.dot.mail.data.model.MessageBody
import app.dot.mail.demo.DemoMailGraph
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Real WebView + RenderThread regression checks. The runner isolates all real mail and preferences. */
@RunWith(AndroidJUnit4::class)
class ReaderRenderingTest {
    private val demo=DemoRule()
    private val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val chain:RuleChain=RuleChain.outerRule(demo).around(compose)

    private fun installLongHtml() {
        @Suppress("UNCHECKED_CAST")
        val field=DemoMailGraph::class.java.getDeclaredField("messages").apply { isAccessible=true }
        val messages=field.get(demo.graph) as MutableStateFlow<List<Message>>
        val html=buildString {
            append("<table style='width:640px;border-collapse:collapse'><tbody>")
            repeat(55) { index ->
                append("<tr><td style='padding:28px;background:${if(index%2==0) "#e8f2ff" else "#fff1da"};color:#13243b'><h2>Rendering fixture $index</h2><p>")
                repeat(8) { append("Stable newsletter content, with emoji 🔥 and tables. ") }
                append("</p></td></tr>")
            }
            append("</tbody></table>")
        }
        messages.value=messages.value.map { if(it.threadId==1L) it.copy(isUnread=false,body=MessageBody("Rendering fixture",html,false)) else it }
    }

    @Test fun longHtmlScrollingAndSystemBackCanRepeatWithoutRendererCrash() {
        compose.waitForText(HIKE)
        compose.runOnIdle { installLongHtml() }
        val device=InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(4) {
            compose.onNodeWithText(HIKE).performClick()
            compose.waitUntil(WAIT_MS) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Archive")).fetchSemanticsNodes().isNotEmpty() }
            // Let Chromium produce real frames, then exercise rapid and slow scrolling on the real device.
            Thread.sleep(900)
            repeat(5) { pass ->
                val height=compose.activity.resources.displayMetrics.heightPixels
                val width=compose.activity.resources.displayMetrics.widthPixels
                android.os.ParcelFileDescriptor.AutoCloseInputStream(device.executeShellCommand(
                    "input swipe ${width/2} ${height*3/4} ${width/2} ${height/3} ${if(pass%2==0) 180 else 650}",
                )).use { it.readBytes() }
                Thread.sleep(200)
                assertNewsletterFrame()
            }
            device.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(WAIT_MS) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Compose")).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private fun assertNewsletterFrame() {
        val view=compose.activity.window.decorView
        val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
        val copied=java.util.concurrent.CountDownLatch(1)
        var result=-1
        android.view.PixelCopy.request(compose.activity.window,bitmap,{ result=it; copied.countDown() },android.os.Handler(android.os.Looper.getMainLooper()))
        org.junit.Assert.assertTrue("Frame copy timed out",copied.await(3,java.util.concurrent.TimeUnit.SECONDS))
        org.junit.Assert.assertEquals(android.view.PixelCopy.SUCCESS,result)
        var colored=0
        for(y in bitmap.height/3 until bitmap.height*3/4 step 12) for(x in bitmap.width/4 until bitmap.width*3/4 step 12) {
            val color=bitmap.getPixel(x,y)
            val r=android.graphics.Color.red(color); val g=android.graphics.Color.green(color); val b=android.graphics.Color.blue(color)
            if((kotlin.math.abs(r-232)<5 && kotlin.math.abs(g-242)<5 && b>250) || (r>250 && kotlin.math.abs(g-241)<5 && kotlin.math.abs(b-218)<5)) colored++
        }
        bitmap.recycle()
        org.junit.Assert.assertTrue("Newsletter tiles disappeared from the visible frame",colored>100)
    }

    @Test fun htmlReaderInAppBackAndRecreationRemainUsable() {
        compose.waitForText(HIKE)
        compose.runOnIdle { installLongHtml() }
        compose.onNodeWithText(HIKE).performClick()
        Thread.sleep(900)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Back")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Compose")).fetchSemanticsNodes().isNotEmpty() }
    }
}
