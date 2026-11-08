package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SearchMigrationTest {
    @Test fun v7UpgradeQueuesExistingMessagesAndIndexesLargeLegacyBodiesWithoutCursorOverflow()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>(); val name="search-migration-test.db"
        context.deleteDatabase(name)
        val schema=javaClass.classLoader!!.getResourceAsStream("app.dot.mail.data.local.MailDatabase/7.json")!!.use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
        val helper=FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object:SupportSQLiteOpenHelper.Callback(7) {
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
                db.execSQL("INSERT INTO folders(id,accountId,path,name,role,unread,total,validity,nextUid,modSeq) VALUES(1,1,'INBOX','Inbox','INBOX',1,1,7,2,-1)")
                db.execSQL("INSERT INTO threads(id,accountId,subject,participants,snippet,lastAt,count,unread,starred,attachments) VALUES(1,1,'Énergie','[]','',1,1,1,0,0)")
                db.execSQL("INSERT INTO messages(id,accountId,folderId,threadId,uid,header,subject,sentAt,unread,starred,hasAttachments,snippet,text,html,bodyLoaded,remoteImages,senderSearch,recipientSearch,snippetFetched,bodyPath,messageIdKey) VALUES(1,1,1,1,1,'{}','Énergie',1,1,0,0,'',?,NULL,1,0,'sender','recipient',1,NULL,NULL)",arrayOf("café "+"x".repeat(3*1024*1024)))
            }
            override fun onUpgrade(db:SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=error("Room owns upgrade")
        }).build())
        helper.writableDatabase; helper.close()
        val upgraded=Room.databaseBuilder(context,MailDatabase::class.java,name).addMigrations(MIGRATION_7_8).allowMainThreadQueries().build()
        val graph=RealMailGraph(context,upgraded,object:CredentialVault {
            override fun encrypt(accountEmail:String,secret:String)=secret
            override fun decrypt(accountEmail:String,encrypted:String)=encrypted
        },scheduling=false)
        try {
            assertEquals(1,upgraded.mail().searchPendingCount()); assertEquals("ciphertext",upgraded.mail().account(1)!!.secret)
            assertEquals(0,graph.searchIndex.drain())
            upgraded.openHelper.writableDatabase.query("SELECT rowid FROM message_fts WHERE message_fts MATCH 'cafe'").use { assertTrue(it.moveToFirst()); assertEquals(1L,it.getLong(0)) }
            upgraded.mail().deleteAccount(1)
            upgraded.openHelper.writableDatabase.query("SELECT COUNT(*) FROM message_fts").use { it.moveToFirst(); assertEquals(0,it.getInt(0)) }
        } finally { graph.scope.cancel(); upgraded.close(); context.deleteDatabase(name) }
    }
}
