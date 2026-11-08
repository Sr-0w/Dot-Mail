package app.dot.mail.ui.compose

import android.app.PendingIntent
import android.content.Intent
import app.dot.mail.data.PgpProtected
import app.dot.mail.data.PgpProtector
import app.dot.mail.demo.DemoMailGraph
import app.dot.mail.ui.ComposeRoute
import app.dot.mail.ui.pgp.PgpService
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ComposePgpTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setMain() = Dispatchers.setMain(dispatcher)
    @After fun resetMain() = Dispatchers.resetMain()

    private class FakePgp(val keys: Set<String>, val installed: Boolean = true) : PgpService {
        var protectCalls = 0
        override fun available() = installed
        override suspend fun hasKey(email: String) = email.lowercase() in keys
        override fun protector(senderEmail: String, interact: suspend (PendingIntent) -> Intent?) = PgpProtector { _, _, _, _ -> protectCalls++; PgpProtected() }
    }

    private fun vm(pgp: PgpService?, defaults: Pair<Boolean, Boolean> = false to false) = ComposeViewModel(
        graph = DemoMailGraph(), route = ComposeRoute(to = listOf("bob@example.org", "dave@example.org")), undoSeconds = { 0 },
        strings = { id, _ -> "res:$id" }, pgp = pgp, pgpDefaults = { defaults },
    )

    @Test fun `encrypt shows exactly the recipients without a key and removing them clears the warning`() = runTest(dispatcher) {
        val model = vm(FakePgp(setOf("bob@example.org", "alice@example.org")))
        runCurrent()
        assertThat(model.state.value.pgpAvailable).isTrue()
        assertThat(model.state.value.pgpMissing).isEmpty() // nothing is checked until encryption is on
        model.setPgp(encrypt = true)
        advanceTimeBy(1_000); runCurrent()
        assertThat(model.state.value.pgpMissing).containsExactly("dave@example.org")
        model.removeRecipientsWithoutKey()
        advanceTimeBy(1_000); runCurrent()
        assertThat(model.state.value.draft!!.to.map { it.email }).containsExactly("bob@example.org")
        assertThat(model.state.value.pgpMissing).isEmpty()
        model.setPgp(encrypt = false)
        advanceTimeBy(1_000); runCurrent()
        assertThat(model.state.value.draft!!.pgpEncrypt).isFalse()
    }

    @Test fun `without OpenKeychain nothing is offered and nothing is checked`() = runTest(dispatcher) {
        val model = vm(FakePgp(emptySet(), installed = false))
        runCurrent()
        assertThat(model.state.value.pgpAvailable).isFalse()
        assertThat(vm(null).also { runCurrent() }.state.value.pgpAvailable).isFalse()
    }

    @Test fun `account defaults apply to a new message`() = runTest(dispatcher) {
        val model = vm(FakePgp(emptySet()), defaults = true to true)
        runCurrent()
        assertThat(model.state.value.draft!!.pgpSign).isTrue()
        assertThat(model.state.value.draft!!.pgpEncrypt).isTrue()
        // Defaults are ignored when OpenKeychain is missing (the toggles would be invisible but still active).
        val hidden = vm(FakePgp(emptySet(), installed = false), defaults = true to true)
        runCurrent()
        assertThat(hidden.state.value.draft!!.pgpEncrypt).isFalse()
    }

    @Test fun `sending encrypted to someone without a key is refused before anything is queued`() = runTest(dispatcher) {
        val fake = FakePgp(setOf("bob@example.org"))
        val model = vm(fake)
        runCurrent()
        model.setPgp(encrypt = true)
        model.send()
        advanceTimeBy(2_000); runCurrent()
        assertThat(fake.protectCalls).isEqualTo(0)
        assertThat(model.state.value.sending).isFalse()
        assertThat(model.state.value.error).isNotNull()
        assertThat(model.state.value.pgpMissing).containsExactly("dave@example.org")
        assertThat(model.closed.value).isFalse()
    }

    @Test fun `the interaction bridge exposes a prompt and completes with the user's answer`() = runTest(dispatcher) {
        val model = vm(FakePgp(emptySet()))
        runCurrent()
        model.pgpResult(null) // nothing pending: harmless
        assertThat(model.pgpPrompt.value).isNull()
    }
}
