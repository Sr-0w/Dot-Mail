package app.dot.mail.ui.settings

import android.content.Context
import androidx.core.content.edit
import app.dot.mail.data.model.EmailAddress
import app.dot.mail.data.model.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why a message's remote images are (or aren't) loaded. Decides what "Stop" can undo. */
enum class LoadReason { NONE, GLOBAL, MESSAGE, SENDER, DOMAIN }

/** An allowlist entry. [accountId] 0 = every account. [value] is a lower-case address or a bare domain. */
data class RemoteImageRule(val isDomain: Boolean, val value: String, val accountId: Long = 0)

/**
 * The pure decision logic, kept free of Android so it is unit-tested on the JVM.
 *
 * Matching is deliberately conservative: addresses must be equal (case-insensitively); a domain rule matches the exact
 * domain or a real subdomain (".example.org"), never a mere suffix of the string ("evilexample.org").
 */
object RemoteImageMatching {
    /** `<ID@host>` → `id@host`. Null/blank when the header is missing. */
    fun normaliseMessageId(header: String?): String? = header?.trim()?.trim('<', '>')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /** Stable key of a message: account + Message-ID header, or account + local id when there is no header. */
    fun messageKey(accountId: Long, messageIdHeader: String?, localId: Long): String =
        "$accountId|" + (normaliseMessageId(messageIdHeader) ?: "local:$localId")

    fun messageKey(m: Message): String = messageKey(m.accountId, m.messageIdHeader, m.id)

    fun normaliseAddress(s: String): String = s.trim().lowercase()

    /** Accepts `example.org`, `@example.org`, `*.example.org`; returns null for anything that isn't a plausible registrable-looking domain. */
    fun normaliseDomain(s: String): String? {
        val d = s.trim().lowercase().removePrefix("*.").removePrefix("@").trim('.')
        if (d.isEmpty() || '.' !in d || d.any { !(it.isLetterOrDigit() || it == '.' || it == '-') } || d.contains("..")) return null
        return d
    }

    fun domainOf(email: String): String = email.trim().lowercase().substringAfterLast('@', "")

    fun domainMatches(senderDomain: String, ruleDomain: String): Boolean =
        senderDomain.isNotEmpty() && (senderDomain == ruleDomain || senderDomain.endsWith(".$ruleDomain"))

    fun reason(
        accountId: Long,
        messageKey: String,
        sender: EmailAddress?,
        globalLoad: Boolean,
        approvals: Set<String>,
        rules: List<RemoteImageRule>,
    ): LoadReason {
        if (globalLoad) return LoadReason.GLOBAL
        if (messageKey in approvals) return LoadReason.MESSAGE
        val address = sender?.email?.let(::normaliseAddress).orEmpty()
        if (address.isEmpty()) return LoadReason.NONE
        val domain = domainOf(address)
        val scoped = rules.filter { it.accountId == 0L || it.accountId == accountId }
        if (scoped.any { !it.isDomain && it.value == address }) return LoadReason.SENDER
        if (scoped.any { it.isDomain && domainMatches(domain, it.value) }) return LoadReason.DOMAIN
        return LoadReason.NONE
    }

    fun reason(message: Message, globalLoad: Boolean, approvals: Set<String>, rules: List<RemoteImageRule>): LoadReason =
        reason(message.accountId, messageKey(message), message.from, globalLoad, approvals, rules)

    fun shouldLoad(message: Message, globalLoad: Boolean, approvals: Set<String>, rules: List<RemoteImageRule>): Boolean =
        reason(message, globalLoad, approvals, rules) != LoadReason.NONE

    // ---- storage format (one rule per line: kind TAB value TAB account) --------------------------------------

    fun encodeRules(rules: List<RemoteImageRule>): String =
        rules.joinToString("\n") { "${if (it.isDomain) "d" else "a"}\t${it.value}\t${it.accountId}" }

    fun decodeRules(raw: String?): List<RemoteImageRule> = raw.orEmpty().lineSequence().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size != 3) return@mapNotNull null
        val account = p[2].toLongOrNull() ?: return@mapNotNull null
        when (p[0]) {
            "d" -> normaliseDomain(p[1])?.let { RemoteImageRule(true, it, account) }
            "a" -> p[1].trim().lowercase().takeIf { '@' in it && ' ' !in it }?.let { RemoteImageRule(false, it, account) }
            else -> null
        }
    }.distinct().toList()
}

/**
 * Persistent remote-image permissions: per-message approvals and sender / sender-domain allowlists. Lives in its own
 * SharedPreferences file (the app disables backup, so nothing leaves the device). Approvals are capped, oldest dropped.
 */
class RemoteImagePolicy private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("remote_images", Context.MODE_PRIVATE)
    private val _rules = MutableStateFlow(RemoteImageMatching.decodeRules(prefs.getString("rules", null)))
    private val _approvals = MutableStateFlow(readApprovals())

    val rules: StateFlow<List<RemoteImageRule>> = _rules.asStateFlow()
    val approvals: StateFlow<Set<String>> = _approvals.asStateFlow()

    private fun readApprovals(): Set<String> =
        prefs.getString("approved", null).orEmpty().lineSequence().filter { it.isNotBlank() }.toCollection(LinkedHashSet())

    @Synchronized fun approveMessage(m: Message) {
        val next = LinkedHashSet(_approvals.value).apply { remove(RemoteImageMatching.messageKey(m)); add(RemoteImageMatching.messageKey(m)) }
        while (next.size > MAX_APPROVALS) next.remove(next.first())
        _approvals.value = next
        prefs.edit { putString("approved", next.joinToString("\n")) }
    }

    @Synchronized fun revokeMessage(m: Message) {
        val next = LinkedHashSet(_approvals.value).apply { remove(RemoteImageMatching.messageKey(m)) }
        _approvals.value = next
        prefs.edit { putString("approved", next.joinToString("\n")) }
    }

    @Synchronized fun forgetAllMessages() {
        _approvals.value = emptySet()
        prefs.edit { remove("approved") }
    }

    /** Returns false for an invalid address/domain. */
    @Synchronized fun addRule(isDomain: Boolean, raw: String, accountId: Long): Boolean {
        val value = if (isDomain) RemoteImageMatching.normaliseDomain(raw) else RemoteImageMatching.normaliseAddress(raw).takeIf { '@' in it && ' ' !in it && it.length > 3 }
        if (value == null) return false
        setRules((_rules.value + RemoteImageRule(isDomain, value, accountId)).distinct())
        return true
    }

    @Synchronized fun removeRule(rule: RemoteImageRule) = setRules(_rules.value - rule)

    @Synchronized fun resetRules() = setRules(emptyList())

    /** Undo whatever allowed [m]: its approval, or the matching sender/domain rules (for that account). */
    @Synchronized fun stop(m: Message, reason: LoadReason) {
        when (reason) {
            LoadReason.MESSAGE -> revokeMessage(m)
            LoadReason.SENDER -> {
                val address = RemoteImageMatching.normaliseAddress(m.from.email)
                setRules(_rules.value.filterNot { !it.isDomain && it.value == address && (it.accountId == 0L || it.accountId == m.accountId) })
            }
            LoadReason.DOMAIN -> {
                val domain = RemoteImageMatching.domainOf(m.from.email)
                setRules(_rules.value.filterNot { it.isDomain && RemoteImageMatching.domainMatches(domain, it.value) && (it.accountId == 0L || it.accountId == m.accountId) })
            }
            else -> Unit
        }
    }

    private fun setRules(next: List<RemoteImageRule>) {
        _rules.value = next
        prefs.edit { putString("rules", RemoteImageMatching.encodeRules(next)) }
    }

    companion object {
        const val MAX_APPROVALS = 5000
        @Volatile private var instance: RemoteImagePolicy? = null
        fun get(context: Context): RemoteImagePolicy =
            instance ?: synchronized(this) { instance ?: RemoteImagePolicy(context.applicationContext).also { instance = it } }
    }
}
