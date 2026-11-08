package app.dot.mail.data

import android.util.Log
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FolderNotFoundException
import app.dot.mail.data.model.SyncProblem
import java.net.*
import javax.net.ssl.SSLException

/** Never pass provider Throwable messages to Log: servers can echo logins, tokens or MIME data. */
internal object SyncDiagnostics {
    /** English text for logs; the UI never shows it (it maps [problem] / the connection kind instead). */
    fun reason(error: Throwable): String {
        val kind=app.dot.mail.engine.ConnectionFailure.classify(error)
        if(kind!=app.dot.mail.engine.ConnectionFailure.OTHER) return kind.humanMessage
        return when(problem(error)) {
            SyncProblem.AUTH -> "Login failed; sign in again"
            SyncProblem.SERVER_NAME -> "Server name could not be resolved"
            SyncProblem.TIMEOUT -> "Server timed out"
            SyncProblem.UNREACHABLE -> "Server unreachable"
            SyncProblem.TLS -> "Secure connection failed"
            SyncProblem.FOLDER_UNAVAILABLE -> "Folder unavailable"
            SyncProblem.CONNECTION_CLOSED -> "Server connection closed"
            SyncProblem.PARSE -> "Server response could not be parsed"
            SyncProblem.DATABASE -> "Local mail database could not be updated"
            SyncProblem.EMPTY_RESULT -> "Unexpected empty server result"
            SyncProblem.INCONSISTENT -> "Server returned inconsistent message numbers"
            else -> "Mail sync failed (${error.javaClass.simpleName})"
        }
    }

    /** The typed cause of [error], for the UI to explain in the user's language. */
    fun problem(error: Throwable): SyncProblem {
        val chain = chain(error)
        return when {
            chain.any { it is AuthenticationFailedException || it is app.dot.mail.data.oauth.ReauthenticationRequired } -> SyncProblem.AUTH
            chain.any { it is UnknownHostException } -> SyncProblem.SERVER_NAME
            chain.any { it is SocketTimeoutException || it is kotlinx.coroutines.TimeoutCancellationException } -> SyncProblem.TIMEOUT
            chain.any { it is ConnectException || it is NoRouteToHostException } -> SyncProblem.UNREACHABLE
            chain.any { it is SSLException } -> SyncProblem.TLS
            chain.any { it is FolderNotFoundException } -> SyncProblem.FOLDER_UNAVAILABLE
            chain.any { it.javaClass.simpleName.contains("Connection", true) || it is java.net.SocketException } -> SyncProblem.CONNECTION_CLOSED
            chain.any { it.javaClass.simpleName.contains("Parsing", true) } -> SyncProblem.PARSE
            chain.any { it is android.database.sqlite.SQLiteException } -> SyncProblem.DATABASE
            chain.any { it is NullPointerException } -> SyncProblem.EMPTY_RESULT
            chain.any { it is IndexOutOfBoundsException } -> SyncProblem.INCONSISTENT
            else -> SyncProblem.OTHER
        }
    }

    /**
     * Stored form: `[CONNECTION_KIND:PROBLEM:FOLDER_ROLE] english text`. The tags are what the UI reads; the text is for logs and for
     * rows written before tags existed (`[KIND] text`, or plain text), which still parse.
     */
    fun stored(error:Throwable,role:String?=null):String {
        val kind=app.dot.mail.engine.ConnectionFailure.classify(error)
        val problem=problem(error).takeIf { kind==app.dot.mail.engine.ConnectionFailure.OTHER }
        return "[${kind.name}:${problem?.name.orEmpty()}:${role.orEmpty()}] " + (role?.let { "Folder $it: " } ?: "") + reason(error)
    }
    fun state(raw:String,auth:Boolean):app.dot.mail.data.model.SyncState.Error {
        val tags=raw.takeIf { it.startsWith("[") && it.contains("] ") }?.substringBefore(']')?.removePrefix("[")?.split(':')
        val kind=tags?.firstOrNull()?.let { name -> app.dot.mail.engine.ConnectionFailure.entries.firstOrNull { it.name==name } }
        val problem=tags?.getOrNull(1)?.let { name -> SyncProblem.entries.firstOrNull { it.name==name } }
        val role=tags?.getOrNull(2)?.takeIf { it.isNotBlank() }
        return app.dot.mail.data.model.SyncState.Error(if(kind==null) raw else raw.substringAfter("] "),auth || kind==app.dot.mail.engine.ConnectionFailure.AUTH || problem==SyncProblem.AUTH,kind,problem,role)
    }
    private fun chain(error:Throwable):List<Throwable> = app.dot.mail.engine.ConnectionFailure.chain(error)
    internal fun response(error: Throwable, secrets: Collection<String> = emptyList()): String? {
        val raw = error.message ?: return null
        val isProtocol = generateSequence(error.javaClass as Class<*>) { it.superclass }.any { it.name == "org.eclipse.angus.mail.iap.ProtocolException" }
        val isReply = error.javaClass == jakarta.mail.MessagingException::class.java && Regex("(?i)^(?:\\*?\\s*(?:[A-Z][0-9]+\\s+)?(?:NO|BAD|BYE)\\b|Failed to load IMAP envelope)").containsMatchIn(raw)
        if (!isProtocol && !isReply) return null
        var clean = raw.replace(Regex("[\\r\\n\\t]+"), " ")
        secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { clean = clean.replace(it, "<redacted>") }
        clean = clean.replace(Regex("(?i)[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9.-]+"), "<email>")
        clean = clean.replace(Regex("(?i)(?:Bearer\\s+|(?:password|access_token|refresh_token|token|authorization)[=:]\\s*)[^\\s]+"), "<credential>")
        clean = clean.replace(Regex("[A-Za-z0-9_+/=-]{32,}"), "<opaque>")
        return clean.take(200)
    }
    internal fun sanitized(error: Throwable, secrets: Collection<String> = emptyList()): Throwable {
        var result: Throwable? = null
        for (cause in chain(error).asReversed()) {
            result = RuntimeException("${cause.javaClass.simpleName}: ${reason(cause)}${response(cause, secrets)?.let { "; server=$it" }.orEmpty()}", result).apply { stackTrace = cause.stackTrace }
        }
        return requireNotNull(result)
    }
    fun warn(phase: String, error: Throwable, secrets: Collection<String> = emptyList()) {
        Log.w("DotMail/Sync", "$phase: ${reason(error)}", sanitized(error, secrets))
    }
}
