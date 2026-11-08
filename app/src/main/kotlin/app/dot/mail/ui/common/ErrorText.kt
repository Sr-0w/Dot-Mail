package app.dot.mail.ui.common

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.dot.mail.R
import app.dot.mail.data.model.ActionErrorKind
import app.dot.mail.data.model.FolderRole
import app.dot.mail.data.model.MailActionException
import app.dot.mail.data.model.MailSyncException
import app.dot.mail.data.model.SyncProblem
import app.dot.mail.data.model.SyncState
import app.dot.mail.engine.ConnectionFailure

/**
 * Text in the user's language for the typed failures the data layer raises. The exceptions' own messages are English and are
 * for logs and tests only; anything that is not typed keeps showing its message as before (or the caller's fallback).
 */
object ErrorText {
    /** Why a sync failed, as a lowercase fragment ("the server took too long to answer"); [problem] refines an unspecific connection kind. */
    @StringRes
    fun syncReasonRes(connection: ConnectionFailure?, problem: SyncProblem?): Int = when (connection) {
        ConnectionFailure.DNS -> R.string.sync_error_dns
        ConnectionFailure.TIMEOUT -> R.string.sync_error_timeout
        ConnectionFailure.TLS -> R.string.sync_error_tls
        ConnectionFailure.AUTH -> R.string.sync_error_auth
        ConnectionFailure.SERVER_CLOSED -> R.string.sync_error_server_closed
        ConnectionFailure.THROTTLED -> R.string.sync_error_throttled
        ConnectionFailure.NETWORK -> R.string.sync_error_network
        ConnectionFailure.OTHER, null -> when (problem) {
            SyncProblem.SERVER_NAME -> R.string.sync_error_dns
            SyncProblem.TIMEOUT -> R.string.sync_error_timeout
            SyncProblem.UNREACHABLE -> R.string.sync_error_network
            SyncProblem.TLS -> R.string.sync_error_tls
            SyncProblem.AUTH -> R.string.sync_error_auth
            SyncProblem.CONNECTION_CLOSED -> R.string.sync_error_server_closed
            SyncProblem.FOLDER_UNAVAILABLE -> R.string.sync_problem_folder
            SyncProblem.PARSE -> R.string.sync_problem_parse
            SyncProblem.DATABASE -> R.string.sync_problem_database
            SyncProblem.EMPTY_RESULT, SyncProblem.INCONSISTENT -> R.string.sync_problem_unexpected
            SyncProblem.HEADERS_UNREADABLE -> R.string.sync_problem_headers
            SyncProblem.OTHER, null -> R.string.sync_error_other
        }
    }

    /** The sync banner / account-list text for a stored sync error; untyped legacy rows keep their stored text. */
    fun syncError(get: (Int) -> String, err: SyncState.Error): String =
        if (err.errorKind == null && err.problem == null) err.message else get(syncReasonRes(err.errorKind, err.problem))

    @Composable
    fun syncError(err: SyncState.Error): String =
        if (err.errorKind == null && err.problem == null) err.message else stringResource(syncReasonRes(err.errorKind, err.problem))

    /** A refused action, or null when the exception carries no kind we can say better than the caller's fallback. */
    fun action(e: MailActionException, get: (Int, Array<out Any>) -> String, roleName: (FolderRole) -> String): String? = when (e.kind) {
        ActionErrorKind.MESSAGE_MOVING -> get(R.string.action_error_message_moving, emptyArray())
        ActionErrorKind.MESSAGE_FOLDER_GONE -> get(R.string.action_error_message_folder_gone, emptyArray())
        ActionErrorKind.DESTINATION_OTHER_ACCOUNT -> get(R.string.action_error_destination_other_account, emptyArray())
        ActionErrorKind.MOVE_SYNCING -> get(R.string.action_error_move_syncing, emptyArray())
        ActionErrorKind.MOVE_SENDING -> get(R.string.action_error_move_sending, emptyArray())
        ActionErrorKind.SOURCE_REQUIRED -> get(R.string.action_error_source_required, emptyArray())
        ActionErrorKind.NO_ROLE_FOLDER -> {
            val role = e.detail?.let { name -> FolderRole.entries.firstOrNull { it.name == name } }
            get(R.string.action_error_no_role_folder, arrayOf(role?.let(roleName) ?: e.detail.orEmpty()))
        }
        ActionErrorKind.MESSAGE_SYNCING -> get(R.string.action_error_message_syncing, emptyArray())
        ActionErrorKind.DESTINATION_GONE -> get(R.string.action_error_destination_gone, emptyArray())
        ActionErrorKind.LOCAL_DRAFT_MOVE -> get(R.string.action_error_local_draft_move, emptyArray())
        ActionErrorKind.MIXED_ACCOUNTS -> get(R.string.action_error_mixed_accounts, emptyArray())
        ActionErrorKind.DRAFT_ACCOUNT_CHANGE -> get(R.string.action_error_draft_account_change, emptyArray())
        ActionErrorKind.OTHER -> null
    }

    /**
     * The message to show for [e]: typed data-layer failures in the user's language, anything else its own message, else
     * [fallbackRes]. Use this wherever a failed action used to show `e.message`.
     */
    fun of(get: (Int, Array<out Any>) -> String, roleName: (FolderRole) -> String, e: Throwable, @StringRes fallbackRes: Int): String = when (e) {
        is MailActionException -> action(e, get, roleName) ?: get(fallbackRes, emptyArray())
        is MailSyncException -> get(R.string.inbox_refresh_failed_reason, arrayOf(get(syncReasonRes(e.connection, e.kind), emptyArray())))
        else -> e.message?.takeIf { it.isNotBlank() } ?: get(fallbackRes, emptyArray())
    }

    fun of(resources: Resources, e: Throwable, @StringRes fallbackRes: Int): String =
        of({ id, args -> resources.getString(id, *args) }, { role -> Format.roleName(resources, role) }, e, fallbackRes)
}
