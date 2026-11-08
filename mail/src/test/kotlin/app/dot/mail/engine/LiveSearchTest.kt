package app.dot.mail.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Read-only opt-in interop. Never prints query, address, subject, message content or credentials. */
class LiveSearchTest {
    @Test fun infomaniakHeaderSearchPreservesUidNamespaceAndSeen()=runBlocking<Unit> {
        val file=Path.of(System.getProperty("user.home"),".config","dotmail","test-account.env")
        assumeTrue(System.getenv("DOTMAIL_LIVE")=="1" && Files.isRegularFile(file))
        val env=Files.readAllLines(file).filter { '=' in it && !it.trimStart().startsWith('#') }.associate { it.substringBefore('=').trim().removePrefix("export ") to it.substringAfter('=').trim().trim('"','\'') }
        val email=requireNotNull(env["DOTMAIL_TEST_EMAIL"]); val password=requireNotNull(env["DOTMAIL_TEST_PASSWORD"])
        val client=MailClient(); val endpoint=Endpoint("mail.infomaniak.com",993,email); val credentials=Credentials(password)
        withTimeout(60000) { client.withImapSession(endpoint,credentials) {
            val before=client.sync(endpoint,credentials,"INBOX",limit=20,windowDays=30)
            assumeTrue(before.headers.isNotEmpty())
            val message=before.headers.maxBy { it.uid }
            val sender=message.from.firstOrNull()?.email
            assumeTrue(!sender.isNullOrBlank())
            val start=System.nanoTime()
            val batch=client.searchBatch(endpoint,credentials,"INBOX","from:$sender",limit=200)
            assertEquals(before.cursor.uidValidity,batch.uidValidity)
            assertTrue("Newest sender match missing",batch.headers.any { it.uid==message.uid })
            val after=client.headers(endpoint,credentials,"INBOX",longArrayOf(message.uid),before.cursor.uidValidity).single()
            assertEquals(message.flags.seen,after.flags.seen)
            println("SEARCH_PROFILE matches=${batch.headers.size} elapsed_ms=${(System.nanoTime()-start)/1000000} seen_preserved=true uid_namespace_preserved=true")
        } }
    }
}
