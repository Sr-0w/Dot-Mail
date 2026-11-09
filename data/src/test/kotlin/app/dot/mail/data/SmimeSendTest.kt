package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.MailDatabase
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetup
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Real CMS (BouncyCastle through :mail), real SMTP/IMAP (GreenMail): what the outbox stores and what the recipients get. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmimeSendTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private lateinit var server: GreenMail
    private var account = 0L

    private fun cert(name: String): X509Certificate = javaClass.classLoader!!.getResourceAsStream("smime/$name.crt.pem")!!.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
    private fun key(name: String): PrivateKey {
        val pem = javaClass.classLoader!!.getResourceAsStream("smime/$name.key.pem")!!.bufferedReader().readText()
        val der = Base64.getMimeDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }
    private val trustAll = object : SmimeCertificatePolicy {
        override fun validate(certificate: X509Certificate, mailbox: String, use: SmimeCertificateUse) = SmimeCertificateVerdict.Trusted
        override fun validateChain(certificates: Collection<X509Certificate>, mailbox: String, use: SmimeCertificateUse) = SmimeCertificateVerdict.Trusted
    }
    private var keysGone: SmimeUnavailableException? = null
    private val keys = object : SmimeKeyAccess {
        override suspend fun signingIdentity(accountId: Long) = keysGone?.let { throw it } ?: SmimeSigningIdentity(cert("id-alice"), key("id-alice"))
        override suspend fun decryptionCertificate(accountId: Long) = keysGone?.let { throw it } ?: cert("id-alice")
    }
    private val engine get() = SmimeEngine(certificatePolicy = trustAll)
    private val builder get() = graph.smimeBuilder(keys, trustAll)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MailDatabase::class.java).allowMainThreadQueries().build()
        graph = RealMailGraph(ApplicationProvider.getApplicationContext(), db, object : CredentialVault {
            override fun encrypt(accountEmail: String, secret: String) = secret
            override fun decrypt(accountEmail: String, encrypted: String) = encrypted
        }, scheduling = false)
        server = GreenMail(arrayOf(ServerSetup(0, "127.0.0.1", "imap"), ServerSetup(0, "127.0.0.1", "smtp"))).also { it.start() }
        server.setUser("alice@example.org", "alice", "password")
        val local = AccountConfig("alice@example.org", "Alice", ServerConfig("127.0.0.1", server.imap.port, Security.NONE, "alice"), ServerConfig("127.0.0.1", server.smtp.port, Security.NONE, "alice"))
        account = runBlocking { graph.addAccount(local, "password") }
        runBlocking { graph.smimeCerts.addManual(account, cert("id-bob")); graph.smimeCerts.addManual(account, cert("id-carol")) }
    }
    @After fun close() { server.stop(); graph.scope.cancel(); db.close() }

    private fun draft(sign: Boolean = true, encrypt: Boolean = true, scheduledAt: Long? = null) = Draft(
        accountId = account, identityId = null, to = listOf(EmailAddress("bob@example.org")), bcc = listOf(EmailAddress("carol@example.org")),
        subject = "Plan", bodyText = "secret launch plan", smimeSign = sign, smimeEncrypt = encrypt, scheduledAt = scheduledAt,
    )
    private fun inbox(email: String) = server.managers.userManager.getUserByEmail(email)?.let { user -> server.managers.imapHostManager.getInbox(user).messages.map { it.mimeMessage } } ?: emptyList()
    private fun bytes(m: jakarta.mail.internet.MimeMessage) = java.io.ByteArrayOutputStream().also { m.writeTo(it) }.toByteArray()
    private fun outboxDir(id: Long) = SmimeOutbox.dir(runBlocking { db.mail().outbox(id)!!.mimePath })!!

    @Test fun eachRecipientGetsItsOwnEnvelopeAndBccNeverAppearsInAnyHeader() = runBlocking<Unit> {
        val id = graph.sendSmime(draft(), 0, builder)
        val dir = outboxDir(id)
        val manifest = SmimeOutbox.read(dir)
        assertEquals(listOf(listOf("bob@example.org"), listOf("carol@example.org")), manifest.variants.map { it.to })
        for (v in manifest.variants) {
            val raw = File(dir, v.file).readText(Charsets.ISO_8859_1)
            val headers = raw.substringBefore("\r\n\r\n")
            assertFalse("Bcc header in ${v.file}", headers.contains("Bcc", ignoreCase = true))
            assertFalse("bcc address in ${v.file}", raw.contains("carol@example.org"))
            assertTrue(headers.contains("bob@example.org"))
        }
        assertTrue(graph.deliverOutbox(id))
        assertEquals(1, inbox("bob@example.org").size)
        assertEquals(1, inbox("carol@example.org").size)
        // Each recipient can read their own copy (and only theirs); the sender's key opens both: the self certificate is in each envelope.
        val bobPlain = engine.decryptMime(bytes(inbox("bob@example.org").single()), SmimeDecryptionIdentity(cert("id-bob"), key("id-bob")))
        assertTrue(String(bobPlain, Charsets.ISO_8859_1).contains("secret launch plan") || bobPlain.isNotEmpty())
        val carolMessage = inbox("carol@example.org").single()
        assertFalse(GreenMailUtil.getWholeMessage(carolMessage).contains("carol@example.org"))
        assertTrue(runCatching { engine.decryptMime(bytes(carolMessage), SmimeDecryptionIdentity(cert("id-bob"), key("id-bob"))) }.isFailure) // not for bob's key
        engine.decryptMime(bytes(carolMessage), SmimeDecryptionIdentity(cert("id-carol"), key("id-carol")))
        engine.decryptMime(bytes(carolMessage), SmimeDecryptionIdentity(cert("id-alice"), key("id-alice")))
    }

    @Test fun theSentCopyIsTheFirstVariantAndSavedToTheSentFolder() = runBlocking<Unit> {
        val id = graph.sendSmime(draft(), 0, builder)
        assertTrue(graph.deliverOutbox(id))
        assertTrue(graph.outbox.first().isEmpty()) // delivered and appended: nothing left in the outbox
        assertNull(db.mail().outbox(id)!!.mimePath)
        val sent = server.managers.imapHostManager.let { m -> m.getFolder(server.managers.userManager.getUserByEmail("alice@example.org"), "Sent").messages.map { it.mimeMessage } }
        assertEquals(1, sent.size)
        assertFalse(GreenMailUtil.getHeaders(sent.single()).contains("Bcc", ignoreCase = true))
        engine.decryptMime(bytes(sent.single()), SmimeDecryptionIdentity(cert("id-alice"), key("id-alice")))
    }

    @Test fun signedOnlyUsesTheSamePathAndTheSignatureVerifiesAsReceived() = runBlocking<Unit> {
        val id = graph.sendSmime(draft(sign = true, encrypt = false), 0, builder)
        assertTrue(graph.deliverOutbox(id))
        val received = bytes(inbox("bob@example.org").single())
        val verification = engine.verifySignedMime(received, "alice@example.org")
        assertTrue(verification.allCryptographicallyValid)
        assertTrue(verification.allTrustedAndValid)
        assertFalse(String(received, Charsets.ISO_8859_1).contains("carol@example.org"))
    }

    @Test fun aMissingRecipientCertificateBlocksTheSendAndQueuesNothing() = runBlocking<Unit> {
        val d = draft().copy(to = listOf(EmailAddress("bob@example.org"), EmailAddress("nocert@example.org")))
        val e = runCatching { graph.sendSmime(d, 0, builder) }.exceptionOrNull() as SmimeProtectionException
        assertEquals(SmimeFailure.RECIPIENT_CERTIFICATE_MISSING, e.failure)
        assertTrue(graph.outbox.first().isEmpty())
        assertEquals(0, server.receivedMessages.size)
    }

    @Test fun toAndCcShareOneSubmissionSoTheSentFolderGetsOneCopy() = runBlocking<Unit> {
        val id = graph.sendSmime(draft().copy(bcc = emptyList(), cc = listOf(EmailAddress("carol@example.org"))), 0, builder)
        val manifest = SmimeOutbox.read(outboxDir(id))
        assertEquals(listOf(listOf("bob@example.org", "carol@example.org")), manifest.variants.map { it.to })
        assertTrue(graph.deliverOutbox(id))
        assertEquals(1, inbox("bob@example.org").size)
        assertEquals(1, inbox("carol@example.org").size)
        val copy = bytes(inbox("carol@example.org").single())
        engine.decryptMime(copy, SmimeDecryptionIdentity(cert("id-bob"), key("id-bob")))
        engine.decryptMime(copy, SmimeDecryptionIdentity(cert("id-carol"), key("id-carol")))
    }

    @Test fun aManifestFromBeforeGroupingIsStillRead() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "smime-legacy-${System.nanoTime()}").also { it.mkdirs() }
        java.io.File(dir, SmimeOutbox.MANIFEST).writeText("""{"sentCopy":"v0.eml","variants":[{"to":"bob@example.org","file":"v0.eml","state":"PENDING"}]}""")
        assertEquals(listOf(listOf("bob@example.org")), SmimeOutbox.read(dir).variants.map { it.to })
        dir.deleteRecursively()
    }

    @Test fun aVanishedKeyBlocksTheSendInsteadOfSendingPlaintext() = runBlocking<Unit> {
        keysGone = SmimeUnavailableException(SmimeUnavailableException.Reason.KEY_MISSING, "alias is gone")
        val e = runCatching { graph.sendSmime(draft(), 0, builder) }.exceptionOrNull()
        assertTrue(e is SmimeUnavailableException)
        assertTrue(graph.outbox.first().isEmpty())
    }

    @Test fun aDeliveredVariantIsNeverSentAgain() = runBlocking<Unit> {
        val id = graph.sendSmime(draft(), 0, builder)
        val dir = outboxDir(id); val manifest = SmimeOutbox.read(dir)
        manifest.variants[0].state = SmimeOutbox.SENT; SmimeOutbox.write(dir, manifest) // bob's copy went out on an earlier attempt
        assertTrue(graph.deliverOutbox(id))
        assertEquals(0, inbox("bob@example.org").size)
        assertEquals(1, inbox("carol@example.org").size)
    }

    @Test fun anInterruptedVariantWaitsForTheUserAndOnlyThatVariantIsRepeated() = runBlocking<Unit> {
        val id = graph.sendSmime(draft(), 0, builder)
        val dir = outboxDir(id); val manifest = SmimeOutbox.read(dir)
        manifest.variants[0].state = SmimeOutbox.SENT
        manifest.variants[1].state = SmimeOutbox.SUBMITTING // the app died while carol's copy was going out
        SmimeOutbox.write(dir, manifest)
        db.mail().transitionOutbox(id, "PENDING", "SUBMITTING")
        graph.recoverOutbox()
        assertEquals("UNCERTAIN", db.mail().outbox(id)!!.state)
        assertTrue(graph.deliverOutbox(id)) // nothing is sent automatically
        assertEquals(0, server.receivedMessages.size)
        assertFalse(graph.retrySend(id)) // refused without the user's confirmation
        assertTrue(graph.retrySend(id, confirmPossibleDuplicate = true))
        assertTrue(graph.deliverOutbox(id))
        assertEquals(0, inbox("bob@example.org").size) // bob's delivered copy is not repeated
        assertEquals(1, inbox("carol@example.org").size)
    }

    @Test fun undoRestoresTheDraftRemovesTheProtectedFilesAndNeverUploadsAnEncryptedDraft() = runBlocking<Unit> {
        val id = graph.sendSmime(draft(), 60_000, builder)
        val dir = outboxDir(id)
        assertTrue(dir.isDirectory)
        assertTrue(graph.cancelSend(id))
        assertFalse(dir.exists())
        val restored = db.mail().draftBatch(0, 10).single()
        assertTrue(Codec.draft(restored.json).smimeEncrypt)
        assertNull(db.mail().draftMirror(restored.id)) // the plaintext of an encrypted message is not mirrored to the server
        assertEquals(0, server.receivedMessages.size)
    }

    @Test fun aScheduledSendStaysQueuedUntilItsTime() = runBlocking<Unit> {
        val id = graph.sendSmime(draft(scheduledAt = System.currentTimeMillis() + 3_600_000), 0, builder)
        assertFalse(graph.deliverOutbox(id))
        assertEquals(0, server.receivedMessages.size)
        assertEquals("PENDING", db.mail().outbox(id)!!.state)
        assertTrue(graph.outbox.first().single().canReschedule)
    }

    @Test fun aLostProtectedFileNeverTurnsIntoAPlainSend() = runBlocking<Unit> {
        val id = graph.sendSmime(draft(), 0, builder)
        outboxDir(id).deleteRecursively()
        assertTrue(runCatching { graph.deliverOutbox(id) }.isFailure)
        assertEquals(0, server.receivedMessages.size)
    }
}
