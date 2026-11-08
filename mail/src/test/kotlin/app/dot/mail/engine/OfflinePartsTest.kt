package app.dot.mail.engine

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class OfflinePartsTest {
    /** Counts fixture traffic without logging commands, authentication or message data. */
    private class CounterProxy(private val target:Int):AutoCloseable {
        private val listener=ServerSocket(0)
        val port get()=listener.localPort
        val received=AtomicLong()
        private val sockets=CopyOnWriteArrayList<Socket>()
        private val accept=thread(isDaemon=true) {
            try { while(!listener.isClosed) {
                val client=listener.accept(); val server=Socket("127.0.0.1",target); sockets+=client; sockets+=server
                fun pipe(source:Socket,dest:Socket,count:Boolean)=thread(isDaemon=true) {
                    try { val buffer=ByteArray(8192); while(true) { val n=source.getInputStream().read(buffer); if(n<0) break; if(count) received.addAndGet(n.toLong()); dest.getOutputStream().write(buffer,0,n); dest.getOutputStream().flush() } } catch(_:Exception) {} finally { runCatching { dest.close() }; runCatching { source.close() } }
                }
                pipe(client,server,false); pipe(server,client,true)
            } } catch(_:Exception) {}
        }
        override fun close() { listener.close(); sockets.forEach { runCatching { it.close() } }; accept.join(1000) }
    }
    @Test fun bodyOnlyCacheDoesNotTransferLargeAttachmentAndExplicitReadDownloadsOnlyThatPart()=runBlocking<Unit> {
        val server=GreenMail(arrayOf(ServerSetup(0,"127.0.0.1","imap"),ServerSetup(0,"127.0.0.1","smtp")))
        server.start(); server.setUser("fixture@example.org","fixture","password")
        val directory=Files.createTempDirectory("offline-parts").toFile()
        try { CounterProxy(server.imap.port).use { proxy ->
            val endpoint=Endpoint("127.0.0.1",proxy.port,"fixture",TransportSecurity.NONE); val credentials=Credentials("password"); val client=MailClient()
            val attachment=ByteArray(2*1024*1024) { (it%127).toByte() }
            client.send(Endpoint("127.0.0.1",server.smtp.port,"fixture",TransportSecurity.NONE),credentials,Mime.build(OutgoingMail(Address("fixture@example.org"),listOf(Address("fixture@example.org")),subject="Fixture",text="Bonjour 🔥 日本語",html="<p>Bonjour 🔥 日本語</p>",attachments=listOf(OutgoingAttachment("large.bin","application/octet-stream",attachment)))))
            val initial=client.sync(endpoint,credentials,"INBOX")
            val uid=initial.headers.single().uid
            val before=proxy.received.get()
            val body=client.cacheBodyParts(endpoint,credentials,"INBOX",uid,java.io.File(directory,"body"),initial.cursor.uidValidity,false)
            assertEquals("Bonjour 🔥 日本語",body.text!!.readText())
            assertEquals(1,body.parts.size); assertTrue(body.attachments.isEmpty())
            assertTrue("Body-only download transferred the large attachment",proxy.received.get()-before<100_000)
            val part=client.cacheAttachment(endpoint,credentials,"INBOX",uid,body.parts.single().partId,java.io.File(directory,"part"),initial.cursor.uidValidity)
            assertEquals(attachment.size.toLong(),part.file.length())
            assertArrayEquals(attachment,part.file.readBytes())
            assertFalse(client.sync(endpoint,credentials,"INBOX").flags.single().seen)
            val failed=java.io.File(directory,"too-small")
            try { client.cacheBodyParts(endpoint,credentials,"INBOX",uid,failed,initial.cursor.uidValidity,true,1024); fail("Expected bounded cache failure") } catch(_:IllegalArgumentException) {}
            assertFalse(failed.exists())
        } } finally { server.stop(); directory.deleteRecursively() }
    }
    @Test fun selectedPartsPreservePgpVerificationBytesAndCancellationCleansStagedFiles()=runBlocking<Unit> {
        val server=GreenMail(ServerSetup(0,"127.0.0.1","imap")); server.start(); server.setUser("fixture@example.org","fixture","password")
        val directory=Files.createTempDirectory("offline-pgp").toFile()
        try {
            val raw="From: fixture@example.org\r\nSubject: Signed fixture\r\nMIME-Version: 1.0\r\nContent-Type: multipart/signed; protocol=\"application/pgp-signature\"; boundary=\"sign\"\r\n\r\n--sign\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\nSigned =E2=9C=93\r\n--sign\r\nContent-Type: application/pgp-signature\r\n\r\nfixture-signature\r\n--sign--\r\n"
            fun message()=jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()),raw.byteInputStream())
            val expected=java.io.File(directory,"expected"); Mime.cache(message(),expected)
            val client=MailClient(); val endpoint=Endpoint("127.0.0.1",server.imap.port,"fixture",TransportSecurity.NONE); val credentials=Credentials("password")
            client.append(endpoint,credentials,"INBOX",message())
            val header=client.sync(endpoint,credentials,"INBOX")
            val actual=java.io.File(directory,"actual")
            client.cacheBodyParts(endpoint,credentials,"INBOX",header.headers.single().uid,actual,header.cursor.uidValidity,false)
            assertArrayEquals(java.io.File(expected,Pgp.SIGNED_FILE).readBytes(),java.io.File(actual,Pgp.SIGNED_FILE).readBytes())
            assertArrayEquals(java.io.File(expected,Pgp.SIGNATURE_FILE).readBytes(),java.io.File(actual,Pgp.SIGNATURE_FILE).readBytes())
            assertTrue(java.io.File(actual,"plain.txt").readText().contains("Signed ✓"))
            val canceled=java.io.File(directory,"canceled"); var chunks=0
            try { Mime.cache(message(),canceled,checkCancelled={ if(++chunks>2) throw kotlinx.coroutines.CancellationException("fixture cancellation") }); fail("Cancellation ignored") } catch(_:kotlinx.coroutines.CancellationException) {}
            assertFalse(canceled.exists())
        } finally { server.stop(); directory.deleteRecursively() }
    }

    @Test fun boundedHeaderPagesResumePastExpungedUidWithoutSkippingOlderMessages()=runBlocking<Unit> {
        val server=GreenMail(ServerSetup(0,"127.0.0.1","imap")); server.start(); server.setUser("fixture@example.org","fixture","password")
        try {
            val endpoint=Endpoint("127.0.0.1",server.imap.port,"fixture",TransportSecurity.NONE); val credentials=Credentials("password"); val client=MailClient()
            repeat(9) { i -> client.append(endpoint,credentials,"INBOX",Mime.build(OutgoingMail(Address("fixture@example.org"),listOf(Address("fixture@example.org")),subject="Fixture $i",text="body",messageId="<$i@fixture.invalid>"))) }
            val first=client.headerPage(endpoint,credentials,"INBOX",limit=3)
            assertEquals(3,first.headers.size); assertTrue(first.hasMore)
            client.setFlags(endpoint,credentials,"INBOX",longArrayOf(first.nextBeforeUid),delete=true)
            val older=client.headerPage(endpoint,credentials,"INBOX",first.nextBeforeUid,3,first.validity)
            assertEquals(3,older.headers.size); assertTrue(older.headers.all { it.uid<first.nextBeforeUid })
            val last=client.headerPage(endpoint,credentials,"INBOX",older.nextBeforeUid,3,first.validity)
            assertEquals(3,last.headers.size); assertFalse(last.hasMore)
            assertEquals(9,(first.headers+older.headers+last.headers).map { it.uid }.distinct().size)
        } finally { server.stop() }
    }
}
