package app.dot.mail.data.local

import androidx.room.*

@Entity(tableName="message_senders",primaryKeys=["messageId","email"],foreignKeys=[ForeignKey(entity=MessageRow::class,parentColumns=["id"],childColumns=["messageId"],onDelete=ForeignKey.CASCADE)],indices=[Index("email")])
data class MessageSenderRow(val messageId:Long,val email:String)
@Entity(tableName="device_contacts",primaryKeys=["email","generation"])
data class DeviceContactRow(val email:String,val generation:Long)
@Entity(tableName="device_contact_state")
data class DeviceContactStateRow(@PrimaryKey val id:Int=1,val generation:Long,val allowed:Boolean)
