package app.dot.mail.engine

import app.dot.mail.engine.jmap.JmapException
import app.dot.mail.engine.jmap.JmapErrorKind
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.net.*
import java.security.cert.CertificateException
import java.security.cert.CertPathBuilderException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException

enum class ConnectionFailure(val transient:Boolean,val humanMessage:String) {
    DNS(true,"Mail server name could not be resolved. Check your connection and server settings."),
    TIMEOUT(true,"Mail server timed out. Please retry."),
    TLS(false,"The secure connection could not be verified. Check the server certificate and device clock."),
    AUTH(false,"Login failed. Sign in again."),
    SERVER_CLOSED(true,"Mail server closed the connection. Please retry."),
    THROTTLED(true,"Mail server is limiting connections. Wait a moment and retry."),
    NETWORK(true,"Mail server is unreachable. Check your connection and retry."),
    OTHER(false,"Mail could not be synchronized. Please retry.");

    companion object {
        fun chain(error:Throwable):List<Throwable> {
            val seen=Collections.newSetFromMap(IdentityHashMap<Throwable,Boolean>())
            val pending=ArrayDeque<Throwable>().apply { add(error) }; val result=mutableListOf<Throwable>()
            while(pending.isNotEmpty() && result.size<32) {
                val next=pending.removeFirst(); if(!seen.add(next)) continue
                result+=next; next.cause?.let(pending::addLast)
                (next as? MessagingException)?.nextException?.let(pending::addLast)
            }
            return result
        }
        fun classify(error:Throwable):ConnectionFailure {
            val chain=chain(error)
            return when {
                chain.any { it is SSLException || it is CertificateException || it is CertPathBuilderException } -> TLS
                chain.any { it is AuthenticationFailedException || it.javaClass.simpleName=="ReauthenticationRequired" || (it as? JmapException)?.kind==JmapErrorKind.AUTH } -> AUTH
                chain.any { (it as? JmapException)?.kind==JmapErrorKind.RATE_LIMIT } -> THROTTLED
                chain.any { (it as? JmapException)?.let { error -> error.kind==JmapErrorKind.TRANSIENT && error.status in setOf(408,504) }==true } -> TIMEOUT
                chain.any { (it as? JmapException)?.kind==JmapErrorKind.TRANSIENT } -> NETWORK
                chain.any { it is UnknownHostException } -> DNS
                chain.any { it is SocketTimeoutException || it.javaClass.simpleName=="TimeoutCancellationException" } -> TIMEOUT
                chain.any { (it.message.orEmpty().lowercase()).let { text -> listOf("throttl","too many","rate limit","connection limit","temporarily unavailable").any(text::contains) } } -> THROTTLED
                chain.any { it is java.io.EOFException || it.javaClass.simpleName in setOf("ConnectionException","StoreClosedException","FolderClosedException") || Regex("(?i)\\bBYE\\b").containsMatchIn(it.message.orEmpty()) } -> SERVER_CLOSED
                chain.any { it is SocketException || it is NoRouteToHostException } -> NETWORK
                else -> OTHER
            }
        }
    }
}

/** Only use around read-only attempts. Never blindly retry an SMTP submission or an uncertain mailbox write. */
suspend fun <T> retryMailRead(attempts:Int=2,backoffMillis:Long=500,block:suspend ()->T):T {
    require(attempts in 1..3)
    repeat(attempts) { attempt ->
        try { return block() }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) {
            if(attempt==attempts-1 || !ConnectionFailure.classify(e).transient) throw e
            delay(backoffMillis*(1L shl attempt)*(if(ConnectionFailure.classify(e)==ConnectionFailure.THROTTLED) 4 else 1))
        }
    }
    error("Unreachable")
}
