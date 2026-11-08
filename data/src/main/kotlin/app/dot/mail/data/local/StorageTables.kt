package app.dot.mail.data.local

import androidx.room.*

@Entity(tableName="cache_access",foreignKeys=[ForeignKey(entity=MessageRow::class,parentColumns=["id"],childColumns=["messageId"],onDelete=ForeignKey.CASCADE)])
data class CacheAccessRow(@PrimaryKey val messageId:Long,val accessedAt:Long,val offlineSaved:Boolean=false,val failureAt:Long=0,val downloadError:String?=null)
/** A backwards header scan resumes by UID; completedThrough bounds the next scan after new arrivals. */
@Entity(tableName="offline_folders",foreignKeys=[ForeignKey(entity=FolderRow::class,parentColumns=["id"],childColumns=["folderId"],onDelete=ForeignKey.CASCADE)])
data class OfflineFolderRow(@PrimaryKey val folderId:Long,val validity:Long,val beforeUid:Long=0,val scanThrough:Long=0,val completedThrough:Long=0,val complete:Boolean=false,val visitedAt:Long=0,val headerFailures:Int=0)
data class StorageCandidate(val id:Long,val bodyLoaded:Boolean,val sentAt:Long,val folderId:Long,val uid:Long,val accessedAt:Long,val offlineSaved:Boolean)
data class StoredAttachment(val id:Long,val messageId:Long,val localUri:String?)

data class CacheReferenceRow(val id:Long,val accountId:Long,val attachmentJson:String)

data class StoragePathRow(val id:Long,val threadId:Long,val bodyPath:String?,val protected:Boolean)

data class OfflineCounts(val headers:Int,val bodies:Int,val attachments:Int,val downloadedAttachments:Int,val serverTotal:Int)

internal const val STORAGE_SELECTION = "m.accountId=:id AND (COALESCE(c.offlineSaved,0)=1 OR ((:keep=0 OR m.id IN(SELECT id FROM messages WHERE accountId=:id ORDER BY sentAt DESC,id DESC LIMIT :keep)) AND (:afterDate<=0 OR m.sentAt>=:afterDate)))"
