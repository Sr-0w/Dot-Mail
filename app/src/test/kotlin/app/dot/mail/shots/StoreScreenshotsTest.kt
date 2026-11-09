package app.dot.mail.shots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import app.dot.design.Dot
import app.dot.design.DotTheme
import app.dot.design.ThemeMode
import app.dot.mail.AppSettings
import app.dot.mail.LocalMailGraph
import app.dot.mail.LocalSettings
import app.dot.mail.demo.DemoMailGraph
import app.dot.mail.ui.inbox.InboxScreen
import app.dot.mail.ui.settings.SettingsScreen
import app.dot.mail.ui.thread.ThreadScreen
import java.io.File
import java.util.Locale
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Explicit export fixture: no DotMailApp, real MailGraph, credentials, network or device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    application = android.app.Application::class,
    qualifiers = "w411dp-h891dp-xxhdpi",
)
class StoreScreenshotsTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun capture(locale: Locale, screen: String) {
        assumeTrue(System.getProperty("dotmail.storeShots") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            val configuration =
                android.content.res.Configuration(context.resources.configuration).apply {
                    setLocale(locale)
                }
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
            @Suppress("DEPRECATION")
            compose.activity.resources.updateConfiguration(
                configuration,
                compose.activity.resources.displayMetrics,
            )
            val settings =
                AppSettings(context).apply {
                    update {
                        it.copy(
                            theme = ThemeMode.DARK,
                            contactPhotos = false,
                            gravatar = false,
                            libravatar = false,
                        )
                    }
                }
            val demo = DemoMailGraph()
            compose.setContent {
                CompositionLocalProvider(
                    LocalSettings provides settings,
                    LocalMailGraph provides demo,
                ) {
                    DotTheme(ThemeMode.DARK) {
                        Box(Modifier.fillMaxSize().background(Dot.colors.background)) {
                            when (screen) {
                                "1-inbox" -> InboxScreen({ _, _ -> }, {}, {}, {}, {}, {}, {})
                                "2-thread" ->
                                    ThreadScreen(1, {}, onReply = { _, _ -> }, onComposeTo = {})
                                "3-settings" -> SettingsScreen({}, {}, {})
                            }
                        }
                    }
                }
            }
            if (screen != "3-settings")
                compose.waitUntil(15000) {
                    compose
                        .onAllNodesWithText("Saturday hike", substring = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
            compose.waitForIdle()
            val view = compose.activity.window.decorView
            check(view.width >= 320 && view.height >= 320)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output =
                File("build/reports/store-shots/${locale.toLanguageTag()}/$screen.png").apply {
                    parentFile!!.mkdirs()
                }
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun inboxEnglish() = capture(Locale.US, "1-inbox")

    @Test fun threadEnglish() = capture(Locale.US, "2-thread")

    @Test fun settingsEnglish() = capture(Locale.US, "3-settings")

    @Test fun inboxFrench() = capture(Locale.FRANCE, "1-inbox")

    @Test fun threadFrench() = capture(Locale.FRANCE, "2-thread")

    @Test fun settingsFrench() = capture(Locale.FRANCE, "3-settings")

    @Test fun inboxGerman() = capture(Locale.GERMANY, "1-inbox")

    @Test fun threadGerman() = capture(Locale.GERMANY, "2-thread")

    @Test fun settingsGerman() = capture(Locale.GERMANY, "3-settings")
}
