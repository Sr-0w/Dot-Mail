package app.dot.mail

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dot.design.DotTheme
import app.dot.design.ThemeMode
import app.dot.mail.ui.DotMailRoot
import app.dot.mail.ui.LockScreen
import kotlinx.coroutines.flow.MutableStateFlow

/** Something an external intent asked us to open. Consumed once by the nav host. */
sealed interface ExternalRequest {
    /** [reply] = the notification's Reply button: open straight into a reply to the last message. */
    data class OpenThread(val threadId: Long, val reply: Boolean = false) : ExternalRequest
    data class Compose(
        val to: List<String> = emptyList(),
        val cc: List<String> = emptyList(),
        val bcc: List<String> = emptyList(),
        val subject: String? = null,
        val body: String? = null,
        val attachments: List<String> = emptyList(),
    ) : ExternalRequest
    data object Search : ExternalRequest
}

// FragmentActivity (a ComponentActivity) because BiometricPrompt requires it.
class MainActivity : FragmentActivity() {
    private val requests = MutableStateFlow<ExternalRequest?>(null)
    private val locked = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val app = dotApp
        locked.value = savedInstanceState?.getBoolean(STATE_LOCKED) ?: app.settings.ui.value.appLock
        if (savedInstanceState == null) requests.value = parse(intent)
        setContent {
            val prefs by app.settings.ui.collectAsStateWithLifecycle()
            val isLocked by locked.collectAsStateWithLifecycle()
            LaunchedEffect(prefs.secureScreen) {
                if (prefs.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            DotTheme(prefs.theme) {
                SyncSystemBars(prefs.theme)
                CompositionLocalProvider(LocalMailGraph provides app.graph, LocalSettings provides app.settings) {
                    if (isLocked) {
                        LockScreen(onUnlock = ::authenticate)
                    } else {
                        DotMailRoot(requests = requests, onRequestConsumed = { requests.value = null })
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LOCKED, locked.value)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parse(intent)?.let { requests.value = it }
    }

    override fun onResume() {
        super.onResume()
        app.dot.mail.ui.common.Format.use24h = android.text.format.DateFormat.is24HourFormat(this)
        // Prompt only while visible (never from the background).
        if (locked.value) authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) leftAt = android.os.SystemClock.elapsedRealtime()
        // "Lock after 0 min" locks as soon as the app leaves the screen.
        val prefs = dotApp.settings.ui.value
        if (prefs.appLock && prefs.lockAfterMinutes == 0 && !isChangingConfigurations) locked.value = true
    }

    override fun onStart() {
        super.onStart()
        val prefs = dotApp.settings.ui.value
        val away = leftAt?.let { android.os.SystemClock.elapsedRealtime() - it }
        if (prefs.appLock && away != null && away >= prefs.lockAfterMinutes * 60_000L) locked.value = true
        leftAt = null
    }

    private var leftAt: Long? = null

    private fun authenticate() {
        val authenticators = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                locked.value = false
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Dot Mail")
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    private fun parse(intent: Intent?): ExternalRequest? {
        intent ?: return null
        intent.getLongExtra(EXTRA_THREAD_ID, -1L).takeIf { it > 0 }?.let { return ExternalRequest.OpenThread(it, reply = intent.getBooleanExtra(EXTRA_REPLY, false)) }
        return when (intent.action) {
            ACTION_COMPOSE -> ExternalRequest.Compose()
            ACTION_SEARCH -> ExternalRequest.Search
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "mailto" }?.let { mailto(it, intent) }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val streams = buildList {
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { add(it) }
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { addAll(it) }
                }
                val ownAuthority = "$packageName.attachments"
                val safeStreams = streams.filter { it.scheme == "content" && it.authority != null && it.authority != ownAuthority }
                ExternalRequest.Compose(
                    to = intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList().orEmpty(),
                    cc = intent.getStringArrayExtra(Intent.EXTRA_CC)?.toList().orEmpty(),
                    bcc = intent.getStringArrayExtra(Intent.EXTRA_BCC)?.toList().orEmpty(),
                    subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
                    body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
                    attachments = safeStreams.map { it.toString() },
                )
            }
            else -> null
        }
    }

    private fun mailto(uri: Uri, intent: Intent): ExternalRequest.Compose {
        val parsed = MailtoParser.parse(uri.toString())
        return parsed.copy(
            subject = parsed.subject ?: intent.getStringExtra(Intent.EXTRA_SUBJECT),
            body = parsed.body ?: intent.getStringExtra(Intent.EXTRA_TEXT),
        )
    }

    companion object {
        /** Notifications put the thread id here (agreed with :data). */
        const val EXTRA_THREAD_ID = "dotmail.threadId"
        const val EXTRA_REPLY = "dotmail.reply"
        const val ACTION_COMPOSE = "app.dot.mail.action.COMPOSE"
        const val ACTION_SEARCH = "app.dot.mail.action.SEARCH"
        private const val STATE_LOCKED = "dotmail.locked"
    }
}

/** RFC 6068 mailto: parsing. */
object MailtoParser {
    fun parse(mailto: String): ExternalRequest.Compose {
        val raw = if (mailto.startsWith("mailto:", ignoreCase = true)) mailto.substring(7) else mailto
        val addrPart = raw.substringBefore('?')
        val query = raw.substringAfter('?', "")
        fun dec(s: String) = runCatching { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
        fun split(s: String) = dec(s).split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        val to = split(addrPart).toMutableList()
        val cc = mutableListOf<String>()
        val bcc = mutableListOf<String>()
        var subject: String? = null
        var body: String? = null
        query.split('&').filter { it.isNotEmpty() }.forEach { pair ->
            val key = pair.substringBefore('=').lowercase()
            val value = pair.substringAfter('=', "")
            when (key) {
                "to" -> to += split(value)
                "cc" -> cc += split(value)
                "bcc" -> bcc += split(value)
                "subject" -> subject = dec(value)
                "body" -> body = dec(value)
            }
        }
        return ExternalRequest.Compose(to = to, cc = cc, bcc = bcc, subject = subject, body = body)
    }
}

@androidx.compose.runtime.Composable
private fun SyncSystemBars(mode: ThemeMode) {
    // enableEdgeToEdge(auto) follows the system theme; when the user forces a theme, fix icon contrast.
    val activity = androidx.compose.ui.platform.LocalContext.current as? FragmentActivity ?: return
    val dark = app.dot.design.Dot.colors.isDark
    LaunchedEffect(dark, mode) {
        val controller = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
}
