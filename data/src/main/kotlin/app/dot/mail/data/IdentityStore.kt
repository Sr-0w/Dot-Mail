package app.dot.mail.data

import android.content.Context
import androidx.core.content.edit
import app.dot.mail.data.model.Identity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sender identities (aliases) per account, kept in their own SharedPreferences file so the Room schema is untouched.
 *
 * Every account has a permanent *primary* identity with id == accountId (its login address). It is never stored as
 * a row of its own unless the user customises it (name, reply-to, signature); the address stays the account's.
 * Added identities get ids from [FIRST_ID] up, so they can never collide with account ids. Drafts written before
 * identities existed carry identityId == accountId, which is the primary: nothing to migrate.
 */
class IdentityStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("dotmail_identities", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)

    /** Changes whenever any identity is saved, deleted or made default. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** All identities of an account (primary first, then added ones in creation order), with [Identity.isDefault] set. */
    @Synchronized
    fun resolve(accountId: Long, accountName: String, accountEmail: String): List<Identity> {
        val stored = read(accountId)
        val defaultId = prefs.getLong("$accountId.default", accountId)
        val primaryOverride = stored.firstOrNull { it.id == accountId }
        val primary = (primaryOverride ?: Identity(accountId, accountId, accountName, accountEmail))
            .copy(email = accountEmail, name = primaryOverride?.name?.ifBlank { null } ?: accountName)
        val all = listOf(primary) + stored.filter { it.id != accountId }
        val effectiveDefault = all.firstOrNull { it.id == defaultId }?.id ?: accountId
        return all.map { it.copy(isDefault = it.id == effectiveDefault) }
    }

    /** Insert (id == 0 or unknown) or update. Validates the address; returns the identity id. */
    @Synchronized
    fun save(identity: Identity, accountEmail: String): Long {
        val email = identity.email.trim()
        require(isValidAddress(email)) { "Invalid address" }
        identity.replyTo?.trim()?.takeIf { it.isNotEmpty() }?.let { require(isValidAddress(it)) { "Invalid reply-to address" } }
        val list = read(identity.accountId).toMutableList()
        val isPrimary = identity.id == identity.accountId
        val clean = identity.copy(
            email = if (isPrimary) accountEmail else email,
            name = identity.name.trim(),
            replyTo = identity.replyTo?.trim()?.ifEmpty { null },
            signature = identity.signature?.ifEmpty { null },
            signatureHtml = identity.signatureHtml?.ifEmpty { null },
            signatureLogo = identity.signatureLogo?.ifEmpty { null },
        )
        val id = when {
            isPrimary -> identity.id
            identity.id >= FIRST_ID && list.any { it.id == identity.id } -> identity.id
            else -> prefs.getLong("next_id", FIRST_ID).also { prefs.edit { putLong("next_id", it + 1) } }
        }
        val at = list.indexOfFirst { it.id == id }
        if (at >= 0) list[at] = clean.copy(id = id) else list.add(clean.copy(id = id)) // updates keep their position
        write(identity.accountId, list)
        return id
    }

    @Synchronized
    fun delete(accountId: Long, identityId: Long) {
        if (identityId == accountId) return // the account address is permanent
        write(accountId, read(accountId).filter { it.id != identityId })
        if (prefs.getLong("$accountId.default", accountId) == identityId) prefs.edit { remove("$accountId.default") }
        _version.value++
    }

    @Synchronized
    fun setDefault(accountId: Long, identityId: Long) {
        prefs.edit { if (identityId == accountId) remove("$accountId.default") else putLong("$accountId.default", identityId) }
        _version.value++
    }

    @Synchronized
    fun forget(accountId: Long) {
        prefs.edit { remove("$accountId.list"); remove("$accountId.default") }
        _version.value++
    }

    private fun read(accountId: Long): List<Identity> {
        val raw = prefs.getString("$accountId.list", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Identity(
                    id = o.getLong("id"), accountId = accountId, name = o.optString("name"), email = o.optString("email"),
                    signature = o.optString("sig").ifEmpty { null }, replyTo = o.optString("replyTo").ifEmpty { null },
                    signatureHtml = o.optString("sigHtml").ifEmpty { null },
                    signatureLogo = o.optString("sigLogo").ifEmpty { null },
                    signatureOnNew = o.optBoolean("sigNew", true), signatureOnReply = o.optBoolean("sigReply", true),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(accountId: Long, list: List<Identity>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("email", it.email)
                .put("sig", it.signature ?: "").put("replyTo", it.replyTo ?: "").put("sigHtml", it.signatureHtml ?: "")
                .put("sigLogo", it.signatureLogo ?: "").put("sigNew", it.signatureOnNew).put("sigReply", it.signatureOnReply))
        }
        prefs.edit { putString("$accountId.list", arr.toString()) }
        _version.value++
    }

    companion object {
        const val FIRST_ID = 1_000_000L
        private val EMAIL = Regex("^[^\\s@<>\",;]+@[^\\s@<>\",;]+\\.[^\\s@<>\",;]{2,}$")
        fun isValidAddress(s: String) = EMAIL.matches(s.trim())

        @Volatile private var instance: IdentityStore? = null
        fun get(context: Context): IdentityStore =
            instance ?: synchronized(this) { instance ?: IdentityStore(context.applicationContext).also { instance = it } }

        /** For tests: a store on a private context, bypassing the singleton. */
        internal fun forTest(context: Context) = IdentityStore(context)

        /** For tests: drop the process-wide instance (Robolectric recreates the Application per test). */
        internal fun resetForTest() { instance = null }
    }
}

/** Picks which identity a reply is sent from. Pure, so it is unit-tested without Android. */
object ReplyIdentity {
    /**
     * The identity whose address appears among the original message's recipients: [to] first, then [cc],
     * then [deliveredTo] (envelope recipient, useful for Bcc/list mail). Case-insensitive. Falls back to the
     * default identity, else the first one, else null.
     */
    fun pick(identities: List<Identity>, to: List<String>, cc: List<String>, deliveredTo: List<String> = emptyList()): Identity? {
        val byAddress = identities.associateBy { it.email.trim().lowercase() }
        for (candidate in to + cc + deliveredTo) byAddress[candidate.trim().lowercase()]?.let { return it }
        return identities.firstOrNull { it.isDefault } ?: identities.firstOrNull()
    }

    /** All addresses that are "me": used to drop myself from Reply-all recipients. */
    fun ownAddresses(identities: List<Identity>): Set<String> = identities.map { it.email.trim().lowercase() }.toSet()
}
