package app.dot.mail.engine

import jakarta.mail.FetchProfile
import jakarta.mail.Session
import jakarta.mail.URLName
import org.eclipse.angus.mail.imap.IMAPStore
import org.eclipse.angus.mail.imap.protocol.FetchItem
import org.eclipse.angus.mail.imap.protocol.FetchResponse
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol

/** Register the Gmail FETCH item with Angus's parser even when using standard IMAP. */
internal object GmailThreadFetch {
    val profile = object : FetchProfile.Item("X-GM-THRID") {}
    val item = object : FetchItem("X-GM-THRID", profile) {
        override fun parseItem(response: FetchResponse): Any = response.readLong()
    }
}
internal class EngineImapStore(private val mailSession: Session) : IMAPStore(mailSession, URLName("imap", null, -1, null, null, null)) {
    override fun newIMAPProtocol(host: String, port: Int): IMAPProtocol =
        object : IMAPProtocol("imap", host, port, mailSession.properties, isSSL, logger) {
            override fun getFetchItems(): Array<FetchItem> = (super.getFetchItems()?.toList().orEmpty() + listOf<FetchItem>(GmailThreadFetch.item)).toTypedArray()
        }
}
