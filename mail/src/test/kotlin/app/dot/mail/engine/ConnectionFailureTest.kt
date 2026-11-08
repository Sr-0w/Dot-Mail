package app.dot.mail.engine

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import javax.net.ssl.SSLException

class ConnectionFailureTest {
    @Test fun nestedAndJakartaChainsHaveDistinctSafeCategories() {
        val cases=listOf(UnknownHostException("private") to ConnectionFailure.DNS,SocketTimeoutException("private") to ConnectionFailure.TIMEOUT,SSLException("private") to ConnectionFailure.TLS,AuthenticationFailedException("private") to ConnectionFailure.AUTH,MessagingException("* BYE fixture") to ConnectionFailure.SERVER_CLOSED,MessagingException("NO too many connections") to ConnectionFailure.THROTTLED,ConnectException("private") to ConnectionFailure.NETWORK)
        cases.forEach { (error,kind) ->
            val wrapped=MessagingException("private").apply { setNextException(error) }
            assertEquals(kind,ConnectionFailure.classify(wrapped))
            assertFalse(kind.humanMessage.contains("private"))
        }
        assertEquals(ConnectionFailure.TLS,ConnectionFailure.classify(AuthenticationFailedException("private").apply { setNextException(SSLException("private")) }))
    }
    @Test fun readRetriesOnlyTransientFailuresAndNeverSwallowsCancellation() = runTest {
        var count=0
        assertEquals("ok",retryMailRead(backoffMillis=1) { if(++count==1) throw SocketTimeoutException("fixture"); "ok" })
        assertEquals(2,count)
        count=0
        try { retryMailRead(backoffMillis=1) { count++; throw AuthenticationFailedException("fixture") }; fail() } catch(_:AuthenticationFailedException) { }
        assertEquals(1,count)
        count=0
        try { retryMailRead(backoffMillis=1) { count++; throw CancellationException("fixture") }; fail() } catch(_:CancellationException) { }
        assertEquals(1,count)
    }
    @Test fun unchangedGuardRequiresEveryCheckpointIncludingCondstore() {
        val cursor=SyncCursor(7,42,100,8)
        assertTrue(MailClient.canSkipFolder(cursor,7,42,8,100))
        assertFalse(MailClient.canSkipFolder(cursor,8,42,8,100))
        assertFalse(MailClient.canSkipFolder(cursor,7,43,8,100))
        assertFalse(MailClient.canSkipFolder(cursor,7,42,7,100))
        assertFalse(MailClient.canSkipFolder(cursor,7,42,8,101))
        assertFalse(MailClient.canSkipFolder(cursor.copy(highestModSeq=-1),7,42,8,-1))
        assertFalse(MailClient.canSkipFolder(cursor.copy(messageCount=-1),7,42,8,100))
    }
}
