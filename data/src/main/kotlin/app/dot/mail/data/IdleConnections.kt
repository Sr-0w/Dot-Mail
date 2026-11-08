package app.dot.mail.data

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.dot.mail.data.local.AccountRow
import jakarta.mail.AuthenticationFailedException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

internal data class IdleNetwork(val id: Long?, val metered: Boolean = true)

/** Reconcile changed accounts only; status writes and one reconnect never restart its siblings. */
internal class IdleConnections(
    private val graph: RealMailGraph,
    private val networks: Flow<IdleNetwork> = idleNetworks(graph.context),
    private val accounts: Flow<List<AccountRow>> = graph.dao.observeAccounts(),
    private val session: suspend (AccountRow, () -> Unit) -> Boolean = { account, changed ->
        val config=Codec.config(account.config)
        val inbox=graph.dao.folders(account.id).firstOrNull { it.role=="INBOX" }?.path ?: "INBOX"
        graph.client.idle(config.imap.endpoint(),graph.credentials(account),inbox,changed)
    },
) {
    internal data class AccountKey(val id:Long,val config:String,val secret:String,val authError:Boolean)

    suspend fun run() = coroutineScope {
        val jobs=mutableMapOf<Long,Pair<AccountKey,Job>>()
        var activeNetwork: Long?=null
        try {
            combine(
                accounts.map { rows -> rows.map { AccountKey(it.id,it.config,it.secret,it.authError) } }.distinctUntilChanged(),
                networks.distinctUntilChanged(),
                AccountSettingsStore.get(graph.context).sync.map { it.wifiOnly }.distinctUntilChanged(),
            ) { accounts,network,wifiOnly -> Triple(accounts,network,wifiOnly) }.collect { (accounts,network,wifiOnly) ->
                val available=network.id?.takeUnless { wifiOnly && network.metered }
                if(available!=activeNetwork) {
                    jobs.values.forEach { it.second.cancel() }
                    jobs.values.forEach { it.second.join() }
                    jobs.clear(); activeNetwork=available
                }
                val eligible=accounts.filter { !it.authError && available!=null }.associateBy { it.id }
                jobs.keys.toList().filter { id -> eligible[id]!=jobs[id]?.first }.forEach { id -> jobs.remove(id)?.second?.cancelAndJoin() }
                eligible.forEach { (id,key) -> if(id !in jobs) jobs[id]=key to launch {
                    var attempts=0
                    while(isActive) {
                        try {
                            val account=graph.dao.account(id) ?: break
                            val supported=withTimeout(25L*60*1000) { session(account) { graph.requestSync() } }
                            if(!supported) awaitCancellation() // Unsupported IDLE: periodic sync remains available, no reconnect churn.
                            attempts=(attempts+1).coerceAtMost(6) // A socket ending unexpectedly backs off too.
                        } catch(_:TimeoutCancellationException) { attempts=0 } // Periodic IDLE renewal.
                        catch(e:CancellationException) { throw e }
                        catch(e:AuthenticationFailedException) {
                            graph.dao.syncError(id,"Login failed. Sign in again.",true)
                            SyncDiagnostics.warn("IDLE account=$id",e)
                            awaitCancellation()
                        }
                        catch(e:app.dot.mail.data.oauth.ReauthenticationRequired) {
                            graph.dao.syncError(id,"Sign in again to receive mail.",true)
                            awaitCancellation()
                        }
                        catch(e:Exception) {
                            SyncDiagnostics.warn("IDLE account=$id",e)
                            attempts=(attempts+1).coerceAtMost(6)
                        }
                        delay(reconnectDelay(attempts))
                    }
                } }
            }
        } finally {
            jobs.values.forEach { it.second.cancel() }
            withContext(NonCancellable) { jobs.values.forEach { it.second.join() } }
        }
    }

    companion object {
        internal fun reconnectDelay(attempts:Int): Long = if(attempts==0) 1_000 else (15_000L shl attempts.coerceIn(1,6)).coerceAtMost(900_000)
    }
}

internal fun idleNetworks(context: android.content.Context): Flow<IdleNetwork> = callbackFlow {
    val manager=context.getSystemService(ConnectivityManager::class.java)
    fun current(): IdleNetwork {
        val network=manager.activeNetwork ?: return IdleNetwork(null)
        val capabilities=manager.getNetworkCapabilities(network) ?: return IdleNetwork(null)
        if(!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return IdleNetwork(null)
        return IdleNetwork(network.networkHandle,!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }
    val callback=object: ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network:Network) { trySend(current()) }
        override fun onLost(network:Network) { trySend(if(manager.activeNetwork==network) IdleNetwork(null) else current()) }
        override fun onCapabilitiesChanged(network:Network,capabilities:NetworkCapabilities) { trySend(current()) }
    }
    manager.registerDefaultNetworkCallback(callback)
    trySend(current())
    awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
}.distinctUntilChanged()
