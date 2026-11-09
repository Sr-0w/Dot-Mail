package app.dot.mail.ui.smime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.dotSurfaceEnter
import app.dot.mail.LocalMailGraph
import app.dot.mail.R
import app.dot.mail.data.model.Message
import app.dot.mail.data.model.MessageBody
import app.dot.mail.data.model.SmimeContent
import app.dot.mail.data.model.SmimeDecryption
import app.dot.mail.data.model.SmimeCertificateInfo
import app.dot.mail.data.model.SmimeSignatureView
import app.dot.mail.data.model.SmimeSignatureView.State
import java.text.DateFormat
import java.util.Date

/** What the reader knows about a message's S/MIME protection. [decrypted] replaces the body for display, in memory only. */
class SmimeReaderState {
    var checking by mutableStateOf(true)
    var signature by mutableStateOf<SmimeSignatureView?>(null)
    var decryption by mutableStateOf<SmimeDecryption?>(null)
    val decrypted: MessageBody? get() = (decryption as? SmimeDecryption.Done)?.body
}

/**
 * Verifies (signed) or decrypts (encrypted) [message] once its body is known. Decrypting needs no prompt: the account's
 * KeyChain key was granted when it was chosen in Settings, and the plaintext stays in memory.
 */
@Composable
fun rememberSmimeReader(message: Message): SmimeReaderState {
    val graph = LocalMailGraph.current
    val content = message.body?.smime
    val state = remember(message.id, content) { SmimeReaderState() }
    LaunchedEffect(state) {
        when (content) {
            null -> Unit
            is SmimeContent.Signed -> state.signature = runCatching { graph.mail.verifySmime(message) }.getOrNull() ?: SmimeSignatureView(State.UNREADABLE)
            is SmimeContent.Encrypted -> {
                val result = runCatching { graph.mail.decryptSmime(message) }.getOrNull() ?: SmimeDecryption.Failed
                state.decryption = result
                state.signature = (result as? SmimeDecryption.Done)?.signature
            }
        }
        state.checking = false
    }
    return state
}

/**
 * The S/MIME lines of a message: encryption (when encrypted) and signature. Only an intact signature from a certificate the
 * device trusts, issued for the sender's address, gets the accent colour and the check icon; everything else stays neutral
 * or red. Tapping the signature opens the certificate details.
 */
@Composable
fun SmimeBar(message: Message, state: SmimeReaderState, modifier: Modifier = Modifier) {
    val content = message.body?.smime ?: return
    if (content is SmimeContent.Encrypted) EncryptionLine(state, modifier)
    if (content is SmimeContent.Signed || state.signature != null) SignatureLine(if (state.checking) null else state.signature ?: SmimeSignatureView(State.UNREADABLE), modifier)
}

@Composable
private fun EncryptionLine(state: SmimeReaderState, modifier: Modifier) {
    val c = Dot.colors
    val (icon, text, tint) = when (state.decryption) {
        null -> Triple(Icons.Outlined.Lock, stringResource(R.string.smime_decrypting), c.textSecondary)
        is SmimeDecryption.Done -> Triple(Icons.Outlined.LockOpen, stringResource(R.string.smime_decrypted), c.textSecondary)
        SmimeDecryption.NoKey -> Triple(Icons.Outlined.Lock, stringResource(R.string.smime_no_key), c.textSecondary)
        SmimeDecryption.NotForThisKey -> Triple(Icons.Outlined.Lock, stringResource(R.string.smime_not_for_key), c.textSecondary)
        SmimeDecryption.WeakEncryption -> Triple(Icons.Outlined.GppBad, stringResource(R.string.smime_weak), c.danger)
        SmimeDecryption.Failed -> Triple(Icons.Outlined.GppBad, stringResource(R.string.smime_decrypt_failed), c.danger)
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs)
            .clip(DotShapes.medium).background(c.surface).padding(horizontal = DotSpacing.m, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(DotSpacing.s))
        Text(text, style = Dot.type.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SignatureLine(view: SmimeSignatureView?, modifier: Modifier) {
    val c = Dot.colors
    var details by remember(view) { mutableStateOf(false) }
    val (icon, text, tint) = line(view)
    Row(
        modifier.fillMaxWidth().padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs)
            .clip(DotShapes.medium)
            .then(if (view?.signer != null) Modifier.clickable { details = true }.semantics { role = Role.Button } else Modifier)
            .background(c.surface).padding(horizontal = DotSpacing.m, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(DotSpacing.s))
        Text(text, style = Dot.type.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
    }
    val signer = view?.signer
    if (details && signer != null) {
        AlertDialog(
            onDismissRequest = { details = false },
            modifier = Modifier.dotSurfaceEnter(),
            containerColor = c.surface,
            shape = DotShapes.dialog,
            title = { Text(stringResource(R.string.smime_details_title), style = Dot.type.title, color = c.textPrimary) },
            text = { Details(view, signer) },
            confirmButton = { DotButton(stringResource(R.string.compose_close), { details = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
}

@Composable
private fun line(view: SmimeSignatureView?): Triple<ImageVector, String, Color> {
    val c = Dot.colors
    val who = view?.signer?.mailboxes?.firstOrNull() ?: view?.signer?.subject.orEmpty()
    return when (view?.state) {
        null -> Triple(Icons.Outlined.GppMaybe, stringResource(R.string.smime_verifying), c.textSecondary)
        State.VALID_TRUSTED -> Triple(Icons.Outlined.VerifiedUser, stringResource(R.string.smime_valid, who), c.accent)
        State.VALID_UNTRUSTED -> Triple(Icons.Outlined.GppMaybe, stringResource(R.string.smime_untrusted), c.textSecondary)
        State.SENDER_MISMATCH -> Triple(Icons.Outlined.GppBad, stringResource(R.string.smime_mismatch, who), c.danger)
        State.INVALID -> Triple(Icons.Outlined.GppBad, stringResource(R.string.smime_invalid), c.danger)
        State.UNREADABLE -> Triple(Icons.Outlined.GppMaybe, stringResource(R.string.smime_unreadable), c.textSecondary)
    }
}

@Composable
private fun Details(view: SmimeSignatureView, signer: SmimeCertificateInfo) {
    val c = Dot.colors
    val dates = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        val summary = when (view.state) {
            State.VALID_TRUSTED -> R.string.smime_details_trusted
            State.VALID_UNTRUSTED -> R.string.smime_details_untrusted
            State.SENDER_MISMATCH -> R.string.smime_details_mismatch
            State.INVALID -> R.string.smime_details_invalid
            State.UNREADABLE -> R.string.smime_unreadable
        }
        Text(stringResource(summary), style = Dot.type.callout, color = c.textPrimary)
        @Composable fun field(label: Int, value: String) {
            Spacer(Modifier.height(DotSpacing.m))
            Text(stringResource(label), style = Dot.type.caption, color = c.textTertiary)
            Text(value, style = Dot.type.callout, color = c.textSecondary)
        }
        field(R.string.smime_details_issued_to, (signer.mailboxes.joinToString("\n").ifEmpty { signer.subject }))
        field(R.string.smime_details_issuer, signer.issuer)
        field(R.string.smime_details_validity, stringResource(R.string.smime_details_validity_range, dates.format(Date(signer.notBefore)), dates.format(Date(signer.notAfter))))
        field(R.string.smime_details_fingerprint, signer.sha256.chunked(2).joinToString(":"))
        Spacer(Modifier.height(DotSpacing.m))
        Text(stringResource(R.string.smime_details_revocation), style = Dot.type.caption, color = c.textTertiary)
    }
}
