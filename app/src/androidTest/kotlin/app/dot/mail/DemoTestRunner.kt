package app.dot.mail

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Instrumented tests always run against [app.dot.mail.demo.DemoMailGraph].
 * The "demo" preference has to be set before DotMailApp.onCreate picks the graph, so we write it
 * in newApplication(), which runs before the Application is created.
 */
class DemoTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        context!!.getSharedPreferences("ui", Context.MODE_PRIVATE).edit()
            .clear()
            .putBoolean("demo", true)
            .putString("theme", "LIGHT")
            .commit()
        return super.newApplication(cl, className, context)
    }
}
