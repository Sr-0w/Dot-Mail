package app.dot.mail.data.aliases

import android.content.Context
import app.dot.mail.data.CredentialVault

/** Stores provider credentials encrypted with the same Keystore vault as account secrets. */
class AliasSettings internal constructor(context: Context, private val vault: CredentialVault) {
    private val prefs = context.getSharedPreferences("alias-provider-secrets", Context.MODE_PRIVATE)

    fun save(provider: String, apiKey: String) {
        require(provider in PROVIDERS && apiKey.isNotBlank())
        prefs.edit().putString(provider, vault.encrypt("aliases:$provider", apiKey)).apply()
    }

    fun remove(provider: String) { require(provider in PROVIDERS); prefs.edit().remove(provider).apply() }

    internal fun key(provider: String): String? = prefs.getString(provider, null)?.let { vault.decrypt("aliases:$provider", it) }

    companion object {
        private val PROVIDERS = setOf("simplelogin", "addy")
        fun create(context: Context): AliasSettings = AliasSettings(context.applicationContext, app.dot.mail.data.KeystoreCredentialVault())
    }
}
