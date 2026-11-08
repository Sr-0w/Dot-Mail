package app.dot.mail.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import app.dot.mail.dotApp

class DotMailWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DotMailWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        DotWidgets.start(context.dotApp)
        WidgetRefreshWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        DotWidgets.stop()
        WidgetRefreshWorker.cancel(context)
    }
}
