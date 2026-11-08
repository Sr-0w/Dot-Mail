package app.dot.mail.data.local

import androidx.paging.PagingSource
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "accounts")
data class AccountRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val email: String, val name: String, val color: Int, val config: String, val secret: String, val syncError: String? = null, @ColumnInfo(defaultValue = "0") val authError: Boolean = false)
@Entity(tableName = "folders", foreignKeys = [ForeignKey(entity = AccountRow::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["accountId", "path"], unique = true)])
data class FolderRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val accountId: Long, val path: String, val name: String, val role: String, val unread: Int = 0, val total: Int = 0, val validity: Long = 0, val nextUid: Long = 1, val modSeq: Long = -1)
@Entity(tableName = "threads", foreignKeys = [ForeignKey(entity = AccountRow::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)], indices = [Index("accountId"), Index(value = ["lastAt", "id"])])
data class ThreadRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val accountId: Long, val subject: String, val participants: String = "[]", val snippet: String = "", val lastAt: Long = 0, val count: Int = 0, val unread: Int = 0, val starred: Boolean = false, val attachments: Boolean = false)
@Entity(tableName = "thread_keys", primaryKeys = ["accountId", "key"], foreignKeys = [ForeignKey(entity = ThreadRow::class, parentColumns = ["id"], childColumns = ["threadId"], onDelete = ForeignKey.CASCADE)], indices = [Index("threadId")])
data class ThreadKeyRow(val accountId: Long, val key: String, val threadId: Long)
@Entity(tableName = "messages", foreignKeys = [ForeignKey(entity = FolderRow::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = ThreadRow::class, parentColumns = ["id"], childColumns = ["threadId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["folderId", "uid"], unique = true), Index("threadId"), Index("accountId"), Index(value = ["threadId", "sentAt"]), Index(value = ["folderId", "messageIdKey"]), Index("bodyPath"),Index(value=["accountId","sentAt","id"])])
data class MessageRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val accountId: Long, val folderId: Long, val threadId: Long, val uid: Long, val header: String, val subject: String, val sentAt: Long, val unread: Boolean, val starred: Boolean, val hasAttachments: Boolean, val snippet: String = "", val text: String? = null, val html: String? = null, val bodyLoaded: Boolean = false, val remoteImages: Boolean = false, val senderSearch: String = "", val recipientSearch: String = "", @ColumnInfo(defaultValue = "0") val snippetFetched: Boolean = false, val bodyPath: String? = null, val messageIdKey: String? = null)
/** Just enough of a message to re-run classification rules. */
data class HeaderOnly(val id: Long, val subject: String, val header: String)
@Entity(tableName = "attachments", foreignKeys = [ForeignKey(entity = MessageRow::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)], indices = [Index("messageId"),Index("localUri")])
data class AttachmentRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val messageId: Long, val partId: String, val name: String, val mime: String, val size: Long, val cid: String?, val inline: Boolean, val localUri: String?)
@Entity(tableName = "drafts", foreignKeys = [ForeignKey(entity = AccountRow::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)], indices = [Index("accountId"), Index(value = ["sourceMessageId"], unique = true)])
data class DraftRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val accountId: Long, val json: String, @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0, val sourceMessageId: Long? = null)
@Entity(tableName = "draft_mirrors", foreignKeys = [ForeignKey(entity = AccountRow::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)], indices = [Index("accountId")])
data class DraftMirrorRow(@PrimaryKey val localId: Long, val accountId: Long, val token: String, val revision: Long, val syncedRevision: Long = 0, val dueAt: Long, val folderId: Long? = null, val validity: Long = 0, val uid: Long = 0, val deleted: Boolean = false, val attemptRevision: Long? = null)
@Entity(tableName = "outbox", foreignKeys = [ForeignKey(entity = AccountRow::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)], indices = [Index("accountId"), Index(value = ["state", "dueAt"])])
data class OutboxRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val accountId: Long, val draft: String, val dueAt: Long, val state: String = "PENDING", val mimePath: String? = null, val error: String? = null)
/**
 * Local-only snooze (IMAP has no standard one; nothing moves on the server). [messageId] null = the whole thread.
 * Hidden while [resurfacedAt] is null; once due it stays (visible) for a while so the thread sorts to the top as "new".
 */
@Entity(tableName = "snoozed", foreignKeys = [ForeignKey(entity = AccountRow::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)], indices = [Index("accountId"), Index("threadId"), Index("until")])
data class SnoozeRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val accountId: Long, val threadId: Long, val messageId: Long? = null, val until: Long, val createdAt: Long, val resurfacedAt: Long? = null)
@Entity(tableName = "operations", foreignKeys = [ForeignKey(entity = AccountRow::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)], indices = [Index("accountId")])
data class OperationRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val accountId: Long, val folderId: Long, val validity: Long, val uid: Long, val action: String, val target: String? = null, val attempts: Int = 0, val error: String? = null, val messageLocalId: Long? = null, val desiredSeen: Boolean? = null, val desiredStarred: Boolean? = null)
@Entity(tableName = "contacts", primaryKeys = ["accountId", "email"], foreignKeys = [ForeignKey(entity = AccountRow::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)])
data class ContactRow(val accountId: Long, val email: String, val name: String?, val lastSeen: Long, @ColumnInfo(defaultValue="0") val corresponded:Boolean=false)

@Entity(tableName = "body_text", primaryKeys = ["messageId", "chunk"], foreignKeys = [ForeignKey(entity = MessageRow::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)])
data class BodyTextRow(val messageId: Long, val chunk: Int, val text: String)
@Entity(tableName = "search_matches", primaryKeys = ["queryHash", "messageId"], foreignKeys = [ForeignKey(entity = MessageRow::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)], indices = [Index("messageId")])
data class SearchMatchRow(val queryHash: String, val messageId: Long)
/** Attachment joined with the few message columns the browser shows. */
data class AttachmentListRow(val id: Long, val messageId: Long, val partId: String, val name: String, val mime: String, val size: Long, val cid: String?, val inline: Boolean, val localUri: String?, val accountId: Long, val sentAt: Long, val header: String, val subject: String)
data class BodyChunk(val text: String?, val html: String?)
/** Compact sync inventory: never hydrate every message's potentially large header JSON. */
data class FolderMessageState(val id:Long,val uid:Long,val threadId:Long,val unread:Boolean,val starred:Boolean)

internal const val MESSAGE_META = "id,accountId,folderId,threadId,uid,header,subject,sentAt,unread,starred,hasAttachments,snippet,NULL AS text,NULL AS html,bodyLoaded,remoteImages,senderSearch,recipientSearch,snippetFetched,bodyPath,messageIdKey"

@Dao
interface MailDao {
    @Query("DELETE FROM message_senders WHERE messageId=:id") suspend fun clearMessageSenders(id:Long)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun putMessageSender(row:MessageSenderRow)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun putDeviceContacts(rows:List<DeviceContactRow>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun deviceContactState(row:DeviceContactStateRow)
    @Query("DELETE FROM device_contacts WHERE generation!=:generation") suspend fun pruneDeviceContacts(generation:Long)
    @Query("DELETE FROM device_contacts") suspend fun clearDeviceContacts()
    @Query("SELECT email FROM device_contacts WHERE generation=(SELECT generation FROM device_contact_state WHERE id=1 AND allowed=1)") suspend fun deviceContactEmails():List<String>

    @Query("SELECT m.id,m.threadId,m.bodyPath,CASE WHEN m.uid<0 OR f.role='DRAFTS' OR COALESCE(c.offlineSaved,0)=1 OR EXISTS(SELECT 1 FROM snoozed s WHERE s.resurfacedAt IS NULL AND (s.messageId=m.id OR s.threadId=m.threadId)) OR EXISTS(SELECT 1 FROM drafts d WHERE d.sourceMessageId=m.id) OR EXISTS(SELECT 1 FROM operations o WHERE o.accountId=m.accountId AND (o.messageLocalId=m.id OR (o.folderId=m.folderId AND o.uid=m.uid))) THEN 1 ELSE 0 END AS protected FROM messages m JOIN folders f ON f.id=m.folderId LEFT JOIN cache_access c ON c.messageId=m.id WHERE m.accountId=:account AND m.id>:after ORDER BY m.id LIMIT 32") suspend fun storagePathBatch(account:Long,after:Long):List<StoragePathRow>
    @Query("SELECT EXISTS(SELECT 1 FROM messages m JOIN folders f ON f.id=m.folderId LEFT JOIN cache_access c ON c.messageId=m.id WHERE m.id=:id AND (m.uid<0 OR f.role='DRAFTS' OR COALESCE(c.offlineSaved,0)=1 OR EXISTS(SELECT 1 FROM snoozed s WHERE s.resurfacedAt IS NULL AND (s.messageId=m.id OR s.threadId=m.threadId)) OR EXISTS(SELECT 1 FROM drafts d WHERE d.sourceMessageId=m.id) OR EXISTS(SELECT 1 FROM operations o WHERE o.accountId=m.accountId AND (o.messageLocalId=m.id OR (o.folderId=m.folderId AND o.uid=m.uid)))))") suspend fun protectedMessage(id:Long):Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE bodyPath=:path)") suspend fun bodyPathReferenced(path:String):Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM attachments WHERE localUri=:uri)") suspend fun attachmentUriReferenced(uri:String):Boolean
    @Query("SELECT id,accountId,substr(json,instr(json,'\"attachments\":'),65536) AS attachmentJson FROM drafts WHERE id>:after ORDER BY id LIMIT 20") suspend fun draftCacheReferences(after:Long):List<CacheReferenceRow>
    @Query("SELECT id,accountId,substr(draft,instr(draft,'\"attachments\":'),65536) AS attachmentJson FROM outbox WHERE id>:after AND state IN('PENDING','SUBMITTING','APPEND','APPENDING','UNCERTAIN') ORDER BY id LIMIT 20") suspend fun outboxCacheReferences(after:Long):List<CacheReferenceRow>
    @Query("SELECT COALESCE(SUM(length(CAST(d.body AS BLOB))+length(CAST(d.files AS BLOB))+length(CAST(d.attachmentText AS BLOB))),0)*2 FROM search_documents d JOIN messages m ON m.id=d.messageId WHERE m.accountId=:id") suspend fun indexedTextBytes(id:Long):Long

    @Query("SELECT * FROM cache_access WHERE messageId=:id") suspend fun cacheAccess(id:Long):CacheAccessRow?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putCacheAccess(row:CacheAccessRow)
    @Query("SELECT messageId FROM cache_access WHERE offlineSaved=1") fun observeOfflineSaved():Flow<List<Long>>
    @Query("SELECT * FROM offline_folders WHERE folderId=:id") suspend fun offlineFolder(id:Long):OfflineFolderRow?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putOfflineFolder(row:OfflineFolderRow)
    @Query("UPDATE offline_folders SET beforeUid=0,scanThrough=0,complete=0,visitedAt=0,headerFailures=0 WHERE complete=1 AND folderId IN(SELECT id FROM folders WHERE accountId=:id)") suspend fun restartCompletedOfflineFolders(id:Long)
    @Query("SELECT COALESCE(SUM(headerFailures),0) FROM offline_folders WHERE folderId IN(SELECT id FROM folders WHERE accountId=:id)") suspend fun offlineHeaderFailures(id:Long):Int
    @Query("DELETE FROM offline_folders WHERE folderId IN(SELECT id FROM folders WHERE accountId=:id)") suspend fun resetOfflineFolders(id:Long)
    @Query("SELECT (SELECT COUNT(*) FROM messages m LEFT JOIN cache_access c ON c.messageId=m.id WHERE " + STORAGE_SELECTION + ") AS headers,(SELECT COUNT(*) FROM messages m LEFT JOIN cache_access c ON c.messageId=m.id WHERE " + STORAGE_SELECTION + " AND m.bodyLoaded=1) AS bodies,(SELECT COUNT(*) FROM attachments a JOIN messages m ON m.id=a.messageId LEFT JOIN cache_access c ON c.messageId=m.id WHERE " + STORAGE_SELECTION + ") AS attachments,(SELECT COUNT(*) FROM attachments a JOIN messages m ON m.id=a.messageId LEFT JOIN cache_access c ON c.messageId=m.id WHERE " + STORAGE_SELECTION + " AND a.localUri IS NOT NULL) AS downloadedAttachments,(SELECT COALESCE(SUM(total),0) FROM folders WHERE accountId=:id) AS serverTotal") fun observeOfflineCounts(id:Long,keep:Int=0,afterDate:Long=0):Flow<OfflineCounts>
    @Query("SELECT COUNT(*) FROM messages WHERE accountId=:id") suspend fun cachedMessageCount(id:Long):Int
    @Query("SELECT COUNT(*) FROM messages WHERE accountId=:id AND bodyLoaded=1") suspend fun cachedBodyCount(id:Long):Int
    @Query("SELECT COALESCE(SUM(total),0) FROM folders WHERE accountId=:id") suspend fun serverMessageCount(id:Long):Int
    @Query("SELECT m.id,m.bodyLoaded,m.sentAt,m.folderId,m.uid,COALESCE(c.accessedAt,0) AS accessedAt,COALESCE(c.offlineSaved,0) AS offlineSaved FROM messages m LEFT JOIN cache_access c ON c.messageId=m.id WHERE m.accountId=:account AND m.id>:after ORDER BY m.id LIMIT :limit") suspend fun storageCandidates(account:Long,after:Long,limit:Int):List<StorageCandidate>
    @Query("SELECT m.id FROM messages m LEFT JOIN cache_access c ON c.messageId=m.id WHERE m.bodyLoaded=1 ORDER BY COALESCE(c.accessedAt,0),m.sentAt LIMIT :limit OFFSET :offset") suspend fun globalEvictionCandidates(limit:Int,offset:Int):List<Long>
    @Query("SELECT m.id FROM messages m LEFT JOIN cache_access c ON c.messageId=m.id WHERE m.accountId=:account AND m.bodyLoaded=1 ORDER BY COALESCE(c.accessedAt,0),m.sentAt LIMIT :limit OFFSET :offset") suspend fun evictionCandidates(account:Long,limit:Int,offset:Int):List<Long>
    @Query("SELECT m.id FROM messages m LEFT JOIN cache_access c ON c.messageId=m.id WHERE " + STORAGE_SELECTION + " AND (:downloadAll OR COALESCE(c.offlineSaved,0)=1) AND (c.downloadError IS NULL OR c.failureAt<:retryBefore) AND (m.bodyLoaded=0 OR (:attachments AND EXISTS(SELECT 1 FROM attachments a WHERE a.messageId=m.id AND a.localUri IS NULL))) ORDER BY m.sentAt DESC,m.id DESC LIMIT :limit OFFSET :offset") suspend fun offlineBodyCandidates(id:Long,attachments:Boolean,downloadAll:Boolean,retryBefore:Long,limit:Int,offset:Int,keep:Int=0,afterDate:Long=0):List<Long>
    @Query("SELECT m.id FROM messages m WHERE m.accountId=:account AND m.uid>0 AND NOT EXISTS(SELECT 1 FROM cache_access c WHERE c.messageId=m.id AND c.offlineSaved=1) AND NOT EXISTS(SELECT 1 FROM folders f WHERE f.id=m.folderId AND f.role='DRAFTS') AND NOT EXISTS(SELECT 1 FROM drafts d WHERE d.sourceMessageId=m.id) AND NOT EXISTS(SELECT 1 FROM operations o WHERE o.accountId=m.accountId AND (o.messageLocalId=m.id OR (o.folderId=m.folderId AND o.uid=m.uid))) ORDER BY m.sentAt DESC,m.id DESC LIMIT 200 OFFSET (:keep+:skip)") suspend fun outsideLatest(account:Long,keep:Int,skip:Int=0):List<Long>
    @Query("SELECT m.id FROM messages m WHERE m.accountId=:account AND m.sentAt<:cutoff AND m.uid>0 AND NOT EXISTS(SELECT 1 FROM cache_access c WHERE c.messageId=m.id AND c.offlineSaved=1) AND NOT EXISTS(SELECT 1 FROM folders f WHERE f.id=m.folderId AND f.role='DRAFTS') AND NOT EXISTS(SELECT 1 FROM drafts d WHERE d.sourceMessageId=m.id) AND NOT EXISTS(SELECT 1 FROM operations o WHERE o.accountId=m.accountId AND (o.messageLocalId=m.id OR (o.folderId=m.folderId AND o.uid=m.uid))) ORDER BY m.sentAt LIMIT :limit OFFSET :skip") suspend fun outsideAge(account:Long,cutoff:Long,limit:Int,skip:Int=0):List<Long>
    @Query("SELECT sentAt FROM messages WHERE accountId=:account ORDER BY sentAt DESC,id DESC LIMIT 1 OFFSET :offset") suspend fun messageDateAt(account:Long,offset:Int):Long?
    @Query("SELECT id FROM messages WHERE accountId=:account ORDER BY sentAt DESC,id DESC LIMIT :limit") suspend fun latestMessageIds(account:Long,limit:Int):List<Long>
    @Query("DELETE FROM attachments WHERE id=:id") suspend fun deleteAttachment(id:Long)
    @Query("UPDATE cache_access SET failureAt=0,downloadError=NULL WHERE messageId IN(SELECT id FROM messages WHERE accountId=:id)") suspend fun clearDownloadErrors(id:Long)
    @Query("SELECT COUNT(*) FROM cache_access c JOIN messages m ON m.id=c.messageId WHERE m.accountId=:id AND c.downloadError IS NOT NULL") suspend fun downloadErrorCount(id:Long):Int
    @Update suspend fun updateAttachment(row:AttachmentRow)
    @Query("UPDATE messages SET bodyLoaded=0,bodyPath=NULL,text=NULL,html=NULL,remoteImages=0 WHERE id=:id") suspend fun evictBody(id:Long)
    @Query("UPDATE attachments SET localUri=NULL WHERE messageId=:id") suspend fun evictAttachments(id:Long)

    @Query("SELECT * FROM search_pending ORDER BY messageId LIMIT :limit") suspend fun searchPending(limit: Int): List<SearchPendingRow>
    @Query("DELETE FROM search_matches WHERE queryHash NOT IN(SELECT queryHash FROM search_matches GROUP BY queryHash ORDER BY MAX(rowid) DESC LIMIT 32)") suspend fun pruneSearchMatches()
    @Query("SELECT COUNT(*) FROM search_pending") suspend fun searchPendingCount(): Int
    @Query("SELECT COUNT(*) FROM search_pending") fun observeSearchPendingCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM messages") suspend fun searchMessageCount(): Int
    @Query("DELETE FROM search_pending WHERE messageId=:id AND revision=:revision") suspend fun finishSearchIndex(id: Long, revision: Long)
    @Query("SELECT * FROM search_documents WHERE messageId=:id") suspend fun searchDocument(id:Long): SearchDocumentRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSearchDocument(row: SearchDocumentRow)
    @Query("DELETE FROM message_fts WHERE rowid=:id") suspend fun clearMessageFts(id: Long)
    @Insert suspend fun putMessageFts(row: MessageFtsRow)
    @RawQuery(observedEntities = [SearchDocumentRow::class, MessageRow::class, ThreadRow::class, FolderRow::class, SearchMatchRow::class]) fun searchPage(query: SupportSQLiteQuery): PagingSource<Int, IndexedSearchRow>

    @Query("SELECT * FROM accounts ORDER BY id") fun observeAccounts(): Flow<List<AccountRow>>
    @Query("SELECT * FROM accounts ORDER BY id") suspend fun accounts(): List<AccountRow>
    @Query("SELECT * FROM accounts WHERE id=:id") suspend fun account(id: Long): AccountRow?
    @Insert suspend fun insertAccount(row: AccountRow): Long
    @Query("UPDATE accounts SET secret=:secret,config=:config,syncError=NULL,authError=0 WHERE id=:id") suspend fun accountAuthorization(id: Long, secret: String, config: String): Int
    @Query("UPDATE accounts SET secret=:secret,syncError=NULL,authError=0 WHERE id=:id") suspend fun accountSecret(id: Long, secret: String): Int
    @Query("DELETE FROM accounts WHERE id=:id") suspend fun deleteAccount(id: Long)
    @Query("UPDATE accounts SET name=:name WHERE id=:id") suspend fun accountName(id: Long, name: String)
    @Query("UPDATE accounts SET syncError=:error,authError=:authError WHERE id=:id") suspend fun syncError(id: Long, error: String?, authError: Boolean = false)
    @Query("SELECT * FROM folders WHERE accountId=:account ORDER BY id") fun observeFolders(account: Long): Flow<List<FolderRow>>
    @Query("SELECT * FROM folders WHERE accountId=:account") suspend fun folders(account: Long): List<FolderRow>
    @Query("SELECT * FROM folders WHERE id=:id") suspend fun folder(id: Long): FolderRow?
    @Insert suspend fun insertFolder(row: FolderRow): Long
    @Insert suspend fun insertSnooze(row: SnoozeRow): Long
    @Query("DELETE FROM snoozed WHERE threadId=:thread AND messageId IS NULL") suspend fun deleteThreadSnooze(thread: Long)
    @Query("DELETE FROM snoozed WHERE threadId=:thread") suspend fun deleteAllSnoozesOfThread(thread: Long)
    @Query("DELETE FROM snoozed WHERE messageId=:message") suspend fun deleteMessageSnooze(message: Long)
    @Query("SELECT id FROM snoozed WHERE threadId=:thread AND resurfacedAt IS NULL") suspend fun activeSnoozeIdsOfThread(thread: Long): List<Long>
    @Query("SELECT id FROM snoozed WHERE messageId=:message AND resurfacedAt IS NULL") suspend fun activeSnoozeIdsOfMessage(message: Long): List<Long>
    @Query("SELECT * FROM snoozed WHERE resurfacedAt IS NULL AND until<=:now ORDER BY until") suspend fun dueSnoozes(now: Long): List<SnoozeRow>
    @Query("UPDATE snoozed SET resurfacedAt=:now WHERE id=:id") suspend fun resurfaceSnooze(id: Long, now: Long)
    @Query("DELETE FROM snoozed WHERE resurfacedAt IS NOT NULL AND resurfacedAt<:before") suspend fun pruneSnoozes(before: Long)
    @Query("SELECT COUNT(*) FROM snoozed WHERE resurfacedAt IS NULL") fun observeSnoozedCount(): Flow<Int>
    @Query("SELECT * FROM snoozed ORDER BY id") suspend fun allSnoozes(): List<SnoozeRow>
    @Query("SELECT MIN(until) FROM snoozed WHERE threadId=:thread AND resurfacedAt IS NULL") suspend fun snoozedUntilOfThread(thread: Long): Long?
    @Query("SELECT MIN(until) FROM snoozed WHERE resurfacedAt IS NULL AND (messageId=:message OR (messageId IS NULL AND threadId=:thread))") suspend fun snoozedUntilOfMessage(message: Long, thread: Long): Long?
    @Query("DELETE FROM folders WHERE id=:id") suspend fun deleteFolderRow(id: Long)
    /** Local ids of folders whose deletion is queued: hidden from the UI meanwhile. */
    @Query("SELECT uid FROM operations WHERE action='FOLDER_DELETE'") fun observeDeletingFolderIds(): Flow<List<Long>>
    @Update suspend fun updateFolder(row: FolderRow)
    @RawQuery suspend fun matchingMessageIds(query:SupportSQLiteQuery):List<Long>
    @RawQuery(observedEntities = [ThreadRow::class, MessageRow::class, FolderRow::class, DraftRow::class, OperationRow::class, BodyTextRow::class, SearchMatchRow::class, SnoozeRow::class, ContactRow::class, MessageSenderRow::class, DeviceContactRow::class, DeviceContactStateRow::class]) fun page(query: SupportSQLiteQuery): PagingSource<Int, ThreadRow>
    @Query("SELECT $MESSAGE_META FROM messages WHERE threadId=:id ORDER BY sentAt,id") fun observeThread(id: Long): Flow<List<MessageRow>>
    @Query("SELECT $MESSAGE_META FROM messages WHERE threadId=:id ORDER BY sentAt,id") suspend fun threadMessages(id: Long): List<MessageRow>
    @Query("SELECT $MESSAGE_META FROM messages WHERE folderId=:id") suspend fun folderMessages(id: Long): List<MessageRow>
    @Query("SELECT id,uid,threadId,unread,starred FROM messages WHERE folderId=:id") suspend fun folderInventory(id:Long):List<FolderMessageState>
    @Query("SELECT EXISTS(SELECT 1 FROM operations WHERE folderId=:id)") suspend fun hasFolderOperations(id:Long):Boolean
    @Query("""SELECT EXISTS(SELECT 1 FROM messages WHERE folderId=:id AND uid>0 AND (instr(header,'"category"')=0 OR instr(header,'"attachmentNames"')=0))""") suspend fun folderNeedsMetadata(id:Long):Boolean
    @Query("""SELECT $MESSAGE_META FROM messages WHERE folderId=:id AND uid>0 AND (instr(header,'"category"')=0 OR instr(header,'"attachmentNames"')=0) ORDER BY uid LIMIT 200""") suspend fun folderMetadataRepairs(id:Long):List<MessageRow>
    @Query("SELECT $MESSAGE_META FROM messages WHERE folderId=:id ORDER BY sentAt DESC,id DESC LIMIT 200") suspend fun recentFolderMessages(id:Long):List<MessageRow>
    @Query("SELECT $MESSAGE_META FROM messages WHERE folderId=:id AND bodyLoaded=1 AND snippetFetched=0 ORDER BY sentAt DESC,id DESC LIMIT 200") suspend fun localFolderPreviewRepairs(id:Long):List<MessageRow>
    @Query("SELECT $MESSAGE_META FROM messages WHERE folderId=:id AND uid>0 AND bodyLoaded=0 AND snippetFetched=0 AND snippet!='' ORDER BY sentAt DESC,id DESC LIMIT 200") suspend fun remoteFolderPreviewRepairs(id:Long):List<MessageRow>
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE folderId=:id AND snippetFetched=0 AND (bodyLoaded=1 OR (uid>0 AND snippet!=''))) OR EXISTS(SELECT 1 FROM (SELECT uid,bodyLoaded,snippetFetched FROM messages WHERE folderId=:id ORDER BY sentAt DESC,id DESC LIMIT 200) WHERE uid>0 AND bodyLoaded=0 AND snippetFetched=0)") suspend fun folderNeedsPreviews(id:Long):Boolean
    @Query("""SELECT EXISTS(SELECT 1 FROM messages WHERE threadId=:id AND (messageIdKey IS NOT NULL OR instr(header,'"messageId"')>0))""") suspend fun threadHasIdentifiedMessages(id:Long):Boolean

    @Query("SELECT $MESSAGE_META FROM messages WHERE id=:id") suspend fun message(id: Long): MessageRow?
    @Query("SELECT $MESSAGE_META FROM messages WHERE id=:id") fun observeMessage(id: Long): Flow<List<MessageRow>>
    /** Single-message list (conversation grouping off). Columns must be MESSAGE_META so bodies stay out of the cursor. */
    @RawQuery(observedEntities = [MessageRow::class, FolderRow::class, SnoozeRow::class, ContactRow::class, MessageSenderRow::class, DeviceContactRow::class, DeviceContactStateRow::class]) fun pageMessages(query: SupportSQLiteQuery): PagingSource<Int, MessageRow>
    @Query("SELECT $MESSAGE_META FROM messages WHERE folderId=:folder AND uid=:uid") suspend fun byUid(folder: Long, uid: Long): MessageRow?
    @Query("SELECT $MESSAGE_META FROM messages WHERE folderId=:folder AND uid<0") suspend fun pendingMoves(folder: Long): List<MessageRow>
    @Query("UPDATE messages SET threadId=:target WHERE threadId=:source") suspend fun mergeMessages(source: Long, target: Long)
    @Query("UPDATE thread_keys SET threadId=:target WHERE threadId=:source") suspend fun mergeKeys(source: Long, target: Long)
    @Query("DELETE FROM threads WHERE id=:id") suspend fun deleteThread(id: Long)
    @Insert suspend fun insertMessage(row: MessageRow): Long
    @Update suspend fun updateMessageRaw(row: MessageRow)
    @Query("SELECT id, subject, header FROM messages WHERE id>:after ORDER BY id LIMIT :limit") suspend fun headersAfter(after: Long, limit: Int): List<HeaderOnly>
    @Query("UPDATE messages SET header=:header WHERE id=:id") suspend fun setHeader(id: Long, header: String)
    @Query("UPDATE messages SET accountId=:account,folderId=:folder,threadId=:thread,uid=:uid,header=:header,subject=:subject,sentAt=:sent,unread=:unread,starred=:starred,hasAttachments=:attachments,snippet=:snippet,bodyLoaded=:loaded,remoteImages=:remote,senderSearch=:sender,recipientSearch=:recipient,snippetFetched=:fetched,messageIdKey=:key WHERE id=:id")
    suspend fun metadata(id: Long,account: Long,folder: Long,thread: Long,uid: Long,header: String,subject: String,sent: Long,unread: Boolean,starred: Boolean,attachments: Boolean,snippet: String,loaded: Boolean,remote: Boolean,sender: String,recipient: String,fetched: Boolean,key: String?)
    suspend fun updateMessage(row: MessageRow) {
        if(row.bodyLoaded && row.bodyPath==null && row.text==null && row.html==null) metadata(row.id,row.accountId,row.folderId,row.threadId,row.uid,row.header,row.subject,row.sentAt,row.unread,row.starred,row.hasAttachments,row.snippet,row.bodyLoaded,row.remoteImages,row.senderSearch,row.recipientSearch,row.snippetFetched,row.messageIdKey)
        else updateMessageRaw(row)
    }
    @Query("SELECT substr(text,:start,:count) AS text,substr(html,:start,:count) AS html FROM messages WHERE id=:id") suspend fun bodyChunk(id: Long,start: Int,count: Int): BodyChunk?
    @Query("SELECT $MESSAGE_META FROM messages WHERE folderId=:folder AND uid<0 AND messageIdKey=:key LIMIT 1") suspend fun pendingMessage(folder: Long,key: String): MessageRow?
    @Query("SELECT id FROM messages WHERE folderId=:folder AND uid<0 AND messageIdKey IS NULL") suspend fun unkeyedPending(folder: Long): List<Long>
    @Query("DELETE FROM body_text WHERE messageId=:id") suspend fun clearBodyText(id: Long)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun putBodyText(row: BodyTextRow)
    @Query("DELETE FROM search_matches WHERE queryHash=:hash") suspend fun clearSearchMatches(hash: String)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun putSearchMatch(row: SearchMatchRow)
    @Query("UPDATE messages SET snippetFetched=0 WHERE accountId=:accountId") suspend fun resetSnippetRevision(accountId:Long)
    @Query("UPDATE messages SET snippet=:value,snippetFetched=1 WHERE id=:id") suspend fun setSnippet(id:Long,value:String)
    @Query("SELECT COUNT(*) FROM messages WHERE accountId=:accountId AND snippetFetched=0 AND ((uid>0 AND snippet!='') OR bodyLoaded=1)") suspend fun pendingSnippetRepairs(accountId:Long):Int
    @Query("DELETE FROM messages WHERE id=:id") suspend fun deleteMessage(id: Long)
    @Query("DELETE FROM messages WHERE folderId=:id") suspend fun clearFolder(id: Long)
    @Insert suspend fun insertThread(row: ThreadRow): Long
    @Update suspend fun updateThread(row: ThreadRow)
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE threadId=:id)") suspend fun threadHasMessages(id:Long):Boolean
    @Query("SELECT * FROM threads WHERE id=:id") suspend fun threadRow(id: Long): ThreadRow?
    @Query("SELECT threadId FROM thread_keys WHERE accountId=:account AND `key`=:key") suspend fun threadKey(account: Long, key: String): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putKey(row: ThreadKeyRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertKey(row: ThreadKeyRow)
    @Query("SELECT localUri FROM attachments WHERE localUri IS NOT NULL") suspend fun attachmentUris(): List<String>
    @Query("SELECT bodyPath FROM messages WHERE bodyPath IS NOT NULL") suspend fun bodyPaths(): List<String>
    @Query("SELECT * FROM drafts ORDER BY id LIMIT :limit OFFSET :offset") suspend fun draftBatch(offset: Int,limit: Int): List<DraftRow>
    @Query("SELECT * FROM attachments WHERE messageId=:id") suspend fun attachments(id: Long): List<AttachmentRow>
    @Query("SELECT * FROM attachments WHERE messageId IN (:ids)") suspend fun attachmentsFor(ids: List<Long>): List<AttachmentRow>
    @Query("SELECT * FROM attachments WHERE id=:id") suspend fun attachment(id: Long): AttachmentRow?
    @Query("SELECT * FROM attachments WHERE localUri=:uri LIMIT 1") suspend fun attachmentUri(uri: String): AttachmentRow?
    @Insert suspend fun insertAttachment(row: AttachmentRow): Long
    @RawQuery(observedEntities = [AttachmentRow::class, MessageRow::class, FolderRow::class]) fun pageAttachments(query: SupportSQLiteQuery): PagingSource<Int, AttachmentListRow>
    @Query("DELETE FROM attachments WHERE messageId=:id") suspend fun clearAttachments(id: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDraft(row: DraftRow): Long
    @Query("SELECT * FROM draft_mirrors WHERE accountId=:accountId") suspend fun draftMirrors(accountId: Long): List<DraftMirrorRow>
    @Query("SELECT * FROM draft_mirrors WHERE localId=:id") suspend fun draftMirror(id: Long): DraftMirrorRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDraftMirror(row: DraftMirrorRow)
    @Query("DELETE FROM draft_mirrors WHERE localId=:id") suspend fun deleteDraftMirror(id: Long)
    @Query("UPDATE drafts SET sourceMessageId=:messageId WHERE id=:id") suspend fun draftSource(id: Long, messageId: Long?)
    @Query("SELECT * FROM drafts WHERE id=:id") suspend fun draft(id: Long): DraftRow?
    @Query("SELECT * FROM drafts WHERE sourceMessageId=:id LIMIT 1") suspend fun draftForMessage(id: Long): DraftRow?
    @Query("DELETE FROM drafts WHERE id=:id") suspend fun deleteDraft(id: Long)
    @Query("SELECT * FROM outbox WHERE state IN ('PENDING','SUBMITTING','APPEND','APPENDING','UNCERTAIN') ORDER BY dueAt,id") fun observeOutbox(): Flow<List<OutboxRow>>
    @Query("SELECT * FROM outbox WHERE state IN ('PENDING','SUBMITTING','APPEND','APPENDING','UNCERTAIN')") suspend fun activeOutbox(): List<OutboxRow>
    @Query("SELECT * FROM outbox WHERE state='APPENDING'") suspend fun appendingOutbox(): List<OutboxRow>
    @Insert suspend fun insertOutbox(row: OutboxRow): Long
    @Query("SELECT * FROM outbox WHERE id=:id") suspend fun outbox(id: Long): OutboxRow?
    @Query("SELECT * FROM outbox WHERE state='SUBMITTING'") suspend fun submittingOutbox(): List<OutboxRow>
    @Query("SELECT * FROM outbox WHERE state IN ('PENDING','APPEND') ORDER BY dueAt") suspend fun pendingOutbox(): List<OutboxRow>
    @Query("UPDATE outbox SET state=:next,error=:error WHERE id=:id AND state=:expected") suspend fun transitionOutbox(id: Long, expected: String, next: String, error: String? = null): Int
    @Query("UPDATE outbox SET dueAt=:due WHERE id=:id AND state='PENDING'") suspend fun outboxDue(id: Long, due: Long): Int
    @Query("UPDATE outbox SET mimePath=:path WHERE id=:id AND state='PENDING'") suspend fun outboxPath(id: Long, path: String): Int
    @Query("UPDATE outbox SET mimePath=NULL WHERE id=:id AND state IN ('SENT','CANCELLED','DISCARDED')") suspend fun clearOutboxMime(id: Long)
    @Update suspend fun updateOutbox(row: OutboxRow)
    @Insert suspend fun insertOperation(row: OperationRow): Long
    @Query("SELECT * FROM operations WHERE accountId=:id ORDER BY id") suspend fun operations(id: Long): List<OperationRow>
    @Query("SELECT * FROM operations WHERE id=:id") suspend fun operation(id: Long): OperationRow?
    @Query("DELETE FROM operations WHERE id=:id") suspend fun deleteOperation(id: Long)
    @Update suspend fun updateOperation(row: OperationRow)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertContact(row:ContactRow):Long
    @Query("UPDATE contacts SET name=CASE WHEN :at>=lastSeen AND :name IS NOT NULL THEN :name ELSE name END,lastSeen=MAX(lastSeen,:at),corresponded=CASE WHEN corresponded=1 OR :known THEN 1 ELSE 0 END WHERE accountId=:account AND email=:email") suspend fun mergeContact(account:Long,email:String,name:String?,at:Long,known:Boolean)
    @Transaction suspend fun putContact(row:ContactRow) { insertContact(row); mergeContact(row.accountId,row.email,row.name,row.lastSeen,row.corresponded) }
    @Query("SELECT email, name, MAX(lastSeen) AS lastSeen, accountId, MAX(corresponded) AS corresponded FROM contacts WHERE email LIKE :prefix ESCAPE '!' OR name LIKE :prefix ESCAPE '!' GROUP BY email ORDER BY lastSeen DESC LIMIT :limit") suspend fun contacts(prefix: String, limit: Int): List<ContactRow>
}

@Database(entities = [AccountRow::class, FolderRow::class, ThreadRow::class, ThreadKeyRow::class, MessageRow::class, AttachmentRow::class, DraftRow::class, OutboxRow::class, OperationRow::class, ContactRow::class, BodyTextRow::class, SearchMatchRow::class, SnoozeRow::class, DraftMirrorRow::class, SearchDocumentRow::class, MessageFtsRow::class, SearchPendingRow::class, CacheAccessRow::class, OfflineFolderRow::class, MessageSenderRow::class, DeviceContactRow::class, DeviceContactStateRow::class], version = 9, exportSchema = true)
abstract class MailDatabase : RoomDatabase() { abstract fun mail(): MailDao }

internal val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN snippetFetched INTEGER NOT NULL DEFAULT 0")
    }
}

internal val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN authError INTEGER NOT NULL DEFAULT 0")
    }
}

internal val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE drafts ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE drafts ADD COLUMN sourceMessageId INTEGER")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_drafts_sourceMessageId ON drafts(sourceMessageId)")
        db.execSQL("ALTER TABLE operations ADD COLUMN messageLocalId INTEGER")
        db.execSQL("ALTER TABLE operations ADD COLUMN desiredSeen INTEGER")
        db.execSQL("ALTER TABLE operations ADD COLUMN desiredStarred INTEGER")
    }
}

internal val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN bodyPath TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN messageIdKey TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_threads_lastAt_id ON threads(lastAt,id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_threadId_sentAt ON messages(threadId,sentAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_folderId_messageIdKey ON messages(folderId,messageIdKey)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_state_dueAt ON outbox(state,dueAt)")
        db.execSQL("CREATE TABLE IF NOT EXISTS body_text (messageId INTEGER NOT NULL, chunk INTEGER NOT NULL, text TEXT NOT NULL, PRIMARY KEY(messageId,chunk), FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS search_matches (queryHash TEXT NOT NULL, messageId INTEGER NOT NULL, PRIMARY KEY(queryHash,messageId), FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_search_matches_messageId ON search_matches(messageId)")
    }
}

internal val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS snoozed (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, accountId INTEGER NOT NULL, threadId INTEGER NOT NULL, messageId INTEGER, until INTEGER NOT NULL, createdAt INTEGER NOT NULL, resurfacedAt INTEGER, FOREIGN KEY(accountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_snoozed_accountId ON snoozed(accountId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_snoozed_threadId ON snoozed(threadId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_snoozed_until ON snoozed(until)")
    }
}

internal val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS draft_mirrors (localId INTEGER NOT NULL PRIMARY KEY, accountId INTEGER NOT NULL, token TEXT NOT NULL, revision INTEGER NOT NULL, syncedRevision INTEGER NOT NULL, dueAt INTEGER NOT NULL, folderId INTEGER, validity INTEGER NOT NULL, uid INTEGER NOT NULL, deleted INTEGER NOT NULL, attemptRevision INTEGER, FOREIGN KEY(accountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_draft_mirrors_accountId ON draft_mirrors(accountId)")
        db.execSQL("INSERT INTO draft_mirrors(localId,accountId,token,revision,syncedRevision,dueAt,folderId,validity,uid,deleted,attemptRevision) SELECT d.id,d.accountId,lower(hex(randomblob(16))),1,0,0,m.folderId,COALESCE(f.validity,0),MAX(COALESCE(m.uid,0),0),0,NULL FROM drafts d LEFT JOIN messages m ON m.id=d.sourceMessageId LEFT JOIN folders f ON f.id=m.folderId")
    }
}


internal val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS search_documents (messageId INTEGER NOT NULL PRIMARY KEY, body TEXT NOT NULL, files TEXT NOT NULL, attachmentText TEXT NOT NULL, FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING FTS4(subject TEXT NOT NULL, sender TEXT NOT NULL, recipients TEXT NOT NULL, body TEXT NOT NULL, files TEXT NOT NULL, attachmentText TEXT NOT NULL, tokenize=unicode61)")
        db.execSQL("CREATE TABLE IF NOT EXISTS search_pending (messageId INTEGER NOT NULL PRIMARY KEY, revision INTEGER NOT NULL, FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE)")
        db.execSQL("DELETE FROM body_text") // Superseded unbounded LIKE chunks; the bounded FTS worker backfills cached files.
        installSearchTriggers(db)
        db.execSQL("INSERT OR IGNORE INTO search_pending(messageId,revision) SELECT id,1 FROM messages")
    }
}

internal val MIGRATION_8_9 = object : androidx.room.migration.Migration(8,9) {
    override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contacts ADD COLUMN corresponded INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS message_senders (messageId INTEGER NOT NULL,email TEXT NOT NULL,PRIMARY KEY(messageId,email),FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_message_senders_email ON message_senders(email)")
        db.execSQL("CREATE TABLE IF NOT EXISTS device_contacts (email TEXT NOT NULL,generation INTEGER NOT NULL,PRIMARY KEY(email,generation))")
        db.execSQL("CREATE TABLE IF NOT EXISTS device_contact_state (id INTEGER NOT NULL PRIMARY KEY,generation INTEGER NOT NULL,allowed INTEGER NOT NULL)")
        backfillCorrespondence(db)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_accountId_sentAt_id ON messages(accountId,sentAt,id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_bodyPath ON messages(bodyPath)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_localUri ON attachments(localUri)")
        db.execSQL("CREATE TABLE IF NOT EXISTS cache_access (messageId INTEGER NOT NULL PRIMARY KEY, accessedAt INTEGER NOT NULL, offlineSaved INTEGER NOT NULL, failureAt INTEGER NOT NULL, downloadError TEXT, FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS offline_folders (folderId INTEGER NOT NULL PRIMARY KEY, validity INTEGER NOT NULL, beforeUid INTEGER NOT NULL, scanThrough INTEGER NOT NULL, completedThrough INTEGER NOT NULL, complete INTEGER NOT NULL, visitedAt INTEGER NOT NULL, headerFailures INTEGER NOT NULL, FOREIGN KEY(folderId) REFERENCES folders(id) ON DELETE CASCADE)")
    }
}
