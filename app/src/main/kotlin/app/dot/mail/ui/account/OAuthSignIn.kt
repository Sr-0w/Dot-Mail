package app.dot.mail.ui.account

import android.net.Uri
import app.dot.mail.data.AccountRepository
import app.dot.mail.data.model.OAuthError
import app.dot.mail.data.model.OAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Where a browser sign-in stands. The system browser hands its result to MainActivity (not to a screen), so the state lives
 * here, outside any composition: the Add account screen starts the flow and observes it, MainActivity completes it.
 */
object OAuthSignIn {
    sealed interface Status {
        data object Idle : Status
        /** The browser was opened; nothing has come back yet. */
        data object Waiting : Status
        /** The redirect arrived; tokens are being exchanged and the account verified. */
        data object Completing : Status
        data class Done(val accountId: Long) : Status
        data class Failed(val code: OAuthError) : Status
        /** The user came back without finishing (closed the browser tab). */
        data object Cancelled : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    /** Where the token exchange runs; tests substitute their own dispatcher. */
    internal var dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
    private val job = SupervisorJob()

    fun waiting() { _status.value = Status.Waiting }
    fun reset() { _status.value = Status.Idle }

    /** Called when the app is back in front: if the redirect has not arrived, the user abandoned the browser. */
    fun cancelIfStillWaiting() { _status.compareAndSet(Status.Waiting, Status.Cancelled) }

    /** Completes the flow from the redirect [uri] (never logged). */
    fun complete(repo: AccountRepository, uri: Uri) {
        _status.value = Status.Completing
        CoroutineScope(job + dispatcher).launch {
            _status.value = try {
                Status.Done(repo.completeOAuth(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: OAuthException) {
                Status.Failed(e.code)
            } catch (e: Exception) {
                Status.Failed(OAuthError.OTHER)
            }
        }
    }
}
