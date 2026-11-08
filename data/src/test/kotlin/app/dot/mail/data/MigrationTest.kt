package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.MailDatabase
import app.dot.mail.data.local.MIGRATION_1_2
import app.dot.mail.data.local.MIGRATION_2_3
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MigrationTest {
    @Test fun v1UpgradePreservesAccountAndAddsPreviewCheckpoint() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val schema = javaClass.classLoader!!.getResourceAsStream("app.dot.mail.data.local.MailDatabase/1.json")!!.use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                    val indices = entity.getJSONArray("indices")
                    for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                db.execSQL("INSERT INTO accounts(id,email,name,color,config,secret,syncError) VALUES(1,'a@example.org','A',0,'{}','ciphertext',NULL)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Room owns the upgrade")
        }).build())
        helper.writableDatabase; helper.close()
        val upgraded = Room.databaseBuilder(context, MailDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3).allowMainThreadQueries().build()
        try {
            assertEquals("a@example.org", upgraded.mail().account(1)!!.email)
            val cursor = upgraded.openHelper.writableDatabase.query("PRAGMA table_info(messages)")
            val columns = cursor.use { buildSet { while (it.moveToNext()) add(it.getString(it.getColumnIndexOrThrow("name"))) } }
            assertTrue("snippetFetched" in columns)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
