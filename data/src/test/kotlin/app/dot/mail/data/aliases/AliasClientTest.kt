package app.dot.mail.data.aliases

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class AliasClientTest {
    private fun serve(body: String, block: (String) -> Unit) {
        val server = ServerSocket(0)
        val received = AtomicReference("")
        val thread = Thread {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                val headers = buildString { while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; append(line).append('\n') } }
                received.set(headers)
                val bytes = body.toByteArray()
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
            }
        }.apply { start() }
        try { block("http://127.0.0.1:${server.localPort}"); thread.join() } finally { server.close() }
        lastHeaders = received.get()
    }
    private var lastHeaders = ""
    @Test fun simpleLoginUsesAuthenticationHeaderAndParsesAliases() {
        lateinit var aliases: List<EmailAlias>
        serve("""{"aliases":[{"id":"1","email":"x@example.net","enabled":true}]}""") { url -> aliases = SimpleLoginClient("$url/api").list("secret") }
        assertEquals("x@example.net", aliases.single().email); assert(lastHeaders.contains("Authentication: secret", true))
    }
    @Test fun addyUsesBearerAuthorization() {
        lateinit var aliases: List<EmailAlias>
        serve("""{"data":[{"id":"a","email":"a@b.test","active":true}]}""") { url -> aliases = AddyClient("$url/api/v1").list("token") }
        assertEquals("a@b.test", aliases.single().email); assert(lastHeaders.contains("Authorization: Bearer token", true))
    }
}
