package app.dot.mail.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.angus.mail.imap.IMAPStore
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A store multiplexes independent folder protocols. Leases prevent IDLE cancellation closing an active refresh. */
internal class ImapConnections(private val connect:suspend (Endpoint,Credentials)->IMAPStore) {
    private data class Key(val endpoint:Endpoint,val credentials:Credentials)
    private class Entry(var users:Int=1) {
        val ready=CompletableDeferred<IMAPStore>()
        var store:IMAPStore?=null
    }
    private val lock=Mutex()
    private val entries=mutableMapOf<Key,Entry>()

    suspend fun <T> lease(endpoint:Endpoint,credentials:Credentials,block:suspend (IMAPStore)->T):T {
        val key=Key(endpoint,credentials)
        var create=false
        val entry=lock.withLock { entries[key]?.also { it.users++ } ?: Entry().also { entries[key]=it; create=true } }
        try {
            if(create) withContext(NonCancellable+Dispatchers.IO) {
                try { connect(endpoint,credentials).also { entry.store=it; entry.ready.complete(it) } }
                catch(e:Exception) { entry.ready.completeExceptionally(e); lock.withLock { if(entries[key]===entry) entries.remove(key) } }
            }
            return block(entry.ready.await())
        } finally {
            withContext(NonCancellable+Dispatchers.IO) {
                val close=lock.withLock { entry.users--; if(entry.users==0) { if(entries[key]===entry) entries.remove(key); entry.store } else null }
                close?.let { runCatching { it.close() } }
            }
        }
    }
    suspend fun invalidate(store:IMAPStore) { lock.withLock { entries.entries.removeAll { it.value.store===store } } }
}

internal class ImapScope(val endpoint:Endpoint,val credentials:Credentials,val store:IMAPStore): AbstractCoroutineContextElement(Key) {
    @Volatile var invalidated=false
    companion object Key: CoroutineContext.Key<ImapScope>
}
