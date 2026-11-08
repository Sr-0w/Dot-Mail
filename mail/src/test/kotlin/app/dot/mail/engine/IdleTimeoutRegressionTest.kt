package app.dot.mail.engine

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class IdleTimeoutRegressionTest {
    @Test
    fun idleSurvivesOrdinaryReadTimeoutAndStillReceivesMailWithoutReconnect() =
        runBlocking<Unit> {
            val server =
                GreenMail(
                    arrayOf(
                        ServerSetup(0, "127.0.0.1", "imap"),
                        ServerSetup(0, "127.0.0.1", "smtp"),
                    )
                )
            server.start()
            server.setUser("idle@example.org", "idle", "fixture-password")
            val ready = CompletableDeferred<Unit>()
            val changed = CompletableDeferred<Unit>()
            val logins = java.util.concurrent.atomic.AtomicInteger()
            val client =
                MailClient(1000) { phase, _ ->
                    if (phase == "idle_ready") ready.complete(Unit)
                    if (phase == "connect_login") logins.incrementAndGet()
                }
            val imap = Endpoint("127.0.0.1", server.imap.port, "idle", TransportSecurity.NONE)
            val smtp = Endpoint("127.0.0.1", server.smtp.port, "idle", TransportSecurity.NONE)
            val credentials = Credentials("fixture-password")
            val idle =
                launch(Dispatchers.IO) { client.idle(imap, credentials) { changed.complete(Unit) } }
            try {
                withTimeout(5000) { ready.await() }
                delay(
                    1500
                ) // Longer than mail.imap.timeout; no delivery wakes the socket during this period.
                assertFalse(
                    "IDLE must not be torn down by the ordinary command timeout",
                    idle.isCompleted,
                )
                client.send(
                    smtp,
                    credentials,
                    Mime.build(
                        OutgoingMail(
                            Address("idle@example.org"),
                            listOf(Address("idle@example.org")),
                            subject = "IDLE timeout fixture",
                            text = "fixture",
                        )
                    ),
                )
                withTimeout(3000) { changed.await() }
                assertEquals("IDLE should keep its original authenticated lease", 1, logins.get())
                withTimeout(3000) { idle.cancelAndJoin() }
            } finally {
                idle.cancelAndJoin()
                server.stop()
            }
        }
}
