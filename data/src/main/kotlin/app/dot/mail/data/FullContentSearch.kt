package app.dot.mail.data

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.paging.*
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.ZoneOffset

internal class FullContentSearch(private val graph:RealMailGraph) {
    private data class ServerState(val running:Boolean=false,val available:Boolean=false,val error:String?=null,val limited:Boolean=false)
    private val states=linkedMapOf<String,MutableStateFlow<ServerState>>()
    private val serverSlots=Semaphore(2)
    private fun state(query:String,scope:SearchScope):MutableStateFlow<ServerState> = synchronized(states) {
        val key=hash(query,scope)
        if(states.size>=32) {
            val old=states.entries.firstOrNull { it.key!=key && it.value.subscriptionCount.value==0 && !it.value.value.running }
            if(old!=null) { states.remove(old.key); graph.scope.launch { graph.dao.clearSearchMatches(old.key) } }
        }
        states.getOrPut(key) { MutableStateFlow(ServerState()) }
    }
    fun hash(query:String,scope:SearchScope):String = java.security.MessageDigest.getInstance("SHA-256").digest("${scope.accountIds.sorted()}:${scope.folderIds.sorted()}:$query".toByteArray()).joinToString("") { "%02x".format(it) }
    fun progress(query:String,scope:SearchScope):Flow<SearchProgress> = combine(graph.searchIndex.progress,state(query,scope)) { index,server -> SearchProgress(index,server.running,server.available,server.error,server.limited) }
    fun search(query:String,scope:SearchScope):Flow<PagingData<SearchResult>> = flow {
        require(query.length<=4096) { "Search is too long" }
        graph.searchIndex.ensure()
        if(graph.scheduling) WorkScheduling.searchIndex(graph.context)
        // Angus socket reads can finish only at their timeout. The graph owns those bounded jobs so
        // flatMapLatest can immediately show the next local query instead of awaiting an old socket.
        val index=if(!graph.scheduling) graph.scope.launch { try { while(graph.searchIndex.drain()>0) yield() } catch(e:CancellationException) { throw e } catch(_:Exception) { /* progress already reports the indexing error */ } } else null
        val server=graph.scope.launch { serverSearch(query,scope) }
        try {
            emitAll(Pager(PagingConfig(pageSize=20,initialLoadSize=20,enablePlaceholders=false)) { source(query,scope) }.flow.map { page -> page.map { row -> result(row,query) } })
        } finally { server.cancel(); index?.cancel() }
    }.flowOn(Dispatchers.IO)
    internal suspend fun result(row:IndexedSearchRow,query:String):SearchResult {
        val values=MailSearch.parse(query).terms.filter { it.field==null || it.field in setOf("from","to","subject") }.map { it.value }
        val document=graph.dao.searchDocument(row.messageId)
        val candidates=listOf(document?.body ?: row.preview,document?.files.orEmpty(),document?.attachmentText.orEmpty())
        val original=candidates.firstOrNull { SearchText.highlights(it,values).isNotEmpty() } ?: (document?.body ?: row.preview)
        val (snippet,ranges)=SearchText.excerpt(original,values)
        val thread=graph.dao.threadRow(row.threadId)?.let { graph.summary(it) } ?: ThreadSummary(row.threadId,0,row.subject,emptyList(),"",0,0,0,false,false)
        return SearchResult(thread,row.messageId,row.folderId,row.subject,SearchText.highlights(row.subject,values),snippet,ranges,row.bodyAvailable,row.serverOnly)
    }
    internal fun source(query:String,scope:SearchScope):PagingSource<Int,IndexedSearchRow> = graph.dao.searchPage(sql(query,scope))
    internal fun sql(query:String,scope:SearchScope):SimpleSQLiteQuery {
        val terms=MailSearch.parse(query).terms.take(64)
        val args=ArrayList<Any>(); val filters=ArrayList<String>(); val expressions=ArrayList<Pair<String,String>>()
        fun values(column:String,ids:Set<Long>) { if(ids.isNotEmpty()) { filters+="$column IN (${ids.joinToString(",") { "?" }})"; args.addAll(ids.sorted()) } }
        values("m.accountId",scope.accountIds); values("m.folderId",scope.folderIds)
        fun like(value:String)="%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%"
        for(term in terms) when(term.field) {
            "is" -> filters+=when(term.value.lowercase()) { "unread"->"m.unread=1"; "read"->"m.unread=0"; else->"m.starred=1" }
            "has" -> filters+="m.hasAttachments=1"
            "folder" -> { filters+="(f.name LIKE ? ESCAPE '!' OR f.path LIKE ? ESCAPE '!')"; args+=like(term.value); args+=like(term.value) }
            "before","after","since" -> { filters+="m.sentAt ${if(term.field=="before") "<" else ">="} ?"; args+=LocalDate.parse(term.value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
            else -> (SearchText.match(term.value,!term.quoted) ?: "\"\"").let { match -> expressions+=(when(term.field) { "subject"->"subject"; "from"->"sender"; "to"->"recipients"; else->"message_fts" }) to match }
        }
        val match=expressions.joinToString(" AND ") { it.second }
        // MATCH is confined to a subquery; SQLite's virtual-table planner rejects MATCH nested in OR.
        val local=expressions.joinToString(" AND ") { (column,_) -> "m.id IN(SELECT rowid FROM message_fts WHERE $column MATCH ?)" }.ifBlank { "1" }
        val hash=hash(query,scope)
        val localArgs=expressions.map { it.second }
        val predicateArgs=args.toList()+if(expressions.isEmpty()) emptyList() else localArgs+hash
        val candidates=if(expressions.isEmpty()) "1" else "m.id IN("+expressions.joinToString(" INTERSECT ") { (column,_) -> "SELECT rowid FROM message_fts WHERE $column MATCH ?" }+" UNION SELECT messageId FROM search_matches WHERE queryHash=?)"
        // Feed the outer query candidate rowids. OR with a correlated EXISTS would scan every cached message.
        val predicate=(filters+candidates).joinToString(" AND ").ifBlank { "1" }
        val boosts=ArrayList<String>(); val boostArgs=ArrayList<Any>()
        if(scope.sort==SearchSort.RELEVANCE && match.isNotBlank()) {
            for((column,weight) in listOf("subject" to 100,"sender" to 35,"recipients" to 20,"files" to 45)) {
                val free=terms.filter { it.field==null || (column=="subject" && it.field=="subject") }.mapNotNull { SearchText.match(it.value,!it.quoted) }
                if(free.isNotEmpty()) { boosts+="CASE WHEN m.id IN(SELECT rowid FROM message_fts WHERE $column MATCH ?) THEN $weight ELSE 0 END"; boostArgs+=free.joinToString(" OR ") }
            }
            boosts+="CASE WHEN m.sentAt>? THEN 20 ELSE 0 END"; boostArgs+=System.currentTimeMillis()-30L*86400000
        }
        val score=boosts.joinToString("+").ifBlank { "0" }
        val date=if(scope.sort==SearchSort.OLDEST) "999999999999999-MAX(0,MIN(m.sentAt,999999999999999))" else "MAX(0,MIN(m.sentAt,999999999999999))"
        // Exactly one MAX aggregate, with a unique message-id tie-break: SQLite's bare columns belong to that winner.
        val rank="MAX(printf('%03d:%015d:%019d',($score),$date,m.id))"
        val winners="SELECT m.id AS messageId,$rank AS rankKey FROM messages m JOIN folders f ON f.id=m.folderId WHERE $predicate GROUP BY m.threadId"
        // Rank/deduplicate narrow rows. Body/PDF excerpts load only for the emitted page, never into a sort buffer.
        val sql="SELECT m.id AS messageId,m.threadId,m.folderId,substr(m.subject,1,4096) AS subject,m.snippet AS preview,m.bodyLoaded AS bodyAvailable,CASE WHEN ($local) THEN 0 ELSE 1 END AS serverOnly,hits.rankKey FROM ($winners) hits JOIN messages m ON m.id=hits.messageId ORDER BY hits.rankKey DESC"

        return SimpleSQLiteQuery(sql,(localArgs+boostArgs+predicateArgs).toTypedArray())
    }
    private fun online():Boolean {
        val manager=graph.context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities=manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    internal suspend fun serverSearch(query:String,scope:SearchScope,networkAvailable:Boolean=online()) {
        val status=state(query,scope)
        if(!scope.includeServer || !networkAvailable || query.isBlank()) { status.value=ServerState(available=networkAvailable && scope.includeServer); return }
        status.value=ServerState(running=true,available=true)
        try {
            withTimeout(60000) {
                val hash=hash(query,scope); graph.dao.clearSearchMatches(hash)
                val semaphore=serverSlots
                coroutineScope {
                    graph.dao.accounts().filter { scope.accountIds.isEmpty() || it.id in scope.accountIds }.forEach { account ->
                        val config=Codec.config(account.config); val credentials=graph.credentials(account)
                        graph.client.withImapSession(config.imap.endpoint(),credentials) {
                            graph.dao.folders(account.id).filter { scope.folderIds.isEmpty() || it.id in scope.folderIds }.filter { folder -> MailSearch.parse(query).terms.filter { it.field=="folder" }.all { folder.path.contains(it.value,true)||folder.name.contains(it.value,true) } }.map { folder -> async {
                                semaphore.withPermit {
                                    try {
                                        val batch=graph.client.searchBatch(config.imap.endpoint(),credentials,folder.path,query,200)
                                        if(batch.limited) status.update { it.copy(limited=true) }
                                        graph.database.withTransaction {
                                            var current=graph.dao.folder(folder.id) ?: return@withTransaction
                                            if(current.validity!=0L && current.validity!=batch.uidValidity) throw IllegalStateException("Folder changed during search")
                                            if(current.validity==0L) { current=current.copy(validity=batch.uidValidity); graph.dao.updateFolder(current) }
                                            val changedThreads=linkedSetOf<Long>()
                                            batch.headers.forEach { header -> changedThreads+=graph.upsertHeader(current,header,rebuild=false).first; graph.dao.byUid(folder.id,header.uid)?.let { graph.dao.putSearchMatch(SearchMatchRow(hash,it.id)) } }
                                            changedThreads.forEach { graph.rebuildThread(it) }
                                        }
                                    } catch(e:CancellationException) { throw e }
                                    catch(e:Exception) { status.update { it.copy(error=SyncDiagnostics.reason(e)) } }
                                }
                            } }.awaitAll()
                        }
                    }
                }
                if(graph.scheduling) WorkScheduling.searchIndex(graph.context)
            }
        } catch(e:TimeoutCancellationException) { status.update { it.copy(error="Server search timed out; local results are available") } }
        catch(e:CancellationException) { throw e }
        catch(e:Exception) { status.update { it.copy(error=SyncDiagnostics.reason(e)) } }
        finally { status.update { it.copy(running=false) }; withContext(NonCancellable) { try { graph.dao.pruneSearchMatches() } catch(_:Exception) { /* bounded cleanup retries on the next search */ } } }
    }
}
