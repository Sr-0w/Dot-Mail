package app.dot.mail.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.dot.mail.engine.OutgoingAttachment
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Optional inline logo of a signature: stored locally (never uploaded) and sent as a `cid:` image in a
 * `multipart/related` part next to the HTML body.
 */
object SignatureLogos {
    /** Content-ID referenced by the signature block (`<img src="cid:dotsig-logo">`). */
    const val CID = "dotsig-logo"
    private const val MAX_WIDTH = 320

    fun file(context: Context, name: String): File = File(File(context.filesDir, "signature-logos"), File(name).name)

    /** Decodes [bytes], downsizes to a signature-friendly width and stores a PNG. Returns the stored file name, or null if not an image. */
    fun save(context: Context, bytes: ByteArray, key: String): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val bitmap = if (decoded.width > MAX_WIDTH) {
            Bitmap.createScaledBitmap(decoded, MAX_WIDTH, (decoded.height * MAX_WIDTH.toFloat() / decoded.width).toInt().coerceAtLeast(1), true)
        } else decoded
        val out = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val name = "$key-${System.currentTimeMillis()}.png"
        val target = file(context, name)
        target.parentFile?.mkdirs()
        target.writeBytes(out.toByteArray())
        return name
    }

    fun delete(context: Context, name: String?) {
        if (name != null) runCatching { file(context, name).delete() }
    }

    private val OWNER = Regex("data-dot-sig=\"(\\d+)\"")

    /**
     * The inline attachment to send when [html] references [CID]. The logo belongs to the identity whose signature
     * block the composer embedded (its marker), falling back to the sending [identity].
     */
    fun attachmentsFor(context: Context, html: String?, identities: List<app.dot.mail.data.model.Identity>, identity: app.dot.mail.data.model.Identity): List<OutgoingAttachment> {
        if (html == null || !html.contains("cid:$CID")) return emptyList()
        val owner = OWNER.find(html)?.groupValues?.get(1)?.toLongOrNull()
        val logo = (identities.firstOrNull { it.id == owner } ?: identity).signatureLogo ?: return emptyList()
        val f = file(context, logo).takeIf { it.isFile } ?: return emptyList()
        return listOf(OutgoingAttachment("logo.png", "image/png", f.readBytes(), contentId = CID))
    }
}
