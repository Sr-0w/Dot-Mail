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
    /** [folderId] = folder the new mail arrived in: actions from the notification are scoped to it (never Sent copies). */
    fun newMail(context: Context, account: AccountRow, threadId: Long, subject: String, folderId: Long = 0) {
        if (!AccountSettingsStore.get(context).current(account.id).notificationsEnabled) return
        if (NotificationRules.get(context).isMuted(threadId)) return // muted conversation: new mail stays unread but silent
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "dotmail.account.${account.id}"
        manager.createNotificationChannel(NotificationChannel(channel, account.email, NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launcher.putExtra("dotmail.threadId", threadId).putExtra("dotmail.folderId", folderId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        launcher.data = android.net.Uri.parse("dotmail://thread/$threadId")
        val open = PendingIntent.getActivity(context, 0, launcher, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(value: String): PendingIntent = PendingIntent.getBroadcast(context, 0, Intent(context, NotificationActionReceiver::class.java).setAction(value).setData(android.net.Uri.parse("dotmail://thread/$threadId/$value")).putExtra("threadId", threadId).putExtra("folderId", folderId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle(account.name.ifBlank { account.email }).setContentText(subject.ifBlank { context.getString(R.string.notif_new_message) })
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open)
        NotificationRules.get(context).actions.value.forEach { a ->
            when (a) {
                NotificationAction.MARK_READ -> builder.addAction(android.R.drawable.ic_menu_view, context.getString(R.string.notif_mark_read), action("MARK_READ"))
                NotificationAction.ARCHIVE -> builder.addAction(android.R.drawable.ic_menu_save, context.getString(R.string.notif_archive), action("ARCHIVE"))
                NotificationAction.DELETE -> builder.addAction(android.R.drawable.ic_menu_delete, context.getString(R.string.notif_delete), action("TRASH"))
                NotificationAction.REPLY -> {
                    // Opens the app straight into a reply (MainActivity understands dotmail.reply).
                    val reply = Intent(launcher).putExtra("dotmail.reply", true).setData(android.net.Uri.parse("dotmail://thread/$threadId/reply"))
                    builder.addAction(android.R.drawable.ic_menu_revert, context.getString(R.string.notif_reply), PendingIntent.getActivity(context, 1, reply, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
            }
        }
        manager.notify(threadId.toString(), 1, builder.build())
    }
}
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val thread = intent.getLongExtra("threadId", -1)
        val action = when (intent.action) { "MARK_READ" -> MessageAction.MARK_READ; "ARCHIVE" -> MessageAction.ARCHIVE; "TRASH" -> MessageAction.TRASH; else -> return }
        if (thread <= 0) return
        val pending = goAsync()
        val graph = GraphRegistry.get(context)
        graph.scope.launch {
            val folder = intent.getLongExtra("folderId", 0)
            try {
                if (folder > 0) graph.apply(action, listOf(thread), app.dot.mail.data.model.Mailbox.InFolder(folder))
                else @Suppress("DEPRECATION") graph.apply(action, listOf(thread))
                 context.getSystemService(NotificationManager::class.java).cancel(thread.toString(), 1) }
            catch (_: Exception) { /* Keep the notification available when the local action cannot be applied. */ }
            finally { pending.finish() }
        }
    }
}
