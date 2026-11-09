package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.*
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v10 -> v11 adds the recipient-certificate table and nothing else. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmimeMigrationTest {
    @Test fun v10UpgradeKeepsEverythingAndAddsAWorkingCertificateTable() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "smime-v11-migration.db"; context.deleteDatabase(name)
        val schema = javaClass.classLoader!!.getResourceAsStream("app.dot.mail.data.local.MailDatabase/10.json")!!.use { JSONObject(it.bufferedReader().readText()).getJSONObject("database") }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(10) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.getJSONArray("indices")
                    for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries"); for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                db.execSQL("INSERT INTO accounts(id,email,name,color,config,secret,authError) VALUES(1,'fixture@example.org','Fixture',0,'{}','ciphertext',0)")
                db.execSQL("INSERT INTO contacts(accountId,email,name,lastSeen) VALUES(1,'friend@example.org','Friend',10)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Room owns migration")
        }).build())
        helper.writableDatabase; helper.close()
        // Room validates the migrated schema against the exported 11.json, so a mismatch fails here.
        val upgraded = Room.databaseBuilder(context, MailDatabase::class.java, name).addMigrations(MIGRATION_10_11).allowMainThreadQueries().build()
        try {
            assertEquals("ciphertext", upgraded.mail().account(1)!!.secret)
            assertEquals("friend@example.org", upgraded.mail().contacts("friend", 10).single().email)
            assertTrue(upgraded.mail().smimeCerts(1, "a@example.org").isEmpty())
            upgraded.mail().putSmimeCert(SmimeRecipientCertRow(1, "a@example.org", "ab".repeat(32), byteArrayOf(1, 2), "CN=A", "CN=CA", 1, 2, 3, 3, "MANUAL", "ACTIVE", false))
            assertEquals(1, upgraded.mail().smimeCerts(1, "a@example.org").size)
            upgraded.mail().deleteAccount(1) // cascades
            assertTrue(upgraded.mail().smimeCerts(1, "a@example.org").isEmpty())
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
