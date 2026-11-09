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
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmimeCertStoreTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private lateinit var store: SmimeCertStore
    private var clock = 1_000L
    private val config = AccountConfig("me@example.org", "Me", ServerConfig("127.0.0.1", 1, Security.NONE, "me"), ServerConfig("127.0.0.1", 1, Security.NONE, "me"))

    private fun cert(name: String): X509Certificate = javaClass.classLoader!!.getResourceAsStream("smime/$name.pem")!!.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MailDatabase::class.java).allowMainThreadQueries().build()
        graph = RealMailGraph(ApplicationProvider.getApplicationContext(), db, object : CredentialVault {
            override fun encrypt(accountEmail: String, secret: String) = secret
            override fun decrypt(accountEmail: String, encrypted: String) = encrypted
        }, scheduling = false)
        store = SmimeCertStore(db.mail()) { clock++ }
    }
    @After fun close() { graph.scope.cancel(); db.close() }

    private suspend fun account() = graph.addAccount(config, "pw")

    @Test fun onlyVerifiedSignaturesAreCollected() = runBlocking<Unit> {
        val a = account()
        assertFalse(store.collect(a, "alice@example.org", cert("alice-san"), signatureVerified = false))
        assertTrue(store.candidates(a, "alice@example.org").isEmpty())
        assertTrue(store.collect(a, "ALICE@example.org ", cert("alice-san"), signatureVerified = true))
        assertEquals(1, store.candidates(a, "alice@example.org").size)
    }

    @Test fun aCertificateIsOnlyStoredForTheMailboxItNames() = runBlocking<Unit> {
        val a = account()
        // A signed message "from" bob carrying Mallory's certificate must not give Bob a certificate.
        assertFalse(store.collect(a, "bob@example.org", cert("mallory"), signatureVerified = true))
        assertTrue(store.candidates(a, "bob@example.org").isEmpty())
        assertTrue(store.collect(a, "mallory@example.org", cert("mallory"), signatureVerified = true))
    }

    @Test fun olderCertificatesNameTheirMailboxInTheSubject() = runBlocking<Unit> {
        val a = account()
        assertEquals(setOf("bob@example.org"), SmimeCertStore.certificateMailboxes(cert("bob-subject-only")))
        assertTrue(store.collect(a, "bob@example.org", cert("bob-subject-only"), true))
    }

    @Test fun keyedPerAccountAndFingerprintAndRefreshedNotDuplicated() = runBlocking<Unit> {
        val a = account(); val b = graph.addAccount(config.copy(email = "other@example.org"), "pw")
        store.collect(a, "alice@example.org", cert("alice-san"), true)
        store.collect(a, "alice@example.org", cert("alice-san"), true) // same fingerprint again
        store.collect(a, "alice@example.org", cert("alice-san-2"), true) // a second certificate
        assertEquals(2, store.candidates(a, "alice@example.org").size)
        assertTrue(store.candidates(b, "alice@example.org").isEmpty())
        val seen = store.observe(a).first().single { it.fingerprint == SmimeCertStore.fingerprint(cert("alice-san")) }
        assertTrue(seen.lastSeen > seen.firstSeen) // refreshed, firstSeen kept
        assertEquals(64, seen.fingerprint.length)
    }

    @Test fun validityMetadataIsStoredAndEvaluatedAtTheGivenTime() = runBlocking<Unit> {
        val a = account()
        store.collect(a, "old@example.org", cert("expired"), true); store.collect(a, "later@example.org", cert("future"), true)
        store.collect(a, "alice@example.org", cert("alice-san"), true)
        val all = store.observe(a).first().associateBy { it.mailbox }
        val now = java.time.Instant.parse("2026-11-09T00:00:00Z").toEpochMilli()
        assertEquals(SmimeCertValidity.EXPIRED, all.getValue("old@example.org").validity(now))
        assertEquals(SmimeCertValidity.NOT_YET_VALID, all.getValue("later@example.org").validity(now))
        assertEquals(SmimeCertValidity.VALID, all.getValue("alice@example.org").validity(now))
        assertTrue(all.getValue("alice@example.org").subject.contains("Alice"))
        // Cache is not trust: an expired certificate is still returned as a candidate; the policy rejects it at send.
        assertEquals(1, store.candidates(a, "old@example.org").size)
    }

    @Test fun pinPutsOneCertificateFirstAndUnpinReverts() = runBlocking<Unit> {
        val a = account()
        store.collect(a, "alice@example.org", cert("alice-san"), true); store.collect(a, "alice@example.org", cert("alice-san-2"), true)
        val one = SmimeCertStore.fingerprint(cert("alice-san")); val two = SmimeCertStore.fingerprint(cert("alice-san-2"))
        assertTrue(store.pin(a, "alice@example.org", one))
        assertTrue(store.pin(a, "alice@example.org", two)) // replaces the earlier pin
        assertEquals(two, store.pinnedFingerprint(a, "alice@example.org"))
        assertEquals(two, SmimeCertStore.fingerprint(store.candidates(a, "alice@example.org").first().certificate))
        store.unpin(a, "alice@example.org", two)
        assertNull(store.pinnedFingerprint(a, "alice@example.org"))
        assertFalse(store.pin(a, "alice@example.org", "00".repeat(32))) // unknown fingerprint
    }

    @Test fun forgetRemovesTheCandidateAndItIsNotCollectedAgainUntilAddedByHand() = runBlocking<Unit> {
        val a = account()
        store.collect(a, "alice@example.org", cert("alice-san"), true)
        val fp = SmimeCertStore.fingerprint(cert("alice-san"))
        store.pin(a, "alice@example.org", fp)
        assertTrue(store.forget(a, "alice@example.org", fp))
        assertTrue(store.candidates(a, "alice@example.org").isEmpty())
        assertNull(store.pinnedFingerprint(a, "alice@example.org"))
        assertTrue(store.observe(a).first().isEmpty())
        assertFalse(store.collect(a, "alice@example.org", cert("alice-san"), true)) // later signed mail does not revive it
        assertTrue(store.candidates(a, "alice@example.org").isEmpty())
        assertEquals(listOf("alice@example.org"), store.addManual(a, cert("alice-san"))) // the user can add it back
        assertEquals(1, store.candidates(a, "alice@example.org").size)
    }

    @Test fun removingTheAccountRemovesItsCertificates() = runBlocking<Unit> {
        val a = account()
        store.collect(a, "alice@example.org", cert("alice-san"), true)
        graph.removeAccount(a)
        assertTrue(db.mail().smimeCerts(a, "alice@example.org").isEmpty())
    }
}
