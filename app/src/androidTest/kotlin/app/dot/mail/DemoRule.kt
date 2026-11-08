package app.dot.mail

import androidx.test.platform.app.InstrumentationRegistry
import app.dot.design.ThemeMode
import app.dot.mail.data.MailGraph
import app.dot.mail.demo.DemoMailGraph
import org.junit.rules.ExternalResource

/**
 * Gives every test a pristine in-memory mailbox and default UI prefs (light theme for determinism).
 * Use it as the OUTER rule: RuleChain.outerRule(DemoRule()).around(composeRule), so the reset happens
 * before MainActivity starts. DemoMailGraph state lives in the Application, which survives between
 * tests; we swap in a fresh graph by reflection so production code needs no test hook.
 */
class DemoRule : ExternalResource() {
    val app: DotMailApp get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DotMailApp

    override fun before() {
        val f = DotMailApp::class.java.getDeclaredField("graph").apply { isAccessible = true }
        check(f.get(app) is DemoMailGraph) { "Tests must run in demo mode (use DemoTestRunner)" }
        f.set(app, DemoMailGraph())
        app.settings.update { app.settings.ui.value.let { _ -> UiPrefs(theme = ThemeMode.LIGHT) } }
    }

    val graph: MailGraph get() = app.graph
}
