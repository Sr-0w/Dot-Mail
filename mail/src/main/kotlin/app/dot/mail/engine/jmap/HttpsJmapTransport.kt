package app.dot.mail.engine.jmap

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * No redirect following or custom TLS trust. The internal factory is solely a fixture-server seam.
 */
class HttpsJmapTransport
internal constructor(
    private val timeoutMs: Int,
    private val connectionFactory: (URI) -> HttpURLConnection,
) : JmapHttpTransport {
    constructor(
        timeoutMs: Int = 15_000
    ) : this(timeoutMs, { it.toURL().openConnection() as HttpURLConnection })

    init {
        require(timeoutMs in 1..120_000)
    }

    override suspend fun execute(request: JmapHttpRequest): JmapHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<HttpURLConnection?>()
            val task =
                FutureTask<Unit> {
                    try {
                        if (!continuation.isActive) return@FutureTask Unit
                        if (
                            !request.uri.scheme.equals("https", true) ||
                                request.uri.host == null ||
                                request.uri.userInfo != null ||
                                request.uri.fragment != null
                        )
                            throw JmapException(JmapErrorKind.ENDPOINT)
                        if (
                            request.maxResponseBytes !in 1..2 * 1024 * 1024 ||
                                (request.body?.size ?: 0) > 1024 * 1024
                        )
                            throw JmapException(JmapErrorKind.LIMIT)
                        val connection = connectionFactory(request.uri)
                        active.set(connection)
                        if (!continuation.isActive) return@FutureTask Unit
                        connection.instanceFollowRedirects = false
                        connection.useCaches = false
                        connection.connectTimeout = timeoutMs
                        connection.readTimeout = timeoutMs
                        connection.requestMethod = request.method
                        request.headers.forEach { (name, value) ->
                            connection.setRequestProperty(name, value)
                        }
                        request.body?.let { bytes ->
                            connection.doOutput = true
                            connection.setFixedLengthStreamingMode(bytes.size)
                            connection.outputStream.use { it.write(bytes) }
                        }
                        val status = connection.responseCode
                        val contentType = connection.contentType
                        // Error payloads are neither needed nor retained (they can echo
                        // credentials/content).
                        val bytes =
                            if (status == 200) {
                                val length = connection.contentLengthLong
                                if (length > request.maxResponseBytes)
                                    throw JmapException(JmapErrorKind.LIMIT)
                                connection.inputStream.use { input ->
                                    val result =
                                        ByteArrayOutputStream(minOf(request.maxResponseBytes, 8192))
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        if (!continuation.isActive) return@FutureTask Unit
                                        val count =
                                            input.read(
                                                buffer,
                                                0,
                                                minOf(
                                                    buffer.size,
                                                    request.maxResponseBytes - result.size() + 1,
                                                ),
                                            )
                                        if (count < 0) break
                                        if (result.size() + count > request.maxResponseBytes)
                                            throw JmapException(JmapErrorKind.LIMIT)
                                        result.write(buffer, 0, count)
                                    }
                                    result.toByteArray()
                                }
                            } else byteArrayOf()
                        continuation.resume(JmapHttpResponse(status, bytes, contentType))
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    } finally {
                        active.getAndSet(null)?.disconnect()
                    }
                }
            continuation.invokeOnCancellation {
                active.getAndSet(null)?.disconnect()
                task.cancel(true)
            }
            try {
                executor.execute(task)
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                continuation.resumeWithException(JmapException(JmapErrorKind.TRANSIENT))
            }
        }

    companion object {
        private val executor =
            ThreadPoolExecutor(
                    4,
                    4,
                    60,
                    TimeUnit.SECONDS,
                    ArrayBlockingQueue(64),
                    { task -> Thread(task, "DotMail-JMAP").apply { isDaemon = true } },
                )
                .apply { allowCoreThreadTimeOut(true) }
    }
}
