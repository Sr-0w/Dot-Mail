package app.dot.mail.ui.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.security.MessageDigest

/** Cached outcome for one key. [bitmap] == null means "looked, nothing there" (negative entry). */
class CacheEntry(val bitmap: Bitmap?)

/**
 * Two-level cache for avatar pictures: LRU in memory, files in cacheDir/avatars.
 * Positive entries live 7 days, negative ones (no picture exists) 1 day; the folder is capped at [MAX_DISK_BYTES].
 * Keys never leave the device; file names are hashes so addresses aren't readable from the folder.
 */
class AvatarCache(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val dir = File(context.cacheDir, "avatars")
    private val memory = object : LruCache<String, CacheEntry>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: CacheEntry) = (value.bitmap?.byteCount ?: 64).coerceAtLeast(64)
    }

    fun peek(key: String): CacheEntry? = memory.get(key)

    fun putMemoryOnly(key: String, bitmap: Bitmap?) { memory.put(key, CacheEntry(bitmap)) }

    /** Memory first, then disk. Null when unknown or expired. */
    fun get(key: String): CacheEntry? {
        memory.get(key)?.let { return it }
        val base = File(dir, name(key))
        val png = File(base.path + ".png")
        val neg = File(base.path + ".neg")
        val now = clock()
        return when {
            png.exists() && now - png.lastModified() < POSITIVE_TTL_MS ->
                BitmapFactory.decodeFile(png.path)?.let { CacheEntry(it).also { e -> memory.put(key, e) } }
            neg.exists() && now - neg.lastModified() < NEGATIVE_TTL_MS -> CacheEntry(null).also { memory.put(key, it) }
            else -> null
        }
    }

    fun put(key: String, bitmap: Bitmap?) {
        memory.put(key, CacheEntry(bitmap))
        runCatching {
            dir.mkdirs()
            val base = File(dir, name(key))
            File(base.path + ".png").delete()
            File(base.path + ".neg").delete()
            if (bitmap != null) File(base.path + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            else File(base.path + ".neg").writeBytes(ByteArray(0))
            prune()
        }
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        memory.evictAll()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun prune() {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_DISK_BYTES * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    private fun name(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)

    companion object {
        const val POSITIVE_TTL_MS = 7L * 24 * 3600 * 1000
        const val NEGATIVE_TTL_MS = 24L * 3600 * 1000
        const val MAX_DISK_BYTES = 8L * 1024 * 1024
    }
}
