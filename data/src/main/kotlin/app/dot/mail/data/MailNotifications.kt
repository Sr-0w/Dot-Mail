package app.dot.mail.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.dot.mail.data.local.AccountRow
import app.dot.mail.data.model.MessageAction
import kotlinx.coroutines.launch

internal object MailNotifications {
    fun newMail(context: Context, account: AccountRow, threadId: Long, subject: String) {
        if (!AccountSettingsStore.get(context).current(account.id).notificationsEnabled) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "dotmail.account.${account.id}"
        manager.createNotificationChannel(NotificationChannel(channel, account.email, NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launcher.putExtra("dotmail.threadId", threadId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        launcher.data = android.net.Uri.parse("dotmail://thread/$threadId")
        val open = PendingIntent.getActivity(context, 0, launcher, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(value: String): PendingIntent = PendingIntent.getBroadcast(context, 0, Intent(context, NotificationActionReceiver::class.java).setAction(value).setData(android.net.Uri.parse("dotmail://thread/$threadId/$value")).putExtra("threadId", threadId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(threadId.toString(), 1, NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle(account.name.ifBlank { account.email }).setContentText(subject.ifBlank { "New message" })
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_view, "Mark read", action("MARK_READ"))
            .addAction(android.R.drawable.ic_menu_save, "Archive", action("ARCHIVE")).build())
    }
}
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val thread = intent.getLongExtra("threadId", -1)
        val action = when (intent.action) { "MARK_READ" -> MessageAction.MARK_READ; "ARCHIVE" -> MessageAction.ARCHIVE; else -> return }
        if (thread <= 0) return
        val pending = goAsync()
        val graph = GraphRegistry.get(context)
        graph.scope.launch {
            try { graph.apply(action, listOf(thread)); context.getSystemService(NotificationManager::class.java).cancel(thread.toString(), 1) }
            catch (_: Exception) { /* Keep the notification available when the local action cannot be applied. */ }
            finally { pending.finish() }
        }
    }
}
