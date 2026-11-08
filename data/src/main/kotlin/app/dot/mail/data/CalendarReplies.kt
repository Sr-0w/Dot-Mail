package app.dot.mail.data

import android.net.Uri
import app.dot.mail.data.local.OutboxRow
import app.dot.mail.data.model.Draft
import app.dot.mail.data.model.EmailAddress
import app.dot.mail.engine.calendar.Ics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant

/** All parsing stays local; organizer and attendee come from the invitation, never from UI-supplied addresses. */
internal suspend fun RealMailGraph.queueCalendarReply(messageId: Long, partstat: Ics.PartStat): Long = withContext(Dispatchers.IO) {
    require(partstat != Ics.PartStat.NEEDS_ACTION) { "Choose Accept, Maybe or Decline" }
    loadBody(messageId)
    val message = dao.message(messageId) ?: error("Invitation is no longer available")
    val account = dao.account(message.accountId) ?: error("Account is no longer available")
    val identities = identityStore.resolve(account.id,account.name,account.email)
    val invitations = dao.attachments(messageId).filter { it.mime.startsWith("text/calendar",true) || it.name.endsWith(".ics",true) }
        .flatMap { part ->
            val uri = part.localUri ?: error("Invitation attachment is not available")
            val bytes = context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                val output=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(8192)
                while(true) {
                    val count=input.read(buffer)
                    if(count<0) break
                    require(output.size()+count<=512*1024) { "Invitation is too large" }
                    output.write(buffer,0,count)
                }
                output.toByteArray()
            } ?: error("Invitation attachment is not available")
            Ics.parse(bytes.toString(Charsets.UTF_8))
        }.distinctBy { it.uid to it.sequence }
    val candidates = invitations.filter { it.method==Ics.Method.REQUEST && !it.status.equals("CANCELLED",true) }
        .mapNotNull { invite -> identities.firstOrNull { identity -> invite.attendees.any { it.email.equals(identity.email,true) } }?.let { invite to it } }
    require(candidates.size==1) { if(candidates.isEmpty()) "No invitation for an identity of this account" else "This message contains multiple invitations" }
    val (invite,identity)=candidates.single()
    val reply = Ics.reply(invite,identity.email,partstat,Instant.now())
    val label=when(partstat) { Ics.PartStat.ACCEPTED -> "Accepted"; Ics.PartStat.TENTATIVE -> "Tentative"; Ics.PartStat.DECLINED -> "Declined"; else -> error("Invalid response") }
    val subject="$label: ${invite.summary}".replace(Regex("[\\r\\n]+")," ")
    val draft=Draft(accountId=account.id,identityId=identity.id,to=listOf(EmailAddress(invite.organizer.email,invite.organizer.name)),subject=subject,bodyText="$label: ${invite.summary}",inReplyTo=Codec.field(message.header,"messageId"),references=(Codec.strings(Codec.field(message.header,"refs") ?: "[]")+listOfNotNull(Codec.field(message.header,"messageId"))).distinct())
    // Validate addresses/headers before persisting a queued action that cannot be prepared later.
    require(listOfNotNull(invite.organizer.email,invite.organizer.name).none { '\r' in it || '\n' in it }) { "Invalid organizer address" }
    jakarta.mail.internet.InternetAddress(invite.organizer.email).validate()
    val dueAt=System.currentTimeMillis()+5_000
    val id=dao.insertOutbox(OutboxRow(accountId=account.id,draft=JSONObject(Codec.draft(draft)).put("calendarReply",reply).put("calendarSender",identity.email).toString(),dueAt=dueAt))
    if(scheduling) WorkScheduling.outbox(context,id,dueAt)
    id
}
