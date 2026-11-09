package app.dot.mail.ui.account

import app.dot.design.components.backdropSource
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import app.dot.mail.R
import app.dot.mail.data.AccountRepository
import app.dot.mail.data.model.AccountConfig
import app.dot.mail.data.model.AuthType
import app.dot.mail.data.model.Security
import app.dot.mail.data.model.ServerConfig
import app.dot.mail.data.model.VerifyResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
        Text(
            stringResource(R.string.setup_welcome_title),
            style = Dot.type.display.copy(fontSize = Dot.type.display.fontSize * 1.35f, lineHeight = Dot.type.display.lineHeight * 1.3f),
            color = c.textPrimary,
        )
        Spacer(Modifier.height(DotSpacing.l))
        Text(
            stringResource(R.string.setup_welcome_body),
            style = Dot.type.body,
            color = c.textSecondary,
        )
        Spacer(Modifier.weight(1.2f))
        DotButton(stringResource(R.string.setup_welcome_cta), onClick = onAddAccount, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(DotSpacing.m))
        Text(
            stringResource(R.string.setup_welcome_providers),
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
    /** This build has a Google client ID; without one the button is replaced by an explanation. */
    val googleAvailable: Boolean = false,
    val google: GooglePhase = GooglePhase.Idle,
    val imapHost: String = "",
    val imapPort: String = "993",
    val imapSecurity: Security = Security.SSL_TLS,
    val smtpHost: String = "",
    val smtpPort: String = "465",
    val smtpSecurity: Security = Security.SSL_TLS,
    val username: String = "",
) {
    enum class Step { Credentials, Manual }
    enum class GooglePhase { Idle, Opening, Waiting, Completing }

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

class AddAccountViewModel(
    private val repo: AccountRepository,
    private val strings: ((Int, Array<out Any>) -> String)? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(SetupState(googleAvailable = repo.oauthAvailability(app.dot.mail.data.model.OAuthProvider.GOOGLE).available))
    private val _launch = kotlinx.coroutines.channels.Channel<android.content.Intent>(kotlinx.coroutines.channels.Channel.BUFFERED)
    /** The system-browser sign-in page to open; the screen starts it. */
    val launch: kotlinx.coroutines.flow.Flow<android.content.Intent> = _launch.receiveAsFlow()
    private var leftForBrowser = false

    /** "Continue with Google": opens the system browser (PKCE); the result comes back through [OAuthSignIn]. */
    fun signInWithGoogle() {
        val s = _state.value
        if (!s.googleAvailable || !s.emailValid || s.busy) return
        leftForBrowser = false
        _state.update { it.copy(busy = true, google = SetupState.GooglePhase.Opening, error = null, status = str(R.string.setup_google_opening, "Opening Google sign-in…")) }
        viewModelScope.launch {
            runCatching { repo.beginOAuth(s.email.trim(), app.dot.mail.data.model.OAuthProvider.GOOGLE) }
                .onSuccess { intent -> OAuthSignIn.waiting(); _launch.send(intent) }
                .onFailure { e ->
                    val code = (e as? app.dot.mail.data.model.OAuthException)?.code ?: app.dot.mail.data.model.OAuthError.OTHER
                    _state.update { it.copy(busy = false, google = SetupState.GooglePhase.Idle, status = null, error = str(oauthErrorRes(code), "Sign-in failed")) }
                }
        }
    }

    /** The browser could not be opened (no browser installed). */
    fun browserUnavailable() {
        OAuthSignIn.reset()
        _state.update { it.copy(busy = false, google = SetupState.GooglePhase.Idle, status = null, error = str(R.string.setup_google_no_browser, "No web browser is available to sign in.")) }
    }

    fun cancelGoogle() {
        OAuthSignIn.reset()
        _state.update { it.copy(busy = false, google = SetupState.GooglePhase.Idle, status = null, error = null) }
    }

    /** The app went to the background (the browser took over). */
    fun onPaused() { if (_state.value.google == SetupState.GooglePhase.Waiting) leftForBrowser = true }

    /** Back in front: if the browser flow never came back with a result, treat it as cancelled after a short grace. */
    fun onResumed() {
        if (_state.value.google != SetupState.GooglePhase.Waiting || !leftForBrowser) return
        viewModelScope.launch { kotlinx.coroutines.delay(RETURN_GRACE_MS); OAuthSignIn.cancelIfStillWaiting() }
    }
    val state: StateFlow<SetupState> = _state.asStateFlow()
    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    private fun str(resId: Int, fallback: String, vararg args: Any): String =
        strings?.invoke(resId, args) ?: fallback

    fun edit(transform: (SetupState) -> SetupState) = _state.update { transform(it).copy(error = null) }

    /** Discover → verify → save. Falls back to the manual form when discovery or login fails. */
    fun connect() {
        val s = _state.value
        if (!s.emailValid || s.password.isEmpty() || s.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    status = str(R.string.setup_status_looking_up, "Looking up your provider…"),
                    error = null,
                )
            }
            val config = if (s.step == SetupState.Step.Manual) {
                s.toConfig()
            } else {
                val found = runCatching { repo.discover(s.email.trim()) }.getOrNull()
                if (found == null) {
                    _state.update {
                        it.copy(
                            busy = false, status = null, step = SetupState.Step.Manual,
                            imapHost = "imap." + it.email.substringAfter('@'), smtpHost = "smtp." + it.email.substringAfter('@'),
                            error = str(R.string.setup_error_not_found, "We couldn't find settings for this address. Check them below."),
                        )
                    }
                    return@launch
                }
                _state.update { it.withConfig(found) }
                if (found.authType == AuthType.OAUTH2) {
                    val provider = found.providerHint ?: str(R.string.setup_this_provider, "This provider")
                    _state.update {
                        it.copy(
                            busy = false, status = null, oauthOnly = true,
                            error = if (it.googleAvailable && found.imap.host.contains("gmail", true)) str(
                                R.string.setup_error_oauth_use_google, "$provider requires browser sign-in: use \"Continue with Google\" above.", provider,
                            ) else str(
                                R.string.setup_error_oauth_only,
                                "$provider requires browser sign-in (OAuth), which isn't available in this version yet.",
                                provider,
                            ),
                        )
                    }
                    return@launch
                }
                if (found.imap.security == Security.NONE || found.smtp.security == Security.NONE) {
                    _state.update {
                        it.copy(
                            busy = false, status = null, step = SetupState.Step.Manual,
                            error = found.providerHint ?: str(
                                R.string.setup_error_unencrypted,
                                "This provider only advertises unencrypted settings. Review the security options below before continuing.",
                            ),
                        )
                    }
                    return@launch
                }
                found.copy(displayName = s.name.ifBlank { found.displayName })
            }
            _state.update {
                it.copy(
                    status = str(
                        R.string.setup_status_signing_in,
                        "Signing in to ${config.imap.host}…",
                        config.imap.host,
                    ),
                )
            }
            val result = runCatching { repo.verify(config, s.password) }.getOrElse {
                VerifyResult.ConnectionFailed(it.message ?: "Unknown error", app.dot.mail.engine.ConnectionFailure.classify(it))
            }
            when (result) {
                VerifyResult.Ok -> {
                    _state.update { it.copy(status = str(R.string.setup_status_setting_up, "Setting up your mailbox…")) }
                    runCatching { repo.addAccount(config, s.password) }
                        .onSuccess { _done.value = true }
                        .onFailure { e ->
                            _state.update {
                                it.copy(
                                    busy = false,
                                    status = null,
                                    error = e.message ?: str(R.string.setup_error_save_failed, "Couldn't save the account."),
                                )
                            }
                        }
                }
                is VerifyResult.AuthFailed -> _state.update {
                    val baseError = str(R.string.setup_error_auth_failed, "Sign-in failed: ${result.message}", str(verifyReasonRes(result), result.message))
                    it.copy(busy = false, status = null, error = baseError + (it.hint?.let { h -> "\n$h" } ?: ""))
                }
                is VerifyResult.ConnectionFailed -> _state.update {
                    it.copy(
                        busy = false,
                        status = null,
                        step = SetupState.Step.Manual,
                        error = str(R.string.setup_error_connection_failed, "Couldn't connect: ${result.message}", str(verifyReasonRes(result), result.message)),
                    )
                }
                is VerifyResult.CertificateProblem -> _state.update {
                    it.copy(
                        busy = false,
                        status = null,
                        step = SetupState.Step.Manual,
                        error = str(R.string.setup_error_certificate, "The server's certificate isn't trusted: ${result.message}", str(verifyReasonRes(result), result.message)),
                    )
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            OAuthSignIn.status.collect { s ->
                when (s) {
                    OAuthSignIn.Status.Idle -> Unit
                    OAuthSignIn.Status.Waiting -> _state.update { it.copy(google = SetupState.GooglePhase.Waiting, busy = true, error = null, status = str(R.string.setup_google_waiting, "Waiting for Google…")) }
                    OAuthSignIn.Status.Completing -> _state.update { it.copy(google = SetupState.GooglePhase.Completing, busy = true, error = null, status = str(R.string.setup_status_setting_up, "Setting up your mailbox…")) }
                    is OAuthSignIn.Status.Done -> { OAuthSignIn.reset(); _state.update { it.copy(google = SetupState.GooglePhase.Idle, busy = false, status = null) }; _done.value = true }
                    is OAuthSignIn.Status.Failed -> { OAuthSignIn.reset(); _state.update { it.copy(google = SetupState.GooglePhase.Idle, busy = false, status = null, error = str(oauthErrorRes(s.code), "Sign-in failed")) } }
                    OAuthSignIn.Status.Cancelled -> { OAuthSignIn.reset(); _state.update { it.copy(google = SetupState.GooglePhase.Idle, busy = false, status = str(R.string.setup_google_cancelled, "Google sign-in was cancelled."), error = null) } }
                }
            }
        }
    }

    private companion object { const val RETURN_GRACE_MS = 1_500L }

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
    val appContext = LocalContext.current.applicationContext
    val vm: AddAccountViewModel = viewModel {
        AddAccountViewModel(
            repo = graph.accounts,
            strings = { id, args -> appContext.getString(id, *args) },
        )
    }
    val state by vm.state.collectAsStateLifecycle()
    val done by vm.done.collectAsStateLifecycle()
    val context = LocalContext.current
    // The system browser page of the Google sign-in; its result comes back through MainActivity.
    LaunchedEffect(Unit) {
        vm.launch.collect { intent ->
            try { context.startActivity(intent) } catch (_: android.content.ActivityNotFoundException) { vm.browserUnavailable() }
        }
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_PAUSE) { vm.onPaused() }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.onResumed() }
    LaunchedEffect(done) { if (done) onDone() }
    val c = Dot.colors
    val passwordFocus = remember { FocusRequester() }

    val frostedSource=app.dot.design.components.rememberGlassBackdrop()
    app.dot.design.components.FrostedHeaderLayout(
        backdrop=frostedSource,modifier=Modifier.fillMaxSize().background(c.background).imePadding(),
        header={ DotTopBar(navigation = { BackButton(onBack) }) },
    ) { topPadding ->
        Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().weight(1f).backdropSource(frostedSource).verticalScroll(rememberScrollState()).padding(horizontal = DotSpacing.gutter),
        ) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(topPadding.calculateTopPadding()))

            Text(stringResource(R.string.setup_title), style = Dot.type.display, color = c.textPrimary)
            Spacer(Modifier.height(DotSpacing.s))
            Text(
                stringResource(R.string.setup_subtitle),
                style = Dot.type.callout, color = c.textSecondary,
            )
            Spacer(Modifier.height(DotSpacing.xxl))
            DotTextField(
                value = state.email, onValueChange = { v -> vm.edit { it.copy(email = v, oauthOnly = false) } },
                label = stringResource(R.string.setup_email_label), placeholder = stringResource(R.string.setup_email_placeholder),
                keyboardType = KeyboardType.Email, contentType = ContentType.EmailAddress + ContentType.Username,
                onImeAction = { passwordFocus.requestFocus() }, enabled = !state.busy,
            )
            if (state.step == SetupState.Step.Credentials) GoogleSignIn(state, vm)
            Spacer(Modifier.height(DotSpacing.l))
            DotTextField(
                value = state.password, onValueChange = { v -> vm.edit { it.copy(password = v) } },
                label = stringResource(R.string.setup_password_label), password = true, contentType = ContentType.Password,
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
                    Text(stringResource(R.string.setup_configure_manually), style = Dot.type.label, color = c.textSecondary)
                }
            }
            Spacer(Modifier.height(DotSpacing.xxl))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp).padding(top = 2.dp), tint = c.textTertiary)
                Spacer(Modifier.width(DotSpacing.s))
                Text(
                    stringResource(R.string.setup_keystore_notice),
                    style = Dot.type.caption, color = c.textTertiary,
                )
            }
            Spacer(Modifier.height(DotSpacing.xl))

        }

        DotButton(
            text = stringResource(if (state.busy && state.google == SetupState.GooglePhase.Idle) R.string.setup_connecting else R.string.setup_continue),
            onClick = vm::connect,
            loading = state.busy && state.google == SetupState.GooglePhase.Idle,
            // With Google available the password route is the quieter, secondary choice.
            style = if (state.googleAvailable && state.step == SetupState.Step.Credentials) ButtonStyle.Secondary else ButtonStyle.Primary,
            enabled = state.emailValid && state.password.isNotEmpty() && !state.oauthOnly,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(DotSpacing.gutter),
        )

        }
    }
}

/**
 * "Continue with Google" (solid accent: the main way in for a Gmail address) with its loading / cancel states, or, when this
 * build has no Google client ID, an honest sentence instead of a dead button. Password sign-in stays below as the quieter
 * alternative, and manual server setup remains its own row.
 */
@Composable
private fun GoogleSignIn(state: SetupState, vm: AddAccountViewModel) {
    val c = Dot.colors
    Column(Modifier.padding(top = DotSpacing.l)) {
        if (!state.googleAvailable) {
            Text(stringResource(R.string.setup_google_unavailable), style = Dot.type.caption, color = c.textTertiary)
            return@Column
        }
        val active = state.google != SetupState.GooglePhase.Idle
        DotButton(
            text = stringResource(R.string.setup_google_continue),
            onClick = vm::signInWithGoogle,
            enabled = state.emailValid && !state.busy || active,
            loading = state.google == SetupState.GooglePhase.Opening || state.google == SetupState.GooglePhase.Completing,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.google == SetupState.GooglePhase.Waiting) {
            DotButton(stringResource(R.string.setup_google_cancel), vm::cancelGoogle, style = ButtonStyle.Ghost, compact = true, modifier = Modifier.padding(top = DotSpacing.xs))
        } else if (!state.emailValid && !active) {
            Text(stringResource(R.string.setup_google_hint), style = Dot.type.caption, color = c.textTertiary, modifier = Modifier.padding(top = DotSpacing.xs))
        }
        Row(Modifier.fillMaxWidth().padding(top = DotSpacing.l), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f).height(1.dp).background(c.border))
            Text(stringResource(R.string.setup_or_password), style = Dot.type.caption, color = c.textTertiary, modifier = Modifier.padding(horizontal = DotSpacing.m))
            androidx.compose.foundation.layout.Box(Modifier.weight(1f).height(1.dp).background(c.border))
        }
    }
}

@Composable
private fun ManualSettings(state: SetupState, vm: AddAccountViewModel) {
    Column(Modifier.padding(top = DotSpacing.xxl)) {
        Text(stringResource(R.string.setup_incoming_imap), style = Dot.type.headline, color = Dot.colors.textPrimary)
        Spacer(Modifier.height(DotSpacing.m))
        ServerFields(
            host = state.imapHost, port = state.imapPort, security = state.imapSecurity,
            onHost = { v -> vm.edit { it.copy(imapHost = v) } },
            onPort = { v -> vm.edit { it.copy(imapPort = v.filter(Char::isDigit).take(5)) } },
            onSecurity = { v -> vm.edit { it.copy(imapSecurity = v, imapPort = defaultPort(v, imap = true)) } },
        )
        Spacer(Modifier.height(DotSpacing.xl))
        Text(stringResource(R.string.setup_outgoing_smtp), style = Dot.type.headline, color = Dot.colors.textPrimary)
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
            label = stringResource(R.string.setup_username_label), placeholder = stringResource(R.string.setup_username_placeholder),
            keyboardType = KeyboardType.Email,
        )
        Spacer(Modifier.height(DotSpacing.l))
        DotTextField(
            value = state.name, onValueChange = { v -> vm.edit { it.copy(name = v) } },
            label = stringResource(R.string.setup_name_label), placeholder = stringResource(R.string.setup_name_placeholder), contentType = ContentType.PersonFullName,
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
        DotTextField(host, onHost, Modifier.weight(1f), label = stringResource(R.string.setup_server_label), keyboardType = KeyboardType.Uri)
        DotTextField(port, onPort, Modifier.widthIn(min = 100.dp).weight(0.4f), label = stringResource(R.string.setup_port_label), keyboardType = KeyboardType.Number)
    }
    Spacer(Modifier.height(DotSpacing.m))
    Segmented(
        options = listOf(
            Security.SSL_TLS to stringResource(R.string.setup_security_ssl_tls),
            Security.STARTTLS to stringResource(R.string.setup_security_starttls),
            Security.NONE to stringResource(R.string.setup_security_none),
        ),
        selected = security,
        onSelect = onSecurity,
    )
    if (security == Security.NONE) {
        Text(
            stringResource(R.string.setup_cleartext_warning),
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
