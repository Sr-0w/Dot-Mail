package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.backup.AccountsBackupCodec
import app.dot.mail.data.backup.BackupGuard
import app.dot.mail.data.local.MailDatabase
import app.dot.mail.data.model.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class AccountsBackupCodecTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun `bad accounts and values are skipped with warnings, good ones survive`() {
        val decoded = AccountsBackupCodec.decode(obj("""{
          "accounts":[
            {"email":"ok@example.org","displayName":"Ok","authType":"PASSWORD",
             "imap":{"host":"imap.example.org","port":993,"security":"SSL_TLS","username":"ok"},
             "smtp":{"host":"smtp.example.org","port":465,"security":"SSL_TLS","username":"ok"},
             "identities":[{"name":"Shop","email":"shop@example.org","replyTo":"nope","isDefault":true},{"email":"bad address"}],
             "settings":{"syncIntervalMinutes":7,"syncWindowDays":90,"notificationsEnabled":false}},
            {"email":"not-an-address","imap":{},"smtp":{}},
            {"email":"noports@example.org","imap":{"host":"h","port":0,"security":"SSL_TLS"},"smtp":{"host":"h","port":25,"security":"NONE"}},
            {"email":"ok@example.org","imap":{"host":"dup","port":1,"security":"NONE"},"smtp":{"host":"dup","port":1,"security":"NONE"}}
          ],
          "notificationActions":["DELETE","REPLY"]
        }"""))
        val a = decoded.backup.accounts.single()
        assertThat(a.email).isEqualTo("ok@example.org")
        assertThat(a.identities.map { it.email }).containsExactly("shop@example.org") // the invalid one is dropped
        assertThat(a.identities.single().replyTo).isNull() // invalid reply-to dropped, identity kept
        assertThat(a.settings.syncIntervalMinutes).isEqualTo(15) // 7 isn't a valid choice: default
        assertThat(a.settings.syncWindowDays).isEqualTo(90)
        assertThat(a.settings.notificationsEnabled).isFalse()
        assertThat(decoded.warnings).hasSize(2)
        assertThat(decoded.backup.notificationActions).containsExactly(NotificationAction.DELETE, NotificationAction.REPLY).inOrder()
    }

    @Test fun `junk documents decode to nothing instead of crashing`() {
        assertThat(AccountsBackupCodec.decode(obj("{}")).backup.accounts).isEmpty()
        assertThat(AccountsBackupCodec.decode(obj("""{"accounts":"x","sync":5,"notificationActions":[1,2]}""")).backup.accounts).isEmpty()
        val many = (1..80).joinToString(",") { """{"email":"a$it@example.org","imap":{"host":"h","port":993,"security":"NONE"},"smtp":{"host":"h","port":25,"security":"NONE"}}""" }
        assertThat(AccountsBackupCodec.decode(obj("""{"accounts":[$many]}""")).backup.accounts).hasSize(AccountsBackupCodec.MAX_ACCOUNTS)
    }

    @Test fun `the guard refuses anything shaped like a credential`() {
        BackupGuard.assertNoSecrets(buildJsonObject { put("host", "imap.example.org"); put("username", "alice") })
        for (key in listOf("password", "appPassword", "secret", "accessToken", "refresh_token", "apiKey", "oauthClientSecret")) {
            assertThrows(IllegalArgumentException::class.java) { BackupGuard.assertNoSecrets(buildJsonObject { put("nested", buildJsonObject { put(key, "x") }) }) }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupRoundTripTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val config = AccountConfig("alice@example.org", "Alice", ServerConfig("imap.example.org", 993, Security.SSL_TLS, "alice"), ServerConfig("smtp.example.org", 465, Security.SSL_TLS, "alice"))
    private val vault = object : CredentialVault {
        override fun encrypt(accountEmail: String, secret: String) = "encrypted:$secret"
        override fun decrypt(accountEmail: String, encrypted: String) = encrypted.removePrefix("encrypted:")
    }

    @Before fun setup() {
        IdentityStore.resetForTest(); NotificationRules.resetForTest()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        graph = RealMailGraph(context, db, vault, scheduling = false)
    }

    @After fun cleanup() { graph.scope.cancel(); db.close() }

    @Test fun `an export carries settings but never the password, and imports into a fresh install signed out`() = runBlocking<Unit> {
        val account = graph.addAccount(config, "hunter2-top-secret")
        val alias = graph.saveIdentity(Identity(0, account, "Shop", "shop@example.org", signature = "Thanks!", replyTo = "support@example.org"))
        graph.setDefaultIdentity(account, alias)
        AccountSettingsStore.get(context).update(account) { it.copy(syncIntervalMinutes = 60, syncWindowDays = 0, notificationsEnabled = false) }
        NotificationRules.get(context).setActions(NotificationAction.DELETE, NotificationAction.REPLY)

        val json = graph.exportBackup()
        assertThat(json).doesNotContain("hunter2")
        assertThat(json).doesNotContain("encrypted:")
        assertThat(json).contains("imap.example.org")
        assertThat(json).contains("shop@example.org")
        assertThat(json).contains("Thanks!")

        // A brand-new install.
        graph.scope.cancel(); db.close()
        context.getSharedPreferences("dotmail_identities", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("dotmail_account_settings", Context.MODE_PRIVATE).edit().clear().commit()
        IdentityStore.resetForTest(); NotificationRules.resetForTest()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        graph = RealMailGraph(context, db, vault, scheduling = false)

        val preview = graph.previewBackup(json)
        assertThat(preview.error).isNull()
        assertThat(preview.accountsNeedingSignIn).containsExactly("alice@example.org")
        assertThat(graph.accounts.first()).isEmpty() // a preview changes nothing

        val applied = graph.applyBackup(json)
        assertThat(applied.accountsNeedingSignIn).containsExactly("alice@example.org")
        val restored = graph.accounts.first().single()
        assertThat(restored.email).isEqualTo("alice@example.org")
        assertThat(restored.syncState).isInstanceOf(SyncState.Error::class.java)
        assertThat((restored.syncState as SyncState.Error).isAuthError).isTrue()
        assertThat(restored.identities.map { it.email }).containsExactly("alice@example.org", "shop@example.org").inOrder()
        val shop = restored.identities.single { it.email == "shop@example.org" }
        assertThat(shop.isDefault).isTrue(); assertThat(shop.signature).isEqualTo("Thanks!"); assertThat(shop.replyTo).isEqualTo("support@example.org")
        assertThat(AccountSettingsStore.get(context).current(restored.id)).isEqualTo(AccountSettings(syncIntervalMinutes = 60, syncWindowDays = 0, notificationsEnabled = false))
        assertThat(NotificationRules.get(context).actions.value).containsExactly(NotificationAction.DELETE, NotificationAction.REPLY).inOrder()
        assertThat(db.mail().account(restored.id)!!.secret).isEqualTo("encrypted:") // no credential came along

        // Importing again onto the same account updates it instead of duplicating it.
        val again = graph.applyBackup(json)
        assertThat(again.accountsNeedingSignIn).isEmpty()
        assertThat(graph.accounts.first()).hasSize(1)
        assertThat(graph.accounts.first().single().identities).hasSize(2)
    }

    @Test fun `files that aren't backups are refused politely`() = runBlocking<Unit> {
        assertThat(graph.previewBackup("not json").error).isNotNull()
        assertThat(graph.previewBackup("[1,2]").error).isNotNull()
        assertThat(graph.applyBackup("").error).isNotNull()
        assertThat(graph.accounts.first()).isEmpty()
    }
}
