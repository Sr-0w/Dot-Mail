package app.dot.mail.data

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import app.dot.mail.data.local.*
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface DeviceContactReader { suspend fun read(consume:suspend (String)->Unit) }
/** Private on-device cache. It never requests permission, sends addresses, or uses privileged permission listeners. */
internal class AddressBookIndex(private val graph:RealMailGraph) {
    internal var hasPermission:()->Boolean = { ContextCompat.checkSelfPermission(graph.context,Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED }
    internal var reader=DeviceContactReader { consume ->
        graph.context.contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),null,null,null)?.use { cursor ->
            while(cursor.moveToNext()) cursor.getString(0)?.let { consume(it) }
        }
    }
    private val lock=Mutex(); private var loadedPermission:Boolean?=null
    private val changes=Channel<Boolean>(Channel.CONFLATED)
    @Volatile private var started=false
    private var observing=false
    private val observer by lazy { object:ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange:Boolean) { changes.trySend(true) } } }
    private fun observe(granted:Boolean) {
        if(!started) return
        if(granted && !observing) runCatching { graph.context.contentResolver.registerContentObserver(ContactsContract.CommonDataKinds.Email.CONTENT_URI,true,observer) }.onSuccess { observing=true }
        else if(!granted && observing) { runCatching { graph.context.contentResolver.unregisterContentObserver(observer) }; observing=false }
    }
    suspend fun refresh(force:Boolean=false)=withContext(Dispatchers.IO) { lock.withLock {
        val granted=hasPermission()
        observe(granted)
        if(!force && loadedPermission==granted) return@withLock
        if(!granted) {
            graph.database.withTransaction { graph.dao.deviceContactState(DeviceContactStateRow(generation=0,allowed=false)); graph.dao.clearDeviceContacts() }
            loadedPermission=false; return@withLock
        }
        val generation=System.nanoTime(); val batch=ArrayList<DeviceContactRow>(256)
        try {
            reader.read { raw ->
                currentCoroutineContext().ensureActive()
                canonicalContactEmail(raw)?.let { batch+=DeviceContactRow(it,generation) }
                if(batch.size>=256) { graph.dao.putDeviceContacts(batch.toList()); batch.clear() }
            }
            if(batch.isNotEmpty()) graph.dao.putDeviceContacts(batch)
            if(!hasPermission()) { graph.database.withTransaction { graph.dao.deviceContactState(DeviceContactStateRow(generation=0,allowed=false)); graph.dao.clearDeviceContacts() }; loadedPermission=false; return@withLock }
            graph.database.withTransaction { graph.dao.deviceContactState(DeviceContactStateRow(generation=generation,allowed=true)); graph.dao.pruneDeviceContacts(generation) }
            loadedPermission=true
        } catch(e:CancellationException) { throw e }
        catch(_:Exception) { graph.database.withTransaction { graph.dao.deviceContactState(DeviceContactStateRow(generation=0,allowed=false)); graph.dao.clearDeviceContacts() }; loadedPermission=null }
    } }
    @OptIn(FlowPreview::class)
    fun start() {
        if(started) return
        started=true
        val application=graph.context.applicationContext as? Application ?: return
        val callbacks=object:Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity:Activity) { changes.trySend(false) }
            override fun onActivityCreated(activity:Activity,state:Bundle?) {}
            override fun onActivityStarted(activity:Activity) {}
            override fun onActivityPaused(activity:Activity) {}
            override fun onActivityStopped(activity:Activity) {}
            override fun onActivitySaveInstanceState(activity:Activity,state:Bundle) {}
            override fun onActivityDestroyed(activity:Activity) {}
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        graph.scope.launch {
            try { changes.trySend(false); changes.receiveAsFlow().debounce(350).collect { force -> refresh(force=force) } }
            finally { application.unregisterActivityLifecycleCallbacks(callbacks); if(observing) runCatching { graph.context.contentResolver.unregisterContentObserver(observer) } }
        }
    }
}
