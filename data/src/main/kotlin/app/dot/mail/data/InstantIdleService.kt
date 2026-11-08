package app.dot.mail.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Explicitly enabled persistent IMAP IDLE; ordinary syncing continues to use WorkManager. */
class InstantIdleService : Service() {
    private val lifetime=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var graph: RealMailGraph?=null
    private var watcher: Job?=null

    override fun onBind(intent:Intent?): IBinder? = null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int): Int {
        val settings=AccountSettingsStore.get(this)
        if(intent?.action==ACTION_STOP) {
            settings.updateSync { it.copy(instantNotifications=false) }
            graph?.foregroundIdle?.serviceRunning?.value=false
            stopSelf(); return START_NOT_STICKY
        }
        if(!settings.sync.value.instantNotifications) { stopSelf(); return START_NOT_STICKY }
        try {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL,"Instant mail notifications",NotificationManager.IMPORTANCE_LOW))
            val stop=PendingIntent.getService(this,0,Intent(this,InstantIdleService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("Instant mail notifications").setContentText("Keeps Inbox connected; may use more battery")
                .setCategory(NotificationCompat.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).addAction(android.R.drawable.ic_menu_close_clear_cancel,"Stop",stop)
            packageManager.getLaunchIntentForPackage(packageName)?.let { launcher ->
                builder.setContentIntent(PendingIntent.getActivity(this,0,launcher,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            ServiceCompat.startForeground(this,NOTIFICATION_ID,builder.build(),if(Build.VERSION.SDK_INT>=34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            val active=graph ?: GraphRegistry.get(this).also { graph=it }
            active.foregroundIdle.serviceRunning.value=true
            if(watcher==null) watcher=lifetime.launch {
                combine(settings.sync.map { it.instantNotifications }.distinctUntilChanged(),active.dao.observeAccounts()) { enabled,accounts -> enabled to accounts.isNotEmpty() }.distinctUntilChanged().collect { (enabled,hasAccounts) ->
                    val keep=enabled && hasAccounts
                    if(!keep) {
                        if(!hasAccounts) settings.updateSync { it.copy(instantNotifications=false) }
                        active.foregroundIdle.serviceRunning.value=false
                        stopSelf()
                    }
                }
            }
            return START_STICKY
        } catch(e:Exception) {
            SyncDiagnostics.warn("Instant notification service",e)
            settings.updateSync { it.copy(instantNotifications=false) }
            graph?.foregroundIdle?.serviceRunning?.value=false
            stopSelf(); return START_NOT_STICKY
        }
    }
    override fun onDestroy() {
        graph?.foregroundIdle?.serviceRunning?.value=false
        lifetime.cancel()
        ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        internal const val CHANNEL="dotmail.instant"
        internal const val NOTIFICATION_ID=0x444F54
        internal const val ACTION_STOP="app.dot.mail.action.STOP_INSTANT_NOTIFICATIONS"
    }
}
