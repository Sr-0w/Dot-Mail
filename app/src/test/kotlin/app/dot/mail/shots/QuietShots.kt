package app.dot.mail.shots

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import app.dot.design.DotTheme
import app.dot.design.ThemeMode
import app.dot.mail.AppSettings
import app.dot.mail.LocalMailGraph
import app.dot.mail.LocalSettings
import app.dot.mail.demo.DemoMailGraph
import app.dot.mail.ui.settings.CategoriesPage
import app.dot.mail.ui.settings.SmartFoldersPage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize

/** Renders screens with Demo data (never real mail) to PNGs under app/build/shots, in light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class QuietShots {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File("build/shots").apply { mkdirs() }

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(context)
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides settings, LocalMailGraph provides DemoMailGraph()) {
                DotTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { content() }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val w = view.width.coerceAtLeast(1); val h = view.height.coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(out, "$name-${if (dark) "dark" else "light"}.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun categoriesLight() = shoot("categories", false) { CategoriesPage(onBack = {}) }
    @Test fun categoriesDark() = shoot("categories", true) { CategoriesPage(onBack = {}) }
    @Test fun smartLight() = shoot("smart-folders", false) { SmartFoldersPage(onBack = {}) }
    @Test fun smartDark() = shoot("smart-folders", true) { SmartFoldersPage(onBack = {}) }

    private fun populated() {
        val store = app.dot.mail.data.SmartFolderStore.get(ApplicationProvider.getApplicationContext<Context>())
        store.replaceAll(listOf(
            app.dot.mail.data.model.SmartFolder("a", "Invoices to pay", subject = "invoice", unreadOnly = true),
            app.dot.mail.data.model.SmartFolder("b", "From the team", from = "team", hasAttachment = true),
        ))
    }

    @Test fun smartListLight() { populated(); shoot("smart-folders-list", false) { SmartFoldersPage(onBack = {}) } }
    @Test fun smartListDark() { populated(); shoot("smart-folders-list", true) { SmartFoldersPage(onBack = {}) } }

    @Test fun smartEditorLight() = shoot("smart-folder-editor", false) {
        app.dot.mail.ui.settings.SmartFolderEditor(
            app.dot.mail.data.model.SmartFolder("a", "Invoices to pay", subject = "invoice", unreadOnly = true), isNew = false, onBack = {},
            store = app.dot.mail.data.SmartFolderStore.get(ApplicationProvider.getApplicationContext<Context>()),
        )
    }
    @Test fun smartEditorDark() = shoot("smart-folder-editor", true) {
        app.dot.mail.ui.settings.SmartFolderEditor(
            app.dot.mail.data.model.SmartFolder("a", "Invoices to pay", subject = "invoice", unreadOnly = true), isNew = false, onBack = {},
            store = app.dot.mail.data.SmartFolderStore.get(ApplicationProvider.getApplicationContext<Context>()),
        )
    }

    private val demoResults = listOf(
        app.dot.mail.data.model.SearchResult(
            app.dot.mail.data.model.ThreadSummary(1, 1, "Invoice March", listOf(app.dot.mail.data.model.EmailAddress("billing@acme.example", "Acme Billing")), "", 1_700_000_000_000, 2, 1, false, true),
            1, 1, "Invoice March", listOf(app.dot.mail.data.model.HighlightRange(0, 7)),
            "…please find the invoice for March attached. Payment is due within 14 days of the invoice date…", listOf(app.dot.mail.data.model.HighlightRange(17, 24), app.dot.mail.data.model.HighlightRange(70, 77)),
            bodyAvailable = true, serverOnly = false,
        ),
        app.dot.mail.data.model.SearchResult(
            app.dot.mail.data.model.ThreadSummary(2, 1, "Re: Q1 numbers", listOf(app.dot.mail.data.model.EmailAddress("anna@example.org", "Anna Müller")), "", 1_690_000_000_000, 1, 0, false, false),
            2, 1, "Re: Q1 numbers", emptyList(), "Thanks, the invoice totals match what we agreed last week.", listOf(app.dot.mail.data.model.HighlightRange(12, 19)),
            bodyAvailable = false, serverOnly = true,
        ),
    )

    private fun searchScreen() = @Composable {
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize().background(app.dot.design.Dot.colors.background)) {
            app.dot.mail.ui.search.ScopeBar(app.dot.mail.ui.search.SearchMode.ALL_FOLDERS, app.dot.mail.data.model.SearchSort.RELEVANCE, true, true, true, {}, {}, {})
            app.dot.mail.ui.search.ProgressLines(app.dot.mail.data.model.SearchProgress(serverRunning = true, index = app.dot.mail.data.model.SearchIndexProgress(120, 480, true)))
            demoResults.forEach { app.dot.mail.ui.search.SearchResultRow(it, onClick = {}) }
        }
    }
    @Test fun searchLight() = shoot("search", false, searchScreen())
    @Test fun searchDark() = shoot("search", true, searchScreen())
}
