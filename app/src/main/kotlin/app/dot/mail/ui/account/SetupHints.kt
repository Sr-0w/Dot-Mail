package app.dot.mail.ui.account

import androidx.annotation.StringRes
import app.dot.mail.R

/**
 * The discovery engine's short provider hints are English-only technical text (the :mail module has no resources). The
 * account setup shows them in the user's language instead, by recognising each known hint. Nothing here promises
 * Microsoft / Outlook sign-in: those accounts are reported as unsupported. Unknown hints are shown as they are.
 */
internal object SetupHints {
    @StringRes fun resFor(hint: String?): Int? = when {
        hint.isNullOrBlank() -> null
        hint.startsWith("Gmail:") -> R.string.setup_hint_gmail
        hint.startsWith("Yahoo:") -> R.string.setup_hint_yahoo
        hint.startsWith("iCloud:") -> R.string.setup_hint_icloud
        hint.startsWith("Proton Mail") -> R.string.setup_hint_proton
        hint.startsWith("Outlook/") || hint.startsWith("Microsoft 365") || hint.startsWith("OAuth sign-in is not available") -> R.string.setup_hint_microsoft
        hint.startsWith("This provider advertised unencrypted") -> R.string.setup_hint_insecure
        else -> null
    }

    fun localize(hint: String?, get: (Int) -> String): String? = resFor(hint)?.let(get) ?: hint
}
