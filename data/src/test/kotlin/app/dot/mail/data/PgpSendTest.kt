package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.MailDatabase
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PgpSendTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private val config = AccountConfig("alice@example.org", "Alice", ServerConfig("127.0.0.1", 1, Security.NONE, "alice"), ServerConfig("127.0.0.1", 1, Security.NONE, "alice"))

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MailDatabase::class.java).allowMainThreadQueries().build()
        graph = RealMailGraph(ApplicationProvider.getApplicationContext(), db, object : CredentialVault {
            override fun encrypt(accountEmail: String, secret: String) = secret
            override fun decrypt(accountEmail: String, encrypted: String) = encrypted
        }, scheduling = false)
    }
    @After fun close() { graph.scope.cancel(); db.close() }

    private fun draft(account: Long, sign: Boolean, encrypt: Boolean) = Draft(
        accountId = account, identityId = null, to = listOf(EmailAddress("bob@example.org")), cc = listOf(EmailAddress("carol@example.org")),
        subject = "Plan", bodyText = "secret launch plan", pgpSign = sign, pgpEncrypt = encrypt,
    )

    @Test fun encryptedSendStoresOnlyTheProtectedMimeInTheOutbox() = runBlocking<Unit> {
        val account = graph.addAccount(config, "password")
        var seen: List<String> = emptyList(); var entityText = ""
        val id = graph.sendProtected(draft(account, sign = true, encrypt = true), 0) { entity, sign, encrypt, recipients ->
            seen = recipients; entityText = String(entity)
            assertTrue(sign && encrypt)
            PgpProtected(ciphertext = "-----BEGIN PGP MESSAGE-----\n\nQUJD\n-----END PGP MESSAGE-----\n".toByteArray())
        }
        assertTrue(seen.containsAll(listOf("bob@example.org", "carol@example.org", "alice@example.org"))) // the sender can read the Sent copy
        assertTrue(entityText.contains("secret launch plan") || entityText.contains("c2VjcmV0")) // the protector received the plaintext entity...
        val row = db.mail().outbox(id)!!
        val file = File(row.mimePath!!)
        val wire = file.readText()
        assertTrue(wire.contains("multipart/encrypted"))
        assertFalse(wire.contains("secret launch plan")) // ...and nothing readable is stored in the MIME
        assertTrue(wire.contains("Subject: Plan"))
    }

    @Test fun signedSendKeepsTheSignedEntityVerbatim() = runBlocking<Unit> {
        val account = graph.addAccount(config, "password")
        var signedEntity = ByteArray(0)
        val id = graph.sendProtected(draft(account, sign = true, encrypt = false), 0) { entity, _, encrypt, _ ->
            assertFalse(encrypt); signedEntity = entity
            PgpProtected(signature = "-----BEGIN PGP SIGNATURE-----\n\nQUJD\n-----END PGP SIGNATURE-----\n".toByteArray(), micalg = "pgp-sha512")
        }
        val raw = File(db.mail().outbox(id)!!.mimePath!!).readBytes()
        assertTrue(String(raw).contains("multipart/signed"))
        assertTrue(String(raw).contains("micalg=pgp-sha512"))
        val dir = java.nio.file.Files.createTempDirectory("pgp").toFile()
        Mime.cache(jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(java.util.Properties()), raw.inputStream()), dir)
        assertArrayEquals(signedEntity, File(dir, Pgp.SIGNED_FILE).readBytes())
    }

    @Test fun aFailingProtectorQueuesNothing() = runBlocking<Unit> {
        val account = graph.addAccount(config, "password")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { graph.sendProtected(draft(account, true, true), 0) { _, _, _, _ -> throw IllegalStateException("user cancelled") } }
        }
        assertTrue(db.mail().activeOutbox().isEmpty())
    }

    @Test fun encryptedDraftsAreNeverMirroredToTheServerAndEarlierCopiesAreRemoved() = runBlocking<Unit> {
        val account = graph.addAccount(config, "password")
        val plain = graph.saveDraft(draft(account, false, false))
        assertNotNull(db.mail().draftMirror(plain))
        assertFalse(db.mail().draftMirror(plain)!!.deleted)
        graph.saveDraft(draft(account, false, true).copy(localId = plain)) // user turns encryption on
        assertTrue(db.mail().draftMirror(plain)!!.deleted)
        val encrypted = graph.saveDraft(draft(account, false, true))
        assertNull(db.mail().draftMirror(encrypted))
        assertTrue(Codec.draft(db.mail().draft(encrypted)!!.json).pgpEncrypt)
    }
}
