package app.dot.mail.data

import android.content.Context
import android.net.Uri
import app.dot.mail.data.local.MailDao
import java.io.File

/** Reference-based orphan cleanup with a grace period for in-flight downloads and MIME preparation. */
internal class PrivateCacheCleanup(private val context: Context, private val dao: MailDao) {
    suspend fun run(now: Long = System.currentTimeMillis(), force: Boolean = false):Boolean {
        val prefs=context.getSharedPreferences("dotmail.cache.maintenance",Context.MODE_PRIVATE)
        if(!force && now-prefs.getLong("last",0)<3_600_000) return false
        val retained=hashSetOf<String>()
        val attachmentRoot=File(context.filesDir,"attachments").canonicalFile
        fun retainUri(raw:String) {
            val uri=Uri.parse(raw)
            if(uri.authority!="${context.packageName}.attachments" || uri.pathSegments.firstOrNull()!="mail_attachments") return
            val file=File(attachmentRoot,uri.pathSegments.drop(1).joinToString("/")).canonicalFile
            if(file.path.startsWith(attachmentRoot.path+File.separator)) retained+=file.path
        }
        dao.attachmentUris().forEach(::retainUri)
        var offset=0
        while(true) { val rows=dao.draftBatch(offset,20); if(rows.isEmpty()) break; rows.forEach { Codec.draft(it.json).attachmentUris.forEach(::retainUri) }; offset+=rows.size }
        dao.activeOutbox().forEach { row -> Codec.draft(row.draft).attachmentUris.forEach(::retainUri); row.mimePath?.let { path -> File(path).let { f -> if (f.isDirectory) f.walkTopDown().filter(File::isFile).forEach { retained+=it.canonicalPath } else retained+=f.canonicalPath } } }
        val bodyDirs=dao.bodyPaths().map { File(it).canonicalPath }.toSet()
        val cutoff=now-3_600_000
        for(name in listOf("attachments","outbox")) {
            val root=File(context.filesDir,name)
            root.walkBottomUp().forEach { file ->
                if(file.isFile && file.lastModified()<cutoff && file.canonicalPath !in retained) file.delete()
                else if(file.isDirectory && file!=root && file.list()?.isEmpty()==true) file.delete()
            }
        }
        val bodies=File(context.filesDir,"bodies")
        bodies.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }.forEach { directory ->
            if(directory.isDirectory && directory.canonicalPath !in bodyDirs && directory.lastModified()<cutoff) directory.deleteRecursively()
        }
        prefs.edit().putLong("last",now).apply()
        return true
    }
}
