package app.dot.mail.data.local

import androidx.room.*

@Entity(
    tableName = "thread_repairs",
    foreignKeys =
        [
            ForeignKey(
                entity = ThreadRow::class,
                parentColumns = ["id"],
                childColumns = ["threadId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class ThreadRepairRow(@PrimaryKey val threadId: Long, val state: String = "PENDING")

/** Durable bridge between the split transaction and SharedPreferences. */
@Entity(
    tableName = "repair_mutes",
    foreignKeys =
        [
            ForeignKey(
                entity = ThreadRow::class,
                parentColumns = ["id"],
                childColumns = ["threadId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class RepairMuteRow(@PrimaryKey val threadId: Long)

/** No body/attachment payloads in repair cursors. */
data class RepairMessage(
    val id: Long,
    val folderId: Long,
    val uid: Long,
    val header: String,
    val subject: String,
    val sentAt: Long,
)
