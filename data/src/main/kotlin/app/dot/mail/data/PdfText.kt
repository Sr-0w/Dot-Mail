package app.dot.mail.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri

/** On-device only. Call off the main thread; unsupported/protected/image-only documents return null. */
object PdfText {
    val supported:Boolean get()=android.os.Build.VERSION.SDK_INT>=35 || (android.os.Build.VERSION.SDK_INT>=31 && android.os.ext.SdkExtensions.getExtensionVersion(31)>=13)
    @android.annotation.SuppressLint("NewApi")
    fun extract(context:Context,uri:String,maxChars:Int=500_000,maxPages:Int=200):String? {
        if(!supported || maxChars<=0 || maxPages<=0) return null
        return runCatching {
            context.contentResolver.openFileDescriptor(Uri.parse(uri),"r")?.use { fd -> PdfRenderer(fd).use { pdf ->
                val result=StringBuilder()
                for(i in 0 until minOf(pdf.pageCount,maxPages)) {
                    if(result.length>=maxChars) break
                    pdf.openPage(i).use { page -> page.textContents.forEach { part ->
                        if(result.length<maxChars) result.append(part.text.take(maxChars-result.length))
                    } }
                    if(result.length<maxChars) result.append("\n\n".take(maxChars-result.length))
                }
                result.toString().trim().ifBlank { null }
            } }
        }.getOrNull()
    }
}
