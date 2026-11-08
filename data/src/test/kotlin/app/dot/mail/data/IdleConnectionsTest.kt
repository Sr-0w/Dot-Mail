package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class IdleConnectionsTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private lateinit var settings: AccountSettingsStore
    private val config=AccountConfig("one@example.org","One",ServerConfig("localhost",1,Security.NONE,"one"),ServerConfig("localhost",1,Security.NONE,"one"))
    @Before fun setup() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        settings=AccountSettingsStore.get(context); settings.updateSync { SyncSettings() }
        db=Room.inMemoryDatabaseBuilder(context,MailDatabase::class.java).allowMainThreadQueries().build()
        graph=RealMailGraph(context,db,object: CredentialVault {
            override fun encrypt(accountEmail:String,secret:String)=secret
            override fun decrypt(accountEmail:String,encrypted:String)=encrypted
        },scheduling=false)
    }
    @After fun close() { settings.updateSync { SyncSettings() }; graph.scope.cancel(); db.close() }
    private suspend fun Channel<Long>.next(): Long = withTimeout(5_000) { receive() }
    @Test fun statusWritesDoNotChurnSocketsAndOnlyChangedAccountReconnects() = runBlocking {
        val a=graph.addAccount(config,"password")
        val b=graph.addAccount(config.copy(email="two@example.org"),"password")
        val accounts=MutableStateFlow(db.mail().accounts())
        val network=MutableStateFlow(IdleNetwork(1,false))
        val starts=Channel<Long>(Channel.UNLIMITED); val closes=Channel<Long>(Channel.UNLIMITED)
        val job=launch { IdleConnections(graph,network,accounts) { account,_ ->
            starts.send(account.id)
            try { awaitCancellation() } finally { closes.trySend(account.id) }
        }.run() }
        try {
            assertEquals(setOf(a,b),setOf(starts.next(),starts.next()))
            db.mail().syncError(a,"Temporary sync status",false); accounts.value=db.mail().accounts()
            assertNull(withTimeoutOrNull(100) { starts.receive() })
            db.mail().accountSecret(a,"changed"); accounts.value=db.mail().accounts()
            assertEquals(a,closes.next()); assertEquals(a,starts.next())
            assertNull(withTimeoutOrNull(100) { starts.receive() })
            network.value=IdleNetwork(null)
            assertEquals(setOf(a,b),setOf(closes.next(),closes.next()))
            network.value=IdleNetwork(2,false)
            assertEquals(setOf(a,b),setOf(starts.next(),starts.next()))
        } finally { job.cancelAndJoin() }
    }
    @Test fun unsupportedIdleDoesNotReconnectAndWifiOnlyPausesMeteredNetworks() = runBlocking {
        val account=graph.addAccount(config,"password")
        settings.updateSync { it.copy(wifiOnly=true) }
        val networks=MutableStateFlow(IdleNetwork(1,true))
        val starts=Channel<Long>(Channel.UNLIMITED)
        val job=launch { IdleConnections(graph,networks,MutableStateFlow(db.mail().accounts())) { row,_ -> starts.send(row.id); false }.run() }
        try {
            assertNull(withTimeoutOrNull(100) { starts.receive() })
            networks.value=IdleNetwork(2,false)
            assertEquals(account,starts.next())
            assertNull(withTimeoutOrNull(100) { starts.receive() })
            assertFalse(job.isCompleted)
        } finally { job.cancelAndJoin() }
    }
    @Test fun idleAuthenticationFailureMarksAccountForReauthorization() = runBlocking {
        val account=graph.addAccount(config,"password")
        val job=launch { IdleConnections(graph,MutableStateFlow(IdleNetwork(1,false)),MutableStateFlow(db.mail().accounts())) { _,_ ->
            throw jakarta.mail.AuthenticationFailedException("fixture")
        }.run() }
        try {
            withTimeout(5_000) { while(db.mail().account(account)?.authError!=true) delay(10) }
            assertEquals("Login failed. Sign in again.",db.mail().account(account)!!.syncError)
        } finally { job.cancelAndJoin() }
    }
    @Test fun reconnectBackoffIsBounded() {
        assertEquals(1_000L,IdleConnections.reconnectDelay(0))
        assertEquals(30_000L,IdleConnections.reconnectDelay(1))
        assertEquals(900_000L,IdleConnections.reconnectDelay(50))
    }
}
