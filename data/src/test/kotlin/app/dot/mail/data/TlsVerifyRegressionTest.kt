package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.MailDatabase
import app.dot.mail.data.model.AccountConfig
import app.dot.mail.data.model.Security
import app.dot.mail.data.model.ServerConfig
import app.dot.mail.data.model.VerifyResult
import app.dot.mail.engine.MailClient
import com.google.common.truth.Truth.assertThat
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TlsVerifyRegressionTest {

    companion object {
        private const val SECRET = "SuperSecretPassword123!"
        private val SECRET_B64 = Base64.getEncoder().encodeToString(SECRET.toByteArray(StandardCharsets.UTF_8))
        private val AUTH_PLAIN_B64 = Base64.getEncoder().encodeToString("\u0000alice\u0000$SECRET".toByteArray(StandardCharsets.UTF_8))

        // PKCS#12 keystore (password "changeit") with self-signed EC cert for CN=localhost, SAN=DNS:localhost,IP:127.0.0.1
        private const val LOCALHOST_P12_B64 =
            "MIIEEAIBAzCCA7oGCSqGSIb3DQEHAaCCA6sEggOnMIIDozCCASoGCSqGSIb3DQEHAaCCARsEggEXMIIBEzCCAQ8GCyqGSIb3DQEMCgECoIG9MIG6MGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3DQEFDDArBBRI2HmMqbYKxiL4xb34THUsOd5GuAICJxACASAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEEDYFjCqgQQqW+aQ+xp0YnlwEUPJt87WbxXtbne8PKKwKxjBI1SL46VXggBHsSRPjuWJ8k+K3NI6eqWsrYHJX2NF1tDg5IINdH/gOe4Ky41JO9ZE51OHVcJQ42o5Nk1jXUmuqMUAwGwYJKoZIhvcNAQkUMQ4eDABzAGUAcgB2AGUAcjAhBgkqhkiG9w0BCRUxFAQSVGltZSAxNzk0MTEwMzIwOTE3MIICcQYJKoZIhvcNAQcGoIICYjCCAl4CAQAwggJXBgkqhkiG9w0BBwEwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFLDMZpt7kBZQI/B/3eF2URdGWXTZAgInEAIBIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQXahEI0liaX3gZy1emgqR/ICCAeAqvQRcHy0AcnEBXPaOjd8b0i6kaVfErZzcQDvsxOxaiSy0BSUNqPw6Yw8Y722A5qaGf4nGiV4aarZii7a3BJLAhPzqnM7BKh9uXUskOCb0VwunUf1H5SBMX7OVyPtXJK7uVildhzdHm/Vqoz/at4OYNgiwLmFhweWMXmlTA0dD2e44uO4IZelXEEMF2Kes+No9NB0cUQhwMLxWFSv+HVXBhsCwi1Ue6C/NuEEfd1qqzHHg4Frz26Oqq2fGDTM0oMZV6ZvrY3Wrc4Y1HWPdKhZ9NMwrhFejhbsDKiaLXrfj7eLGnNsG/5LAtW8zZN6ivP00O7UF/fhslkDvZVc/oGzUbhhNhr6RQ29XMe+zP7rrMJjq5hImNSCMMlAcyTcieDXmZaM31rjfuxiCDSUixYtJNhVKu5blv1f5Q0mi9//DGvz45aY8mVLzCuOntdfnebeaXPKdm+thTllOzKooOtSdhekjbqFpsvMw6pIT44skihap5buw7c52z4UW355qHlXjHsTlrcUsFjoNie2Wt1+wLAPR9a+5Upko4juwtfl6bFLDD5xj8gm5P+QH0xIN1lFPtmU17Cw0ztu4E/u+PZfaKu8gY2Y40zZmN7Z5ChfUiNx5fGSYuZmOTqiEowis//4wTTAxMA0GCWCGSAFlAwQCAQUABCDux7EuHzsHBMRig2Pv6hirDPH3FNpsN8zNUsdh+2ftVQQU+ZL8+9BKu+Zlgx2rv6vd9nRKiu0CAicQ"

        // PKCS#12 keystore (password "changeit") with self-signed EC cert for CN=wrong.example.test, SAN=DNS:wrong.example.test
        private const val WRONGHOST_P12_B64 =
            "MIIEIAIBAzCCA8oGCSqGSIb3DQEHAaCCA7sEggO3MIIDszCCASoGCSqGSIb3DQEHAaCCARsEggEXMIIBEzCCAQ8GCyqGSIb3DQEMCgECoIG9MIG6MGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3DQEFDDArBBSNpKyDo+mYqSBKC5x4KAGa/CJzsAICJxACASAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEEPSDJ9EZT1eZ1p4tXcK0gLcEUAWmIGytMlVLQgYWEResPXKvA+nStgHqY9GZjNXlOwpjzmqR5/qEDugHPeghe302BiYjpwJ8Gy3XbTcmN3cWTnSnFSmw32bb7kLkOmS5LV0VMUAwGwYJKoZIhvcNAQkUMQ4eDABzAGUAcgB2AGUAcjAhBgkqhkiG9w0BCRUxFAQSVGltZSAxNzk0MTEwMzIyMzgwMIICgQYJKoZIhvcNAQcGoIICcjCCAm4CAQAwggJnBgkqhkiG9w0BBwEwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFKMn1u6aAtCOJn7NMQPhMSKqkMEBAgInEAIBIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQiHQ6htr7q+YX0p8jS0gD5oCCAfDK6Am0SO+WCqFe5nzs/PIkgeKVlhFyU8NxGFNtzOe9idoqDwA7YrD26jNAoWuulnfbAs58u1v8WrM7vJSyG8DMoCmmrsJKt+wbWC7xU6d6Y2fonAFScgG4s9Pfp24/84gDALV1h5B2LjbJI8IddOeJDswB+101MrOeXFEqIpoWJDQgEVTVlD/uJg/umuBHLxjmmF9PbEFYJzTKig8S2dRsKTcTaiWiwbK4RpKZiQjPH85fgbqxikpJEhZEcJcBqRWo39ZA5d46Hwthqk4LroXiLJ01anWoi+OSLErwkpfXZwxoFj6MNF546R/fo+p3VdKKDNQZwf677HdbDS5L3BTtb6W2OJ3ctIduy24Z1pGIPQzeavdAhVAu/avR+ze2XA2sz++tXTIqhhpg/omwkgLDHzSHiWfUmYPTuKlek85GZrXItT5hHwavTRdbhfEKNtxvMj3q4pbQHAxkPoK0YSO4BDZO20nXqqTYqOCpP11iQbDpkKWTAncCBhfMLsjyk+4YBRrEHUz4SZAdBlwNbPijjDS7sQc2i7KpebARKFCz/PDIHye/UKNhwBhE6HaIByNqQDOg7lgsiYjl0xEfS7SqJChKh/jGmo1bRRESGGQSl6UkaXTFP6PggTV2xGxMaQniUUYWTZRaPoa6VhfkJoPkME0wMTANBglghkgBZQMEAgEFAAQgN20Lah65m0eRiki+UsFZ9/acNo417XbV6gB0zqi1ScMEFHil9mQQT8WQZw79jM5K8quXlviqAgInEA=="

        private fun sslContextFromP12(base64P12: String): SSLContext {
            val ks = KeyStore.getInstance("PKCS12")
            val pass = "changeit".toCharArray()
            ks.load(ByteArrayInputStream(Base64.getDecoder().decode(base64P12)), pass)
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, tmf.trustManagers, null) }
        }
    }

    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        graph = RealMailGraph(
            context = context,
            database = db,
            vault = object : CredentialVault {
                override fun encrypt(accountEmail: String, secret: String) = "enc:$secret"
                override fun decrypt(accountEmail: String, encrypted: String) = encrypted.removePrefix("enc:")
            },
            client = MailClient(timeoutMs = 2_000),
            scheduling = false,
        )
    }

    @After
    fun tearDown() {
        graph.scope.cancel()
        db.close()
    }

    private class FakeTcpServer(
        private val serverSocket: ServerSocket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")),
        private val handler: (Socket, BufferedReader, PrintWriter, MutableList<String>) -> Unit,
    ) : AutoCloseable {
        val port: Int get() = serverSocket.localPort
        val receivedLines: MutableList<String> = CopyOnWriteArrayList()

        private val worker = thread(start = true, isDaemon = true) {
            try {
                serverSocket.use { ss ->
                    while (!ss.isClosed) {
                        val socket = try {
                            ss.accept()
                        } catch (_: Exception) {
                            break
                        }
                        socket.use { s ->
                            s.soTimeout = 3_000
                            val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                            val writer = PrintWriter(OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)
                            handler(s, reader, writer, receivedLines)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        fun assertNoCredentialsSent() {
            val all = receivedLines.joinToString("\n")
            assertFalse("Password sent in cleartext: $all", all.contains(SECRET))
            assertFalse("Base64 password sent: $all", all.contains(SECRET_B64))
            assertFalse("SASL PLAIN credentials sent: $all", all.contains(AUTH_PLAIN_B64))
            assertFalse("IMAP LOGIN sent: $all", receivedLines.any { it.contains(" LOGIN ", ignoreCase = true) })
            assertFalse("AUTHENTICATE sent: $all", receivedLines.any { it.contains("AUTHENTICATE", ignoreCase = true) })
            assertFalse("SMTP AUTH sent: $all", receivedLines.any { it.startsWith("AUTH ", ignoreCase = true) })
        }

        override fun close() {
            runCatching { serverSocket.close() }
            worker.join(1_000)
        }
    }

    @Test
    fun `verify returns CertificateProblem on self-signed certificate and never sends credentials`() = runBlocking {
        val ctx = sslContextFromP12(LOCALHOST_P12_B64)
        val sslServer = ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        FakeTcpServer(sslServer) { socket, reader, writer, received ->
            (socket as SSLSocket).startHandshake()
            writer.print("* OK [CAPABILITY IMAP4rev1 AUTH=PLAIN] ready\r\n")
            writer.flush()
            while (true) {
                val line = reader.readLine() ?: break
                received += line
            }
        }.use { server ->
            val cfg = AccountConfig(
                email = "alice@example.org",
                displayName = "Alice",
                imap = ServerConfig("127.0.0.1", server.port, Security.SSL_TLS, "alice"),
                smtp = ServerConfig("127.0.0.1", 1, Security.SSL_TLS, "alice"),
            )
            val result = graph.verify(cfg, SECRET)
            assertThat(result).isInstanceOf(VerifyResult.CertificateProblem::class.java)
            server.assertNoCredentialsSent()
        }
    }

    @Test
    fun `verify returns CertificateProblem on wrong-hostname certificate even when cert is trusted`() = runBlocking {
        val previousDefault = SSLContext.getDefault()
        val trustedWrongHostCtx = sslContextFromP12(WRONGHOST_P12_B64)
        SSLContext.setDefault(trustedWrongHostCtx)
        try {
            val sslServer = trustedWrongHostCtx.serverSocketFactory
                .createServerSocket(0, 5, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            FakeTcpServer(sslServer) { socket, reader, writer, received ->
                (socket as SSLSocket).startHandshake()
                writer.print("* OK [CAPABILITY IMAP4rev1 AUTH=PLAIN] ready\r\n")
                writer.flush()
                while (true) {
                    val line = reader.readLine() ?: break
                    received += line
                }
            }.use { server ->
                val cfg = AccountConfig(
                    email = "alice@example.org",
                    displayName = "Alice",
                    imap = ServerConfig("127.0.0.1", server.port, Security.SSL_TLS, "alice"),
                    smtp = ServerConfig("127.0.0.1", 1, Security.SSL_TLS, "alice"),
                )
                val result = graph.verify(cfg, SECRET)
                assertThat(result).isInstanceOf(VerifyResult.CertificateProblem::class.java)
                server.assertNoCredentialsSent()
            }
        } finally {
            SSLContext.setDefault(previousDefault)
        }
    }

    @Test
    fun `verify fails when STARTTLS is omitted or rejected and never sends credentials`() = runBlocking {
        FakeTcpServer { _, reader, writer, received ->
            writer.print("* OK [CAPABILITY IMAP4rev1 AUTH=PLAIN] PlainOnly ready\r\n")
            writer.flush()
            while (true) {
                val line = reader.readLine() ?: break
                received += line
                val tag = line.substringBefore(' ')
                val cmd = line.substringAfter(' ').uppercase()
                if (cmd.startsWith("CAPABILITY")) {
                    writer.print("* CAPABILITY IMAP4rev1 AUTH=PLAIN\r\n$tag OK CAPABILITY\r\n")
                    writer.flush()
                } else {
                    writer.print("$tag BAD Not allowed\r\n")
                    writer.flush()
                    break
                }
            }
        }.use { server ->
            val cfg = AccountConfig(
                email = "alice@example.org",
                displayName = "Alice",
                imap = ServerConfig("127.0.0.1", server.port, Security.STARTTLS, "alice"),
                smtp = ServerConfig("127.0.0.1", 1, Security.STARTTLS, "alice"),
            )
            val result = graph.verify(cfg, SECRET)
            assertThat(result).isInstanceOf(VerifyResult.ConnectionFailed::class.java)
            server.assertNoCredentialsSent()
        }
    }
}
