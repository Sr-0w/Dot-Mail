package app.dot.mail.ui.theme

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.dot.design.DotTheme
import app.dot.design.ThemeMode
import app.dot.design.components.TopEdgeFade
import app.dot.design.components.edgeProgress
import app.dot.design.components.edgeMaskStops
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h640dp")
class TopEdgeFadeTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun `progress interpolates with the scroll distance and clamps`(): Unit {
        assertThat(edgeProgress(0f, 100f)).isEqualTo(0f)
        assertThat(edgeProgress(25f, 100f)).isWithin(1e-6f).of(0.25f)
        assertThat(edgeProgress(100f, 100f)).isEqualTo(1f)
        assertThat(edgeProgress(900f, 100f)).isEqualTo(1f)
        assertThat(edgeProgress(-30f, 100f)).isEqualTo(0f) // overscroll never goes negative
        assertThat(edgeProgress(1f, 0f)).isEqualTo(1f)
        assertThat(edgeProgress(0f, 0f)).isEqualTo(0f)
    }

    /** Green channel of the top-row pixel of a red page under a light theme's white fade: how far the fade went. */
    private fun fadeAmount(progress: Float): Int {
        compose.setContent {
            DotTheme(ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(Color.Red)) {
                    TopEdgeFade(null, progress, Modifier.align(Alignment.TopCenter), height = 40.dp)
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        return android.graphics.Color.green(bmp.getPixel(bmp.width / 2, 2))
    }

    @Test fun `at rest the content is untouched`(): Unit {
        assertThat(fadeAmount(0f)).isEqualTo(0)
    }

    @Test fun `a half scrolled edge fades part of the way`(): Unit {
        val half = fadeAmount(0.5f)
        assertThat(half).isGreaterThan(20)
        assertThat(half).isLessThan(200)
    }

    @Test fun `fully scrolled edge is strongly faded`(): Unit {
        assertThat(fadeAmount(1f)).isGreaterThan(200)
    }

    @Test fun `reduced motion makes the edge steady instead of interpolated`(): Unit {
        val context = ApplicationProvider.getApplicationContext<Context>()
        android.provider.Settings.Global.putFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            assertThat(fadeAmount(0.2f)).isGreaterThan(200) // any scroll shows the full, steady fade
        } finally {
            android.provider.Settings.Global.putFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }

    @Test fun `the edge mask is solid over the header, then eases out monotonically with no early dark strip`(): Unit {
        val stops = edgeMaskStops(solidUntil = 0.5f, fadeLength = 0.4f)
        assertThat(stops.first()).isEqualTo(0f to 1f)
        assertThat(stops.filter { it.first <= 0.5f }.map { it.second }.distinct()).containsExactly(1f) // nothing fades above the header bottom
        assertThat(stops.last()).isEqualTo(0.9f to 0f)
        stops.toList().zipWithNext().forEach { (a, b) -> assertThat(b.first).isGreaterThan(a.first); assertThat(b.second).isAtMost(a.second) }
    }

    @Test fun `a collapsed header moves the solid band up instead of leaving it at the expanded size`(): Unit {
        val expanded = edgeMaskStops(solidUntil = 0.7f, fadeLength = 0.3f)
        val collapsed = edgeMaskStops(solidUntil = 0.45f, fadeLength = 0.3f)
        assertThat(collapsed.last().first).isWithin(1e-6f).of(0.75f)
        assertThat(collapsed.last().first).isLessThan(expanded.last().first)
    }
}
