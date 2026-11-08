package app.dot.mail.data

import app.dot.mail.data.local.*
import app.dot.mail.data.model.EmailAddress
import jakarta.mail.internet.InternetAddress
import java.util.Locale

/** Actual mailbox addresses only; display names and substrings never confer correspondence. */
internal fun canonicalContactEmail(raw:String):String? = runCatching {
    if(raw.length>1024) return@runCatching null
    val address=InternetAddress.parse(raw,true).singleOrNull() ?: return@runCatching null
    address.validate()
    address.address.trim().lowercase(Locale.ROOT).takeIf { it.length in 3..320 && '@' in it }
}.getOrNull()
internal suspend fun RealMailGraph.markCorresponded(accountId:Long,addresses:List<EmailAddress>,at:Long=System.currentTimeMillis()) {
    for(address in addresses) canonicalContactEmail(address.email)?.let { dao.putContact(ContactRow(accountId,it,address.name?.take(512),at,corresponded=true)) }
}
internal suspend fun RealMailGraph.indexSenders(messageId:Long,header:app.dot.mail.engine.Header) {
    dao.clearMessageSenders(messageId)
    header.from.mapNotNull { canonicalContactEmail(it.email) }.distinct().take(256).forEach { dao.putMessageSender(MessageSenderRow(messageId,it)) }
}
