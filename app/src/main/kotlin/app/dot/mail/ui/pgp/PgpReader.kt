package app.dot.mail.ui.pgp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.mail.R
import app.dot.mail.data.model.MessageBody
import app.dot.mail.data.model.PgpContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the reader knows about one message's OpenPGP processing. Plaintext lives here, in memory, never on disk. */
@Stable
class PgpReaderState internal constructor() {
    sealed interface Status {
        data object Idle : Status
        data object Working : Status
        data object Done : Status
        data class Failed(val message: String?) : Status
    }
    var status: Status by mutableStateOf(Status.Idle)
        internal set
    var decrypted: MessageBody? by mutableStateOf(null)
        internal set
    var signature: OpenKeychain.Signature? by mutableStateOf(null)
        internal set
    internal var run: (Intent?) -> Unit = {}
}

/** Drives decryption/verification of [body] through OpenKeychain; signed mail is verified automatically. */
@Composable
fun rememberPgpReader(messageId: Long, body: MessageBody?): PgpReaderState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(messageId) { PgpReaderState() }
    val pgp = body?.pgp
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) state.run(res.data ?: Intent()) else state.status = PgpReaderState.Status.Idle
    }
    state.run = { resume ->
        if (pgp != null && body != null) scope.launch {
            state.status = PgpReaderState.Status.Working
            val outcome = runCatching { process(context, pgp, body, resume) }.getOrElse { OpenKeychain.Outcome.Failed(it.message) }
            when (outcome) {
                is OpenKeychain.Outcome.NeedsUser -> launcher.launch(IntentSenderRequest.Builder(outcome.intent.intentSender).build())
                is OpenKeychain.Outcome.Failed -> state.status = PgpReaderState.Status.Failed(outcome.message)
                is OpenKeychain.Outcome.Done -> {
                    state.signature = outcome.signature
                    if (pgp !is PgpContent.Signed) {
                        val parsed = withContext(Dispatchers.Default) {
                            app.dot.mail.engine.Pgp.parseDecrypted(outcome.output, mime = pgp is PgpContent.Encrypted)
                        }
                        state.decrypted = MessageBody(parsed.text, parsed.html, hasRemoteImages = false)
                    }
                    state.status = PgpReaderState.Status.Done
                }
            }
        }
    }
    // Verifying a signature reveals nothing new, so it runs on its own; decrypting waits for the user.
    LaunchedEffect(messageId, pgp) {
        if ((pgp is PgpContent.Signed || pgp is PgpContent.InlineSigned) && OpenKeychain.installed(context)) state.run(null)
    }
    return state
}

private suspend fun process(context: Context, pgp: PgpContent, body: MessageBody, resume: Intent?): OpenKeychain.Outcome = when (pgp) {
    is PgpContent.Encrypted -> OpenKeychain.decryptVerify(context, { File(pgp.ciphertext).inputStream() }, resume = resume)
    is PgpContent.Signed -> {
        val signature = withContext(Dispatchers.IO) { File(pgp.signature).readBytes() }
        OpenKeychain.decryptVerify(context, { File(pgp.signedEntity).inputStream() }, detachedSignature = signature, resume = resume)
    }
    PgpContent.InlineEncrypted, PgpContent.InlineSigned ->
        OpenKeychain.decryptVerify(context, { body.plainText.orEmpty().toByteArray().inputStream() }, resume = resume)
}

/** The quiet status line above an OpenPGP message: what it is, what was checked, and the one action that makes sense. */
@Composable
fun PgpBar(body: MessageBody, state: PgpReaderState, modifier: Modifier = Modifier) {
    val pgp = body.pgp ?: return
    val context = LocalContext.current
    val c = Dot.colors
    val installed = remember { OpenKeychain.installed(context) }
    val encrypted = pgp is PgpContent.Encrypted || pgp is PgpContent.InlineEncrypted
    val sig = state.signature
    val status = state.status

    val (icon: ImageVector, text: String, tint) = when {
        !installed -> Triple(if (encrypted) Icons.Outlined.Lock else Icons.Outlined.GppMaybe,
            stringResource(if (encrypted) R.string.pgp_install_encrypted else R.string.pgp_install_signed), c.textSecondary)
        status is PgpReaderState.Status.Working -> Triple(Icons.Outlined.Lock,
            stringResource(if (encrypted) R.string.pgp_decrypting else R.string.pgp_verifying), c.textSecondary)
        status is PgpReaderState.Status.Failed -> Triple(Icons.Outlined.GppBad,
            stringResource(R.string.pgp_failed, status.message ?: "—"), c.danger)
        sig != null -> signatureLine(sig, decrypted = encrypted && state.decrypted != null)
        encrypted && state.decrypted != null -> Triple(Icons.Outlined.LockOpen, stringResource(R.string.pgp_decrypted), c.textSecondary)
        encrypted -> Triple(Icons.Outlined.Lock, stringResource(R.string.pgp_encrypted), c.textSecondary)
        else -> Triple(Icons.Outlined.GppMaybe, stringResource(R.string.pgp_signed_unchecked), c.textSecondary)
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs)
            .clip(DotShapes.medium).background(c.surface).padding(horizontal = DotSpacing.m, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(DotSpacing.s))
        Text(text, style = Dot.type.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
        when {
            !installed -> DotButton(stringResource(R.string.pgp_get_openkeychain), {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OpenKeychain.FDROID_URL))) }
            }, style = ButtonStyle.Ghost, compact = true)
            encrypted && state.decrypted == null && status !is PgpReaderState.Status.Working ->
                DotButton(stringResource(R.string.pgp_decrypt), { state.run(null) }, compact = true)
            status is PgpReaderState.Status.Failed && !encrypted ->
                DotButton(stringResource(R.string.pgp_retry), { state.run(null) }, style = ButtonStyle.Ghost, compact = true)
        }
    }
}

@Composable
private fun signatureLine(sig: OpenKeychain.Signature, decrypted: Boolean): Triple<ImageVector, String, androidx.compose.ui.graphics.Color> {
    val c = Dot.colors
    val who = sig.signer ?: stringResource(R.string.pgp_unknown_signer)
    val prefix = if (decrypted) stringResource(R.string.pgp_decrypted_prefix) else ""
    return when (sig.verdict) {
        OpenKeychain.Verdict.VALID_CONFIRMED -> Triple(Icons.Outlined.VerifiedUser, prefix + stringResource(R.string.pgp_sig_valid, who), c.accent)
        OpenKeychain.Verdict.VALID_UNCONFIRMED -> Triple(Icons.Outlined.GppMaybe, prefix + stringResource(R.string.pgp_sig_unconfirmed, who), c.textSecondary)
        OpenKeychain.Verdict.KEY_MISSING -> Triple(Icons.Outlined.GppMaybe, prefix + stringResource(R.string.pgp_sig_key_missing), c.textSecondary)
        OpenKeychain.Verdict.KEY_EXPIRED_OR_REVOKED -> Triple(Icons.Outlined.GppBad, prefix + stringResource(R.string.pgp_sig_expired, who), c.danger)
        OpenKeychain.Verdict.INVALID -> Triple(Icons.Outlined.GppBad, prefix + stringResource(R.string.pgp_sig_invalid), c.danger)
        OpenKeychain.Verdict.UNSIGNED -> Triple(Icons.Outlined.LockOpen, stringResource(R.string.pgp_decrypted), c.textSecondary)
    }
}
