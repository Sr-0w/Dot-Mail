package app.dot.mail.ui.calendar

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.engine.calendar.Ics
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RRuleTextTest {
    private fun resources(tag: String): android.content.res.Resources {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val cfg = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(cfg).resources
    }
    private fun parts(raw: String) = Ics.parseRrule(raw)!!
    private fun text(raw: String, tag: String) = RRuleText.format(parts(raw), resources(tag), Locale.forLanguageTag(tag))

    @Test fun `weekly with count in three languages`(): Unit {
        assertThat(text("FREQ=WEEKLY;COUNT=4", "en")).isEqualTo("Weekly, 4 times")
        assertThat(text("FREQ=WEEKLY;COUNT=4", "fr")).isEqualTo("Toutes les semaines, 4 fois")
        assertThat(text("FREQ=WEEKLY;COUNT=4", "de")).isEqualTo("Wöchentlich, 4 Mal")
    }

    @Test fun `interval, weekday and ordinal use the language's own words`(): Unit {
        assertThat(text("FREQ=DAILY;INTERVAL=3", "en")).isEqualTo("Every 3 days")
        assertThat(text("FREQ=DAILY;INTERVAL=3", "de")).isEqualTo("Alle 3 Tage")
        assertThat(text("FREQ=WEEKLY;BYDAY=MO", "fr")).isEqualTo("Toutes les semaines, chaque lundi")
        assertThat(text("FREQ=MONTHLY;BYDAY=2TU", "de")).isEqualTo("Monatlich, am zweiten Dienstag")
        assertThat(text("FREQ=MONTHLY;BYDAY=-1FR", "en")).isEqualTo("Monthly, on the last Friday")
    }

    @Test fun `until date is formatted for the locale`(): Unit {
        assertThat(text("FREQ=YEARLY;UNTIL=20281231", "fr")).contains("31")
        assertThat(text("FREQ=YEARLY;UNTIL=20281231", "fr")).startsWith("Tous les ans, jusqu’au")
        assertThat(text("FREQ=YEARLY;UNTIL=20281231", "en")).isNotEqualTo(text("FREQ=YEARLY;UNTIL=20281231", "de"))
    }
}
