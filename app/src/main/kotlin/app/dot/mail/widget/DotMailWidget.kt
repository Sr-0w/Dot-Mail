package app.dot.mail.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.text.TextAlign
import app.dot.design.DarkColors
import app.dot.design.LightColors
import app.dot.mail.MainActivity
import app.dot.mail.dotApp

/**
 * Home-screen widget: unread count of the unified inbox + the newest unread conversations.
 * Quiet look: near-black / white surface, neutral text hierarchy, no Material You colours.
 * Data is read once per update through [app.dot.mail.data.MailGraph]; updates are pushed by [DotWidgets].
 */
class DotMailWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.dotApp
        // With app lock / hidden Recents the widget (visible on the lock screen) shows only the count.
        val ui = app.settings.ui.value
        val privateMode = ui.appLock || ui.secureScreen
        val data = runCatching { WidgetRepository.load(app.graph, includeThreads = !privateMode) }
            .getOrDefault(WidgetData(0, emptyList()))
        provideContent { WidgetContent(context, data, privateMode) }
    }
}

private val bg = ColorProvider(LightColors.background, DarkColors.background)
private val primary = ColorProvider(LightColors.textPrimary, DarkColors.textPrimary)
private val secondary = ColorProvider(LightColors.textSecondary, DarkColors.textSecondary)
private val tertiary = ColorProvider(LightColors.textTertiary, DarkColors.textTertiary)
private val divider = ColorProvider(LightColors.border, DarkColors.border)
private val accent = ColorProvider(LightColors.accent, DarkColors.accent)

private fun openApp(context: Context) = actionStartActivity(
    Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
)

private fun openThread(context: Context, threadId: Long) = actionStartActivity(
    Intent(context, MainActivity::class.java)
        .setAction("app.dot.mail.action.OPEN_THREAD")
        // Unique data keeps the PendingIntents of different rows distinct; MainActivity reads only the extra.
        .setData(android.net.Uri.parse("dotmail://thread/$threadId"))
        .putExtra(MainActivity.EXTRA_THREAD_ID, threadId),
)

@Composable
private fun WidgetContent(context: Context, data: WidgetData, privateMode: Boolean) {
    val height = LocalSize.current.height
    // Header takes ~64dp; every row ~54dp.
    val rows = ((height.value - 64f) / 54f).toInt().coerceIn(0, WidgetRepository.MAX_THREADS)
    Column(
        GlanceModifier.fillMaxSize().background(bg).cornerRadius(22.dp).padding(horizontal = 16.dp, vertical = 14.dp)
            .clickable(openApp(context)),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.Bottom) {
            Column(GlanceModifier.defaultWeight()) {
                Text("INBOX", style = TextStyle(color = tertiary, fontSize = 11.sp, fontWeight = FontWeight.Medium))
                Text(
                    if (data.unread > 0) data.unread.toString() else "0",
                    style = TextStyle(color = primary, fontSize = 30.sp, fontWeight = FontWeight.Bold),
                )
            }
            Text(
                if (data.unread == 0) "All caught up" else if (data.unread == 1) "unread" else "unread",
                style = TextStyle(color = secondary, fontSize = 13.sp, textAlign = TextAlign.End),
            )
        }
        if (rows > 0 && data.threads.isNotEmpty()) {
            Spacer(GlanceModifier.height(8.dp))
            data.threads.take(rows).forEach { t ->
                Column(GlanceModifier.fillMaxWidth().padding(vertical = 5.dp).clickable(openThread(context, t.threadId))) {
                    Text(t.sender, maxLines = 1, style = TextStyle(color = primary, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                    Text(t.subject, maxLines = 1, style = TextStyle(color = secondary, fontSize = 12.sp))
                }
            }
        } else if (privateMode && data.unread > 0 && rows > 0) {
            Spacer(GlanceModifier.height(8.dp))
            Text("Open Dot Mail to read", style = TextStyle(color = tertiary, fontSize = 12.sp))
        }
    }
}
