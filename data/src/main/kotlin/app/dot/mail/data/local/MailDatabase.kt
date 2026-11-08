package app.dot.mail.data.local

import androidx.paging.PagingSource
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "accounts")
data class AccountRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val email: String, val name: String, val color: Int, val config: String, val secret: String, val syncError: String? = null, @ColumnInfo(defaultValue = "0") val authError: Boolean = false)
@Entity(tableName = "folders", foreignKeys = [ForeignKey(entity = AccountRow::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["accountId", "path"], unique = true)])
data class FolderRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val accountId: Long, val path: String, val name: String, val role: String, val unread: Int = 0, val total: Int = 0, val validity: Long = 0, val nextUid: Long = 1, val modSeq: Long = -1)
@Entity(tableName = "threads", foreignKeys = [ForeignKey(entity = AccountRow::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)], indices = [Index("accountId")])
data class ThreadRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val accountId: Long, val subject: String, val participants: String = "[]", val snippet: String = "", val lastAt: Long = 0, val count: Int = 0, val unread: Int = 0, val starred: Boolean = false, val attachments: Boolean = false)
@Entity(tableName = "thread_keys", primaryKeys = ["accountId", "key"], foreignKeys = [ForeignKey(entity = ThreadRow::class, parentColumns = ["id"], childColumns = ["threadId"], onDelete = ForeignKey.CASCADE)], indices = [Index("threadId")])
data class ThreadKeyRow(val accountId: Long, val key: String, val threadId: Long)
@Entity(tableName = "messages", foreignKeys = [ForeignKey(entity = FolderRow::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = ThreadRow::class, parentColumns = ["id"], childColumns = ["threadId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["folderId", "uid"], unique = true), Index("threadId"), Index("accountId")])
data class MessageRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val accountId: Long, val folderId: Long, val threadId: Long, val uid: Long, val header: String, val subject: String, val sentAt: Long, val unread: Boolean, val starred: Boolean, val hasAttachments: Boolean, val snippet: String = "", val text: String? = null, val html: String? = null, val bodyLoaded: Boolean = false, val remoteImages: Boolean = false, val senderSearch: String = "", val recipientSearch: String = "", @ColumnInfo(defaultValue = "0") val snippetFetched: Boolean = false)
@Entity(tableName = "attachments", foreignKeys = [ForeignKey(entity = MessageRow::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)], indices = [Index("messageId")])
data class AttachmentRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val messageId: Long, val partId: String, val name: String, val mime: String, val size: Long, val cid: String?, val inline: Boolean, val localUri: String?)
@Entity(tableName = "drafts", foreignKeys = [ForeignKey(entity = AccountRow::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)], indices = [Index("accountId"), Index(value = ["sourceMessageId"], unique = true)])
data class DraftRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val accountId: Long, val json: String, @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0, val sourceMessageId: Long? = null)
@Entity(tableName = "outbox", foreignKeys = [ForeignKey(entity = AccountRow::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)], indices = [Index("accountId")])
data class OutboxRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val accountId: Long, val draft: String, val dueAt: Long, val state: String = "PENDING", val mimePath: String? = null, val error: String? = null)
@Entity(tableName = "operations", foreignKeys = [ForeignKey(entity = AccountRow::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)], indices = [Index("accountId")])
data class OperationRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val accountId: Long, val folderId: Long, val validity: Long, val uid: Long, val action: String, val target: String? = null, val attempts: Int = 0, val error: String? = null, val messageLocalId: Long? = null, val desiredSeen: Boolean? = null, val desiredStarred: Boolean? = null)
@Entity(tableName = "contacts", primaryKeys = ["accountId", "email"], foreignKeys = [ForeignKey(entity = AccountRow::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)])
data class ContactRow(val accountId: Long, val email: String, val name: String?, val lastSeen: Long)

@Dao
interface MailDao {
    @Query("SELECT * FROM accounts ORDER BY id") fun observeAccounts(): Flow<List<AccountRow>>
    @Query("SELECT * FROM accounts ORDER BY id") suspend fun accounts(): List<AccountRow>
    @Query("SELECT * FROM accounts WHERE id=:id") suspend fun account(id: Long): AccountRow?
    @Insert suspend fun insertAccount(row: AccountRow): Long
    @Query("UPDATE accounts SET secret=:secret,config=:config,syncError=NULL,authError=0 WHERE id=:id") suspend fun accountAuthorization(id: Long, secret: String, config: String): Int
    @Query("UPDATE accounts SET secret=:secret,syncError=NULL,authError=0 WHERE id=:id") suspend fun accountSecret(id: Long, secret: String): Int
    @Query("DELETE FROM accounts WHERE id=:id") suspend fun deleteAccount(id: Long)
    @Query("UPDATE accounts SET syncError=:error,authError=:authError WHERE id=:id") suspend fun syncError(id: Long, error: String?, authError: Boolean = false)
    @Query("SELECT * FROM folders WHERE accountId=:account ORDER BY id") fun observeFolders(account: Long): Flow<List<FolderRow>>
    @Query("SELECT * FROM folders WHERE accountId=:account") suspend fun folders(account: Long): List<FolderRow>
    @Query("SELECT * FROM folders WHERE id=:id") suspend fun folder(id: Long): FolderRow?
    @Insert suspend fun insertFolder(row: FolderRow): Long
    @Update suspend fun updateFolder(row: FolderRow)
    @RawQuery(observedEntities = [ThreadRow::class, MessageRow::class, FolderRow::class, DraftRow::class]) fun page(query: SupportSQLiteQuery): PagingSource<Int, ThreadRow>
    @Query("SELECT * FROM messages WHERE threadId=:id ORDER BY sentAt,id") fun observeThread(id: Long): Flow<List<MessageRow>>
    @Query("SELECT * FROM messages WHERE threadId=:id ORDER BY sentAt,id") suspend fun threadMessages(id: Long): List<MessageRow>
    @Query("SELECT * FROM messages WHERE folderId=:id") suspend fun folderMessages(id: Long): List<MessageRow>
    @Query("SELECT * FROM messages WHERE id=:id") suspend fun message(id: Long): MessageRow?
    @Query("SELECT * FROM messages WHERE folderId=:folder AND uid=:uid") suspend fun byUid(folder: Long, uid: Long): MessageRow?
    @Insert suspend fun insertMessage(row: MessageRow): Long
    @Update suspend fun updateMessage(row: MessageRow)
    @Query("DELETE FROM messages WHERE id=:id") suspend fun deleteMessage(id: Long)
    @Query("DELETE FROM messages WHERE folderId=:id") suspend fun clearFolder(id: Long)
    @Insert suspend fun insertThread(row: ThreadRow): Long
    @Update suspend fun updateThread(row: ThreadRow)
    @Query("SELECT * FROM threads WHERE id=:id") suspend fun threadRow(id: Long): ThreadRow?
    @Query("SELECT threadId FROM thread_keys WHERE accountId=:account AND `key`=:key") suspend fun threadKey(account: Long, key: String): Long?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertKey(row: ThreadKeyRow)
    @Query("SELECT * FROM attachments WHERE messageId=:id") suspend fun attachments(id: Long): List<AttachmentRow>
    @Query("SELECT * FROM attachments WHERE id=:id") suspend fun attachment(id: Long): AttachmentRow?
    @Query("SELECT * FROM attachments WHERE localUri=:uri LIMIT 1") suspend fun attachmentUri(uri: String): AttachmentRow?
    @Insert suspend fun insertAttachment(row: AttachmentRow): Long
    @Query("DELETE FROM attachments WHERE messageId=:id") suspend fun clearAttachments(id: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDraft(row: DraftRow): Long
    @Query("SELECT * FROM drafts WHERE id=:id") suspend fun draft(id: Long): DraftRow?
    @Query("SELECT * FROM drafts WHERE sourceMessageId=:id LIMIT 1") suspend fun draftForMessage(id: Long): DraftRow?
    @Query("DELETE FROM drafts WHERE id=:id") suspend fun deleteDraft(id: Long)
    @Query("SELECT * FROM outbox WHERE state IN ('PENDING','SUBMITTING','APPEND','APPENDING','UNCERTAIN') ORDER BY dueAt,id") fun observeOutbox(): Flow<List<OutboxRow>>
    @Query("SELECT * FROM outbox WHERE state='APPENDING'") suspend fun appendingOutbox(): List<OutboxRow>
    @Insert suspend fun insertOutbox(row: OutboxRow): Long
    @Query("SELECT * FROM outbox WHERE id=:id") suspend fun outbox(id: Long): OutboxRow?
    @Query("SELECT * FROM outbox WHERE state='SUBMITTING'") suspend fun submittingOutbox(): List<OutboxRow>
    @Query("SELECT * FROM outbox WHERE state IN ('PENDING','APPEND') ORDER BY dueAt") suspend fun pendingOutbox(): List<OutboxRow>
    @Query("UPDATE outbox SET state=:next,error=:error WHERE id=:id AND state=:expected") suspend fun transitionOutbox(id: Long, expected: String, next: String, error: String? = null): Int
    @Query("UPDATE outbox SET mimePath=:path WHERE id=:id AND state='PENDING'") suspend fun outboxPath(id: Long, path: String): Int
    @Update suspend fun updateOutbox(row: OutboxRow)
    @Insert suspend fun insertOperation(row: OperationRow): Long
    @Query("SELECT * FROM operations WHERE accountId=:id ORDER BY id") suspend fun operations(id: Long): List<OperationRow>
    @Query("DELETE FROM operations WHERE id=:id") suspend fun deleteOperation(id: Long)
    @Update suspend fun updateOperation(row: OperationRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putContact(row: ContactRow)
    @Query("SELECT email, name, MAX(lastSeen) AS lastSeen, accountId FROM contacts WHERE email LIKE :prefix ESCAPE '!' OR name LIKE :prefix ESCAPE '!' GROUP BY email ORDER BY lastSeen DESC LIMIT :limit") suspend fun contacts(prefix: String, limit: Int): List<ContactRow>
}

@Database(entities = [AccountRow::class, FolderRow::class, ThreadRow::class, ThreadKeyRow::class, MessageRow::class, AttachmentRow::class, DraftRow::class, OutboxRow::class, OperationRow::class, ContactRow::class], version = 4, exportSchema = true)
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
