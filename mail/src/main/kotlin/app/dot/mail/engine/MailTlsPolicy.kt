package app.dot.mail.engine

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

/** Angus passes ssl.protocols directly to SSLSocket; unsupported names fail on Android 26–28. */
internal object MailTlsPolicy {
    fun protocols(supported: Collection<String>): String {
        val allowed = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }
        if (allowed.isEmpty()) throw SSLException("TLS 1.2 or newer is required")
        return allowed.joinToString(" ")
    }

    fun protocols(): String = protocols(SSLContext.getDefault().supportedSSLParameters.protocols.toList())
}
