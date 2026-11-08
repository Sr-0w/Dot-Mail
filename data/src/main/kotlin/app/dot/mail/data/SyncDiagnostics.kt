package app.dot.mail.data

import android.util.Log
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FolderNotFoundException
import java.net.*
import javax.net.ssl.SSLException

/** Never pass provider Throwable messages to Log: servers can echo logins, tokens or MIME data. */
internal object SyncDiagnostics {
    fun reason(error: Throwable): String {
        val chain = chain(error)
        return when {
            chain.any { it is AuthenticationFailedException || it is app.dot.mail.data.oauth.ReauthenticationRequired } -> "Login failed; sign in again"
            chain.any { it is UnknownHostException } -> "Server name could not be resolved"
            chain.any { it is SocketTimeoutException || it is kotlinx.coroutines.TimeoutCancellationException } -> "Server timed out"
            chain.any { it is ConnectException || it is NoRouteToHostException } -> "Server unreachable"
            chain.any { it is SSLException } -> "Secure connection failed"
            chain.any { it is FolderNotFoundException } -> "Folder unavailable"
            chain.any { it.javaClass.simpleName.contains("Connection", true) || it is java.net.SocketException } -> "Server connection closed"
            chain.any { it.javaClass.simpleName.contains("Parsing", true) } -> "Server response could not be parsed"
            chain.any { it is android.database.sqlite.SQLiteException } -> "Local mail database could not be updated"
            chain.any { it is NullPointerException } -> "Unexpected empty server result"
            chain.any { it is IndexOutOfBoundsException } -> "Server returned inconsistent message numbers"
            else -> "Mail sync failed (${error.javaClass.simpleName})"
        }
    }
    private fun chain(error: Throwable): List<Throwable> = buildList {
        var current: Throwable? = error
        while (size < 8) {
            val item = current ?: break
            if (any { it === item }) break
            add(item); current = item.cause
        }
    }
    internal fun sanitized(error: Throwable): Throwable {
        var result: Throwable? = null
        for (cause in chain(error).asReversed()) {
            result = RuntimeException("${cause.javaClass.simpleName}: ${reason(cause)}", result).apply { stackTrace = cause.stackTrace }
        }
        return requireNotNull(result)
    }
    fun warn(phase: String, error: Throwable) {
        Log.w("DotMail/Sync", "$phase: ${reason(error)}", sanitized(error))
    }
}
