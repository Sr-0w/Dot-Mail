package app.dot.mail.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.dot.design.DotTheme
import app.dot.design.ThemeMode
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LicenceNoticesTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun bundledInventoryCoversEveryListedComponentAndFullLicenseTexts() {
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        val bytes = assets.open("third-party-notices.txt").use { it.readBytes() }
        val text = bytes.toString(Charsets.UTF_8)
        val manifest =
            assets.open("third-party-components.json").bufferedReader().use {
                JSONObject(it.readText())
            }
        val hash =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        assertEquals(manifest.getString("bundle_sha256"), hash)
        manifest.getJSONObject("components").keys().forEach { assertTrue(it, text.contains(it)) }
        assertTrue(text.contains("Apache License"))
        assertTrue(text.contains("END OF TERMS AND CONDITIONS"))
        assertTrue(text.contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue(text.contains("Copyright (c) 2016 The Inter Project Authors"))
        assertTrue(text.contains("Copyright (c) 2009-2024 Jonathan Hedley"))
        assertTrue(text.contains("Redistribution and use in source and binary forms"))
        assertTrue(text.contains("Notices for Jakarta Mail"))
    }

    @Test
    fun settingsNoticeContentCanBeReadWithoutNetwork() {
        compose.setContent { DotTheme(ThemeMode.DARK) { LicenceNotices() } }
        compose.waitUntil(10000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test.hasText("Dot Mail — licenses and third-party notices", substring = true)
                )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Dot Mail — licenses and third-party notices", substring = true).assertIsDisplayed()
    }
}
