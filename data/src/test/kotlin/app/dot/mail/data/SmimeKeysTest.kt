package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.MailDatabase
import app.dot.mail.data.model.*
import app.dot.mail.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmimeKeysTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun cert(name: String): X509Certificate = javaClass.classLoader!!.getResourceAsStream("smime/$name.pem")!!.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
    private fun idCert(name: String) = cert("$name.crt")
    private fun idKey(name: String): PrivateKey {
        val pem = javaClass.classLoader!!.getResourceAsStream("smime/$name.key.pem")!!.bufferedReader().readText()
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(pem.lines().filterNot { it.startsWith("-----") }.joinToString(""))))
    }
    private val chains = mutableMapOf<String, Array<X509Certificate>>()
    private val keys = mutableMapOf<String, PrivateKey>()
    private var failure: Exception? = null
    private fun keyAccess(cfg: SmimeAccountConfig) = KeyChainSmimeKeys(context, { cfg }, { _, alias -> failure?.let { throw it }; chains[alias] }, { _, alias -> keys[alias] })

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        graph = RealMailGraph(context, db, object : CredentialVault {
            override fun encrypt(accountEmail: String, secret: String) = secret
            override fun decrypt(accountEmail: String, encrypted: String) = encrypted
        }, scheduling = false)
        chains["sign"] = arrayOf(idCert("id-alice")); keys["sign"] = idKey("id-alice")
        chains["dec"] = arrayOf(idCert("id-bob")); keys["dec"] = idKey("id-bob")
    }
    @After fun close() { graph.scope.cancel(); db.close() }

    private fun unavailable(block: suspend () -> Unit): SmimeUnavailableException = runBlocking { runCatching { block() }.exceptionOrNull() as SmimeUnavailableException }

    @Test fun keysAreResolvedFromTheStoredAliases() = runBlocking<Unit> {
        val k = keyAccess(SmimeAccountConfig(signingKeyChainAlias = "sign", decryptionKeyChainAlias = "dec"))
        val signer = k.signingIdentity(1)
        assertEquals(idCert("id-alice"), signer.certificate)
        assertEquals(listOf(idCert("id-alice")), signer.certificateChain)
        assertEquals(idCert("id-bob"), k.decryptionCertificate(1))
        assertEquals(idCert("id-bob"), k.decryptionIdentity(1).certificate)
    }

    @Test fun noAliasMeansNotConfigured() {
        val k = keyAccess(SmimeAccountConfig())
        assertEquals(SmimeUnavailableException.Reason.NOT_CONFIGURED, unavailable { k.signingIdentity(1) }.reason)
        assertEquals(SmimeUnavailableException.Reason.NOT_CONFIGURED, unavailable { k.decryptionCertificate(1) }.reason)
    }

    @Test fun aVanishedAliasIsUnavailableNotACrash() {
        chains.remove("sign")
        val k = keyAccess(SmimeAccountConfig(signingKeyChainAlias = "sign", decryptionKeyChainAlias = "dec"))
        assertEquals(SmimeUnavailableException.Reason.KEY_MISSING, unavailable { k.signingIdentity(1) }.reason)
        keys.remove("dec")
        assertEquals(SmimeUnavailableException.Reason.KEY_MISSING, unavailable { k.decryptionCertificate(1) }.reason)
    }

    @Test fun keychainFailuresMapToUnavailable() {
        val k = keyAccess(SmimeAccountConfig(signingKeyChainAlias = "sign"))
        failure = SecurityException("grant revoked")
        assertEquals(SmimeUnavailableException.Reason.ACCESS_DENIED, unavailable { k.signingIdentity(1) }.reason)
        failure = IllegalStateException("must not be called from the main thread")
        assertEquals(SmimeUnavailableException.Reason.KEY_MISSING, unavailable { k.signingIdentity(1) }.reason)
    }

    @Test fun statusDescribesTheCertificateOrSaysPickAgain() = runBlocking<Unit> {
        val k = keyAccess(SmimeAccountConfig())
        assertEquals(SmimeKeyStatus.NotSet, k.status(null))
        assertEquals(SmimeKeyStatus.Unavailable, k.status("gone"))
        val ok = k.status("sign") as SmimeKeyStatus.Available
        assertEquals("Alice", ok.subject)
        assertTrue(ok.namesMailbox("ALICE@example.org"))
        assertFalse(ok.namesMailbox("bob@example.org"))
        val now = java.time.Instant.parse("2026-11-09T00:00:00Z").toEpochMilli()
        assertEquals(SmimeCertValidity.VALID, ok.validity(now))
        assertEquals(SmimeCertValidity.EXPIRED, ok.validity(ok.notAfter + 1))
        chains["old"] = arrayOf(cert("expired"))
        assertEquals(SmimeCertValidity.EXPIRED, (k.status("old") as SmimeKeyStatus.Available).validity(now))
        failure = SecurityException("x")
        assertEquals(SmimeKeyStatus.Unavailable, k.status("sign")) // status never throws
    }

    @Test fun accountSmimeSettingsRoundTripAndKeepTheRestOfTheConfig() = runBlocking<Unit> {
        val cfg = AccountConfig("me@example.org", "Me", ServerConfig("imap.example.org", 993, Security.SSL_TLS, "me"), ServerConfig("smtp.example.org", 465, Security.SSL_TLS, "me"))
        val id = graph.addAccount(cfg, "pw")
        assertEquals(SmimeAccountConfig(), graph.accounts.first().single().smime)
        val smime = SmimeAccountConfig("sign", "dec", signByDefault = true, encryptByDefault = true)
        graph.updateSmime(id, smime)
        assertEquals(smime, graph.accounts.first().single().smime)
        val stored = Codec.config(db.mail().account(id)!!.config)
        assertEquals(smime, stored.smime); assertEquals("imap.example.org", stored.imap.host); assertEquals("me", stored.smtp.username)
        graph.updateSmime(id, SmimeAccountConfig()) // "Remove"
        assertEquals(SmimeAccountConfig(), graph.accounts.first().single().smime)
        assertEquals("imap.example.org", Codec.config(db.mail().account(id)!!.config).imap.host)
    }

    @Test fun certificateListPinAndForgetGoThroughTheRepository() = runBlocking<Unit> {
        val cfg = AccountConfig("me@example.org", "Me", ServerConfig("h", 993, Security.SSL_TLS, "me"), ServerConfig("h", 465, Security.SSL_TLS, "me"))
        val id = graph.addAccount(cfg, "pw")
        graph.smimeCerts.collect(id, "alice@example.org", cert("alice-san"), true); graph.smimeCerts.collect(id, "alice@example.org", cert("alice-san-2"), true)
        val repo = graph.smime
        val list = repo.certificates(id).first()
        assertEquals(2, list.size)
        val fp = list.first().fingerprint
        assertTrue(repo.pin(id, "alice@example.org", fp)); assertEquals(fp, repo.pinnedFingerprint(id, "alice@example.org"))
        repo.unpin(id, "alice@example.org", fp); assertNull(repo.pinnedFingerprint(id, "alice@example.org"))
        assertTrue(repo.forget(id, "alice@example.org", fp)); assertEquals(1, repo.certificates(id).first().size)
        assertEquals(1, repo.certificatesFor(id, listOf("alice@example.org", "nobody@example.org")).getValue("alice@example.org").size)
        assertTrue(repo.certificatesFor(id, listOf("nobody@example.org")).getValue("nobody@example.org").isEmpty())
    }

    @Test fun twoQuickChangesBothSurviveBecauseEachReadsTheStoredConfig() = runBlocking<Unit> {
        val cfg = AccountConfig("me@example.org", "Me", ServerConfig("h", 993, Security.SSL_TLS, "me"), ServerConfig("h", 465, Security.SSL_TLS, "me"))
        val id = graph.addAccount(cfg, "pw")
        // Both pickers fire before either write is observed by the UI: each transform must start from what is stored.
        coroutineScope {
            launch { graph.updateSmime(id) { it.copy(signingKeyChainAlias = "sign") } }
            launch { graph.updateSmime(id) { it.copy(decryptionKeyChainAlias = "dec") } }
        }
        val stored = Codec.config(db.mail().account(id)!!.config).smime
        assertEquals("sign", stored.signingKeyChainAlias); assertEquals("dec", stored.decryptionKeyChainAlias)
    }

    @Test fun recipientStatusSeparatesMissingUnusableUsableAndPinnedUnavailable() = runBlocking<Unit> {
        val cfg = AccountConfig("me@example.org", "Me", ServerConfig("h", 993, Security.SSL_TLS, "me"), ServerConfig("h", 465, Security.SSL_TLS, "me"))
        val id = graph.addAccount(cfg, "pw")
        graph.smimeCerts.collect(id, "alice@example.org", cert("alice-san"), true)
        graph.smimeCerts.collect(id, "bob@example.org", cert("bob-subject-only"), true)
        val rejectBob = object : SmimeCertificatePolicy {
            override fun validate(certificate: X509Certificate, mailbox: String, use: SmimeCertificateUse) = validateChain(listOf(certificate), mailbox, use)
            override fun validateChain(certificates: Collection<X509Certificate>, mailbox: String, use: SmimeCertificateUse) =
                if (mailbox == "bob@example.org") SmimeCertificateVerdict.Rejected("not trusted") else SmimeCertificateVerdict.Trusted
        }
        graph.recipientPolicy = rejectBob
        val boxes = listOf("Alice@example.org", "bob@example.org", "nobody@example.org")
        assertEquals(mapOf("alice@example.org" to RecipientCertStatus.USABLE, "bob@example.org" to RecipientCertStatus.UNUSABLE, "nobody@example.org" to RecipientCertStatus.MISSING), graph.smime.recipientStatus(id, boxes))
        // A pin on a certificate that stopped being usable is reported distinctly, never replaced by another one.
        graph.smimeCerts.pin(id, "bob@example.org", SmimeCertStore.fingerprint(cert("bob-subject-only")))
        assertEquals(RecipientCertStatus.PINNED_UNAVAILABLE, graph.smime.recipientStatus(id, listOf("bob@example.org")).getValue("bob@example.org"))
        graph.recipientPolicy = object : SmimeCertificatePolicy {
            override fun validate(certificate: X509Certificate, mailbox: String, use: SmimeCertificateUse) = SmimeCertificateVerdict.Trusted
            override fun validateChain(certificates: Collection<X509Certificate>, mailbox: String, use: SmimeCertificateUse) = SmimeCertificateVerdict.Trusted
        }
        assertEquals(RecipientCertStatus.USABLE, graph.smime.recipientStatus(id, listOf("bob@example.org")).getValue("bob@example.org")) // trust is judged again at every call
    }
}
