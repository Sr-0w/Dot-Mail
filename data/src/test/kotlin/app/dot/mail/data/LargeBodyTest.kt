package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LargeBodyTest {
    @Test fun oversizedLegacyRowsAreSafeForMetadataAndBoundedReading() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,MailDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao=db.mail()
            val account=dao.insertAccount(AccountRow(email="a@example.org",name="A",color=0,config="{}",secret="cipher"))
            val folder=dao.insertFolder(FolderRow(accountId=account,path="INBOX",name="INBOX",role="INBOX"))
            val thread=dao.insertThread(ThreadRow(accountId=account,subject="Large"))
            val id=dao.insertMessage(MessageRow(accountId=account,folderId=folder,threadId=thread,uid=1,header="{}",subject="Large",sentAt=1,unread=true,starred=false,hasAttachments=false,bodyLoaded=true,text="x".repeat(3_000_000)))
            val metadata=dao.folderMessages(folder).single()
            assertNull(metadata.text); assertTrue(metadata.bodyLoaded)
            val body=BodyStore(context,dao).read(metadata)!!
            assertEquals(512*1024,body.plainText!!.length); assertTrue(body.isTruncated)
            dao.updateMessage(metadata.copy(unread=false))
            assertEquals("x",dao.bodyChunk(id,1,1)!!.text)
        } finally { db.close() }
    }
}
