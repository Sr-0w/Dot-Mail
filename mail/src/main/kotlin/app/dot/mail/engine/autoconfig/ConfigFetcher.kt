package app.dot.mail.engine.autoconfig

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Fetches autoconfig documents. Implementations must be safe to call from any
 * thread and must never block for long.
 *
 * Returns the response body for HTTP 200 and `null` otherwise (any other status,
 * a non-HTTPS URL, a network/parse error, or an oversized document).
 */
fun interface ConfigFetcher {
    fun fetch(url: String): String?
}

/**
 * HTTPS-only [ConfigFetcher] built on [java.net.HttpURLConnection]:
 *
 * - only `https://` URLs are fetched; anything else returns `null` without I/O;
 * - 8 s connect timeout and 8 s read timeout;
 * - documents larger than 256 KiB are rejected (defence against entity-bomb /
 *   huge-document attacks);
 * - no cookies: the JDK URL connections carry no cookie handler by default and we
 *   never install one;
 * - redirects are followed manually, at most 5 hops, and only to `https://` URLs.
 */
class HttpsConfigFetcher internal constructor(
    private val openConnection: (URI) -> HttpURLConnection = { uri ->
        uri.toURL().openConnection() as HttpURLConnection
    },
) : ConfigFetcher {

    override fun fetch(url: String): String? {
        var uri: URI = try {
            URI(url)
        } catch (e: Exception) {
            return null
        }
        if (uri.scheme?.lowercase() != "https") return null

        var connection: HttpURLConnection? = null
        try {
            var redirected = 0
            while (true) {
                connection?.disconnect()
                val conn = openConnection(uri)
                connection = conn
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/xml, text/xml, */*;q=0.8")

                when (val code = conn.responseCode) {
                    in REDIRECT_STATUSES -> {
                        if (redirected++ >= MAX_REDIRECTS) return null
                        val location = conn.getHeaderField("Location")
                        if (location.isNullOrBlank()) return null
                        uri = try {
                            val target = URI(location)
                            if (target.isAbsolute) target else uri.resolve(target)
                        } catch (e: Exception) {
                            return null
                        }
                        if (uri.scheme?.lowercase() != "https") return null
                        continue
                    }
                    HttpURLConnection.HTTP_OK -> return readBounded(conn)
                    else -> return null
                }
            }
        } catch (e: IOException) {
            return null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Reads at most [MAX_BODY_BYTES] + 1 bytes using an explicit buffer, then stops —
     * it never waits for EOF, so a misbehaving server cannot stall the read or make us
     * buffer unbounded data. Returns null for oversized documents.
     */
    private fun readBounded(conn: HttpURLConnection): String? = conn.inputStream.use { input ->
        val buffer = ByteArray(MAX_BODY_BYTES + 1)
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read == -1) break
            total += read
        }
        if (total > MAX_BODY_BYTES) null else String(buffer, 0, total, Charsets.UTF_8)
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 8_000
        const val MAX_BODY_BYTES = 256 * 1024
        private const val MAX_REDIRECTS = 5
        private const val USER_AGENT = "DotMail/0.1 (autoconfig)"
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }
}
