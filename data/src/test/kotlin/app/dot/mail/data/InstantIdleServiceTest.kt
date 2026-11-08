package app.dot.mail.data

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.MailDatabase
import app.dot.mail.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class InstantIdleServiceTest {
    private lateinit var context: Context
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private lateinit var settings: AccountSettingsStore
    private var previousGraph: Any?=null
    private val registry=GraphRegistry::class.java.getDeclaredField("graph").apply { isAccessible=true }
    @Before fun setup() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context=ApplicationProvider.getApplicationContext()
        settings=AccountSettingsStore.get(context); settings.updateSync { SyncSettings() }
        db=Room.inMemoryDatabaseBuilder(context,MailDatabase::class.java).allowMainThreadQueries().build()
        graph=RealMailGraph(context,db,object: CredentialVault {
            override fun encrypt(accountEmail:String,secret:String)=secret
            override fun decrypt(accountEmail:String,encrypted:String)=encrypted
        },scheduling=false)
        previousGraph=registry.get(null); registry.set(null,graph)
        graph.addAccount(AccountConfig("one@example.org","One",ServerConfig("localhost",1,Security.NONE,"one"),ServerConfig("localhost",1,Security.NONE,"one")),"password")
    }
    @After fun close() { settings.updateSync { SyncSettings() }; registry.set(null,previousGraph); graph.scope.cancel(); db.close(); Dispatchers.resetMain() }
    @Test fun defaultIsOffAndDisabledServiceDoesNotBecomeSticky() = runBlocking {
        assertFalse(graph.instantNotifications.first())
        val controller=Robolectric.buildService(InstantIdleService::class.java).create()
        try {
            assertEquals(android.app.Service.START_NOT_STICKY,controller.get().onStartCommand(null,0,1))
            assertFalse(graph.foregroundIdle.serviceRunning.value)
        } finally { controller.destroy() }
    }
    @Test fun enabledServiceShowsBatteryTradeoffAndStopActionDisablesIt() {
        settings.updateSync { it.copy(instantNotifications=true) }
        val controller=Robolectric.buildService(InstantIdleService::class.java).create()
        val service=controller.get()
        try {
            assertEquals(android.app.Service.START_STICKY,service.onStartCommand(null,0,1))
            val notification=shadowOf(service).lastForegroundNotification
            assertNotNull(notification)
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("battery"))
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals("Stop",notification.actions.single().title.toString())
            assertEquals(NotificationManager.IMPORTANCE_LOW,context.getSystemService(NotificationManager::class.java).getNotificationChannel(InstantIdleService.CHANNEL).importance)
            assertTrue(graph.foregroundIdle.serviceRunning.value)
            service.onStartCommand(Intent(service,InstantIdleService::class.java).setAction(InstantIdleService.ACTION_STOP),0,2)
            assertFalse(settings.sync.value.instantNotifications)
            assertFalse(graph.foregroundIdle.serviceRunning.value)
        } finally { controller.destroy() }
        assertFalse(graph.foregroundIdle.serviceRunning.value)
    }
    @Test fun visibleAppCanEnableAndDisableThroughRepositoryApi() = runBlocking {
        val lifecycle=ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
        val prior=lifecycle.currentState
        try {
            lifecycle.currentState=Lifecycle.State.CREATED
            assertFalse(graph.setInstantNotificationsEnabled(true))
            lifecycle.currentState=Lifecycle.State.STARTED
            assertTrue(graph.setInstantNotificationsEnabled(true))
            assertTrue(graph.instantNotifications.first())
            assertTrue(graph.setInstantNotificationsEnabled(false))
            assertFalse(graph.instantNotifications.first())
        } finally { lifecycle.currentState=prior }
    }
    @Test fun serviceHandoffKeepsOneSessionOwnerUntilNeitherModeIsActive() = runBlocking {
        val starts=kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val stops=kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val coordinator=ForegroundIdle(graph) {
            starts.send(Unit)
            try { awaitCancellation() } finally { stops.trySend(Unit) }
        }
        coordinator.start()
        coordinator.onStart(ProcessLifecycleOwner.get())
        withTimeout(5_000) { starts.receive() }
        coordinator.serviceRunning.value=true
        coordinator.onStop(ProcessLifecycleOwner.get())
        assertNull(withTimeoutOrNull(100) { stops.receive() })
        assertNull(withTimeoutOrNull(100) { starts.receive() })
        coordinator.serviceRunning.value=false
        withTimeout(5_000) { stops.receive() }
    }
    @Test fun idleTriggeredSyncBypassesPeriodicManualOnlySetting() = runBlocking {
        val account=db.mail().accounts().single().id
        settings.update(account) { it.copy(syncIntervalMinutes=0) }
        try {
            val periodic=androidx.work.testing.TestListenableWorkerBuilder<SyncWorker>(context).build()
            assertEquals(androidx.work.ListenableWorker.Result.success(),periodic.doWork())
            assertNull(db.mail().account(account)!!.syncError)
            val immediate=androidx.work.testing.TestListenableWorkerBuilder<SyncWorker>(context)
                .setInputData(androidx.work.workDataOf("scheduled" to false)).build()
            // The fixture server is intentionally unreachable. An immediate/IDLE job must attempt it anyway.
            assertEquals(androidx.work.ListenableWorker.Result.retry(),immediate.doWork())
            assertNotNull(db.mail().account(account)!!.syncError)
        } finally { settings.update(account) { it.copy(syncIntervalMinutes=15) } }
    }
    @Test fun manifestDeclaresUnexportedSpecialUseService() {
        val info=context.packageManager.getServiceInfo(ComponentName(context,InstantIdleService::class.java),0)
        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,info.foregroundServiceType)
    }
}
