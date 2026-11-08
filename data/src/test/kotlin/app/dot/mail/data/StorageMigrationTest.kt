package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.*
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class StorageMigrationTest {
    @Test fun v8UpgradePreservesMailAndBackfillsOnlySentAndOutboxCorrespondents()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>(); val name="storage-v9-migration.db"; context.deleteDatabase(name)
        val schema=javaClass.classLoader!!.getResourceAsStream("app.dot.mail.data.local.MailDatabase/8.json")!!.use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
        val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(8) {
            override fun onCreate(db:SupportSQLiteDatabase) {
                val entities=schema.getJSONArray("entities")
                for(i in 0 until entities.length()) {
                    val entity=entities.getJSONObject(i)
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
                    val indices=entity.getJSONArray("indices")
                    for(j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
                }
                val setup=schema.getJSONArray("setupQueries"); for(i in 0 until setup.length()) db.execSQL(setup.getString(i))
                db.execSQL("INSERT INTO accounts(id,email,name,color,config,secret,authError) VALUES(1,'fixture@example.org','Fixture',0,'{}','ciphertext',0)")
                for((id,role) in listOf(1 to "INBOX",2 to "SENT")) db.execSQL("INSERT INTO folders(id,accountId,path,name,role,unread,total,validity,nextUid,modSeq) VALUES(?,1,?,?,?,1,1,7,3,-1)",arrayOf<Any?>(id,role,role,role))
                val incoming=Header(1,"<incoming@fixture.invalid>",emptyList(),null,listOf(Address("unknown@example.org")),listOf(Address("fixture@example.org")),emptyList(),emptyList(),emptyList(),"Inbox",1,RemoteFlags(1,false,false,false),false)
                val sent=incoming.copy(uid=2,messageId="<sent@fixture.invalid>",from=listOf(Address("fixture@example.org")),to=listOf(Address("Friend@example.org")),cc=listOf(Address("cc@example.org")),bcc=listOf(Address("bcc@example.org")))
                for((id,header) in listOf(1 to incoming,2 to sent)) {
                    db.execSQL("INSERT INTO threads(id,accountId,subject,participants,snippet,lastAt,count,unread,starred,attachments) VALUES(?,1,'Fixture','[]','',1,1,1,0,0)",arrayOf(id))
                    db.execSQL("INSERT INTO messages(id,accountId,folderId,threadId,uid,header,subject,sentAt,unread,starred,hasAttachments,snippet,text,html,bodyLoaded,remoteImages,senderSearch,recipientSearch,snippetFetched,bodyPath,messageIdKey) VALUES(?,1,?,?,?,?,'Fixture',1,1,0,0,'',NULL,NULL,0,0,'sender','recipient',1,NULL,NULL)",arrayOf<Any?>(id,id,id,id,Codec.header(header)))
                }
                db.execSQL("INSERT INTO contacts(accountId,email,name,lastSeen) VALUES(1,'unknown@example.org','Unknown',10)")
                db.execSQL("INSERT INTO contacts(accountId,email,name,lastSeen) VALUES(1,'friend@example.org','Friend',10)")
                val huge=Codec.draft(Draft(accountId=1,identityId=1,to=listOf(EmailAddress("queued@example.org")),cc=listOf(EmailAddress("queued-cc@example.org")),bodyText="x".repeat(3*1024*1024)))
                db.execSQL("INSERT INTO outbox(id,accountId,draft,dueAt,state) VALUES(1,1,?,1,'PENDING')",arrayOf(huge))
                db.execSQL("INSERT INTO outbox(id,accountId,draft,dueAt,state) VALUES(2,1,?,1,'CANCELLED')",arrayOf(Codec.draft(Draft(accountId=1,identityId=1,to=listOf(EmailAddress("cancelled@example.org"))))))
            }
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=error("Room owns migration")
        }).build())
        helper.writableDatabase; helper.close()
        val upgraded=Room.databaseBuilder(context,MailDatabase::class.java,name).addMigrations(MIGRATION_8_9).allowMainThreadQueries().build()
        try {
            assertEquals("ciphertext",upgraded.mail().account(1)!!.secret)
            assertEquals(2,upgraded.mail().cachedMessageCount(1))
            for(email in listOf("friend@example.org","cc@example.org","bcc@example.org","queued@example.org","queued-cc@example.org")) assertTrue(email,upgraded.mail().contacts(email,10).single().corresponded)
            assertFalse(upgraded.mail().contacts("unknown@example.org",10).single().corresponded)
            assertTrue(upgraded.mail().contacts("cancelled@example.org",10).isEmpty())
            upgraded.openHelper.writableDatabase.query("SELECT email FROM message_senders ORDER BY messageId").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("unknown@example.org",cursor.getString(0)); assertTrue(cursor.moveToNext()); assertEquals("fixture@example.org",cursor.getString(0)) }
            upgraded.mail().putCacheAccess(CacheAccessRow(1,123,true)); assertTrue(upgraded.mail().cacheAccess(1)!!.offlineSaved)
            upgraded.mail().putOfflineFolder(OfflineFolderRow(1,7,beforeUid=2)); assertEquals(2L,upgraded.mail().offlineFolder(1)!!.beforeUid)
            assertTrue(upgraded.mail().deviceContactEmails().isEmpty())
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
