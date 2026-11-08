package app.dot.mail.engine

import app.dot.mail.engine.autoconfig.AutoDiscovery
import app.dot.mail.engine.autoconfig.AutoconfigXml
import app.dot.mail.engine.autoconfig.ConfigFetcher
import app.dot.mail.engine.autoconfig.SocketType
import app.dot.mail.engine.autoconfig.Source
import com.google.common.truth.Truth.assertThat
import jakarta.mail.MessagingException
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
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TlsSecurityTest {

    companion object {
        private const val SECRET = "SuperSecretPassword123!"
        private val SECRET_B64 = Base64.getEncoder().encodeToString(SECRET.toByteArray(StandardCharsets.UTF_8))
        private val AUTH_PLAIN_B64 = Base64.getEncoder().encodeToString("\u0000alice\u0000$SECRET".toByteArray(StandardCharsets.UTF_8))

        // PKCS#12 keystore (password "changeit") with self-signed EC cert for CN=localhost, SAN=DNS:localhost,IP:127.0.0.1
        internal const val LOCALHOST_P12_B64 =
            "MIIEEAIBAzCCA7oGCSqGSIb3DQEHAaCCA6sEggOnMIIDozCCASoGCSqGSIb3DQEHAaCCARsEggEXMIIBEzCCAQ8GCyqGSIb3DQEMCgECoIG9MIG6MGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3DQEFDDArBBRI2HmMqbYKxiL4xb34THUsOd5GuAICJxACASAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEEDYFjCqgQQqW+aQ+xp0YnlwEUPJt87WbxXtbne8PKKwKxjBI1SL46VXggBHsSRPjuWJ8k+K3NI6eqWsrYHJX2NF1tDg5IINdH/gOe4Ky41JO9ZE51OHVcJQ42o5Nk1jXUmuqMUAwGwYJKoZIhvcNAQkUMQ4eDABzAGUAcgB2AGUAcjAhBgkqhkiG9w0BCRUxFAQSVGltZSAxNzk0MTEwMzIwOTE3MIICcQYJKoZIhvcNAQcGoIICYjCCAl4CAQAwggJXBgkqhkiG9w0BBwEwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFLDMZpt7kBZQI/B/3eF2URdGWXTZAgInEAIBIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQXahEI0liaX3gZy1emgqR/ICCAeAqvQRcHy0AcnEBXPaOjd8b0i6kaVfErZzcQDvsxOxaiSy0BSUNqPw6Yw8Y722A5qaGf4nGiV4aarZii7a3BJLAhPzqnM7BKh9uXUskOCb0VwunUf1H5SBMX7OVyPtXJK7uVildhzdHm/Vqoz/at4OYNgiwLmFhweWMXmlTA0dD2e44uO4IZelXEEMF2Kes+No9NB0cUQhwMLxWFSv+HVXBhsCwi1Ue6C/NuEEfd1qqzHHg4Frz26Oqq2fGDTM0oMZV6ZvrY3Wrc4Y1HWPdKhZ9NMwrhFejhbsDKiaLXrfj7eLGnNsG/5LAtW8zZN6ivP00O7UF/fhslkDvZVc/oGzUbhhNhr6RQ29XMe+zP7rrMJjq5hImNSCMMlAcyTcieDXmZaM31rjfuxiCDSUixYtJNhVKu5blv1f5Q0mi9//DGvz45aY8mVLzCuOntdfnebeaXPKdm+thTllOzKooOtSdhekjbqFpsvMw6pIT44skihap5buw7c52z4UW355qHlXjHsTlrcUsFjoNie2Wt1+wLAPR9a+5Upko4juwtfl6bFLDD5xj8gm5P+QH0xIN1lFPtmU17Cw0ztu4E/u+PZfaKu8gY2Y40zZmN7Z5ChfUiNx5fGSYuZmOTqiEowis//4wTTAxMA0GCWCGSAFlAwQCAQUABCDux7EuHzsHBMRig2Pv6hirDPH3FNpsN8zNUsdh+2ftVQQU+ZL8+9BKu+Zlgx2rv6vd9nRKiu0CAicQ"

        // PKCS#12 keystore (password "changeit") with self-signed EC cert for CN=wrong.example.test, SAN=DNS:wrong.example.test
        internal const val WRONGHOST_P12_B64 =
            "MIIEIAIBAzCCA8oGCSqGSIb3DQEHAaCCA7sEggO3MIIDszCCASoGCSqGSIb3DQEHAaCCARsEggEXMIIBEzCCAQ8GCyqGSIb3DQEMCgECoIG9MIG6MGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3DQEFDDArBBSNpKyDo+mYqSBKC5x4KAGa/CJzsAICJxACASAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEEPSDJ9EZT1eZ1p4tXcK0gLcEUAWmIGytMlVLQgYWEResPXKvA+nStgHqY9GZjNXlOwpjzmqR5/qEDugHPeghe302BiYjpwJ8Gy3XbTcmN3cWTnSnFSmw32bb7kLkOmS5LV0VMUAwGwYJKoZIhvcNAQkUMQ4eDABzAGUAcgB2AGUAcjAhBgkqhkiG9w0BCRUxFAQSVGltZSAxNzk0MTEwMzIyMzgwMIICgQYJKoZIhvcNAQcGoIICcjCCAm4CAQAwggJnBgkqhkiG9w0BBwEwZgYJKoZIhvcNAQUNMFkwOAYJKoZIhvcNAQUMMCsEFKMn1u6aAtCOJn7NMQPhMSKqkMEBAgInEAIBIDAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQiHQ6htr7q+YX0p8jS0gD5oCCAfDK6Am0SO+WCqFe5nzs/PIkgeKVlhFyU8NxGFNtzOe9idoqDwA7YrD26jNAoWuulnfbAs58u1v8WrM7vJSyG8DMoCmmrsJKt+wbWC7xU6d6Y2fonAFScgG4s9Pfp24/84gDALV1h5B2LjbJI8IddOeJDswB+101MrOeXFEqIpoWJDQgEVTVlD/uJg/umuBHLxjmmF9PbEFYJzTKig8S2dRsKTcTaiWiwbK4RpKZiQjPH85fgbqxikpJEhZEcJcBqRWo39ZA5d46Hwthqk4LroXiLJ01anWoi+OSLErwkpfXZwxoFj6MNF546R/fo+p3VdKKDNQZwf677HdbDS5L3BTtb6W2OJ3ctIduy24Z1pGIPQzeavdAhVAu/avR+ze2XA2sz++tXTIqhhpg/omwkgLDHzSHiWfUmYPTuKlek85GZrXItT5hHwavTRdbhfEKNtxvMj3q4pbQHAxkPoK0YSO4BDZO20nXqqTYqOCpP11iQbDpkKWTAncCBhfMLsjyk+4YBRrEHUz4SZAdBlwNbPijjDS7sQc2i7KpebARKFCz/PDIHye/UKNhwBhE6HaIByNqQDOg7lgsiYjl0xEfS7SqJChKh/jGmo1bRRESGGQSl6UkaXTFP6PggTV2xGxMaQniUUYWTZRaPoa6VhfkJoPkME0wMTANBglghkgBZQMEAgEFAAQgN20Lah65m0eRiki+UsFZ9/acNo417XbV6gB0zqi1ScMEFHil9mQQT8WQZw79jM5K8quXlviqAgInEA=="

        fun sslContextFromP12(base64P12: String): SSLContext {
            val ks = KeyStore.getInstance("PKCS12")
            val pass = "changeit".toCharArray()
            ks.load(ByteArrayInputStream(Base64.getDecoder().decode(base64P12)), pass)
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, tmf.trustManagers, null) }
        }
    }

    private class FakeTcpServer(
        private val serverSocket: ServerSocket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")),
        private val handler: (Socket, BufferedReader, PrintWriter, MutableList<String>) -> Unit,
    ) : AutoCloseable {
        val port: Int get() = serverSocket.localPort
        val receivedLines: MutableList<String> = CopyOnWriteArrayList()

        private val worker = thread(start = true, isDaemon = true, name = "fake-mail-server-$port") {
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
            assertFalse("IMAP LOGIN command sent before TLS: $all", receivedLines.any { it.contains(" LOGIN ", ignoreCase = true) })
            assertFalse("AUTHENTICATE command sent before TLS: $all", receivedLines.any { it.contains("AUTHENTICATE", ignoreCase = true) })
            assertFalse("SMTP AUTH command sent before TLS: $all", receivedLines.any { it.startsWith("AUTH ", ignoreCase = true) })
        }

        override fun close() {
            runCatching { serverSocket.close() }
            worker.join(1_000)
        }
    }

    private fun acceptingImapServer(): FakeTcpServer = FakeTcpServer { _, reader, writer, received ->
        writer.print("* OK [CAPABILITY IMAP4rev1 AUTH=PLAIN] FakeIMAP ready\r\n")
        writer.flush()
        while (true) {
            val line = reader.readLine() ?: break
            received += line
            val tag = line.substringBefore(' ')
            val cmd = line.substringAfter(' ').uppercase()
            when {
                cmd.startsWith("CAPABILITY") -> {
                    writer.print("* CAPABILITY IMAP4rev1 AUTH=PLAIN\r\n$tag OK CAPABILITY completed\r\n")
                    writer.flush()
                }
                cmd.startsWith("LOGIN") || cmd.startsWith("AUTHENTICATE") -> {
                    writer.print("$tag OK Logged in\r\n")
                    writer.flush()
                }
                cmd.startsWith("LOGOUT") -> {
                    writer.print("* BYE logging out\r\n$tag OK LOGOUT completed\r\n")
                    writer.flush()
                    break
                }
                else -> {
                    writer.print("$tag OK Done\r\n")
                    writer.flush()
                }
            }
        }
    }

    // --------------------------------------------------------- Autoconfig / Discovery Security

    @Test
    fun `discovery rejects plain socket when encrypted alternative exists in xml`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <clientInfo>
              <emailProvider id="mixed.example">
                <displayName>Mixed Provider</displayName>
                <incomingServer type="imap">
                  <hostname>imap-plain.mixed.example</hostname>
                  <port>143</port>
                  <socketType>plain</socketType>
                  <username>%EMAILADDRESS%</username>
                </incomingServer>
                <incomingServer type="pop3">
                  <hostname>pop-tls.mixed.example</hostname>
                  <port>995</port>
                  <socketType>SSL</socketType>
                  <username>%EMAILADDRESS%</username>
                </incomingServer>
                <outgoingServer type="smtp">
                  <hostname>smtp-plain.mixed.example</hostname>
                  <port>25</port>
                  <socketType>plain</socketType>
                  <username>%EMAILADDRESS%</username>
                </outgoingServer>
                <outgoingServer type="smtp">
                  <hostname>smtp-starttls.mixed.example</hostname>
                  <port>587</port>
                  <socketType>STARTTLS</socketType>
                  <username>%EMAILADDRESS%</username>
                </outgoingServer>
              </emailProvider>
            </clientInfo>
        """.trimIndent()

        val parsed = AutoconfigXml.parse(xml, "alice@mixed.example", "alice", "mixed.example")!!
        assertThat(parsed.imap.host).isEqualTo("pop-tls.mixed.example")
        assertThat(parsed.imap.socket).isEqualTo(SocketType.SSL)
        assertThat(parsed.smtp.host).isEqualTo("smtp-starttls.mixed.example")
        assertThat(parsed.smtp.socket).isEqualTo(SocketType.STARTTLS)
        assertThat(parsed.insecure).isFalse()
    }

    @Test
    fun `discovery rejects opportunistic starttls in favor of required tls`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <clientInfo>
              <emailProvider id="opp.example">
                <incomingServer type="imap">
                  <hostname>imap-opp.opp.example</hostname>
                  <port>143</port>
                  <socketType>STARTTLS</socketType>
                  <requireTls>false</requireTls>
                  <username>%EMAILADDRESS%</username>
                </incomingServer>
                <incomingServer type="imap">
                  <hostname>imap-strict.opp.example</hostname>
                  <port>143</port>
                  <socketType>STARTTLS</socketType>
                  <requireTls>true</requireTls>
                  <username>%EMAILADDRESS%</username>
                </incomingServer>
                <outgoingServer type="smtp">
                  <hostname>smtp.opp.example</hostname>
                  <port>465</port>
                  <socketType>SSL</socketType>
                  <username>%EMAILADDRESS%</username>
                </outgoingServer>
              </emailProvider>
            </clientInfo>
        """.trimIndent()

        val parsed = AutoconfigXml.parse(xml, "alice@opp.example", "alice", "opp.example")!!
        assertThat(parsed.imap.host).isEqualTo("imap-strict.opp.example")
        assertThat(parsed.imap.socket).isEqualTo(SocketType.STARTTLS)
        assertThat(parsed.insecure).isFalse()
    }

    @Test
    fun `plain-only provider autoconfig yields to secure ispdb and marks insecure when nothing else exists`() = runTest {
        val plainOnlyXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <clientInfo>
              <emailProvider id="legacy.example">
                <displayName>Legacy Plain</displayName>
                <incomingServer type="imap">
                  <hostname>imap.legacy.example</hostname>
                  <port>143</port>
                  <socketType>plain</socketType>
                  <username>%EMAILADDRESS%</username>
                </incomingServer>
                <outgoingServer type="smtp">
                  <hostname>smtp.legacy.example</hostname>
                  <port>25</port>
                  <socketType>plain</socketType>
                  <username>%EMAILADDRESS%</username>
                </outgoingServer>
              </emailProvider>
            </clientInfo>
        """.trimIndent()

        val secureIspdbXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <clientInfo>
              <emailProvider id="legacy.example">
                <displayName>Legacy TLS</displayName>
                <incomingServer type="imap">
                  <hostname>imap.legacy.example</hostname>
                  <port>993</port>
                  <socketType>SSL</socketType>
                  <username>%EMAILADDRESS%</username>
                </incomingServer>
                <outgoingServer type="smtp">
                  <hostname>smtp.legacy.example</hostname>
                  <port>465</port>
                  <socketType>SSL</socketType>
                  <username>%EMAILADDRESS%</username>
                </outgoingServer>
              </emailProvider>
            </clientInfo>
        """.trimIndent()

        // 1. When ISPDB has TLS, plain-only autoconfig is skipped in favor of TLS.
        val withSecureBackup = AutoDiscovery(
            fetcher = ConfigFetcher { url ->
                when (url) {
                    "https://autoconfig.legacy.example/mail/config-v1.1.xml" -> plainOnlyXml
                    "https://autoconfig.thunderbird.net/v1.1/legacy.example" -> secureIspdbXml
                    else -> null
                }
            }
        ).discover("alice@legacy.example")!!
        assertThat(withSecureBackup.source).isEqualTo(Source.ISPDB)
        assertThat(withSecureBackup.imap.socket).isEqualTo(SocketType.SSL)
        assertThat(withSecureBackup.smtp.socket).isEqualTo(SocketType.SSL)
        assertThat(withSecureBackup.insecure).isFalse()

        // 2. When nothing else exists, plain config is returned ONLY with insecure = true.
        val onlyPlain = AutoDiscovery(
            fetcher = ConfigFetcher { url ->
                if (url == "https://autoconfig.legacy.example/mail/config-v1.1.xml") plainOnlyXml else null
            }
        ).discover("alice@legacy.example")!!
        assertThat(onlyPlain.insecure).isTrue()
        assertThat(onlyPlain.imap.socket).isEqualTo(SocketType.PLAIN)
        assertThat(onlyPlain.smtp.socket).isEqualTo(SocketType.PLAIN)
        assertThat(onlyPlain.hint).isNotNull()

        // 3. Guessed servers are always TLS-only and never insecure.
        val guessed = AutoDiscovery(fetcher = ConfigFetcher { null }).discover("alice@unknown.example")!!
        assertThat(guessed.source).isEqualTo(Source.GUESS)
        assertThat(guessed.imap.socket).isEqualTo(SocketType.SSL)
        assertThat(guessed.smtp.socket).isEqualTo(SocketType.SSL)
        assertThat(guessed.insecure).isFalse()
    }

    // --------------------------------------------------------- STARTTLS Downgrade / Strip

    @Test
    fun `imap omitting STARTTLS from CAPABILITY fails verify without sending credentials`() = runBlocking {
        FakeTcpServer { _, reader, writer, received ->
            writer.print("* OK [CAPABILITY IMAP4rev1 AUTH=PLAIN] NoTLS ready\r\n")
            writer.flush()
            while (true) {
                val line = reader.readLine() ?: break
                received += line
                val tag = line.substringBefore(' ')
                val cmd = line.substringAfter(' ').uppercase()
                when {
                    cmd.startsWith("CAPABILITY") -> {
                        writer.print("* CAPABILITY IMAP4rev1 AUTH=PLAIN\r\n$tag OK CAPABILITY completed\r\n")
                        writer.flush()
                    }
                    cmd.startsWith("LOGOUT") -> {
                        writer.print("* BYE\r\n$tag OK LOGOUT\r\n")
                        writer.flush()
                        break
                    }
                    else -> {
                        writer.print("$tag BAD Command not allowed\r\n")
                        writer.flush()
                    }
                }
            }
        }.use { imapServer ->
            val imap = Endpoint("127.0.0.1", imapServer.port, "alice", TransportSecurity.STARTTLS)
            val smtp = Endpoint("127.0.0.1", 1, "alice", TransportSecurity.SSL_TLS)
            val error = runCatching { MailClient(timeoutMs = 2_000).verify(imap, smtp, Credentials(SECRET)) }.exceptionOrNull()
            assertThat(error).isInstanceOf(MessagingException::class.java)
            imapServer.assertNoCredentialsSent()
        }
    }

    @Test
    fun `imap advertising STARTTLS but rejecting upgrade fails verify without sending credentials`() = runBlocking {
        FakeTcpServer { _, reader, writer, received ->
            writer.print("* OK [CAPABILITY IMAP4rev1 STARTTLS AUTH=PLAIN] FakeIMAP ready\r\n")
            writer.flush()
            while (true) {
                val line = reader.readLine() ?: break
                received += line
                val tag = line.substringBefore(' ')
                val cmd = line.substringAfter(' ').uppercase()
                when {
                    cmd.startsWith("CAPABILITY") -> {
                        writer.print("* CAPABILITY IMAP4rev1 STARTTLS AUTH=PLAIN\r\n$tag OK CAPABILITY completed\r\n")
                        writer.flush()
                    }
                    cmd.startsWith("STARTTLS") -> {
                        writer.print("$tag NO STARTTLS upgrade failed\r\n")
                        writer.flush()
                    }
                    cmd.startsWith("LOGOUT") -> {
                        writer.print("* BYE\r\n$tag OK LOGOUT\r\n")
                        writer.flush()
                        break
                    }
                    else -> {
                        writer.print("$tag BAD Unexpected\r\n")
                        writer.flush()
                    }
                }
            }
        }.use { imapServer ->
            val imap = Endpoint("127.0.0.1", imapServer.port, "alice", TransportSecurity.STARTTLS)
            val smtp = Endpoint("127.0.0.1", 1, "alice", TransportSecurity.SSL_TLS)
            val error = runCatching { MailClient(timeoutMs = 2_000).verify(imap, smtp, Credentials(SECRET)) }.exceptionOrNull()
            assertThat(error).isInstanceOf(MessagingException::class.java)
            imapServer.assertNoCredentialsSent()
        }
    }

    @Test
    fun `imap accepting STARTTLS command but corrupting tls handshake fails with SSLException`() = runBlocking {
        FakeTcpServer { socket, reader, writer, received ->
            writer.print("* OK [CAPABILITY IMAP4rev1 STARTTLS] FakeIMAP ready\r\n")
            writer.flush()
            while (true) {
                val line = reader.readLine() ?: break
                received += line
                val tag = line.substringBefore(' ')
                val cmd = line.substringAfter(' ').uppercase()
                if (cmd.startsWith("CAPABILITY")) {
                    writer.print("* CAPABILITY IMAP4rev1 STARTTLS\r\n$tag OK CAPABILITY\r\n")
                    writer.flush()
                } else if (cmd.startsWith("STARTTLS")) {
                    writer.print("$tag OK Begin TLS negotiation now\r\n")
                    writer.flush()
                    // Write non-TLS plaintext garbage where ServerHello is expected
                    socket.getOutputStream().write("NOT_A_TLS_HANDSHAKE\r\n".toByteArray(StandardCharsets.US_ASCII))
                    socket.getOutputStream().flush()
                    break
                }
            }
        }.use { imapServer ->
            val imap = Endpoint("127.0.0.1", imapServer.port, "alice", TransportSecurity.STARTTLS)
            val smtp = Endpoint("127.0.0.1", 1, "alice", TransportSecurity.SSL_TLS)
            val error = runCatching { MailClient(timeoutMs = 2_000).verify(imap, smtp, Credentials(SECRET)) }.exceptionOrNull()
            assertThat(error).isNotNull()
            assertTrue(
                "Expected SSLException in cause chain, got: ${generateSequence(error) { it.cause }.toList()}",
                generateSequence(error) { it.cause }.any { it is SSLException },
            )
            imapServer.assertNoCredentialsSent()
        }
    }

    @Test
    fun `smtp omitting STARTTLS from EHLO fails verify without sending credentials`() = runBlocking {
        acceptingImapServer().use { imapServer ->
            FakeTcpServer { _, reader, writer, received ->
                writer.print("220 smtp.example ESMTP ready\r\n")
                writer.flush()
                while (true) {
                    val line = reader.readLine() ?: break
                    received += line
                    val upper = line.uppercase()
                    when {
                        upper.startsWith("EHLO") || upper.startsWith("HELO") -> {
                            writer.print("250-smtp.example\r\n250 AUTH PLAIN LOGIN\r\n")
                            writer.flush()
                        }
                        upper.startsWith("QUIT") -> {
                            writer.print("221 Bye\r\n")
                            writer.flush()
                            break
                        }
                        else -> {
                            writer.print("500 Unexpected\r\n")
                            writer.flush()
                        }
                    }
                }
            }.use { smtpServer ->
                val imap = Endpoint("127.0.0.1", imapServer.port, "alice", TransportSecurity.NONE)
                val smtp = Endpoint("127.0.0.1", smtpServer.port, "alice", TransportSecurity.STARTTLS)
                val error = runCatching { MailClient(timeoutMs = 2_000).verify(imap, smtp, Credentials(SECRET)) }.exceptionOrNull()
                assertThat(error).isInstanceOf(MessagingException::class.java)
                smtpServer.assertNoCredentialsSent()
            }
        }
    }

    @Test
    fun `smtp advertising STARTTLS but rejecting upgrade fails verify without sending credentials`() = runBlocking {
        acceptingImapServer().use { imapServer ->
            FakeTcpServer { _, reader, writer, received ->
                writer.print("220 smtp.example ESMTP ready\r\n")
                writer.flush()
                while (true) {
                    val line = reader.readLine() ?: break
                    received += line
                    val upper = line.uppercase()
                    when {
                        upper.startsWith("EHLO") -> {
                            writer.print("250-smtp.example\r\n250-STARTTLS\r\n250 AUTH PLAIN LOGIN\r\n")
                            writer.flush()
                        }
                        upper.startsWith("STARTTLS") -> {
                            writer.print("454 4.7.0 TLS not available due to temporary reason\r\n")
                            writer.flush()
                        }
                        upper.startsWith("QUIT") -> {
                            writer.print("221 Bye\r\n")
                            writer.flush()
                            break
                        }
                        else -> {
                            writer.print("500 Unexpected\r\n")
                            writer.flush()
                        }
                    }
                }
            }.use { smtpServer ->
                val imap = Endpoint("127.0.0.1", imapServer.port, "alice", TransportSecurity.NONE)
                val smtp = Endpoint("127.0.0.1", smtpServer.port, "alice", TransportSecurity.STARTTLS)
                val error = runCatching { MailClient(timeoutMs = 2_000).verify(imap, smtp, Credentials(SECRET)) }.exceptionOrNull()
                assertThat(error).isInstanceOf(MessagingException::class.java)
                smtpServer.assertNoCredentialsSent()
            }
        }
    }

    // --------------------------------------------------------- Certificate & Hostname Verification

    @Test
    fun `self-signed certificate on implicit TLS fails with SSLException and sends no credentials`() = runBlocking {
        val ctx = sslContextFromP12(LOCALHOST_P12_B64)
        val sslServerSocket = ctx.serverSocketFactory.createServerSocket(0, 5, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        FakeTcpServer(sslServerSocket) { socket, reader, writer, received ->
            (socket as SSLSocket).startHandshake()
            writer.print("* OK [CAPABILITY IMAP4rev1 AUTH=PLAIN] ready\r\n")
            writer.flush()
            while (true) {
                val line = reader.readLine() ?: break
                received += line
            }
        }.use { imapServer ->
            val imap = Endpoint("127.0.0.1", imapServer.port, "alice", TransportSecurity.SSL_TLS)
            val smtp = Endpoint("127.0.0.1", 1, "alice", TransportSecurity.SSL_TLS)
            val error = runCatching { MailClient(timeoutMs = 2_000).verify(imap, smtp, Credentials(SECRET)) }.exceptionOrNull()
            assertThat(error).isNotNull()
            assertTrue(
                "Expected SSLException in cause chain, got: ${generateSequence(error) { it.cause }.toList()}",
                generateSequence(error) { it.cause }.any { it is SSLException },
            )
            imapServer.assertNoCredentialsSent()
        }
    }

    @Test
    fun `wrong-hostname certificate fails even when cert is trusted by default SSLContext`() = runBlocking {
        val previousDefault = SSLContext.getDefault()
        val trustedWrongHostCtx = sslContextFromP12(WRONGHOST_P12_B64)
        SSLContext.setDefault(trustedWrongHostCtx)
        try {
            val sslServerSocket = trustedWrongHostCtx.serverSocketFactory
                .createServerSocket(0, 5, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            FakeTcpServer(sslServerSocket) { socket, reader, writer, received ->
                (socket as SSLSocket).startHandshake()
                writer.print("* OK [CAPABILITY IMAP4rev1 AUTH=PLAIN] ready\r\n")
                writer.flush()
                while (true) {
                    val line = reader.readLine() ?: break
                    received += line
                }
            }.use { imapServer ->
                // Connect to 127.0.0.1 while server presents cert for wrong.example.test
                val imap = Endpoint("127.0.0.1", imapServer.port, "alice", TransportSecurity.SSL_TLS)
                val smtp = Endpoint("127.0.0.1", 1, "alice", TransportSecurity.SSL_TLS)
                val error = runCatching { MailClient(timeoutMs = 2_000).verify(imap, smtp, Credentials(SECRET)) }.exceptionOrNull()
                assertThat(error).isNotNull()
                val causes = generateSequence(error) { it.cause }.toList()
                assertTrue(
                    "Expected SSLException or CertificateException in cause chain, got: $causes",
                    causes.any { it is SSLException || it is java.security.cert.CertificateException },
                )
                imapServer.assertNoCredentialsSent()
            }
        } finally {
            SSLContext.setDefault(previousDefault)
        }
    }

    @Test
    fun `self-signed certificate on IMAP STARTTLS fails with SSLException and sends no credentials`() = runBlocking {
        val ctx = sslContextFromP12(LOCALHOST_P12_B64)
        FakeTcpServer { socket, reader, writer, received ->
            writer.print("* OK [CAPABILITY IMAP4rev1 STARTTLS AUTH=PLAIN] FakeIMAP ready\r\n")
            writer.flush()
            while (true) {
                val line = reader.readLine() ?: break
                received += line
                val tag = line.substringBefore(' ')
                val cmd = line.substringAfter(' ').uppercase()
                if (cmd.startsWith("CAPABILITY")) {
                    writer.print("* CAPABILITY IMAP4rev1 STARTTLS AUTH=PLAIN\r\n$tag OK CAPABILITY\r\n")
                    writer.flush()
                } else if (cmd.startsWith("STARTTLS")) {
                    writer.print("$tag OK Begin TLS negotiation\r\n")
                    writer.flush()
                    val sslSocket = ctx.socketFactory.createSocket(socket, "127.0.0.1", socket.port, true) as SSLSocket
                    sslSocket.useClientMode = false
                    sslSocket.startHandshake()
                    break
                }
            }
        }.use { imapServer ->
            val imap = Endpoint("127.0.0.1", imapServer.port, "alice", TransportSecurity.STARTTLS)
            val smtp = Endpoint("127.0.0.1", 1, "alice", TransportSecurity.SSL_TLS)
            val error = runCatching { MailClient(timeoutMs = 2_000).verify(imap, smtp, Credentials(SECRET)) }.exceptionOrNull()
            assertThat(error).isNotNull()
            val causes = generateSequence(error) { it.cause }.toList()
            println("IMAP_STARTTLS_SELF_SIGNED_CAUSES: ${causes.map { "${it.javaClass.name}: ${it.message}" }}")
            imapServer.assertNoCredentialsSent()
        }
    }

    @Test
    fun `wrong-hostname certificate on IMAP STARTTLS fails and sends no credentials`() = runBlocking {
        val previousDefault = SSLContext.getDefault()
        val trustedWrongHostCtx = sslContextFromP12(WRONGHOST_P12_B64)
        SSLContext.setDefault(trustedWrongHostCtx)
        try {
            FakeTcpServer { socket, reader, writer, received ->
                writer.print("* OK [CAPABILITY IMAP4rev1 STARTTLS AUTH=PLAIN] FakeIMAP ready\r\n")
                writer.flush()
                while (true) {
                    val line = reader.readLine() ?: break
                    received += line
                    val tag = line.substringBefore(' ')
                    val cmd = line.substringAfter(' ').uppercase()
                    if (cmd.startsWith("CAPABILITY")) {
                        writer.print("* CAPABILITY IMAP4rev1 STARTTLS AUTH=PLAIN\r\n$tag OK CAPABILITY\r\n")
                        writer.flush()
                    } else if (cmd.startsWith("STARTTLS")) {
                        writer.print("$tag OK Begin TLS negotiation\r\n")
                        writer.flush()
                        val sslSocket = trustedWrongHostCtx.socketFactory.createSocket(socket, "127.0.0.1", socket.port, true) as SSLSocket
                        sslSocket.useClientMode = false
                        sslSocket.startHandshake()
                        val tlsReader = BufferedReader(InputStreamReader(sslSocket.inputStream, StandardCharsets.UTF_8))
                        while (true) {
                            val tlsLine = tlsReader.readLine() ?: break
                            received += tlsLine
                        }
                        break
                    }
                }
            }.use { imapServer ->
                val imap = Endpoint("127.0.0.1", imapServer.port, "alice", TransportSecurity.STARTTLS)
                val smtp = Endpoint("127.0.0.1", 1, "alice", TransportSecurity.SSL_TLS)
                val error = runCatching { MailClient(timeoutMs = 2_000).verify(imap, smtp, Credentials(SECRET)) }.exceptionOrNull()
                assertThat(error).isNotNull()
                val causes = generateSequence(error) { it.cause }.toList()
                println("IMAP_STARTTLS_WRONG_HOST_CAUSES: ${causes.map { "${it.javaClass.name}: ${it.message}" }}")
                imapServer.assertNoCredentialsSent()
            }
        } finally {
            SSLContext.setDefault(previousDefault)
        }
    }
}
