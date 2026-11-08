package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.MailDatabase
import app.dot.mail.data.model.*
import app.dot.mail.engine.MailClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Read-only on the real server. Only opaque role/count/phase timings are printed, never mail or credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LiveRefreshProfileTest {
    private fun configEndpoint(email:String)=app.dot.mail.engine.Endpoint("mail.infomaniak.com",993,email)
    @Test fun profileWarmRefresh() = runBlocking<Unit> {
        val file=Path.of(System.getProperty("user.home"),".config","dotmail","test-account.env")
        assumeTrue(System.getenv("DOTMAIL_LIVE")=="1" && Files.isRegularFile(file))
        val env=Files.readAllLines(file).filter { '=' in it && !it.trimStart().startsWith('#') }.associate { it.substringBefore('=').trim().removePrefix("export ") to it.substringAfter('=').trim().trim('"','\'') }
        val email=requireNotNull(env["DOTMAIL_TEST_EMAIL"]); val password=requireNotNull(env["DOTMAIL_TEST_PASSWORD"])
        val totals=ConcurrentHashMap<String,AtomicLong>(); val counts=ConcurrentHashMap<String,AtomicLong>()
        val client=MailClient(timing={ phase,ms -> totals.computeIfAbsent(phase) { AtomicLong() }.addAndGet(ms); counts.computeIfAbsent(phase) { AtomicLong() }.incrementAndGet() })
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,MailDatabase::class.java).allowMainThreadQueries().build()
        val graph=RealMailGraph(context,db,object:CredentialVault {
            override fun encrypt(accountEmail:String,secret:String)=secret
            override fun decrypt(accountEmail:String,encrypted:String)=encrypted
        },client=client,scheduling=false)
        try {
            graph.addAccount(AccountConfig(email,"Profile",ServerConfig("mail.infomaniak.com",993,Security.SSL_TLS,email),ServerConfig("mail.infomaniak.com",587,Security.STARTTLS,email)),password)
            withTimeout(240_000) { assertTrue("Initial read-only sync failed",graph.syncAll()) }
            totals.clear(); counts.clear()
            val start=System.nanoTime()
            withTimeout(120_000) { assertTrue("Warm read-only sync failed",graph.syncAll()) }
            println("PROFILE warm_total_ms=${(System.nanoTime()-start)/1_000_000}")
            totals.keys.sorted().forEach { println("PROFILE phase=$it ms=${totals[it]!!.get()} calls=${counts[it]!!.get()}") }
            println("PROFILE phases may overlap: connect_login wraps first connect/login; connect_tls_greeting and login include pooled protocol creation; body work is snippet_bodies only")
            totals.clear(); counts.clear()
            val manual=System.nanoTime()
            graph.refresh(Mailbox.Unified(FolderRole.INBOX))
            val inboxMillis=(System.nanoTime()-manual)/1_000_000
            graph.syncLock.withLock { }
            println("PROFILE manual_inbox_ms=$inboxMillis background_total_ms=${(System.nanoTime()-manual)/1_000_000}")
            val idle=graph.scope.launch { client.idle(configEndpoint(email),app.dot.mail.engine.Credentials(password)) { } }
            try {
                withTimeout(10_000) { while(counts["idle_ready"]?.get()!=1L) delay(10) }
                assertTrue(graph.syncAll()) // prime spare folder protocols on the IDLE-owned store
                totals.clear(); counts.clear()
                val shared=System.nanoTime()
                assertTrue(graph.syncAll())
                println("PROFILE idle_shared_total_ms=${(System.nanoTime()-shared)/1_000_000}")
                totals.keys.sorted().forEach { println("PROFILE idle_phase=$it ms=${totals[it]!!.get()} calls=${counts[it]!!.get()}") }
            } finally { idle.cancelAndJoin() }

        } finally { graph.scope.cancel(); db.close() }
    }
}
