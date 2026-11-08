package app.dot.mail.data

import app.dot.mail.data.model.VerifyResult
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import java.security.cert.CertificateException
import java.security.cert.CertPathBuilderException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException

/** Jakarta Mail may chain provider errors through nextException rather than cause. */
internal fun verificationFailure(error: Exception): VerifyResult {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val pending = ArrayDeque<Throwable>().apply { add(error) }
    var auth = false
    while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (!visited.add(next)) continue
        if (next is SSLException || next is CertificateException || next is CertPathBuilderException)
            return VerifyResult.CertificateProblem("Server TLS certificate could not be verified")
        if (next is AuthenticationFailedException) auth = true
        next.cause?.let(pending::addLast)
        (next as? MessagingException)?.nextException?.let(pending::addLast)
    }
    return if (auth) VerifyResult.AuthFailed("Authentication failed. Check your password or app password.")
    else VerifyResult.ConnectionFailed("Could not connect to the mail server")
}
