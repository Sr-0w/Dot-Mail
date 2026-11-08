package app.dot.mail.data

import androidx.room.withTransaction
import app.dot.mail.data.local.*
import app.dot.mail.data.model.SearchIndexProgress
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup

/** Local-only bounded work, independent of the account/network synchronization mutex. */
internal class SearchIndex(private val graph:RealMailGraph) {
    private val lock=Mutex()
    @Volatile private var initialized=false
    val progress=MutableStateFlow(SearchIndexProgress())
    suspend fun ensure() { if(!initialized) lock.withLock { initialize() } }
    private suspend fun initialize() {
        if(initialized) return
        graph.database.withTransaction {
            val db=graph.database.openHelper.writableDatabase
            installSearchTriggers(db)
            db.execSQL("INSERT OR IGNORE INTO search_pending(messageId,revision) SELECT id,1 FROM messages WHERE id NOT IN(SELECT messageId FROM search_documents)")
        }
        initialized=true
        updateProgress(false)
    }
    private suspend fun updateProgress(running:Boolean,error:String?=null) {
        val total=graph.dao.searchMessageCount(); val pending=graph.dao.searchPendingCount()
        progress.value=SearchIndexProgress((total-pending).coerceAtLeast(0),total,running,error)
    }
    /** One batch yields to cancellation between documents, never projects legacy multi-MB body columns. */
    suspend fun drain(limit:Int=32):Int = withContext(Dispatchers.IO) { ensure(); lock.withLock {
        updateProgress(true)
        try {
            val pending=graph.dao.searchPending(limit.coerceIn(1,64))
            for(item in pending) {
                currentCoroutineContext().ensureActive()
                val row=graph.dao.message(item.messageId) ?: continue
                val attachments=graph.dao.attachments(row.id)
                val body=try { graph.bodyStore.read(row) } catch(e:java.io.IOException) { null }
                val plain=SearchText.prefix(body?.plainText.orEmpty(),TEXT_LIMIT)
                val html=body?.html?.let { raw -> Jsoup.parse(raw.take(512*1024)).apply { select("script,style,head,template,noscript").remove() }.body().text().let { SearchText.prefix(it,TEXT_LIMIT) } }.orEmpty()
                val text=when {
                    !row.bodyLoaded || (plain.isBlank() && html.isBlank()) -> row.snippet
                    plain.isBlank() -> html
                    html.isBlank() || plain==html -> plain
                    else -> {
                        // Reserve space for each MIME alternative; a long plain part must not hide HTML-only text.
                        val rich=SearchText.prefix(html,TEXT_LIMIT/2)
                        SearchText.prefix(plain,TEXT_LIMIT-rich.length-1)+"\n"+rich
                    }
                }.let { SearchText.prefix(it,TEXT_LIMIT) }
                val files=(attachments.filter { !it.inline }.map { it.name }+runCatching { Codec.strings(Codec.field(row.header,"attachmentNames") ?: "[]") }.getOrDefault(emptyList())).distinct().joinToString("\n") { SearchText.prefix(it,512) }.let { SearchText.prefix(it,8192) }
                val pdf=StringBuilder()
                for(attachment in attachments.filter { it.localUri!=null && it.mime.equals("application/pdf",true) }.take(4)) {
                    currentCoroutineContext().ensureActive()
                    if(pdf.length>=PDF_LIMIT) break
                    PdfText.extract(graph.context,attachment.localUri!!,PDF_LIMIT-pdf.length,20)?.let { pdf.append(it).append('\n') }
                }
                graph.database.withTransaction {
                    // A concurrent edit increments revision. Leave it queued even if this snapshot was just indexed.
                    if(graph.dao.message(row.id)!=null) {
                        graph.dao.putSearchDocument(SearchDocumentRow(row.id,text,files,SearchText.prefix(pdf.toString(),PDF_LIMIT)))
                        graph.dao.clearMessageFts(row.id)
                        graph.dao.putMessageFts(MessageFtsRow(row.id,SearchText.normalize(SearchText.prefix(row.subject,4096)),SearchText.normalize(SearchText.prefix(row.senderSearch,8192)),SearchText.normalize(SearchText.prefix(row.recipientSearch,16384)),SearchText.normalize(text),SearchText.normalize(files),SearchText.normalize(SearchText.prefix(pdf.toString(),PDF_LIMIT))))
                        graph.dao.finishSearchIndex(row.id,item.revision)
                    }
                }
            }
            updateProgress(false)
            graph.dao.searchPendingCount()
        } catch(e:CancellationException) { withContext(NonCancellable) { updateProgress(false) }; throw e }
        catch(e:Exception) { updateProgress(false,"Search indexing interrupted. Retrying automatically."); throw e }
    } }
    companion object { const val TEXT_LIMIT=64*1024; const val PDF_LIMIT=32*1024 }
}

class SearchIndexWorker(context:android.content.Context,parameters:androidx.work.WorkerParameters):androidx.work.CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result = try {
        val index=GraphRegistry.get(applicationContext).searchIndex
        var remaining=0
        repeat(8) { remaining=index.drain(); if(remaining==0) return Result.success(); yield() }
        Result.retry()
    } catch(e:CancellationException) { throw e }
    catch(e:Exception) { Result.retry() }
}
