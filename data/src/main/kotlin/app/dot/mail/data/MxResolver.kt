package app.dot.mail.data

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import app.dot.mail.engine.autoconfig.MxResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Minimal DNS wire-format reader: just enough to extract MX records from a response. */
internal object DnsWire {
    const val TYPE_MX = 15
    const val CLASS_IN = 1

    /** MX exchange hosts of a DNS response, lowest preference first. Empty on error/truncation/NXDOMAIN. */
    fun parseMx(msg: ByteArray): List<String> = try {
        parse(msg)
    } catch (e: IndexOutOfBoundsException) {
        emptyList()
    } catch (e: IllegalArgumentException) {
        emptyList()
    }

    private fun parse(m: ByteArray): List<String> {
        if (m.size < 12) return emptyList()
        val flags = u16(m, 2)
        if (flags and 0x8000 == 0) return emptyList() // not a response
        if (flags and 0x000F != 0) return emptyList() // NXDOMAIN, SERVFAIL, ...
        val qd = u16(m, 4)
        val an = u16(m, 6)
        var p = 12
        repeat(qd) { p = skipName(m, p) + 4 }
        val found = mutableListOf<Pair<Int, String>>()
        repeat(an) {
            p = skipName(m, p)
            val type = u16(m, p)
            val rdLen = u16(m, p + 8)
            val rd = p + 10
            if (type == TYPE_MX && rdLen >= 3) {
                val host = readName(m, rd + 2).trimEnd('.').lowercase()
                if (host.isNotEmpty()) found += u16(m, rd) to host
            }
            p = rd + rdLen
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    private fun u16(m: ByteArray, i: Int) = ((m[i].toInt() and 0xFF) shl 8) or (m[i + 1].toInt() and 0xFF)

    /** Position after the name that starts at [start] (a compression pointer ends it). */
    private fun skipName(m: ByteArray, start: Int): Int {
        var p = start
        while (true) {
            val len = m[p].toInt() and 0xFF
            when {
                len == 0 -> return p + 1
                len and 0xC0 == 0xC0 -> return p + 2
                else -> p += 1 + len
            }
        }
    }

    /** Reads a possibly compressed name; pointer loops are cut off. */
    private fun readName(m: ByteArray, start: Int): String {
        val sb = StringBuilder()
        var p = start
        var jumps = 0
        while (true) {
            val len = m[p].toInt() and 0xFF
            when {
                len == 0 -> return sb.toString()
                len and 0xC0 == 0xC0 -> {
                    require(++jumps <= 16) { "compression loop" }
                    p = ((len and 0x3F) shl 8) or (m[p + 1].toInt() and 0xFF)
                }
                len and 0xC0 != 0 -> throw IllegalArgumentException("bad label")
                else -> {
                    sb.append(String(m, p + 1, len, Charsets.US_ASCII)).append('.')
                    p += 1 + len
                }
            }
        }
    }
}

/**
 * [MxResolver] on top of the platform resolver (honours the device's DNS/Private DNS settings, no
 * extra dependency). API 29+; returns an empty list on older versions, on errors and after [timeoutMs].
 * Blocking: call from a background thread (AutoDiscovery already does).
 */
class AndroidMxResolver(private val timeoutMs: Long = 4_000) : MxResolver {
    override fun lookup(domain: String): List<String> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        return query(domain)
    }

    @androidx.annotation.RequiresApi(29)
    private fun query(domain: String): List<String> {
        val latch = CountDownLatch(1)
        val result = AtomicReference<ByteArray?>(null)
        val cancel = CancellationSignal()
        val executor = Executors.newSingleThreadExecutor()
        try {
            DnsResolver.getInstance().rawQuery(
                null, domain, DnsWire.CLASS_IN, DnsWire.TYPE_MX, DnsResolver.FLAG_EMPTY, executor, cancel,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) { result.set(answer); latch.countDown() }
                    override fun onError(error: DnsResolver.DnsException) { latch.countDown() }
                },
            )
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) cancel.cancel()
        } catch (e: Exception) {
            return emptyList()
        } finally {
            executor.shutdown()
        }
        return result.get()?.let(DnsWire::parseMx).orEmpty()
    }
}
