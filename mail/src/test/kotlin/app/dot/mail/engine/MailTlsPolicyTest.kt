package app.dot.mail.engine

import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.SSLException

class MailTlsPolicyTest {
    @Test fun olderAndroidKeepsTls12AndNeverEnablesLegacyVersions() {
        assertEquals("TLSv1.2", MailTlsPolicy.protocols(listOf("SSLv3", "TLSv1", "TLSv1.1", "TLSv1.2")))
        assertEquals("TLSv1.3 TLSv1.2", MailTlsPolicy.protocols(listOf("TLSv1.2", "TLSv1.3", "TLSv1.1")))
    }
    @Test(expected = SSLException::class) fun providerWithoutModernTlsFailsClosed() {
        MailTlsPolicy.protocols(listOf("TLSv1", "TLSv1.1"))
    }
    @Test fun allSessionVariantsRestrictTlsAndDisableDebugging() {
        for (smtp in listOf(false,true)) for (security in listOf(TransportSecurity.SSL_TLS,TransportSecurity.STARTTLS)) {
            val session = MailClient().session(Endpoint("localhost",993,"fixture",security),Credentials("fixture"),smtp)
            for (protocol in listOf("imap","imaps","smtp","smtps")) {
                val enabled = session.getProperty("mail.$protocol.ssl.protocols").split(" ")
                assertTrue(enabled.isNotEmpty())
                assertTrue(enabled.all { it == "TLSv1.3" || it == "TLSv1.2" })
            }
            val protocol = if (smtp) "smtp" else "imap"
            assertEquals("true",session.getProperty("mail.$protocol.ssl.checkserveridentity"))
            assertEquals((security==TransportSecurity.STARTTLS).toString(),session.getProperty("mail.$protocol.starttls.required"))
            assertEquals("false",session.getProperty("mail.debug"))
            assertEquals("false",session.getProperty("mail.debug.auth"))
            assertFalse(session.debug)
        }
    }
}
