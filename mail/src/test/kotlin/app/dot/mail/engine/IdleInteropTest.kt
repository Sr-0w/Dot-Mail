package app.dot.mail.engine

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class IdleInteropTest {
    @Test fun greenMailIdleReportsDeliveryWithoutMarkingSeenAndCancelsPromptly() = runBlocking<Unit> {
        val server=GreenMail(arrayOf(ServerSetup(0,"127.0.0.1","imap"),ServerSetup(0,"127.0.0.1","smtp")))
        server.start(); server.setUser("fixture@example.org","fixture","password")
        val client=MailClient(1_000)
        val imap=Endpoint("127.0.0.1",server.imap.port,"fixture",TransportSecurity.NONE)
        val smtp=Endpoint("127.0.0.1",server.smtp.port,"fixture",TransportSecurity.NONE)
        val credentials=Credentials("password")
        val changed=CompletableDeferred<Unit>()
        val idle=launch(Dispatchers.IO) { client.idle(imap,credentials) { changed.complete(Unit) } }
        try {
            // Delivery may race initial EXAMINE, so continue fixture deliveries until one reaches the active IDLE.
            withTimeout(5_000) {
                while(!changed.isCompleted) {
                    client.send(smtp,credentials,Mime.build(OutgoingMail(Address("fixture@example.org"),listOf(Address("fixture@example.org")),subject="IDLE fixture",text="fixture")))
                    withTimeoutOrNull(200) { changed.await() }
                }
            }
            assertFalse(idle.isCompleted)
            withTimeout(3_000) { idle.cancelAndJoin() }
            val headers=client.sync(imap,credentials,"INBOX")
            assertTrue(headers.headers.isNotEmpty())
            assertTrue(headers.flags.none { it.seen })
        } finally { idle.cancelAndJoin(); server.stop() }
    }
}
