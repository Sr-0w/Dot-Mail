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
/** Live prefix lookup in the Android address book for recipient autocomplete; nothing is cached or stored. */
internal fun interface DeviceContactSearcher { suspend fun search(prefix:String,limit:Int):List<app.dot.mail.data.model.EmailAddress> }
/** Private on-device cache. It never requests permission, sends addresses, or uses privileged permission listeners. */
internal class AddressBookIndex(private val graph:RealMailGraph) {
    internal var hasPermission:()->Boolean = { ContextCompat.checkSelfPermission(graph.context,Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED }
    internal var reader=DeviceContactReader { consume ->
        graph.context.contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),null,null,null)?.use { cursor ->
            while(cursor.moveToNext()) cursor.getString(0)?.let { consume(it) }
        }
    }
    internal var searcher=DeviceContactSearcher { prefix,limit ->
        val uri=ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI.buildUpon().appendPath(prefix).appendQueryParameter("limit",limit.toString()).build()
        val out=ArrayList<app.dot.mail.data.model.EmailAddress>()
        graph.context.contentResolver.query(uri,arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS,ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY),null,null,null)?.use { cursor ->
            while(cursor.moveToNext() && out.size<limit) {
                val email=canonicalContactEmail(cursor.getString(0) ?: continue) ?: continue
                out+=app.dot.mail.data.model.EmailAddress(email,cursor.getString(1)?.takeIf { it.isNotBlank() && !it.equals(email,true) })
            }
        }
        out
    }
    /**
     * Address-book matches for [prefix], only while READ_CONTACTS is already granted (this class never asks). Any failure of
     * the platform provider means "no address-book suggestions", never a failed autocomplete.
     */
    suspend fun suggest(prefix:String,limit:Int):List<app.dot.mail.data.model.EmailAddress> = withContext(Dispatchers.IO) {
        if(prefix.isBlank() || !hasPermission()) return@withContext emptyList()
        try { searcher.search(prefix.trim(),limit) } catch(e:CancellationException) { throw e } catch(_:Exception) { emptyList() }
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
