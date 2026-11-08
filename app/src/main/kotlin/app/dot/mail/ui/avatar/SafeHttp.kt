package app.dot.mail.ui.avatar

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class Fetched(val bytes: ByteArray, val contentType: String)

/**
 * Tiny HTTPS GET for avatar pictures. HTTPS only (also after redirects), no cookies, no credentials,
 * hosts that resolve to private/loopback/link-local ranges are refused (a sender's domain must not make the phone
 * poke at the local network), bounded time and size. Blocking: call from a background thread.
 */
internal object SafeHttp {
    private const val MAX_REDIRECTS = 3

    fun get(url: String, maxBytes: Int = 512 * 1024, accept: String = "image/*"): Fetched? {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val u = runCatching { URL(current) }.getOrNull() ?: return null
            if (u.protocol != "https" || u.host.isNullOrEmpty() || u.userInfo != null) return null
            if (!isPublicHost(u.host)) return null
            val conn = (u.openConnection() as? HttpsURLConnection) ?: return null
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 4_000
                conn.readTimeout = 5_000
                conn.useCaches = false
                conn.setRequestProperty("Accept", accept)
                conn.setRequestProperty("User-Agent", "DotMail")
                val code = conn.responseCode
                if (code in 301..308 && code != 304) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    current = runCatching { URL(u, loc).toString() }.getOrNull() ?: return null
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) return null
                val declared = conn.contentLengthLong
                if (declared > maxBytes) return null
                val out = java.io.ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) return null
                    }
                }
                return Fetched(out.toByteArray(), conn.contentType.orEmpty().lowercase())
            } catch (e: Exception) {
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    fun isPublicHost(host: String): Boolean = try {
        InetAddress.getAllByName(host).all { a ->
            val b = a.address
            val ula = b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC
            val cgnat = b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64
            !(a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress || ula || cgnat)
        }
    } catch (e: Exception) {
        false
    }
}
