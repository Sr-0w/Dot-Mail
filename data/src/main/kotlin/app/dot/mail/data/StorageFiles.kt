package app.dot.mail.data

import android.net.Uri
import app.dot.mail.data.local.*
import org.json.JSONArray
import org.json.JSONTokener
import java.io.File

internal data class ProtectedCacheFiles(val files:Set<String>,val uncertainAccounts:Set<Long>)
internal class StorageFiles(private val graph:RealMailGraph) {
    private val root=graph.context.filesDir.canonicalFile
    fun uriFile(raw:String?):File? {
        if(raw==null) return null
        val uri=Uri.parse(raw)
        if(uri.scheme!="content" || uri.authority!="${graph.context.packageName}.attachments" || uri.pathSegments.firstOrNull()!="mail_attachments") return null
        val folder=File(root,"attachments").canonicalFile
        val file=File(folder,uri.pathSegments.drop(1).joinToString("/")).canonicalFile
        return file.takeIf { it.path.startsWith(folder.path+File.separator) }
    }
    fun size(file:File):Long {
        if(!file.exists()) return 0
        if(file.isFile) return file.length()
        return java.nio.file.Files.walk(file.toPath()).use { paths -> paths.filter { java.nio.file.Files.isRegularFile(it,java.nio.file.LinkOption.NOFOLLOW_LINKS) }.mapToLong { java.nio.file.Files.size(it) }.sum() }
    }
    suspend fun protectedFiles():ProtectedCacheFiles {
        val files=hashSetOf<String>(); val uncertain=hashSetOf<Long>()
        suspend fun collect(fetch:suspend (Long)->List<CacheReferenceRow>) {
            var after=0L
            while(true) {
                val rows=fetch(after); if(rows.isEmpty()) break
                for(row in rows) {
                    try {
                        val value=JSONTokener(row.attachmentJson.substringAfter(':')).nextValue()
                        val array=if(value is String) JSONArray(value) else value as JSONArray
                        repeat(array.length()) { uriFile(array.getString(it))?.let { f -> if(files.size<10000) files+=f.path else uncertain+=row.accountId } }
                    } catch(_:Exception) { uncertain+=row.accountId }
                }
                after=rows.last().id
            }
        }
        collect(graph.dao::draftCacheReferences); collect(graph.dao::outboxCacheReferences)
        return ProtectedCacheFiles(files,uncertain)
    }
    suspend fun deleteBodyIfUnreferenced(path:String?) {
        if(path==null || graph.dao.bodyPathReferenced(path)) return
        val file=File(path).canonicalFile; val bodyRoot=File(root,"bodies").canonicalFile
        if(file.path.startsWith(bodyRoot.path+File.separator)) file.deleteRecursively()
    }
    suspend fun deleteAttachmentIfUnreferenced(raw:String?,protected:ProtectedCacheFiles) {
        if(raw==null || graph.dao.attachmentUriReferenced(raw)) return
        val file=uriFile(raw) ?: return
        if(file.path !in protected.files) { file.delete(); file.parentFile?.takeIf { it.list()?.isEmpty()==true }?.delete() }
    }
    fun deleteCreated(file:File) {
        val folder=File(root,"attachments").canonicalFile
        if(!file.canonicalPath.startsWith(folder.path+File.separator)) return
        file.delete()
        var parent=file.parentFile
        while(parent!=null && parent.canonicalPath.startsWith(folder.path+File.separator)) {
            if(java.nio.file.Files.newDirectoryStream(parent.toPath()).use { it.iterator().hasNext() }) break
            parent.delete(); parent=parent.parentFile
        }
    }
    fun exists(raw:String?):Boolean=uriFile(raw)?.isFile==true
}
