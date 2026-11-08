package app.dot.mail.data.model

/** ALL plus maxBytes=0 means unlimited by policy; available device space still bounds downloads. */
enum class RetentionMode { LATEST_MESSAGES, LAST_DAYS, ALL }
data class StoragePolicy(
    val retention:RetentionMode=RetentionMode.LAST_DAYS,
    val latestMessages:Int=500,
    val days:Int=30,
    val maxBytes:Long=512L*1024*1024,
    /** False: sync headers and cache bodies only when opened. */
    val downloadBodies:Boolean=false,
    val downloadAttachments:Boolean=false,
    val wifiOnly:Boolean=true,
    val chargingOnly:Boolean=false,
)
enum class OfflineState { IDLE, QUEUED, DOWNLOADING, PAUSED, WAITING_FOR_NETWORK, WAITING_FOR_CHARGING, STORAGE_LIMIT, DEVICE_FULL, COMPLETE, PARTIAL, ERROR }
data class OfflineProgress(val accountId:Long,val state:OfflineState=OfflineState.IDLE,val cachedMessages:Int=0,val totalMessages:Int=0,val downloadedBodies:Int=0,val error:String?=null,val skippedMessages:Int=0,val downloadedAttachments:Int=0,val totalAttachments:Int=0)
data class AccountStorageUsage(val accountId:Long,val headers:Int,val cachedBodies:Int,val bodyBytes:Long,val attachmentBytes:Long,val protectedBytes:Long,val indexedTextBytes:Long)
data class StorageUsage(val accounts:List<AccountStorageUsage> = emptyList(),val databaseBytes:Long=0,val outboxBytes:Long=0,val draftAttachmentBytes:Long=0,val availableBytes:Long=0,val isApproximate:Boolean=false,val orphanCacheBytes:Long=0) {
    val cacheBytes:Long get()=accounts.sumOf { it.bodyBytes+it.attachmentBytes }
    val totalBytes:Long get()=cacheBytes+databaseBytes+outboxBytes+draftAttachmentBytes+orphanCacheBytes
}
data class CacheCleanupResult(val freedBytes:Long,val skippedProtectedMessages:Int)
