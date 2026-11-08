package app.dot.mail.engine.autoconfig

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Test

class HttpsConfigFetcherTest {

    /**
     * In-memory stand-in for an HTTPS response. The fetcher never touches the real
     * network in tests: the connection factory is injected and every stub answers
     * with a fixed status/body/location.
     *
     * Overrides only members that exist on JDK 17+ (several legacy HttpURLConnection
     * members were removed; connect/disconnect/usingProxy are the only abstract ones).
     */
    private class StubConnection(
        private val code: Int,
        body: ByteArray = ByteArray(0),
        private val location: String? = null,
    ) : HttpURLConnection(URL("https://stub.invalid/")) {

        private val bodyStream = body.inputStream()
        val requestProps = HashMap<String, String>()
        var connectTimeoutSet = -1
        var readTimeoutSet = -1
        var followRedirects: Boolean? = null

        // --- the three abstract members -------------------------------------
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false

        // --- response ----------------------------------------------------------
        override fun getResponseCode(): Int = code
        override fun getResponseMessage(): String = "stub"
        override fun getInputStream(): InputStream =
            if (code in 200..299) bodyStream else throw IOException("HTTP $code")
        override fun getErrorStream(): InputStream? = if (code >= 400) bodyStream else null
        override fun getHeaderField(name: String): String? =
            location?.takeIf { name.equals("Location", ignoreCase = true) }
        override fun getHeaderFields(): Map<String, List<String>> =
            if (location != null) mapOf("Location" to listOf(location)) else emptyMap()

        // --- request properties -------------------------------------------------
        override fun setRequestProperty(key: String, value: String) { requestProps[key] = value }
        override fun getRequestProperty(key: String): String? = requestProps[key]
        override fun getRequestProperties(): Map<String, List<String>> =
            requestProps.mapValues { listOf(it.value) }

        // --- knobs the fetcher sets, recorded for assertions --------------------
        override fun setConnectTimeout(ms: Int) { connectTimeoutSet = ms }
        override fun getConnectTimeout(): Int = connectTimeoutSet
        override fun setReadTimeout(ms: Int) { readTimeoutSet = ms }
        override fun getReadTimeout(): Int = readTimeoutSet
        override fun setInstanceFollowRedirects(b: Boolean) { followRedirects = b }
        override fun getInstanceFollowRedirects(): Boolean = followRedirects ?: true
    }

    /** Builds a fetcher whose connections come from [stubs]; extra requests get a 404 stub. */
    private fun fetcherWith(stubs: List<StubConnection> = emptyList()): Pair<HttpsConfigFetcher, ArrayDeque<StubConnection>> {
        val queue = ArrayDeque(stubs)
        val fetcher = HttpsConfigFetcher { uri -> queue.removeFirstOrNull() ?: StubConnection(404) }
        return fetcher to queue
    }

    @Test
    fun `non https urls are rejected without opening a connection`() {
        val (fetcher, queue) = fetcherWith(listOf(StubConnection(200, "<xml/>".toByteArray())))
        assertThat(fetcher.fetch("http://example.com/mail/config-v1.1.xml")).isNull()
        assertThat(fetcher.fetch("ftp://example.com/x")).isNull()
        assertThat(fetcher.fetch("example.com/relative")).isNull()
        assertThat(fetcher.fetch("not a url at all")).isNull()
        assertThat(queue).hasSize(1) // nothing was opened
    }

    @Test
    fun `http 200 returns the body`() {
        val (fetcher, _) = fetcherWith(listOf(StubConnection(200, "<clientInfo/>".toByteArray())))
        assertThat(fetcher.fetch("https://example.com/c.xml")).isEqualTo("<clientInfo/>")
    }

    @Test
    fun `non 200 status returns null`() {
        listOf(304, 404, 500).forEach { code ->
            val (fetcher, _) = fetcherWith(listOf(StubConnection(code, "nope".toByteArray())))
            assertThat(fetcher.fetch("https://example.com/c.xml")).isNull()
        }
    }

    @Test
    fun `body exactly at the 256 kib limit is accepted`() {
        val limit = HttpsConfigFetcher.MAX_BODY_BYTES
        val body = ByteArray(limit) { ('a'.code + (it % 26)).toByte() }
        val (fetcher, _) = fetcherWith(listOf(StubConnection(200, body)))
        val result = fetcher.fetch("https://example.com/c.xml")
        assertThat(result).isNotNull()
        assertThat(result!!.length).isEqualTo(limit)
    }

    @Test
    fun `body one byte over the limit is rejected`() {
        val body = ByteArray(HttpsConfigFetcher.MAX_BODY_BYTES + 1) { 'x'.code.toByte() }
        val (fetcher, _) = fetcherWith(listOf(StubConnection(200, body)))
        assertThat(fetcher.fetch("https://example.com/c.xml")).isNull()
    }

    @Test
    fun `redirects to https are followed`() {
        val (fetcher, _) = fetcherWith(listOf(
            StubConnection(301, location = "https://cdn.example.com/c.xml"),
            StubConnection(200, "moved body".toByteArray()),
        ))
        assertThat(fetcher.fetch("https://example.com/c.xml")).isEqualTo("moved body")
    }

    @Test
    fun `redirect to http is refused`() {
        val (fetcher, queue) = fetcherWith(listOf(
            StubConnection(301, location = "http://example.com/c.xml"),
            StubConnection(200, "should not be read".toByteArray()),
        ))
        assertThat(fetcher.fetch("https://example.com/c.xml")).isNull()
        assertThat(queue).hasSize(1) // second connection was never opened
    }

    @Test
    fun `relative redirects are resolved and accepted`() {
        val (fetcher, _) = fetcherWith(listOf(
            StubConnection(302, location = "/c2.xml"),
            StubConnection(200, "relative ok".toByteArray()),
        ))
        assertThat(fetcher.fetch("https://example.com/c.xml")).isEqualTo("relative ok")
    }

    @Test
    fun `redirect loops are bounded`() {
        val (fetcher, _) = fetcherWith(List(10) {
            StubConnection(301, location = "https://example.com/c.xml")
        })
        assertThat(fetcher.fetch("https://example.com/c.xml")).isNull()
    }

    @Test
    fun `connection uses the mandated timeouts and headers`() {
        val stub = StubConnection(200, "ok".toByteArray())
        val (fetcher, _) = fetcherWith(listOf(stub))
        fetcher.fetch("https://example.com/c.xml")
        assertThat(stub.connectTimeoutSet).isEqualTo(8000)
        assertThat(stub.readTimeoutSet).isEqualTo(8000)
        assertThat(stub.followRedirects == false).isTrue()
        assertThat(stub.requestProps["User-Agent"] ?: "").contains("DotMail")
        assertThat(stub.requestProps["Accept"] ?: "").contains("xml")
    }
}
