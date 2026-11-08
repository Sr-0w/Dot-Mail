package app.dot.mail.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.data.local.MailDatabase
import app.dot.mail.data.model.*
import app.dot.mail.engine.Credentials
import com.google.common.truth.Truth.assertThat
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetup
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun id(email: String, default: Boolean = false, accountId: Long = 1, ident: Long = 1) =
    Identity(ident, accountId, email.substringBefore('@'), email, isDefault = default)

/** Reply identity selection is pure: no Android needed. */
class ReplyIdentityTest {
    private val ids = listOf(id("me@example.org", default = true, ident = 1), id("shop@example.org", ident = 2), id("Work@Example.org", ident = 3))

    @Test fun `picks the identity the message was sent to, case-insensitively`(): Unit {
        assertThat(ReplyIdentity.pick(ids, to = listOf("someone@else.com", "SHOP@example.ORG"), cc = emptyList())!!.id).isEqualTo(2)
        assertThat(ReplyIdentity.pick(ids, to = listOf("x@y.z"), cc = listOf("work@example.org"))!!.id).isEqualTo(3)
    }

    @Test fun `To wins over Cc, Cc over Delivered-To`(): Unit {
        assertThat(ReplyIdentity.pick(ids, to = listOf("shop@example.org"), cc = listOf("work@example.org"))!!.id).isEqualTo(2)
        assertThat(ReplyIdentity.pick(ids, to = emptyList(), cc = listOf("work@example.org"), deliveredTo = listOf("shop@example.org"))!!.id).isEqualTo(3)
        assertThat(ReplyIdentity.pick(ids, to = listOf("list@x.org"), cc = emptyList(), deliveredTo = listOf("shop@example.org"))!!.id).isEqualTo(2)
    }

    @Test fun `falls back to the default, then the first`(): Unit {
        assertThat(ReplyIdentity.pick(ids, to = listOf("nobody@x.org"), cc = emptyList())!!.id).isEqualTo(1)
        val noDefault = ids.map { it.copy(isDefault = false) }
        assertThat(ReplyIdentity.pick(noDefault, to = emptyList(), cc = emptyList())!!.id).isEqualTo(1)
        assertThat(ReplyIdentity.pick(emptyList(), to = listOf("a@b.c"), cc = emptyList())).isNull()
    }

    @Test fun `own addresses are normalised`(): Unit {
        assertThat(ReplyIdentity.ownAddresses(ids)).containsExactly("me@example.org", "shop@example.org", "work@example.org")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IdentityStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val account = 7L

    @Test fun `primary identity always exists and is default at first`(): Unit {
        val store = IdentityStore.forTest(context)
        val list = store.resolve(account, "Alice", "alice@example.org")
        assertThat(list).hasSize(1)
        assertThat(list.single().id).isEqualTo(account)
        assertThat(list.single().isDefault).isTrue()
        assertThat(list.single().email).isEqualTo("alice@example.org")
    }

    @Test fun `add, update, default and delete`(): Unit {
        val store = IdentityStore.forTest(context)
        val id = store.save(Identity(0, account, "Shop", "shop@example.org", signature = "Thanks", replyTo = "support@example.org"), "alice@example.org")
        assertThat(id).isAtLeast(IdentityStore.FIRST_ID)
        store.save(Identity(0, account, "Work", "work@example.org", signatureHtml = "<b>W</b>"), "alice@example.org")

        var list = store.resolve(account, "Alice", "alice@example.org")
        assertThat(list.map { it.email }).containsExactly("alice@example.org", "shop@example.org", "work@example.org").inOrder()
        assertThat(list[1].signature).isEqualTo("Thanks")
        assertThat(list[1].replyTo).isEqualTo("support@example.org")
        assertThat(list[2].signatureHtml).isEqualTo("<b>W</b>")

        store.save(list[1].copy(name = "Shop team", signature = "Cheers"), "alice@example.org") // same id → update
        list = store.resolve(account, "Alice", "alice@example.org")
        assertThat(list).hasSize(3)
        assertThat(list[1].name).isEqualTo("Shop team")

        store.setDefault(account, id)
        list = store.resolve(account, "Alice", "alice@example.org")
        assertThat(list.single { it.isDefault }.id).isEqualTo(id)

        store.delete(account, id) // default deleted → primary is default again
        list = store.resolve(account, "Alice", "alice@example.org")
        assertThat(list.map { it.email }).containsExactly("alice@example.org", "work@example.org").inOrder()
        assertThat(list.single { it.isDefault }.id).isEqualTo(account)
    }

    @Test fun `the account address cannot be deleted or changed, but can be customised`(): Unit {
        val store = IdentityStore.forTest(context)
        store.delete(account, account)
        store.save(Identity(account, account, "Alice A.", "attacker@evil.example", signature = "-- Alice", replyTo = "alice@home.example"), "alice@example.org")
        val primary = store.resolve(account, "Alice", "alice@example.org").single()
        assertThat(primary.email).isEqualTo("alice@example.org")
        assertThat(primary.name).isEqualTo("Alice A.")
        assertThat(primary.signature).isEqualTo("-- Alice")
        assertThat(primary.replyTo).isEqualTo("alice@home.example")
    }

    @Test fun `invalid addresses are rejected`(): Unit {
        val store = IdentityStore.forTest(context)
        assertThrows(IllegalArgumentException::class.java) { store.save(Identity(0, account, "x", "not-an-address"), "a@b.co") }
        assertThrows(IllegalArgumentException::class.java) { store.save(Identity(0, account, "x", "ok@example.org", replyTo = "nope"), "a@b.co") }
        assertThat(IdentityStore.isValidAddress("a@b.co")).isTrue()
        assertThat(IdentityStore.isValidAddress("a b@c.de")).isFalse()
    }

    @Test fun `data survives a new store instance and forget clears it`(): Unit {
        val a = IdentityStore.forTest(context)
        a.save(Identity(0, account, "Shop", "shop@example.org"), "alice@example.org")
        val b = IdentityStore.forTest(context)
        assertThat(b.resolve(account, "Alice", "alice@example.org")).hasSize(2)
        b.forget(account)
        assertThat(b.resolve(account, "Alice", "alice@example.org")).hasSize(1)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IdentitySendingTest {
    private lateinit var db: MailDatabase
    private lateinit var graph: RealMailGraph
    private val config = AccountConfig("alice@example.org", "Alice", ServerConfig("localhost", 143, Security.NONE, "alice"), ServerConfig("localhost", 25, Security.NONE, "alice"))

    @Before fun setup() {
        IdentityStore.resetForTest()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MailDatabase::class.java).allowMainThreadQueries().build()
        graph = RealMailGraph(context, db, object : CredentialVault {
            override fun encrypt(accountEmail: String, secret: String) = "encrypted:$secret"
            override fun decrypt(accountEmail: String, encrypted: String) = encrypted.removePrefix("encrypted:")
        }, scheduling = false)
    }

    @After fun cleanup() { graph.scope.cancel(); db.close() }

    @Test fun `accounts flow exposes real identities and repository methods work`() = runBlocking<Unit> {
        val account = graph.addAccount(config, "password")
        assertThat(graph.identities(account).first().map { it.email }).containsExactly("alice@example.org")
        val alias = graph.saveIdentity(Identity(0, account, "Shop", "shop@example.org"))
        graph.setDefaultIdentity(account, alias)
        val seen = graph.accounts.first().single().identities
        assertThat(seen.map { it.email }).containsExactly("alice@example.org", "shop@example.org").inOrder()
        assertThat(seen.single { it.isDefault }.email).isEqualTo("shop@example.org")
        graph.deleteIdentity(account, account) // permanent
        assertThat(graph.identities(account).first()).hasSize(2)
        graph.deleteIdentity(account, alias)
        assertThat(graph.identities(account).first().map { it.email }).containsExactly("alice@example.org")
    }

    @Test fun `alias goes in From and Reply-To while SMTP submits as the account`() = runBlocking<Unit> {
        val server = GreenMail(arrayOf(ServerSetup(0, "127.0.0.1", "imap"), ServerSetup(0, "127.0.0.1", "smtp")))
        server.start()
        try {
            server.setUser("alice@example.org", "alice", "password")
            val local = config.copy(imap = config.imap.copy(host = "127.0.0.1", port = server.imap.port), smtp = config.smtp.copy(host = "127.0.0.1", port = server.smtp.port))
            val account = graph.addAccount(local, "password")
            val alias = graph.saveIdentity(Identity(0, account, "Alice Shop", "shop@example.org", replyTo = "support@example.org"))
            val outbox = graph.send(Draft(accountId = account, identityId = alias, to = listOf(EmailAddress("alice@example.org")), subject = "From an alias", bodyText = "hi"), 0)
            assertThat(graph.deliverOutbox(outbox)).isTrue()
            assertThat(server.waitForIncomingEmail(5000, 1)).isTrue()
            val received = server.receivedMessages.first { it.subject == "From an alias" }
            assertThat(received.from.single().toString()).contains("shop@example.org")
            assertThat(received.replyTo.single().toString()).contains("support@example.org")
            val returnPath = GreenMailUtil.getHeaders(received).lineSequence().firstOrNull { it.startsWith("Return-Path", true) }.orEmpty()
            assertThat(returnPath).contains("alice@example.org")
            assertThat(returnPath).doesNotContain("shop@example.org")

            // An unknown identity id (deleted alias, old draft) falls back to the default identity.
            val fallback = graph.send(Draft(accountId = account, identityId = 999_999, to = listOf(EmailAddress("alice@example.org")), subject = "Fallback", bodyText = "hi"), 0)
            assertThat(graph.deliverOutbox(fallback)).isTrue()
            assertThat(server.waitForIncomingEmail(5000, 2)).isTrue()
            assertThat(server.receivedMessages.first { it.subject == "Fallback" }.from.single().toString()).contains("alice@example.org")
        } finally { server.stop() }
    }
}

class ScheduledSyncTest {
    private val minute = 60_000L
    @Test fun `manual accounts are never synced in the background`() {
        assertThat(isScheduledSyncDue(AccountSettings(syncIntervalMinutes = 0), lastSyncedAt = 0, now = 10_000 * minute)).isFalse()
    }

    @Test fun `an account is due once its own interval has elapsed`() {
        val s = AccountSettings(syncIntervalMinutes = 30)
        assertThat(isScheduledSyncDue(s, lastSyncedAt = 100 * minute, now = 110 * minute)).isFalse()
        assertThat(isScheduledSyncDue(s, lastSyncedAt = 100 * minute, now = 129 * minute + 1)).isTrue() // 1 minute of WorkManager slack
        assertThat(isScheduledSyncDue(s, lastSyncedAt = 0, now = 100 * minute)).isTrue() // never synced
    }

    @Test fun `the 15 minute interval syncs on every WorkManager run`() {
        assertThat(isScheduledSyncDue(AccountSettings(syncIntervalMinutes = 15), lastSyncedAt = 100 * minute, now = 115 * minute)).isTrue()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AccountSettingsStoreTest {
    @Test fun `per-account values persist and bookkeeping is separate`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AccountSettingsStore.get(context)
        assertThat(store.current(5).syncWindowDays).isEqualTo(30)
        store.update(5) { it.copy(syncIntervalMinutes = 60, syncWindowDays = 0, notificationsEnabled = false) }
        assertThat(store.current(5)).isEqualTo(AccountSettings(syncIntervalMinutes = 60, syncWindowDays = 0, notificationsEnabled = false))
        assertThat(store.current(6).notificationsEnabled).isTrue() // other accounts untouched
        assertThat(store.appliedWindow(5)).isNull()
        store.markWindowApplied(5, 0); store.markSynced(5, 1234)
        assertThat(store.appliedWindow(5)).isEqualTo(0)
        assertThat(store.lastSynced(5)).isEqualTo(1234)
        store.forget(5)
        assertThat(store.current(5)).isEqualTo(AccountSettings())
    }
}
