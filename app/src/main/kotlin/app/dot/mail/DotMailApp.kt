package app.dot.mail

import android.app.Application
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import app.dot.mail.data.MailGraph
import app.dot.mail.data.createMailGraph
import app.dot.mail.demo.DemoMailGraph

class DotMailApp : Application() {
    lateinit var settings: AppSettings
        private set
    lateinit var graph: MailGraph
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        // Demo mode (debug builds only) shows sample mail with no network at all: used for
        // screenshots, UI tests and trying the app without an account.
        graph = if (BuildConfig.DEBUG && settings.demoMode) DemoMailGraph() else createMailGraph(this)
        app.dot.mail.widget.DotWidgets.install(this)
    }
}

val Context.dotApp: DotMailApp get() = applicationContext as DotMailApp

val LocalMailGraph = staticCompositionLocalOf<MailGraph> { error("MailGraph not provided") }
val LocalSettings = staticCompositionLocalOf<AppSettings> { error("AppSettings not provided") }
