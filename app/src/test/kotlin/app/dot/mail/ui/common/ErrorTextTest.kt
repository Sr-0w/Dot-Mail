package app.dot.mail.ui.common

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import app.dot.mail.R
import app.dot.mail.data.model.ActionErrorKind
import app.dot.mail.data.model.MailActionException
import app.dot.mail.data.model.MailActionFailure
import app.dot.mail.data.model.MailSyncException
import app.dot.mail.data.model.SyncProblem
import app.dot.mail.data.model.SyncState
import app.dot.mail.engine.ConnectionFailure
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ErrorTextTest {
    private fun resources(tag: String): android.content.res.Resources {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }).resources
    }
    private fun action(kind: ActionErrorKind, detail: String? = null) = MailActionException(MailActionFailure.OPERATION_PENDING, "English log text", kind, detail)

    @Test fun `every action kind has its own text, translated, and never the English log message`(): Unit {
        val kinds = ActionErrorKind.entries.filter { it != ActionErrorKind.OTHER }
        val english = kinds.map { ErrorText.of(resources("en"), action(it, "TRASH"), R.string.inbox_error_action_failed) }
        for (tag in listOf("en", "fr", "de")) {
            val texts = kinds.map { ErrorText.of(resources(tag), action(it, "TRASH"), R.string.inbox_error_action_failed) }
            assertThat(texts.toSet()).hasSize(kinds.size)
            assertThat(texts).doesNotContain("English log text")
            if (tag != "en") assertThat(texts).containsNoneIn(english)
        }
    }

    @Test fun `the missing-folder message names the folder in the user's language`(): Unit {
        assertThat(ErrorText.of(resources("fr"), action(ActionErrorKind.NO_ROLE_FOLDER, "TRASH"), R.string.inbox_error_action_failed)).contains("Corbeille")
        assertThat(ErrorText.of(resources("de"), action(ActionErrorKind.NO_ROLE_FOLDER, "TRASH"), R.string.inbox_error_action_failed)).contains("Papierkorb")
    }

    @Test fun `an untyped action failure uses the caller's fallback and other errors keep their message`(): Unit {
        val r = resources("fr")
        assertThat(ErrorText.of(r, action(ActionErrorKind.OTHER), R.string.inbox_error_move_failed)).isEqualTo(r.getString(R.string.inbox_error_move_failed))
        assertThat(ErrorText.of(r, java.io.IOException("disk full"), R.string.inbox_error_move_failed)).isEqualTo("disk full")
        assertThat(ErrorText.of(r, RuntimeException(), R.string.inbox_error_move_failed)).isEqualTo(r.getString(R.string.inbox_error_move_failed))
    }

    @Test fun `every sync problem and connection kind maps to a translated reason`(): Unit {
        val ids = (SyncProblem.entries.map { ErrorText.syncReasonRes(null, it) } + ConnectionFailure.entries.map { ErrorText.syncReasonRes(it, null) }).toSet()
        for (tag in listOf("fr", "de")) {
            val r = resources(tag); val en = resources("en")
            ids.forEach { assertThat(r.getString(it)).isNotEqualTo(en.getString(it)) }
        }
        // A specific connection kind wins over a vague problem; an unspecific one defers to the problem.
        assertThat(ErrorText.syncReasonRes(ConnectionFailure.TIMEOUT, SyncProblem.PARSE)).isEqualTo(R.string.sync_error_timeout)
        assertThat(ErrorText.syncReasonRes(ConnectionFailure.OTHER, SyncProblem.PARSE)).isEqualTo(R.string.sync_problem_parse)
        assertThat(ErrorText.syncReasonRes(null, null)).isEqualTo(R.string.sync_error_other)
    }

    @Test fun `a failed refresh is explained in the user's language`(): Unit {
        val fr = ErrorText.of(resources("fr"), MailSyncException("Folder is no longer available", SyncProblem.FOLDER_UNAVAILABLE), R.string.inbox_error_refresh_failed)
        assertThat(fr).startsWith("Actualisation impossible")
        assertThat(fr).doesNotContain("Folder is no longer")
        val de = ErrorText.of(resources("de"), MailSyncException("Sync timed out. Please retry.", SyncProblem.TIMEOUT), R.string.inbox_error_refresh_failed)
        assertThat(de).startsWith("Aktualisierung fehlgeschlagen")
    }

    @Test fun `stored sync errors show the typed reason and legacy rows keep their text`(): Unit {
        val get = { id: Int -> resources("fr").getString(id) }
        assertThat(ErrorText.syncError(get, SyncState.Error("Folder X: Folder unavailable", false, ConnectionFailure.OTHER, SyncProblem.FOLDER_UNAVAILABLE)))
            .isEqualTo(resources("fr").getString(R.string.sync_problem_folder))
        assertThat(ErrorText.syncError(get, SyncState.Error("Sign in again to reconnect this account", true))).isEqualTo("Sign in again to reconnect this account")
    }
}
