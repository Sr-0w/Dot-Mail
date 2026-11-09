package app.dot.mail.ui.compose

import app.dot.mail.data.*
import app.dot.mail.data.model.*
import app.dot.mail.demo.DemoMailGraph
import app.dot.mail.engine.*
import app.dot.mail.ui.ComposeRoute
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.cert.X509Certificate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ComposeSmimeTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setMain() = Dispatchers.setMain(dispatcher)
    @After fun resetMain() = Dispatchers.resetMain()

    private var statuses = mapOf("bob@example.org" to RecipientCertStatus.USABLE, "dave@example.org" to RecipientCertStatus.MISSING)
    private val sent = mutableListOf<Draft>()
    private var sendFailure: Exception? = null
    private var keyFailure: SmimeUnavailableException? = null
    private val policy = object : SmimeCertificatePolicy {
        override fun validate(certificate: X509Certificate, mailbox: String, use: SmimeCertificateUse) = SmimeCertificateVerdict.Trusted
    }
    private val keys = object : SmimeKeyAccess {
        override suspend fun signingIdentity(accountId: Long): SmimeSigningIdentity = keyFailure?.let { throw it } ?: error("not reached in these tests")
        override suspend fun decryptionCertificate(accountId: Long): X509Certificate = keyFailure?.let { throw it } ?: error("not reached in these tests")
    }

    private fun graph(config: SmimeAccountConfig): MailGraph {
        val demo = DemoMailGraph()
        return object : MailGraph by demo {
            override val accounts = object : AccountRepository by demo.accounts {
                override val accounts = demo.accounts.accounts.map { list -> list.map { it.copy(smime = config) } }
            }
            override val smime = object : SmimeRepository by SmimeRepository.None {
                override suspend fun recipientStatus(accountId: Long, mailboxes: List<String>) = mailboxes.associateWith { statuses[it.lowercase()] ?: RecipientCertStatus.MISSING }
            }
            override val compose = object : ComposeRepository by demo.compose {
                override fun smimeBuilder(keys: SmimeKeyAccess, policy: SmimeCertificatePolicy) = SmimeBuilder { _, _, _ -> emptyList() }
                override suspend fun sendSmime(draft: Draft, undoWindowMs: Long, builder: SmimeBuilder): Long { sendFailure?.let { throw it }; sent += draft; return 1 }
            }
        }
    }

    private val configured = SmimeAccountConfig("sign", "dec")

    private fun vm(config: SmimeAccountConfig = configured, withKeys: Boolean = true, pgp: Boolean = false) = ComposeViewModel(
        graph = graph(config), route = ComposeRoute(to = listOf("bob@example.org", "dave@example.org")), undoSeconds = { 0 },
        strings = { id, args -> "res:$id" + args.joinToString(prefix = "[", postfix = "]") }, smimeKeys = if (withKeys) keys else null, smimePolicy = if (withKeys) policy else null,
        pgpDefaults = { pgp to pgp }, pgp = if (pgp) object : app.dot.mail.ui.pgp.PgpService {
            override fun available() = true
            override suspend fun hasKey(email: String) = true
            override fun protector(senderEmail: String, interact: suspend (android.app.PendingIntent) -> android.content.Intent?) = PgpProtector { _, _, _, _ -> PgpProtected() }
        } else null,
    )

    @Test fun `S MIME is offered only for an account with a KeyChain certificate`() = runTest(dispatcher) {
        assertThat(vm().also { runCurrent() }.state.value.smimeAvailable).isTrue()
        assertThat(vm(SmimeAccountConfig()).also { runCurrent() }.state.value.smimeAvailable).isFalse()
        assertThat(vm(withKeys = false).also { runCurrent() }.state.value.smimeAvailable).isFalse()
    }

    @Test fun `account defaults apply to a new message`() = runTest(dispatcher) {
        val m = vm(SmimeAccountConfig("sign", "dec", signByDefault = true, encryptByDefault = true)); runCurrent()
        assertThat(m.state.value.draft!!.smimeSign).isTrue()
        assertThat(m.state.value.draft!!.smimeEncrypt).isTrue()
        val off = vm(SmimeAccountConfig(signByDefault = true, encryptByDefault = true), withKeys = false); runCurrent()
        assertThat(off.state.value.draft!!.smimeEncrypt).isFalse() // hidden toggles never stay switched on
    }

    @Test fun `encrypting shows a lock state for every recipient`() = runTest(dispatcher) {
        val m = vm(); runCurrent()
        assertThat(m.state.value.smimeRecipients).isEmpty() // nothing is checked until protection is on
        m.setSmime(encrypt = true)
        advanceTimeBy(1_000); runCurrent()
        assertThat(m.state.value.smimeRecipients).containsExactly("bob@example.org", RecipientCertStatus.USABLE, "dave@example.org", RecipientCertStatus.MISSING)
        m.setSmime(encrypt = false)
        advanceTimeBy(1_000); runCurrent()
        assertThat(m.state.value.smimeRecipients).isEmpty()
    }

    @Test fun `sending encrypted to someone without a usable certificate is blocked and nothing is queued`() = runTest(dispatcher) {
        val m = vm(); runCurrent()
        m.setSmime(encrypt = true)
        m.send()
        advanceTimeBy(2_000); runCurrent()
        assertThat(sent).isEmpty()
        assertThat(m.state.value.sending).isFalse()
        assertThat(m.state.value.error).contains("dave@example.org")
        assertThat(m.state.value.draft!!.smimeEncrypt).isTrue() // never silently switched off
        assertThat(m.closed.value).isFalse()
    }

    @Test fun `a pinned certificate that is no longer usable says so`() = runTest(dispatcher) {
        statuses = mapOf("bob@example.org" to RecipientCertStatus.PINNED_UNAVAILABLE, "dave@example.org" to RecipientCertStatus.USABLE)
        val m = vm(); runCurrent()
        m.setSmime(encrypt = true); m.send()
        advanceTimeBy(2_000); runCurrent()
        assertThat(m.state.value.error).contains("res:" + app.dot.mail.R.string.compose_smime_pinned_unavailable)
        assertThat(sent).isEmpty()
    }

    @Test fun `with every recipient ready the message is queued with its protection flags`() = runTest(dispatcher) {
        statuses = statuses + ("dave@example.org" to RecipientCertStatus.USABLE)
        val m = vm(); runCurrent()
        m.setSmime(sign = true, encrypt = true)
        m.send()
        advanceTimeBy(2_000); runCurrent()
        assertThat(sent).hasSize(1)
        assertThat(sent.single().smimeSign).isTrue(); assertThat(sent.single().smimeEncrypt).isTrue()
        assertThat(m.closed.value).isTrue()
    }

    @Test fun `signing only needs no recipient certificates`() = runTest(dispatcher) {
        val m = vm(); runCurrent()
        m.setSmime(sign = true)
        m.send()
        advanceTimeBy(2_000); runCurrent()
        assertThat(sent).hasSize(1)
    }

    @Test fun `a vanished KeyChain entry is flagged and a failing send explains it`() = runTest(dispatcher) {
        statuses = statuses + ("dave@example.org" to RecipientCertStatus.USABLE)
        keyFailure = SmimeUnavailableException(SmimeUnavailableException.Reason.KEY_MISSING, "gone")
        val m = vm(); runCurrent()
        m.setSmime(sign = true)
        advanceTimeBy(1_000); runCurrent()
        assertThat(m.state.value.smimeKeyProblem).isTrue()
        sendFailure = keyFailure
        m.send()
        advanceTimeBy(2_000); runCurrent()
        assertThat(m.state.value.error).contains("res:" + app.dot.mail.R.string.smime_key_unavailable)
        assertThat(sent).isEmpty()
    }

    @Test fun `engine failures become plain messages and never a downgrade`() = runTest(dispatcher) {
        statuses = statuses + ("dave@example.org" to RecipientCertStatus.USABLE)
        sendFailure = SmimeProtectionException(SmimeFailure.RECIPIENT_PINNED_CERTIFICATE_UNAVAILABLE, "pinned")
        val m = vm(); runCurrent()
        m.setSmime(encrypt = true); m.send()
        advanceTimeBy(2_000); runCurrent()
        assertThat(m.state.value.error).contains("res:" + app.dot.mail.R.string.compose_smime_err_pinned)
        assertThat(m.state.value.draft!!.smimeEncrypt).isTrue()
        assertThat(m.closed.value).isFalse()
    }

    @Test fun `OpenPGP and S MIME exclude each other`() = runTest(dispatcher) {
        val m = vm(pgp = true); runCurrent()
        m.setSmime(sign = true)
        assertThat(m.state.value.draft!!.smimeSign).isTrue()
        m.setPgp(encrypt = true)
        assertThat(m.state.value.draft!!.smimeSign).isFalse()
        assertThat(m.state.value.draft!!.pgpEncrypt).isTrue()
        m.setSmime(encrypt = true)
        assertThat(m.state.value.draft!!.pgpEncrypt).isFalse()
    }
}
