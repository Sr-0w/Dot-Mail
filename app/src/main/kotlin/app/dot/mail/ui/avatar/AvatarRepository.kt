package app.dot.mail.ui.avatar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import app.dot.mail.data.AndroidTxtResolver
import app.dot.mail.data.TxtResolver
import app.dot.mail.engine.autoconfig.registrableDomain
import app.dot.mail.data.model.EmailAddress
import com.caverock.androidsvg.SVG
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Finds a picture for a sender, trying the enabled sources in order: contact photo (local), Gravatar, BIMI brand
 * logo, website icon. Everything runs off the main thread, concurrently-asked addresses are de-duplicated, network
 * work is limited to a few parallel requests, and every outcome (also "nothing found") is cached, see [AvatarCache].
 * WebView is never involved: pictures are fetched with [SafeHttp] and decoded to a small bitmap.
 */
class AvatarRepository private constructor(
    private val context: Context,
    private val cache: AvatarCache,
    private val txt: TxtResolver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<AvatarImage?>>()
    private val network = Semaphore(4)

    /** Synchronous, memory-only: the picture if it's already known, so lists don't flash initials while scrolling. */
    fun peek(address: EmailAddress, sources: AvatarSources): AvatarImage? = order(address, sources).firstNotNullOfOrNull { step ->
        cache.peek(step.key)?.bitmap?.let { AvatarImage(it, step.plated) }
    }

    suspend fun load(address: EmailAddress, sources: AvatarSources): AvatarImage? {
        if (!sources.any) return null
        val email = address.email.trim().lowercase()
        if ('@' !in email) return null
        val id = "$email|${sources.contacts}${sources.gravatar}${sources.brandLogos}${sources.websiteIcons}"
        return inFlight.getOrPut(id) {
            scope.async {
                try { resolve(address, sources) } finally { inFlight.remove(id) }
            }
        }.await()
    }

    fun cacheSizeBytes() = cache.sizeBytes()
    fun clearCache() = cache.clear()

    private class Step(val key: String, val plated: Boolean, val fetch: () -> Bitmap?, val network: Boolean)

    private fun order(address: EmailAddress, sources: AvatarSources): List<Step> {
        val email = address.email.trim().lowercase()
        val domain = email.substringAfter('@', "")
        if (domain.isEmpty()) return emptyList()
        val org = registrableDomain(domain)
        val free = FreeMail.isFreeMail(domain)
        return buildList {
            if (sources.contacts && hasContactsPermission()) add(Step("c:$email", false, { contactPhoto(email) }, network = false))
            if (sources.gravatar) add(Step("g:${sha256(email)}", false, { gravatar(email) }, network = true))
            if (sources.brandLogos && !free) add(Step("b:$org", true, { brandLogo(domain, org) }, network = true))
            if (sources.websiteIcons && !free) add(Step("w:$org", true, { websiteIcon(org) }, network = true))
        }
    }

    private suspend fun resolve(address: EmailAddress, sources: AvatarSources): AvatarImage? {
        for (step in order(address, sources)) {
            val cached = cache.get(step.key)
            if (cached != null) {
                cached.bitmap?.let { return AvatarImage(it, step.plated) }
                continue
            }
            val bmp = if (step.network) network.withPermit { runCatching { step.fetch() }.getOrNull() } else runCatching { step.fetch() }.getOrNull()
            // Contact photos stay in memory only: a copy of the address book doesn't belong in a disk cache.
            if (step.key.startsWith("c:")) cache.putMemoryOnly(step.key, bmp) else cache.put(step.key, bmp)
            if (bmp != null) return AvatarImage(bmp, step.plated)
        }
        return null
    }

    // ---- sources ---------------------------------------------------------------------------------------------

    private fun hasContactsPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun contactPhoto(email: String): Bitmap? {
        val email0 = ContactsContract.CommonDataKinds.Email.CONTENT_URI
        val uri = context.contentResolver.query(
            email0, arrayOf(ContactsContract.CommonDataKinds.Email.PHOTO_THUMBNAIL_URI),
            "LOWER(${ContactsContract.CommonDataKinds.Email.ADDRESS}) = ?", arrayOf(email), null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return null
        val bytes = context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() } ?: return null
        return decode(bytes, minSide = 1)
    }

    private fun gravatar(email: String): Bitmap? {
        val f = SafeHttp.get("https://gravatar.com/avatar/${sha256(email)}?s=128&d=404") ?: return null
        return decode(f.bytes, minSide = 1)
    }

    private fun brandLogo(domain: String, org: String): Bitmap? {
        val names = listOf(domain, org).distinct().map { "default._bimi.$it" }
        for (name in names) {
            val record = txt.lookup(name).firstOrNull { it.trimStart().startsWith("v=BIMI1", ignoreCase = true) } ?: continue
            val logo = record.split(';').map { it.trim() }.firstOrNull { it.startsWith("l=", ignoreCase = true) }
                ?.substring(2)?.trim()?.takeIf { it.startsWith("https://") } ?: continue
            val f = SafeHttp.get(logo, maxBytes = 256 * 1024, accept = "image/svg+xml") ?: continue
            renderSvg(f.bytes)?.let { return it }
        }
        return null
    }

    private fun websiteIcon(org: String): Bitmap? {
        for (path in listOf("apple-touch-icon.png", "favicon.ico")) {
            val f = SafeHttp.get("https://$org/$path") ?: continue
            decode(f.bytes, minSide = 32)?.let { return it }
        }
        return null
    }

    // ---- decoding ---------------------------------------------------------------------------------------------

    private fun decode(bytes: ByteArray, minSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < minSide || bounds.outHeight < minSide) return null
        if (bounds.outWidth > 4096 || bounds.outHeight > 4096) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val side = minOf(raw.width, raw.height)
        // Centre-crop to a square so photos fill the circle; scale down to SIZE.
        val square = Bitmap.createBitmap(raw, (raw.width - side) / 2, (raw.height - side) / 2, side, side)
        return if (side > SIZE) Bitmap.createScaledBitmap(square, SIZE, SIZE, true) else square
    }

    private fun renderSvg(bytes: ByteArray): Bitmap? = try {
        val svg = SVG.getFromInputStream(bytes.inputStream())
        svg.setDocumentWidth(SIZE.toFloat())
        svg.setDocumentHeight(SIZE.toFloat())
        Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { svg.renderToCanvas(Canvas(it)) }
    } catch (e: Exception) {
        null
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val SIZE = 128

        @Volatile private var instance: AvatarRepository? = null
        fun get(context: Context): AvatarRepository = instance ?: synchronized(this) {
            instance ?: AvatarRepository(context.applicationContext, AvatarCache(context.applicationContext), AndroidTxtResolver())
                .also { instance = it }
        }
    }
}
