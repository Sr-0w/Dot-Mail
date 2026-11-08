package app.dot.mail.engine

import jakarta.mail.Message

/** IMAP HEADER.FIELDS takes exact field names, not wildcard patterns. No body/all-header FETCH. */
internal object ClassificationHeaders {
    val names = listOf("List-Id", "List-Unsubscribe", "Precedence", "Auto-Submitted", "Feedback-ID",
        "X-Mailchimp-ID", "X-Mailchimp-EID", "X-Campaign", "X-Campaign-ID", "X-CampaignId", "X-GitHub-Reason")

    fun read(message: Message): Map<String,List<String>> = names.mapNotNull { name ->
        message.getHeader(name)?.take(3)?.map { it.take(2_000) }?.takeIf { it.isNotEmpty() }?.let { name to it }
    }.toMap()
}
