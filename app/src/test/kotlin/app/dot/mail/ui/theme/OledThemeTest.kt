package app.dot.mail.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import app.dot.design.DarkColors
import app.dot.design.Dot
import app.dot.design.DotColors
import app.dot.design.DotTheme
import app.dot.design.LightColors
import app.dot.design.OledColors
import app.dot.design.ThemeMode
import app.dot.mail.AppSettings
import app.dot.mail.UiPrefs
import app.dot.mail.windowBackgroundFor
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h640dp")
class OledThemeTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun luminance(c: Color): Double {
        fun lin(v: Float) = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = luminance(a).coerceAtLeast(luminance(b)) to luminance(a).coerceAtMost(luminance(b))
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun `the page background is exactly black and the rest of the palette stays dark`(): Unit {
        assertThat(OledColors.background).isEqualTo(Color(0xFF000000))
        assertThat(OledColors.isDark).isTrue()
        assertThat(DarkColors.background).isNotEqualTo(OledColors.background) // Dark is unchanged
        assertThat(LightColors.background).isEqualTo(Color.White)
        assertThat(OledColors.accent).isEqualTo(DarkColors.accent) // the electric-blue accent is kept
        assertThat(OledColors.onInverse).isEqualTo(Color.Black)
    }

    @Test fun `lifted surfaces are distinguishable from the background but not slabs`(): Unit {
        val bg = OledColors.background
        // Surfaces must read as separate (contrast above 1.05) yet remain very dark (below 1.5).
        for (surface in listOf(OledColors.surface, OledColors.surfaceRaised)) {
            assertThat(contrast(surface, bg)).isGreaterThan(1.05)
            assertThat(contrast(surface, bg)).isLessThan(1.5)
        }
        assertThat(contrast(OledColors.surfaceRaised, OledColors.surface)).isGreaterThan(1.05)
        assertThat(contrast(OledColors.border, bg)).isGreaterThan(1.1) // hairlines stay visible on black
        assertThat(contrast(OledColors.borderStrong, bg)).isGreaterThan(OledColors.border.let { contrast(it, bg) })
    }

    @Test fun `text keeps WCAG AA on the background and on every lifted surface`(): Unit {
        fun check(c: DotColors) {
            for (surface in listOf(c.background, c.surface, c.surfaceRaised)) {
                assertThat(contrast(c.textPrimary, surface)).isAtLeast(7.0)
                assertThat(contrast(c.textSecondary, surface)).isAtLeast(4.5)
                assertThat(contrast(c.textTertiary, surface)).isAtLeast(4.5)
                assertThat(contrast(c.accent, surface)).isAtLeast(4.5)
            }
        }
        check(OledColors)
    }

    @Test fun `the window background follows the theme before compose exists`(): Unit {
        val dark = Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_YES }
        val light = Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_NO }
        assertThat(windowBackgroundFor(ThemeMode.OLED, light)).isEqualTo(Color.Black.toArgb())
        assertThat(windowBackgroundFor(ThemeMode.OLED, dark)).isEqualTo(Color.Black.toArgb())
        assertThat(windowBackgroundFor(ThemeMode.DARK, light)).isEqualTo(DarkColors.background.toArgb())
        assertThat(windowBackgroundFor(ThemeMode.LIGHT, dark)).isEqualTo(LightColors.background.toArgb())
        assertThat(windowBackgroundFor(ThemeMode.SYSTEM, dark)).isEqualTo(DarkColors.background.toArgb())
        assertThat(windowBackgroundFor(ThemeMode.SYSTEM, light)).isEqualTo(LightColors.background.toArgb())
    }

    @Test fun `the choice persists`(): Unit {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppSettings(context).update { it.copy(theme = ThemeMode.OLED) }
        assertThat(AppSettings(context).ui.value.theme).isEqualTo(ThemeMode.OLED)
        assertThat(UiPrefs().theme).isEqualTo(ThemeMode.SYSTEM) // default unchanged
        AppSettings(context).update { it.copy(theme = ThemeMode.SYSTEM) }
    }

    @Test fun `rendered pixels are pure black in OLED and charcoal in Dark`(): Unit {
        fun renderedPixel(mode: ThemeMode): Int {
            compose.setContent { DotTheme(mode) { Box(Modifier.fillMaxSize().background(Dot.colors.background)) } }
            compose.waitForIdle()
            val view = compose.activity.window.decorView
            val bmp = android.graphics.Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bmp))
            return bmp.getPixel(bmp.width / 2, bmp.height / 2)
        }
        assertThat(renderedPixel(ThemeMode.OLED)).isEqualTo(0xFF000000.toInt())
    }
}
