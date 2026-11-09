package app.dot.mail.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.security.KeyChain
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.BackButton
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotTopBar
import app.dot.design.components.EmptyState
import app.dot.design.components.Hairline
import app.dot.design.components.ListRow
import app.dot.design.components.SectionLabel
import app.dot.design.components.backdropSource
import app.dot.design.components.dotSurfaceEnter
import app.dot.mail.LocalMailGraph
import app.dot.mail.R
import app.dot.mail.data.KeyChainSmimeKeys
import app.dot.mail.data.SmimeCertValidity
import app.dot.mail.data.SmimeKeyStatus
import app.dot.mail.data.StoredSmimeCert
import app.dot.mail.data.model.Account
import app.dot.mail.data.model.SmimeAccountConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Pure presentation rules for the S/MIME settings, kept free of Compose so they are unit-tested. */
internal object SmimeUi {
    /** "AB12 CD34 EF56 7890": the first 16 hex digits of a SHA-256 fingerprint, grouped by four, upper case. */
    fun shortFingerprint(fingerprint: String): String = fingerprint.take(16).uppercase().chunked(4).joinToString(" ")

    enum class Slot { SIGNING, DECRYPTION }

    /** Protection defaults may only be turned on while the keys they need are usable. */
    fun canDefaultToSign(signing: SmimeKeyStatus) = signing is SmimeKeyStatus.Available && signing.validity(System.currentTimeMillis()) == SmimeCertValidity.VALID
    fun canDefaultToEncrypt(decryption: SmimeKeyStatus) = decryption is SmimeKeyStatus.Available && decryption.validity(System.currentTimeMillis()) == SmimeCertValidity.VALID

    /** Which alias slot a state change applies to, and what "Remove" resets (references and defaults; KeyChain itself is untouched). */
    fun withAlias(config: SmimeAccountConfig, slot: Slot, alias: String?): SmimeAccountConfig = when (slot) {
        Slot.SIGNING -> config.copy(signingKeyChainAlias = alias, signByDefault = config.signByDefault && alias != null)
        Slot.DECRYPTION -> config.copy(decryptionKeyChainAlias = alias, encryptByDefault = config.encryptByDefault && alias != null)
    }

    fun isConfigured(config: SmimeAccountConfig) = config.signingKeyChainAlias != null || config.decryptionKeyChainAlias != null
}

private tailrec fun Context.findActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.findActivity(); else -> null }

private fun date(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

/** Account page section: KeyChain certificates for signing and decryption, defaults, removal and the contact certificate list. */
@Composable
internal fun SmimeAccountSection(account: Account, certCount: Int, onOpenContacts: () -> Unit) {
    val c = Dot.colors
    val context = LocalContext.current
    val graph = LocalMailGraph.current
    val scope = rememberCoroutineScope()
    val smime = account.smime
    val keys = remember(account.id, smime) { KeyChainSmimeKeys(context.applicationContext, { smime }) }
    // KeyChain can lose an entry behind our back (user removes it in system settings): look again whenever the page resumes.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    var signing by remember { mutableStateOf<SmimeKeyStatus>(SmimeKeyStatus.NotSet) }
    var decryption by remember { mutableStateOf<SmimeKeyStatus>(SmimeKeyStatus.NotSet) }
    LaunchedEffect(smime.signingKeyChainAlias, refresh) { signing = keys.status(smime.signingKeyChainAlias) }
    LaunchedEffect(smime.decryptionKeyChainAlias, refresh) { decryption = keys.status(smime.decryptionKeyChainAlias) }
    var confirmRemove by remember { mutableStateOf(false) }

    fun choose(slot: SmimeUi.Slot) {
        val activity = context.findActivity() ?: return
        KeyChain.choosePrivateKeyAlias(activity, { alias ->
            // Called on a binder thread; null = the user cancelled.
            if (alias != null) scope.launch(Dispatchers.Main) { graph.accounts.updateSmime(account.id) { SmimeUi.withAlias(it, slot, alias) } }
        }, arrayOf("RSA", "EC"), null, null, -1, if (slot == SmimeUi.Slot.SIGNING) smime.signingKeyChainAlias else smime.decryptionKeyChainAlias)
    }

    SectionLabel(stringResource(R.string.account_section_smime))
    KeyRow(stringResource(R.string.smime_signing_key), signing, account.email, Icons.Outlined.VerifiedUser) { choose(SmimeUi.Slot.SIGNING) }
    KeyRow(stringResource(R.string.smime_decryption_key), decryption, account.email, Icons.Outlined.Lock) { choose(SmimeUi.Slot.DECRYPTION) }
    ToggleRow(
        stringResource(R.string.smime_sign_default), stringResource(R.string.smime_sign_default_sub), Icons.Outlined.Key,
        smime.signByDefault, enabled = SmimeUi.canDefaultToSign(signing) || smime.signByDefault,
    ) { v -> scope.launch { graph.accounts.updateSmime(account.id) { it.copy(signByDefault = v) } } }
    ToggleRow(
        stringResource(R.string.smime_encrypt_default), stringResource(R.string.smime_encrypt_default_sub), Icons.Outlined.Key,
        smime.encryptByDefault, enabled = SmimeUi.canDefaultToEncrypt(decryption) || smime.encryptByDefault,
    ) { v -> scope.launch { graph.accounts.updateSmime(account.id) { it.copy(encryptByDefault = v) } } }
    ListRow(
        stringResource(R.string.smime_contacts), subtitle = if (certCount == 0) stringResource(R.string.smime_contacts_none) else pluralStringResource(R.plurals.smime_contacts_count, certCount, certCount),
        icon = Icons.Outlined.Key, onClick = onOpenContacts,
    )
    if (SmimeUi.isConfigured(smime)) {
        ListRow(stringResource(R.string.smime_remove), subtitle = stringResource(R.string.smime_remove_sub), icon = Icons.Outlined.DeleteOutline, titleColor = c.danger, onClick = { confirmRemove = true })
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false }, modifier = Modifier.dotSurfaceEnter(), containerColor = c.surface, shape = DotShapes.dialog,
            title = { Text(stringResource(R.string.smime_remove_title), style = Dot.type.title, color = c.textPrimary) },
            text = { Text(stringResource(R.string.smime_remove_body), style = Dot.type.callout, color = c.textSecondary) },
            confirmButton = { DotButton(stringResource(R.string.smime_remove_confirm), { confirmRemove = false; scope.launch { graph.accounts.updateSmime(account.id) { SmimeAccountConfig() } } }, style = ButtonStyle.Danger, compact = true) },
            dismissButton = { DotButton(stringResource(R.string.outbox_cancel), { confirmRemove = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
}

@Composable
private fun KeyRow(title: String, status: SmimeKeyStatus, accountEmail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onChoose: () -> Unit) {
    val c = Dot.colors
    val now = System.currentTimeMillis()
    val (subtitle, color) = when (status) {
        SmimeKeyStatus.NotSet -> stringResource(R.string.smime_key_not_set) to c.textSecondary
        // A vanished entry is never a silent state: say what to do.
        SmimeKeyStatus.Unavailable -> stringResource(R.string.smime_key_unavailable) to c.danger
        is SmimeKeyStatus.Available -> {
            val line = when (status.validity(now)) {
                SmimeCertValidity.VALID -> stringResource(R.string.smime_key_valid, status.subject, date(status.notAfter))
                SmimeCertValidity.EXPIRED -> stringResource(R.string.smime_key_expired, status.subject, date(status.notAfter))
                SmimeCertValidity.NOT_YET_VALID -> stringResource(R.string.smime_key_not_yet_valid, status.subject, date(status.notBefore))
            }
            val other = if (!status.namesMailbox(accountEmail) && status.mailboxes.isNotEmpty()) "\n" + stringResource(R.string.smime_key_other_address, status.mailboxes.sorted().joinToString(", ")) else ""
            (line + other) to (if (status.validity(now) == SmimeCertValidity.VALID && other.isEmpty()) c.textSecondary else c.danger)
        }
    }
    ListRow(title, subtitle = subtitle, subtitleColor = color, subtitleMaxLines = 4, icon = icon, onClick = onChoose)
}

/** Per-contact certificates collected from verified signed mail: view, pin one per address, or forget. */
@Composable
internal fun SmimeContactsPage(account: Account, onBack: () -> Unit) {
    val c = Dot.colors
    val graph = LocalMailGraph.current
    val scope = rememberCoroutineScope()
    val certs by remember(account.id) { graph.smime.certificates(account.id) }.collectAsStateWithLifecycle(emptyList())
    var forgetting by remember { mutableStateOf<StoredSmimeCert?>(null) }
    val scroll = rememberScrollState()
    val frostedSource = app.dot.design.components.rememberGlassBackdrop()
    app.dot.design.components.FrostedHeaderLayout(
        backdrop = frostedSource, modifier = Modifier.fillMaxSize().background(c.background),
        header = { DotTopBar(title = stringResource(R.string.smime_contacts), subtitle = account.email, elevated = scroll.value > 0, navigation = { BackButton(onBack) }) },
    ) { topPadding ->
        Column(Modifier.fillMaxSize().backdropSource(frostedSource).verticalScroll(scroll).navigationBarsPadding()) {
            Spacer(Modifier.height(topPadding.calculateTopPadding()))
            if (certs.isEmpty()) {
                EmptyState(Icons.Outlined.VerifiedUser, stringResource(R.string.smime_contacts_empty_title), stringResource(R.string.smime_contacts_empty_body))
            } else {
                Text(stringResource(R.string.smime_contacts_note), style = Dot.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s))
                val now = System.currentTimeMillis()
                certs.groupBy { it.mailbox }.forEach { (mailbox, list) ->
                    SectionLabel(mailbox)
                    list.forEach { cert ->
                        val validity = when (cert.validity(now)) {
                            SmimeCertValidity.VALID -> stringResource(R.string.smime_cert_expires, date(cert.notAfter))
                            SmimeCertValidity.EXPIRED -> stringResource(R.string.smime_cert_expired, date(cert.notAfter))
                            SmimeCertValidity.NOT_YET_VALID -> stringResource(R.string.smime_cert_not_yet_valid, date(cert.notBefore))
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.m)) {
                            Text(app.dot.mail.data.smimeSubjectNameFromDn(cert.subject), style = Dot.type.headline, color = c.textPrimary)
                            Text("${SmimeUi.shortFingerprint(cert.fingerprint)} · $validity", style = Dot.type.caption, color = if (cert.validity(now) == SmimeCertValidity.VALID) c.textSecondary else c.danger)
                            if (cert.pinned) Text(stringResource(R.string.smime_cert_pinned), style = Dot.type.caption, color = c.accent)
                            Row(Modifier.padding(top = DotSpacing.s)) {
                                if (cert.pinned) DotButton(stringResource(R.string.smime_cert_unpin), { scope.launch { graph.smime.unpin(account.id, cert.mailbox, cert.fingerprint) } }, style = ButtonStyle.Secondary, compact = true)
                                else DotButton(stringResource(R.string.smime_cert_pin), { scope.launch { graph.smime.pin(account.id, cert.mailbox, cert.fingerprint) } }, style = ButtonStyle.Secondary, compact = true)
                                Spacer(Modifier.padding(horizontal = DotSpacing.xs))
                                DotButton(stringResource(R.string.smime_cert_forget), { forgetting = cert }, style = ButtonStyle.Ghost, compact = true)
                            }
                        }
                        Hairline(Modifier.padding(horizontal = DotSpacing.gutter))
                    }
                }
            }
        }
    }
    forgetting?.let { cert ->
        AlertDialog(
            onDismissRequest = { forgetting = null }, modifier = Modifier.dotSurfaceEnter(), containerColor = c.surface, shape = DotShapes.dialog,
            title = { Text(stringResource(R.string.smime_cert_forget_title, cert.mailbox), style = Dot.type.title, color = c.textPrimary) },
            text = { Text(stringResource(R.string.smime_cert_forget_body), style = Dot.type.callout, color = c.textSecondary) },
            confirmButton = { DotButton(stringResource(R.string.smime_cert_forget), { forgetting = null; scope.launch { graph.smime.forget(account.id, cert.mailbox, cert.fingerprint) } }, style = ButtonStyle.Danger, compact = true) },
            dismissButton = { DotButton(stringResource(R.string.outbox_cancel), { forgetting = null }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
}
