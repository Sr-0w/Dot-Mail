package app.dot.mail.data.local

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName="search_documents", foreignKeys=[ForeignKey(entity=MessageRow::class,parentColumns=["id"],childColumns=["messageId"],onDelete=ForeignKey.CASCADE)])
data class SearchDocumentRow(@PrimaryKey val messageId:Long,val body:String,val files:String,val attachmentText:String)
@Fts4(tokenizer=FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName="message_fts")
data class MessageFtsRow(@PrimaryKey @ColumnInfo(name="rowid") val rowid:Long,val subject:String,val sender:String,val recipients:String,val body:String,val files:String,val attachmentText:String)
@Entity(tableName="search_pending", foreignKeys=[ForeignKey(entity=MessageRow::class,parentColumns=["id"],childColumns=["messageId"],onDelete=ForeignKey.CASCADE)])
data class SearchPendingRow(@PrimaryKey val messageId:Long,val revision:Long)
data class IndexedSearchRow(val messageId:Long,val threadId:Long,val folderId:Long,val subject:String,val preview:String,val bodyAvailable:Boolean,val serverOnly:Boolean,val rankKey:String)

/** Persistent queues catch edits from every writer, including body eviction, attachment downloads and cascades. */
internal fun installSearchTriggers(db:SupportSQLiteDatabase) {
    fun enqueue(id:String)="UPDATE search_pending SET revision=revision+1 WHERE messageId=$id; INSERT INTO search_pending(messageId,revision) SELECT $id,1 WHERE EXISTS(SELECT 1 FROM messages WHERE id=$id) AND NOT EXISTS(SELECT 1 FROM search_pending WHERE messageId=$id);"
    db.execSQL("CREATE TRIGGER IF NOT EXISTS search_message_insert AFTER INSERT ON messages BEGIN ${enqueue("new.id")} END")
    db.execSQL("CREATE TRIGGER IF NOT EXISTS search_message_update AFTER UPDATE OF header,subject,senderSearch,recipientSearch,snippet,text,html,bodyLoaded,bodyPath ON messages WHEN new.header IS NOT old.header OR new.subject IS NOT old.subject OR new.senderSearch IS NOT old.senderSearch OR new.recipientSearch IS NOT old.recipientSearch OR new.snippet IS NOT old.snippet OR new.text IS NOT old.text OR new.html IS NOT old.html OR new.bodyLoaded IS NOT old.bodyLoaded OR new.bodyPath IS NOT old.bodyPath BEGIN ${enqueue("new.id")} END")
    db.execSQL("CREATE TRIGGER IF NOT EXISTS search_message_delete AFTER DELETE ON messages BEGIN DELETE FROM message_fts WHERE rowid=old.id; END")
    for(event in listOf("INSERT","UPDATE","DELETE")) {
        val row=if(event=="DELETE") "old" else "new"
        db.execSQL("CREATE TRIGGER IF NOT EXISTS search_attachment_${event.lowercase()} AFTER $event ON attachments BEGIN ${enqueue("$row.messageId")} END")
    }
}
