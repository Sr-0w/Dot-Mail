package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.*
import app.dot.mail.engine.SmimeParts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Bodies cached before the S/MIME parser kept the signature as an attachment; they must be fetched again, once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LegacySmimeBodyTest {
    private fun withStore(block: suspend (MailDao, BodyStore, (String, String?) -> MessageRow) -> Unit) = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,MailDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao=db.mail()
            val account=dao.insertAccount(AccountRow(email="a@example.org",name="A",color=0,config="{}",secret="cipher"))
            val folder=dao.insertFolder(FolderRow(accountId=account,path="INBOX",name="INBOX",role="INBOX"))
            val thread=dao.insertThread(ThreadRow(accountId=account,subject="S"))
            var uid=0L
            val message: (String, String?) -> MessageRow = { name, attachmentMime ->
                val dir=File(context.filesDir,"bodies/test/$name").apply { mkdirs(); File(this,"plain.txt").writeText("hello"); File(this,".complete").writeText("") }
                val id=runBlocking {
                    val id=dao.insertMessage(MessageRow(accountId=account,folderId=folder,threadId=thread,uid=++uid,header="{}",subject="S",sentAt=1,unread=false,starred=false,hasAttachments=true,bodyLoaded=true,bodyPath=dir.path))
                    if(attachmentMime!=null) dao.insertAttachment(AttachmentRow(messageId=id,partId="1.1",name="smime.p7s",mime=attachmentMime,size=10,cid=null,inline=false,localUri=null))
                    id
                }
                runBlocking { dao.message(id)!! }
            }
            block(dao, BodyStore(context,dao), message)
        } finally { db.close() }
    }

    @Test fun `legacy signed body is not loaded so the reader fetches it again`() = withStore { _, store, message ->
        val row=message("legacy","application/pkcs7-signature; name=smime.p7s")
        assertNull(store.read(row))
        assertTrue(store.legacySmime(row.id, File(row.bodyPath!!)))
    }

    @Test fun `body cached with the current parser is read normally`() = withStore { _, store, message ->
        val row=message("current","application/pkcs7-signature")
        File(row.bodyPath!!, BodyStore.FORMAT_MARKER).writeText("")
        assertEquals("hello", store.read(row)!!.plainText)
    }

    @Test fun `legacy body that already has S MIME files is read normally`() = withStore { _, store, message ->
        val row=message("hasfiles","application/x-pkcs7-signature")
        File(row.bodyPath!!, SmimeParts.SIGNED_FILE).writeText("entity"); File(row.bodyPath!!, SmimeParts.SIGNATURE_FILE).writeText("sig")
        assertNotNull(store.read(row)!!.smime)
    }

    @Test fun `legacy body without S MIME is marked once and never refetched`() = withStore { _, store, message ->
        val row=message("plain","application/pdf")
        assertEquals("hello", store.read(row)!!.plainText)
        assertTrue(File(row.bodyPath!!, BodyStore.FORMAT_MARKER).exists())
    }
}
