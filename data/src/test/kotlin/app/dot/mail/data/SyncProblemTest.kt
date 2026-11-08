package app.dot.mail.data

import app.dot.mail.data.model.SyncProblem
import app.dot.mail.engine.ConnectionFailure
import com.google.common.truth.Truth.assertThat
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FolderNotFoundException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncProblemTest {
    @Test fun `each failure maps to a typed problem`(): Unit {
        assertThat(SyncDiagnostics.problem(FolderNotFoundException())).isEqualTo(SyncProblem.FOLDER_UNAVAILABLE)
        assertThat(SyncDiagnostics.problem(NullPointerException())).isEqualTo(SyncProblem.EMPTY_RESULT)
        assertThat(SyncDiagnostics.problem(IndexOutOfBoundsException())).isEqualTo(SyncProblem.INCONSISTENT)
        assertThat(SyncDiagnostics.problem(android.database.sqlite.SQLiteException("x"))).isEqualTo(SyncProblem.DATABASE)
        assertThat(SyncDiagnostics.problem(UnknownHostException())).isEqualTo(SyncProblem.SERVER_NAME)
        assertThat(SyncDiagnostics.problem(ConnectException())).isEqualTo(SyncProblem.UNREACHABLE)
        assertThat(SyncDiagnostics.problem(SSLException("x"))).isEqualTo(SyncProblem.TLS)
        assertThat(SyncDiagnostics.problem(AuthenticationFailedException("x"))).isEqualTo(SyncProblem.AUTH)
        assertThat(SyncDiagnostics.problem(IllegalStateException("something odd"))).isEqualTo(SyncProblem.OTHER)
    }

    @Test fun `the problem and the folder travel in the stored tag and come back typed`(): Unit {
        val stored = SyncDiagnostics.stored(FolderNotFoundException(), "ARCHIVE")
        assertThat(stored).startsWith("[OTHER:FOLDER_UNAVAILABLE:ARCHIVE] ")
        val state = SyncDiagnostics.state(stored, false)
        assertThat(state.errorKind).isEqualTo(ConnectionFailure.OTHER)
        assertThat(state.problem).isEqualTo(SyncProblem.FOLDER_UNAVAILABLE)
        assertThat(state.folderRole).isEqualTo("ARCHIVE")
        assertThat(state.message).isEqualTo("Folder ARCHIVE: Folder unavailable") // English stays for logs only
    }

    @Test fun `a connection kind needs no extra problem tag`(): Unit {
        val state = SyncDiagnostics.state(SyncDiagnostics.stored(java.net.SocketTimeoutException()), false)
        assertThat(state.errorKind).isEqualTo(ConnectionFailure.TIMEOUT)
        assertThat(state.problem).isNull()
        assertThat(state.folderRole).isNull()
    }

    @Test fun `rows stored before the tags existed still parse`(): Unit {
        val legacy = SyncDiagnostics.state("[DNS] Mail server name could not be resolved.", false)
        assertThat(legacy.errorKind).isEqualTo(ConnectionFailure.DNS)
        assertThat(legacy.problem).isNull()
        assertThat(legacy.message).startsWith("Mail server name")
        val plain = SyncDiagnostics.state("Sign in again to reconnect this account", true)
        assertThat(plain.errorKind).isNull()
        assertThat(plain.message).isEqualTo("Sign in again to reconnect this account")
        assertThat(plain.isAuthError).isTrue()
    }

    @Test fun `an auth problem marks the state as needing sign-in`(): Unit {
        val state = SyncDiagnostics.state("[OTHER:AUTH:] Login failed; sign in again", false)
        assertThat(state.isAuthError).isTrue()
        assertThat(state.canRetry).isFalse()
    }
}
