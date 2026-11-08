package app.dot.mail.ui.account

import android.content.Intent
import android.net.Uri
import app.dot.mail.data.AccountRepository
import app.dot.mail.data.model.OAuthAvailability
import app.dot.mail.data.model.OAuthError
import app.dot.mail.data.model.OAuthException
import app.dot.mail.data.model.OAuthProvider
import app.dot.mail.demo.DemoMailGraph
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
class GoogleSignInTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setMain() { Dispatchers.setMain(dispatcher); OAuthSignIn.dispatcher = dispatcher; OAuthSignIn.reset() }
    @After fun resetMain() {
        // A screen's ViewModel is cleared when it leaves; do the same so no collector outlives its test.
        models.forEach { it.viewModelScope.cancel() }; models.clear()
        Dispatchers.resetMain(); OAuthSignIn.dispatcher = Dispatchers.Default; OAuthSignIn.reset() }

    private class FakeRepo(
        val clientId: Boolean = true,
        val begin: () -> Intent = { Intent("browser") },
        val complete: suspend (Uri) -> Long = { 7L },
    ) : AccountRepository by DemoMailGraph().accounts {
        override fun oauthAvailability(provider: OAuthProvider) = OAuthAvailability(provider, clientId, if (clientId) null else "not available")
        override suspend fun beginOAuth(email: String, provider: OAuthProvider, accountId: Long?) = begin()
        override suspend fun completeOAuth(resultUri: Uri) = complete(resultUri)
    }

    private val models = mutableListOf<AddAccountViewModel>()
    private fun vm(repo: AccountRepository) = AddAccountViewModel(repo) { id, _ -> "res:$id" }.also { models += it }

    @Test fun `without a client id there is no button and an honest message is possible`() = runTest(dispatcher) {
        val model = vm(FakeRepo(clientId = false))
        runCurrent()
        assertThat(model.state.value.googleAvailable).isFalse()
        model.edit { it.copy(email = "me@gmail.com") }
        model.signInWithGoogle() // a stray call does nothing
        runCurrent()
        assertThat(model.state.value.google).isEqualTo(SetupState.GooglePhase.Idle)
        assertThat(OAuthSignIn.status.value).isEqualTo(OAuthSignIn.Status.Idle)
    }

    @Test fun `an invalid address cannot start the flow`() = runTest(dispatcher) {
        val model = vm(FakeRepo())
        runCurrent()
        model.signInWithGoogle()
        runCurrent()
        assertThat(model.state.value.busy).isFalse()
    }

    @Test fun `starting opens the browser intent and shows the waiting state, cancel returns to idle`() = runTest(dispatcher) {
        val model = vm(FakeRepo())
        model.edit { it.copy(email = "me@gmail.com") }
        runCurrent()
        model.signInWithGoogle()
        runCurrent()
        assertThat(model.launch.first().action).isEqualTo("browser")
        runCurrent()
        assertThat(model.state.value.google).isEqualTo(SetupState.GooglePhase.Waiting)
        assertThat(model.state.value.busy).isTrue()
        model.cancelGoogle()
        runCurrent()
        assertThat(model.state.value.busy).isFalse()
        assertThat(OAuthSignIn.status.value).isEqualTo(OAuthSignIn.Status.Idle)
    }

    @Test fun `coming back from the browser without a result is a cancellation`() = runTest(dispatcher) {
        val model = vm(FakeRepo())
        model.edit { it.copy(email = "me@gmail.com") }
        runCurrent()
        model.signInWithGoogle(); runCurrent()
        model.onPaused(); model.onResumed()
        advanceTimeBy(2_000); runCurrent()
        assertThat(model.state.value.google).isEqualTo(SetupState.GooglePhase.Idle)
        assertThat(model.state.value.status).isEqualTo("res:${app.dot.mail.R.string.setup_google_cancelled}")
        assertThat(model.state.value.error).isNull()
    }

    @Test fun `a redirect arriving before the grace period wins over cancellation`() = runTest(dispatcher) {
        val model = vm(FakeRepo())
        model.edit { it.copy(email = "me@gmail.com") }
        runCurrent()
        model.signInWithGoogle(); runCurrent()
        model.onPaused(); model.onResumed()
        OAuthSignIn.complete(FakeRepo(), Uri.parse("app.dot.mail:/oauth2redirect?code=x"))
        advanceUntilIdle()
        assertThat(model.done.value).isTrue()
    }

    @Test fun `a failed exchange shows the translated error and stays on the screen`() = runTest(dispatcher) {
        val model = vm(FakeRepo(complete = { throw OAuthException("x", OAuthError.PROVIDER_REJECTED) }))
        runCurrent()
        OAuthSignIn.complete(FakeRepo(complete = { throw OAuthException("x", OAuthError.PROVIDER_REJECTED) }), Uri.parse("app.dot.mail:/oauth2redirect"))
        advanceUntilIdle()
        assertThat(model.done.value).isFalse()
        assertThat(model.state.value.error).isEqualTo("res:${oauthErrorRes(OAuthError.PROVIDER_REJECTED)}")
        assertThat(model.state.value.busy).isFalse()
    }

    @Test fun `beginOAuth failing explains instead of hanging`() = runTest(dispatcher) {
        val model = vm(FakeRepo(begin = { throw OAuthException("x", OAuthError.NOT_AVAILABLE) }))
        model.edit { it.copy(email = "me@gmail.com") }
        runCurrent()
        model.signInWithGoogle(); runCurrent()
        assertThat(model.state.value.busy).isFalse()
        assertThat(model.state.value.error).isEqualTo("res:${oauthErrorRes(OAuthError.NOT_AVAILABLE)}")
    }

    @Test fun `the holder alone completes with the account id`() = runTest(dispatcher) {
        OAuthSignIn.complete(FakeRepo(), Uri.parse("app.dot.mail:/oauth2redirect?code=x"))
        assertThat(OAuthSignIn.status.value).isEqualTo(OAuthSignIn.Status.Completing)
        advanceUntilIdle()
        assertThat(OAuthSignIn.status.value).isEqualTo(OAuthSignIn.Status.Done(7L))
    }
}
