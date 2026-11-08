package app.dot.mail.data

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/** Reconcile only changed accounts; status writes and one account's reconnect do not restart its siblings. */
internal class IdleConnections(private val graph: RealMailGraph) {
    internal data class AccountKey(val id:Long,val config:String,val secret:String,val authError:Boolean)
    private fun network()=callbackFlow<Network?> {
        val manager=graph.context.getSystemService(ConnectivityManager::class.java)
        fun current():Network?=manager.activeNetwork?.takeIf { manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true }
        val callback=object:ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network:Network) { trySend(current()) }
            override fun onLost(network:Network) { trySend(current()) }
            override fun onCapabilitiesChanged(network:Network, capabilities:NetworkCapabilities) { trySend(current()) }
        }
        manager.registerDefaultNetworkCallback(callback); trySend(current())
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
    suspend fun run()=coroutineScope {
        val jobs=mutableMapOf<Long,Pair<AccountKey,Job>>()
        var activeNetwork:Network?=null
        try {
            combine(graph.dao.observeAccounts().map { rows -> rows.map { AccountKey(it.id,it.config,it.secret,it.authError) } }.distinctUntilChanged(),network()) { accounts,net -> accounts to net }
                .collect { (accounts,net) ->
                    if(net!=activeNetwork) { jobs.values.forEach { it.second.cancel() }; jobs.clear(); activeNetwork=net }
                    val eligible=accounts.filter { !it.authError && net!=null }.associateBy { it.id }
                    jobs.keys.toList().filter { id -> eligible[id]!=jobs[id]?.first }.forEach { id -> jobs.remove(id)?.second?.cancel() }
                    eligible.forEach { (id,key) -> if(id !in jobs) jobs[id]=key to launch {
                        var attempts=0
                        while(isActive) {
                            try {
                                val account=graph.dao.account(id) ?: break
                                val config=Codec.config(account.config)
                                val inbox=graph.dao.folders(id).firstOrNull { it.role=="INBOX" }?.path ?: "INBOX"
                                withTimeout(25L*60*1000) { graph.client.idle(config.imap.endpoint(),graph.credentials(account),inbox) { graph.requestSync() } }
                                attempts=0
                            } catch(_:TimeoutCancellationException) { attempts=0 }
                            catch(e:CancellationException) { throw e }
                            catch(e:app.dot.mail.data.oauth.ReauthenticationRequired) { awaitCancellation() }
                            catch(e:Exception) { SyncDiagnostics.warn("IDLE account=$id",e); attempts=(attempts+1).coerceAtMost(6) }
                            delay(if(attempts==0) 1000 else (15_000L shl attempts.coerceAtMost(6)).coerceAtMost(900_000))
                        }
                    } }
                }
        } finally { jobs.values.forEach { it.second.cancel() } }
    }
}
