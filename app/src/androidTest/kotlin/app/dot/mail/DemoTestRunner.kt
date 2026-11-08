package app.dot.mail

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.runner.AndroidJUnitRunner

/**
 * Instrumented tests always run against [app.dot.mail.demo.DemoMailGraph].
 *
 * Tests may run on a phone with a real Dot Mail install, so they must never write the real "ui"
 * preferences (even if the run is killed half-way). The Application is attached to a context that
 * redirects the "ui" SharedPreferences file to "ui_test"; that file is reset to demo mode + light
 * theme at every start. The real prefs, database and accounts are not touched: the demo graph is
 * purely in memory.
 */
class DemoTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        val redirected = object : ContextWrapper(context!!) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                super.getSharedPreferences(if (name == "ui") "ui_test" else name, mode)
        }
        redirected.getSharedPreferences("ui", Context.MODE_PRIVATE).edit()
            .clear().putBoolean("demo", true).putString("theme", "LIGHT").commit()
        return super.newApplication(cl, className, redirected)
    }
}
