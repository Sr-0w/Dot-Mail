package app.dot.mail.engine

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SharedConnectionTest {
    @Test fun nestedScopesReuseStoreAndIdleCancellationDoesNotCloseBorrowers() = runBlocking<Unit> {
        val server=GreenMail(arrayOf(ServerSetup(0,"127.0.0.1","imap"),ServerSetup(0,"127.0.0.1","smtp")))
        server.start(); server.setUser("fixture@example.org","fixture","password")
        val connects=AtomicInteger(); val ready=CompletableDeferred<Unit>()
        val client=MailClient(1000) { phase,_ -> if(phase=="connect_login") connects.incrementAndGet(); if(phase=="idle_ready") ready.complete(Unit) }
        val endpoint=Endpoint("127.0.0.1",server.imap.port,"fixture",TransportSecurity.NONE)
        val creds=Credentials("password")
        val idle=launch(Dispatchers.IO) { client.idle(endpoint,creds) { } }
        try {
            withTimeout(5000) { ready.await() }
            client.withImapSession(endpoint,creds) {
                client.folders(endpoint,creds)
                client.withImapSession(endpoint,creds) { client.sync(endpoint,creds,"INBOX") }
                assertEquals(1,connects.get())
                withTimeout(3000) { idle.cancelAndJoin() }
                // The refreshing caller still owns its lease after IDLE releases its selected folder.
                assertNotNull(client.sync(endpoint,creds,"INBOX"))
                assertEquals(1,connects.get())
            }
            client.folders(endpoint,creds) // Last borrower closed the old store; a later call creates a fresh one.
            assertEquals(2,connects.get())
        } finally { idle.cancelAndJoin(); server.stop() }
    }
    @Test fun concurrentCallersShareOneStoreAndReleaseAfterFailure() = runBlocking<Unit> {
        val server=GreenMail(ServerSetup(0,"127.0.0.1","imap")); server.start(); server.setUser("fixture@example.org","fixture","password")
        val connects=AtomicInteger()
        val client=MailClient(1000) { phase,_ -> if(phase=="connect_login") connects.incrementAndGet() }
        val endpoint=Endpoint("127.0.0.1",server.imap.port,"fixture",TransportSecurity.NONE)
        try {
            client.withImapSession(endpoint,Credentials("password")) {
                coroutineScope { (1..3).map { async(Dispatchers.IO) { client.sync(endpoint,Credentials("password"),"INBOX") } }.awaitAll() }
                assertEquals(1,connects.get())
            }
        } finally { server.stop() }
    }
}
