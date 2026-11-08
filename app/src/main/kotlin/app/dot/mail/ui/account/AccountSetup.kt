package app.dot.mail.ui.account

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.BackButton
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotTextField
import app.dot.design.components.DotTopBar
import app.dot.mail.LocalMailGraph
import app.dot.mail.data.AccountRepository
import app.dot.mail.data.model.AccountConfig
import app.dot.mail.data.model.AuthType
import app.dot.mail.data.model.Security
import app.dot.mail.data.model.ServerConfig
import app.dot.mail.data.model.VerifyResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen(onAddAccount: () -> Unit) {
    val c = Dot.colors
    Column(
        Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = DotSpacing.xxl),
    ) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(14.dp).clip(CircleShape).background(c.accent))
        Spacer(Modifier.height(DotSpacing.xxl))
        Text("Your mail.\nNothing else.", style = Dot.type.display.copy(fontSize = Dot.type.display.fontSize * 1.35f, lineHeight = Dot.type.display.lineHeight * 1.3f), color = c.textPrimary)
        Spacer(Modifier.height(DotSpacing.l))
        Text(
            "Dot Mail connects straight to your provider over IMAP and SMTP. No account with us, no servers in between, no tracking.",
            style = Dot.type.body,
            color = c.textSecondary,
        )
        Spacer(Modifier.weight(1.2f))
        DotButton("Add an email account", onClick = onAddAccount, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(DotSpacing.m))
        Text(
            "Works with Infomaniak, Gmail, Fastmail, iCloud, Yahoo, mailbox.org, Posteo and any IMAP server.",
            style = Dot.type.caption,
            color = c.textTertiary,
            modifier = Modifier.padding(bottom = DotSpacing.l),
        )
    }
}

// ---------------------------------------------------------------------------------------------

data class SetupState(
    val step: Step = Step.Credentials,
    val email: String = "",
    val password: String = "",
    val name: String = "",
    val busy: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val hint: String? = null,
    val oauthOnly: Boolean = false,
    val imapHost: String = "",
    val imapPort: String = "993",
    val imapSecurity: Security = Security.SSL_TLS,
    val smtpHost: String = "",
    val smtpPort: String = "465",
    val smtpSecurity: Security = Security.SSL_TLS,
    val username: String = "",
) {
    enum class Step { Credentials, Manual }

    val emailValid: Boolean get() = EMAIL.matches(email.trim())

    fun toConfig() = AccountConfig(
        email = email.trim(),
        displayName = name.ifBlank { email.substringBefore('@') }.trim(),
        imap = ServerConfig(imapHost.trim(), imapPort.toIntOrNull() ?: 993, imapSecurity, username.ifBlank { email }.trim()),
        smtp = ServerConfig(smtpHost.trim(), smtpPort.toIntOrNull() ?: 465, smtpSecurity, username.ifBlank { email }.trim()),
        authType = AuthType.PASSWORD,
    )

    companion object {
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")
    }
}

class AddAccountViewModel(private val repo: AccountRepository) : ViewModel() {
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()
    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    fun edit(transform: (SetupState) -> SetupState) = _state.update { transform(it).copy(error = null) }

    /** Discover → verify → save. Falls back to the manual form when discovery or login fails. */
    fun connect() {
        val s = _state.value
        if (!s.emailValid || s.password.isEmpty() || s.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "Looking up your provider…", error = null) }
            val config = if (s.step == SetupState.Step.Manual) {
                s.toConfig()
            } else {
                val found = runCatching { repo.discover(s.email.trim()) }.getOrNull()
                if (found == null) {
                    _state.update {
                        it.copy(
                            busy = false, status = null, step = SetupState.Step.Manual,
                            imapHost = "imap." + it.email.substringAfter('@'), smtpHost = "smtp." + it.email.substringAfter('@'),
                            error = "We couldn't find settings for this address. Check them below.",
                        )
                    }
                    return@launch
                }
                _state.update { it.withConfig(found) }
                if (found.authType == AuthType.OAUTH2) {
                    _state.update {
                        it.copy(
                            busy = false, status = null, oauthOnly = true,
                            error = "${found.providerHint ?: "This provider"} requires browser sign-in (OAuth), which isn't available in this version yet.",
                        )
                    }
                    return@launch
                }
                if (found.imap.security == Security.NONE || found.smtp.security == Security.NONE) {
                    _state.update {
                        it.copy(
                            busy = false, status = null, step = SetupState.Step.Manual,
                            error = found.providerHint ?: "This provider only advertises unencrypted settings. Review the security options below before continuing.",
                        )
                    }
                    return@launch
                }
                found.copy(displayName = s.name.ifBlank { found.displayName })
            }
            _state.update { it.copy(status = "Signing in to ${config.imap.host}…") }
            when (val result = runCatching { repo.verify(config, s.password) }.getOrElse { VerifyResult.ConnectionFailed(it.message ?: "Unknown error") }) {
                VerifyResult.Ok -> {
                    _state.update { it.copy(status = "Setting up your mailbox…") }
                    runCatching { repo.addAccount(config, s.password) }
                        .onSuccess { _done.value = true }
                        .onFailure { e -> _state.update { it.copy(busy = false, status = null, error = e.message ?: "Couldn't save the account.") } }
                }
                is VerifyResult.AuthFailed -> _state.update {
                    it.copy(busy = false, status = null, error = "Sign-in failed: ${result.message}" + (it.hint?.let { h -> "\n$h" } ?: ""))
                }
                is VerifyResult.ConnectionFailed -> _state.update {
                    it.copy(busy = false, status = null, step = SetupState.Step.Manual, error = "Couldn't connect: ${result.message}")
                }
                is VerifyResult.CertificateProblem -> _state.update {
                    it.copy(busy = false, status = null, step = SetupState.Step.Manual, error = "The server's certificate isn't trusted: ${result.message}")
                }
            }
        }
    }

    private fun SetupState.withConfig(c: AccountConfig) = copy(
        hint = c.providerHint,
        imapHost = c.imap.host, imapPort = c.imap.port.toString(), imapSecurity = c.imap.security,
        smtpHost = c.smtp.host, smtpPort = c.smtp.port.toString(), smtpSecurity = c.smtp.security,
        username = c.imap.username,
    )
}

@Composable
fun AddAccountScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val graph = LocalMailGraph.current
    val vm: AddAccountViewModel = viewModel { AddAccountViewModel(graph.accounts) }
    val state by vm.state.collectAsStateLifecycle()
    val done by vm.done.collectAsStateLifecycle()
    LaunchedEffect(done) { if (done) onDone() }
    val c = Dot.colors
    val passwordFocus = remember { FocusRequester() }

    Column(Modifier.fillMaxSize().background(c.background).imePadding()) {
        DotTopBar(navigation = { BackButton(onBack) })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = DotSpacing.gutter),
        ) {
            Text("Add account", style = Dot.type.display, color = c.textPrimary)
            Spacer(Modifier.height(DotSpacing.s))
            Text(
                "Sign in with your email address. For Gmail, iCloud and Yahoo use an app password.",
                style = Dot.type.callout, color = c.textSecondary,
            )
            Spacer(Modifier.height(DotSpacing.xxl))
            DotTextField(
                value = state.email, onValueChange = { v -> vm.edit { it.copy(email = v, oauthOnly = false) } },
                label = "Email", placeholder = "you@example.com",
                keyboardType = KeyboardType.Email, contentType = ContentType.EmailAddress + ContentType.Username,
                onImeAction = { passwordFocus.requestFocus() }, enabled = !state.busy,
            )
            Spacer(Modifier.height(DotSpacing.l))
            DotTextField(
                value = state.password, onValueChange = { v -> vm.edit { it.copy(password = v) } },
                label = "Password", password = true, contentType = ContentType.Password,
                imeAction = ImeAction.Done, onImeAction = vm::connect, focusRequester = passwordFocus,
                enabled = !state.busy, supporting = state.hint,
            )
            AnimatedVisibility(state.step == SetupState.Step.Manual) { ManualSettings(state, vm) }
            AnimatedContent(
                targetState = state.error ?: state.status,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "status",
            ) { msg ->
                if (msg != null) {
                    Text(
                        msg,
                        style = Dot.type.callout,
                        color = if (state.error != null) c.danger else c.textSecondary,
                        modifier = Modifier.padding(top = DotSpacing.l),
                    )
                }
            }
            if (state.step == SetupState.Step.Credentials) {
                Row(
                    Modifier.padding(top = DotSpacing.xl).clip(DotShapes.small)
                        .clickable(enabled = !state.busy, role = Role.Button) {
                            vm.edit {
                                it.copy(
                                    step = SetupState.Step.Manual,
                                    imapHost = it.imapHost.ifBlank { if (it.emailValid) "imap." + it.email.substringAfter('@') else "" },
                                    smtpHost = it.smtpHost.ifBlank { if (it.emailValid) "smtp." + it.email.substringAfter('@') else "" },
                                )
                            }
                        }
                        .heightIn(min = 48.dp)
                        .padding(vertical = DotSpacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp), tint = c.textSecondary)
                    Spacer(Modifier.width(DotSpacing.s))
                    Text("Configure servers manually", style = Dot.type.label, color = c.textSecondary)
                }
            }
            Spacer(Modifier.height(DotSpacing.xxl))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp).padding(top = 2.dp), tint = c.textTertiary)
                Spacer(Modifier.width(DotSpacing.s))
                Text(
                    "Your password is encrypted on this device with the Android Keystore and only ever sent to your mail server.",
                    style = Dot.type.caption, color = c.textTertiary,
                )
            }
            Spacer(Modifier.height(DotSpacing.xl))
        }
        DotButton(
            text = if (state.busy) "Connecting" else "Continue",
            onClick = vm::connect,
            loading = state.busy,
            enabled = state.emailValid && state.password.isNotEmpty() && !state.oauthOnly,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(DotSpacing.gutter),
        )
    }
}

@Composable
private fun ManualSettings(state: SetupState, vm: AddAccountViewModel) {
    Column(Modifier.padding(top = DotSpacing.xxl)) {
        Text("Incoming (IMAP)", style = Dot.type.headline, color = Dot.colors.textPrimary)
        Spacer(Modifier.height(DotSpacing.m))
        ServerFields(
            host = state.imapHost, port = state.imapPort, security = state.imapSecurity,
            onHost = { v -> vm.edit { it.copy(imapHost = v) } },
            onPort = { v -> vm.edit { it.copy(imapPort = v.filter(Char::isDigit).take(5)) } },
            onSecurity = { v -> vm.edit { it.copy(imapSecurity = v, imapPort = defaultPort(v, imap = true)) } },
        )
        Spacer(Modifier.height(DotSpacing.xl))
        Text("Outgoing (SMTP)", style = Dot.type.headline, color = Dot.colors.textPrimary)
        Spacer(Modifier.height(DotSpacing.m))
        ServerFields(
            host = state.smtpHost, port = state.smtpPort, security = state.smtpSecurity,
            onHost = { v -> vm.edit { it.copy(smtpHost = v) } },
            onPort = { v -> vm.edit { it.copy(smtpPort = v.filter(Char::isDigit).take(5)) } },
            onSecurity = { v -> vm.edit { it.copy(smtpSecurity = v, smtpPort = defaultPort(v, imap = false)) } },
        )
        Spacer(Modifier.height(DotSpacing.l))
        DotTextField(
            value = state.username, onValueChange = { v -> vm.edit { it.copy(username = v) } },
            label = "Username", placeholder = "Usually your email address",
            keyboardType = KeyboardType.Email,
        )
        Spacer(Modifier.height(DotSpacing.l))
        DotTextField(
            value = state.name, onValueChange = { v -> vm.edit { it.copy(name = v) } },
            label = "Your name", placeholder = "Shown to recipients", contentType = ContentType.PersonFullName,
        )
    }
}

private fun defaultPort(security: Security, imap: Boolean) = when (security) {
    Security.SSL_TLS -> if (imap) "993" else "465"
    Security.STARTTLS -> if (imap) "143" else "587"
    Security.NONE -> if (imap) "143" else "25"
}

@Composable
private fun ServerFields(
    host: String, port: String, security: Security,
    onHost: (String) -> Unit, onPort: (String) -> Unit, onSecurity: (Security) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(DotSpacing.m)) {
        DotTextField(host, onHost, Modifier.weight(1f), label = "Server", keyboardType = KeyboardType.Uri)
        DotTextField(port, onPort, Modifier.widthIn(min = 100.dp).weight(0.4f), label = "Port", keyboardType = KeyboardType.Number)
    }
    Spacer(Modifier.height(DotSpacing.m))
    Segmented(
        options = listOf(Security.SSL_TLS to "SSL/TLS", Security.STARTTLS to "STARTTLS", Security.NONE to "None"),
        selected = security,
        onSelect = onSecurity,
    )
    if (security == Security.NONE) {
        Text(
            "Without encryption your password and mail travel in clear text. Only use this on a trusted local network.",
            style = Dot.type.caption, color = Dot.colors.danger, modifier = Modifier.padding(top = DotSpacing.s),
        )
    }
}

/** Quiet segmented control: hairline container, raised fill for the selection. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Dot.colors
    Row(
        modifier.fillMaxWidth().selectableGroup().clip(DotShapes.medium).border(1.dp, c.border, DotShapes.medium).padding(3.dp),
    ) {
        options.forEach { (value, label) ->
            val isSel = value == selected
            Box(
                Modifier.weight(1f).clip(DotShapes.small)
                    .background(if (isSel) c.surfaceRaised else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = isSel, role = Role.RadioButton, onClick = { onSelect(value) })
                    .heightIn(min = 48.dp)
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Dot.type.label, color = if (isSel) c.textPrimary else c.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun <T> StateFlow<T>.collectAsStateLifecycle() = collectAsStateWithLifecycle()
