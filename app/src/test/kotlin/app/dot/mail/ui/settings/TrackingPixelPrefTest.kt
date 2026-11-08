package app.dot.mail.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.AppSettings
import app.dot.mail.UiPrefs
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TrackingPixelPrefTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `blocking is on by default and independent of loading remote images`(): Unit {
        val d = UiPrefs()
        assertThat(d.blockTrackingPixels).isTrue()
        assertThat(d.loadRemoteImages).isFalse()
        val settings = AppSettings(context)
        settings.update { it.copy(loadRemoteImages = true) }
        assertThat(AppSettings(context).ui.value.blockTrackingPixels).isTrue() // loading images does not switch it off
        settings.update { it.copy(blockTrackingPixels = false) }
        assertThat(AppSettings(context).ui.value.blockTrackingPixels).isFalse()
        assertThat(AppSettings(context).ui.value.loadRemoteImages).isTrue() // and the reverse
        settings.update { it.copy(blockTrackingPixels = true, loadRemoteImages = false) }
    }
}
