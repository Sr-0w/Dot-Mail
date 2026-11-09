package app.dot.mail.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.text.input.ImeAction
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotTextField
import androidx.compose.ui.res.stringResource
import app.dot.mail.R
import app.dot.mail.LocalMailGraph
import app.dot.mail.data.model.Account
import app.dot.mail.data.model.VerifyResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * "Sign in again" / "Update password": asks for a new password or app password, verifies IMAP and SMTP with it
 * (nothing is stored unless both log in), and only then closes. A refusal is shown inline.
 * Autofill-friendly: the field is a password field with the Password content type, with show / hide.
 */
@Composable
fun UpdatePasswordDialog(account: Account, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    val c = Dot.colors
    val graph = LocalMailGraph.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failedText = stringResource(R.string.signin_failed)
    val authText = stringResource(R.string.verify_auth_failed)
    val connText = stringResource(R.string.verify_connection_failed)
    val certText = stringResource(R.string.verify_certificate)
    val reasons = mapOf(
        app.dot.mail.engine.ConnectionFailure.DNS to stringResource(R.string.sync_error_dns), app.dot.mail.engine.ConnectionFailure.TIMEOUT to stringResource(R.string.sync_error_timeout),
        app.dot.mail.engine.ConnectionFailure.SERVER_CLOSED to stringResource(R.string.sync_error_server_closed), app.dot.mail.engine.ConnectionFailure.THROTTLED to stringResource(R.string.sync_error_throttled),
        app.dot.mail.engine.ConnectionFailure.NETWORK to stringResource(R.string.sync_error_network),
    )

    fun submit() {
        if (password.isEmpty() || busy) return
        busy = true; error = null
        scope.launch {
            try {
                when (val r = graph.accounts.updatePassword(account.id, password)) {
                    VerifyResult.Ok -> { onSuccess(); onDismiss() }
                    is VerifyResult.AuthFailed -> error = authText
                    is VerifyResult.ConnectionFailed -> error = when (r.kind) {
                        app.dot.mail.engine.ConnectionFailure.TLS -> certText
                        app.dot.mail.engine.ConnectionFailure.AUTH -> authText
                        else -> reasons[r.kind] ?: connText
                    }
                    is VerifyResult.CertificateProblem -> error = certText
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: failedText
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = c.surface,
        shape = DotShapes.dialog,
        title = { Text(stringResource(R.string.signin_title), style = Dot.type.title, color = c.textPrimary) },
        text = {
            Column {
                Text(account.email, style = Dot.type.callout, color = c.textSecondary, modifier = Modifier.padding(bottom = DotSpacing.m))
                DotTextField(
                    value = password, onValueChange = { password = it; error = null },
                    label = stringResource(R.string.signin_password), password = true, contentType = ContentType.Password,
                    imeAction = ImeAction.Done, onImeAction = ::submit, enabled = !busy,
                    isError = error != null,
                    supporting = error ?: stringResource(R.string.signin_note),
                )
            }
        },
        confirmButton = { DotButton(stringResource(R.string.signin_confirm), enabled = password.isNotEmpty() && !busy, loading = busy, compact = true, onClick = ::submit) },
        dismissButton = { DotButton(stringResource(R.string.signin_cancel), onDismiss, style = ButtonStyle.Ghost, compact = true, enabled = !busy) },
    )
}
