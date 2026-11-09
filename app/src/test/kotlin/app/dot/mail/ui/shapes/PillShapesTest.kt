package app.dot.mail.ui.shapes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.dot.design.DotShapes
import app.dot.design.DotTheme
import app.dot.design.ThemeMode
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotFilterChip
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** FR-55: pills have true semicircular ends at any height; the shape tokens are the single source. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h640dp")
class PillShapesTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val density = Density(1f)

    private fun radius(shape: androidx.compose.ui.graphics.Shape, w: Float, h: Float): Float =
        (shape.createOutline(Size(w, h), LayoutDirection.Ltr, density) as Outline.Rounded).roundRect.topLeftCornerRadius.x

    @Test fun `the pill token is half the height whatever the size or font scale`() {
        for (h in listOf(24f, 34f, 48f, 80f)) assertThat(radius(DotShapes.pill, 200f, h)).isEqualTo(h / 2)
    }

    @Test fun `the circle token is a circle and containers are more generously rounded than before`() {
        assertThat(DotShapes.circle.createOutline(Size(40f, 40f), LayoutDirection.Ltr, density)).isInstanceOf(Outline.Rounded::class.java)
        assertThat(radius(DotShapes.circle, 40f, 40f)).isEqualTo(20f)
        assertThat(radius(DotShapes.dialog, 400f, 300f)).isEqualTo(28f)
        assertThat(radius(DotShapes.sheet, 400f, 300f)).isEqualTo(28f)
    }

    /** Renders [content] on white and reports the visible (non-white) box of the tagged node plus the bitmap. */
    private fun render(content: @androidx.compose.runtime.Composable () -> Unit): Pair<IntArray, android.graphics.Bitmap> {
        compose.setContent {
            DotTheme(ThemeMode.LIGHT) { Box(Modifier.background(Color.White).padding(16.dp), contentAlignment = Alignment.TopStart) { Box(Modifier.testTag("n")) { content() } } }
        }
        compose.waitForIdle()
        val b = compose.onNodeWithTag("n").getBoundsInRoot()
        val view = compose.activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var bt = -1
        for (y in b.top.value.toInt()..b.bottom.value.toInt().coerceAtMost(bmp.height - 1)) for (x in b.left.value.toInt()..b.right.value.toInt().coerceAtMost(bmp.width - 1)) {
            if (bmp.getPixel(x, y) != android.graphics.Color.WHITE) { l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); bt = maxOf(bt, y) }
        }
        return intArrayOf(l, t, r, bt) to bmp
    }

    /** A pixel 3px in from a square-ish corner is filled for an 8-12dp rectangle but empty for a semicircular end. */
    private fun assertPillEnds(content: @androidx.compose.runtime.Composable () -> Unit) {
        val (box, bmp) = render(content)
        assertThat(box[2]).isGreaterThan(box[0])
        val h = box[3] - box[1]
        assertThat(h).isAtLeast(30)
        val white = android.graphics.Color.WHITE
        val info = "box=${box.toList()} h=$h"
        assertWithMessage(info).that(bmp.getPixel(box[0] + 3, box[1] + 3)).isEqualTo(white)
        assertWithMessage(info).that(bmp.getPixel(box[2] - 3, box[3] - 3)).isEqualTo(white)
        assertWithMessage(info).that(bmp.getPixel(box[0], (box[1] + box[3]) / 2)).isNotEqualTo(white) // the outermost point of the end is drawn
    }

    @Test fun `a primary button has semicircular ends`() = assertPillEnds { DotButton("Send", onClick = {}) }

    @Test fun `a selected filter chip has semicircular ends`() = assertPillEnds { DotFilterChip("Unread", selected = true, onClick = {}) }

    @Test fun `an unselected outlined chip keeps the same ends as its selected state`() = assertPillEnds { DotFilterChip("Unread", selected = false, onClick = {}) }

    @Test fun `a secondary button has semicircular ends`() = assertPillEnds { DotButton("Cancel", onClick = {}, style = ButtonStyle.Secondary) }
}
