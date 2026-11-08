package app.dot.mail.data

import app.dot.mail.data.model.VerifyResult
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import org.junit.Assert.*
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertPathBuilderException
import javax.net.ssl.SSLException

class VerificationFailureTest {
    @Test fun allCertificateFailureFormsHaveSafePresentation() {
        for (cause in listOf(SSLException("private"),CertificateException("private"),CertPathBuilderException("private"))) {
            val result = verificationFailure(MessagingException("private",cause))
            assertTrue(result is VerifyResult.CertificateProblem)
            assertFalse((result as VerifyResult.CertificateProblem).message.contains("private"))
        }
    }
    @Test fun chainedMailExceptionAndCyclesAreHandled() {
        val root = MessagingException("private")
        root.setNextException(MessagingException("private",CertificateException("private")))
        assertTrue(verificationFailure(root) is VerifyResult.CertificateProblem)
        val cyclic = Exception("private")
        cyclic.initCause(cyclic.let { Exception("private",it) })
        assertTrue(verificationFailure(cyclic) is VerifyResult.ConnectionFailed)
    }
    @Test fun loginAndConnectionFailuresRemainDistinct() {
        assertTrue(verificationFailure(MessagingException("private",AuthenticationFailedException("private"))) is VerifyResult.AuthFailed)
        assertTrue(verificationFailure(java.net.ConnectException("private")) is VerifyResult.ConnectionFailed)
    }
}
