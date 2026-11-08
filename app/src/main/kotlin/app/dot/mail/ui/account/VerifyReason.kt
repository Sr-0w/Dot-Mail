package app.dot.mail.ui.account

import androidx.annotation.StringRes
import app.dot.mail.R
import app.dot.mail.data.model.VerifyResult
import app.dot.mail.engine.ConnectionFailure

/** The translated reason for a failed verification; the engine's English [VerifyResult] messages are for logs only. */
@StringRes
internal fun verifyReasonRes(result: VerifyResult): Int = when (result) {
    VerifyResult.Ok -> R.string.verify_connection_failed
    is VerifyResult.AuthFailed -> R.string.verify_auth_failed
    is VerifyResult.CertificateProblem -> R.string.verify_certificate
    is VerifyResult.ConnectionFailed -> when (result.kind) {
        ConnectionFailure.DNS -> R.string.sync_error_dns
        ConnectionFailure.TIMEOUT -> R.string.sync_error_timeout
        ConnectionFailure.TLS -> R.string.verify_certificate
        ConnectionFailure.AUTH -> R.string.verify_auth_failed
        ConnectionFailure.SERVER_CLOSED -> R.string.sync_error_server_closed
        ConnectionFailure.THROTTLED -> R.string.sync_error_throttled
        ConnectionFailure.NETWORK -> R.string.sync_error_network
        ConnectionFailure.OTHER, null -> R.string.verify_connection_failed
    }
}

/** Translated message for a failed OAuth step; the exception's English text is never shown. */
@StringRes
internal fun oauthErrorRes(code: app.dot.mail.data.model.OAuthError): Int = when (code) {
    app.dot.mail.data.model.OAuthError.NOT_AVAILABLE -> R.string.oauth_error_not_available
    app.dot.mail.data.model.OAuthError.INVALID_EMAIL -> R.string.oauth_error_invalid_email
    app.dot.mail.data.model.OAuthError.ACCOUNT_GONE -> R.string.oauth_error_account_gone
    app.dot.mail.data.model.OAuthError.WRONG_ACCOUNT -> R.string.oauth_error_wrong_account
    app.dot.mail.data.model.OAuthError.STORAGE -> R.string.oauth_error_storage
    app.dot.mail.data.model.OAuthError.INVALID_CALLBACK -> R.string.oauth_error_invalid_callback
    app.dot.mail.data.model.OAuthError.NO_PENDING -> R.string.oauth_error_no_pending
    app.dot.mail.data.model.OAuthError.STATE_LOST -> R.string.oauth_error_state_lost
    app.dot.mail.data.model.OAuthError.BUILD_CHANGED -> R.string.oauth_error_build_changed
    app.dot.mail.data.model.OAuthError.COMPLETE_FAILED -> R.string.oauth_error_complete_failed
    app.dot.mail.data.model.OAuthError.PROVIDER_REJECTED -> R.string.oauth_error_provider_rejected
    app.dot.mail.data.model.OAuthError.ACCOUNT_CHANGED -> R.string.oauth_error_account_changed
    app.dot.mail.data.model.OAuthError.EXPIRED -> R.string.oauth_error_expired
    app.dot.mail.data.model.OAuthError.DENIED -> R.string.oauth_error_denied
    app.dot.mail.data.model.OAuthError.PROVIDER_RESPONSE -> R.string.oauth_error_provider_response
    app.dot.mail.data.model.OAuthError.OTHER -> R.string.oauth_error_other
}
