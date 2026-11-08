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
internal class EngineImapStore(private val mailSession: Session, private val timing: (String,Long)->Unit = { _,_ -> }) : IMAPStore(mailSession, URLName("imap", null, -1, null, null, null)) {
    override fun newIMAPProtocol(host: String, port: Int): IMAPProtocol {
        val start=System.nanoTime()
        return try { object : IMAPProtocol("imap", host, port, mailSession.properties, isSSL, logger) {
            private fun <T> auth(block:()->T):T { val start=System.nanoTime(); return try { block() } finally { timing("login",(System.nanoTime()-start)/1_000_000) } }
            override fun login(user:String,password:String) = auth { super.login(user,password) }
            override fun authlogin(user:String,password:String) = auth { super.authlogin(user,password) }
            override fun authplain(authzid:String?,user:String,password:String) = auth { super.authplain(authzid,user,password) }
            override fun authoauth2(user:String,token:String) = auth { super.authoauth2(user,token) }
            override fun getFetchItems(): Array<FetchItem> = (super.getFetchItems()?.toList().orEmpty() + listOf<FetchItem>(GmailThreadFetch.item)).toTypedArray()
        } } finally { timing("connect_tls_greeting",(System.nanoTime()-start)/1_000_000) }
    }
}
