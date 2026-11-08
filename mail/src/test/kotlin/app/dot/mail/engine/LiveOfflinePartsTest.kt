package app.dot.mail.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Opt-in READ ONLY. Prints aggregate sizes/timing only; no names, addresses, content, query or credentials. */
class LiveOfflinePartsTest {
    @Test fun infomaniakSelectedPartsCachePreservesUnreadState()=runBlocking<Unit> {
        val envFile=Path.of(System.getProperty("user.home"),".config","dotmail","test-account.env")
        assumeTrue(System.getenv("DOTMAIL_LIVE")=="1" && Files.isRegularFile(envFile))
        val env=Files.readAllLines(envFile).filter { '=' in it && !it.trimStart().startsWith('#') }.associate { it.substringBefore('=').trim().removePrefix("export ") to it.substringAfter('=').trim().trim('"','\'') }
        val email=requireNotNull(env["DOTMAIL_TEST_EMAIL"]); val password=requireNotNull(env["DOTMAIL_TEST_PASSWORD"])
        val directory=Files.createTempDirectory("dotmail-live-parts").toFile()
        try {
            val client=MailClient(); val endpoint=Endpoint("mail.infomaniak.com",993,email); val credentials=Credentials(password)
            withTimeout(60000) { client.withImapSession(endpoint,credentials) {
                val initial=client.sync(endpoint,credentials,"INBOX",limit=20)
                assumeTrue(initial.headers.isNotEmpty())
                val message=initial.headers.maxBy { it.uid }
                val started=System.nanoTime()
                val body=client.cacheBodyParts(endpoint,credentials,"INBOX",message.uid,directory,initial.cursor.uidValidity,false)
                val after=client.headers(endpoint,credentials,"INBOX",longArrayOf(message.uid),initial.cursor.uidValidity).single()
                assertEquals(message.flags.seen,after.flags.seen)
                assertTrue(body.text!=null || body.html!=null || directory.listFiles().orEmpty().isNotEmpty())
                println("OFFLINE_PROFILE elapsed_ms=${(System.nanoTime()-started)/1000000} text_bytes=${body.text?.length() ?: 0} html_bytes=${body.html?.length() ?: 0} part_metadata=${body.parts.size} cached_parts=${body.attachments.size} seen_preserved=true")
            } }
        } finally { directory.deleteRecursively() }
    }
}
