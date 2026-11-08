package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.MailDatabase
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Explicitly opted-in (DOTMAIL_LIVE=1 + external credentials file): replays queued folder create / nest / rename /
 * delete against the real test account — the part GreenMail can't exercise. Touches only one uniquely named
 * throwaway folder tree, which is removed in `finally` whatever happens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LiveFolderOpsTest {
    @Test fun folderReplayRoundTripOnRealServer() = runBlocking {
        val file = Path.of(System.getProperty("user.home"), ".config", "dotmail", "test-account.env")
        assumeTrue(System.getenv("DOTMAIL_LIVE") == "1" && Files.isRegularFile(file))
        val env = Files.readAllLines(file).filter { '=' in it && !it.trimStart().startsWith('#') }
            .associate { it.substringBefore('=').trim().removePrefix("export ") to it.substringAfter('=').trim().trim('"', '\'') }
        val email = requireNotNull(env["DOTMAIL_TEST_EMAIL"])
        val password = requireNotNull(env["DOTMAIL_TEST_PASSWORD"])
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        val graph = RealMailGraph(context, db, object : CredentialVault {
            override fun encrypt(accountEmail: String, secret: String) = "encrypted:$secret"
            override fun decrypt(accountEmail: String, encrypted: String) = encrypted.removePrefix("encrypted:")
        }, scheduling = false)
        val imap = ServerConfig("mail.infomaniak.com", 993, Security.SSL_TLS, email)
        val config = AccountConfig(email, "Live", imap, ServerConfig("mail.infomaniak.com", 587, Security.STARTTLS, email))
        val endpoint = imap.endpoint()
        val creds = Credentials(password)
        val root = "DotMailTest-" + UUID.randomUUID().toString().take(8)
        suspend fun serverPaths() = graph.client.folders(endpoint, creds).map { it.path }
        try {
            val account = graph.addAccount(config, password)
            assertThat(graph.syncAll()).isTrue()
            val sep = graph.folderSeparator(account)
            println("LIVE separator=$sep")

            val parent = graph.createFolder(account, null, root)
            assertThat(graph.syncAll()).isTrue()
            assertThat(serverPaths()).contains(root)

            val child = graph.createFolder(account, parent, "Projects")
            assertThat(graph.syncAll()).isTrue()
            assertThat(serverPaths()).contains("$root${sep}Projects")

            graph.renameFolder(child, "Clients")
            assertThat(graph.syncAll()).isTrue()
            assertThat(serverPaths()).contains("$root${sep}Clients")
            assertThat(serverPaths()).doesNotContain("$root${sep}Projects")

            graph.deleteFolder(child)
            assertThat(graph.syncAll()).isTrue()
            assertThat(serverPaths()).doesNotContain("$root${sep}Clients")

            graph.deleteFolder(parent)
            assertThat(graph.syncAll()).isTrue()
            assertThat(serverPaths()).doesNotContain(root)
            assertThat(db.mail().operations(account)).isEmpty()
        } finally {
            runCatching {
                serverPaths().filter { it.startsWith(root) }.sortedByDescending { it.length }
                    .forEach { graph.client.deleteFolder(endpoint, creds, it) }
            }
            graph.scope.cancel(); db.close()
        }
    }
}
