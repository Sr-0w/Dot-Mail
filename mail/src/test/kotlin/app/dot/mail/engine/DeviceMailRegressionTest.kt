package app.dot.mail.engine

import app.dot.mail.engine.text.Snippets
import org.junit.Assert.*
import org.junit.Test

class DeviceMailRegressionTest {
    @Test fun specialUseWinsAndLosingFolderKeepsItsRealName() {
        val folders = MailClient.uniqueRoles(listOf(
            RemoteFolder("Spam", "Spam", Role.SPAM,0,0),
            RemoteFolder("Junk", "Junk", Role.SPAM,10,207,specialUse=true),
            RemoteFolder("Receipts", "Receipts", Role.CUSTOM,0,5),
        ))
        assertEquals(1,folders.count { it.role==Role.SPAM })
        assertEquals(Role.SPAM,folders.single { it.path=="Junk" }.role)
        assertEquals(Role.CUSTOM,folders.single { it.path=="Spam" }.role)
        assertEquals("Spam",folders.single { it.path=="Spam" }.name)
    }
    @Test fun whitespaceOnlyNodesBrAndBlockBoundariesRemainReadable() {
        val expected = "Any news on this ? Kind regards, Robin Sent from my Galaxy"
        assertEquals(expected,Snippets.fromHtml("<span>Any news on this ?</span>\n<span>Kind regards,</span>\n<span>Robin</span>\n<span>Sent from my Galaxy</span>"))
        assertEquals(expected,Snippets.fromHtml("<p>Any news on this ?</p><div>Kind regards,<br>Robin<br>Sent from my Galaxy</div>"))
        assertEquals(expected,Snippets.fromPlainText("Any news on this ?\r\nKind regards,\r\nRobin\r\nSent from my Galaxy"))
        assertEquals("Hello",Snippets.fromHtml("<b>Hel</b>lo"))
    }
}
