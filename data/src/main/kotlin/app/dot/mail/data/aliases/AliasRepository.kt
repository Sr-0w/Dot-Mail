package app.dot.mail.data.aliases

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AliasService(val id: String) { SIMPLELOGIN("simplelogin"), ADDY("addy") }

/** Keeps Keystore and provider work off the UI thread. */
class AliasRepository(context: Context) {
    private val settings = AliasSettings.create(context.applicationContext)
    private val providers: Map<AliasService, AliasProvider> = mapOf(
        AliasService.SIMPLELOGIN to SimpleLoginClient(),
        AliasService.ADDY to AddyClient(),
    )

    suspend fun savedKey(service: AliasService): String? = withContext(Dispatchers.IO) { settings.key(service.id) }
    suspend fun saveKey(service: AliasService, key: String) = withContext(Dispatchers.IO) { settings.save(service.id, key) }
    suspend fun removeKey(service: AliasService) = withContext(Dispatchers.IO) { settings.remove(service.id) }
    suspend fun list(service: AliasService, key: String): List<EmailAlias> = withContext(Dispatchers.IO) { providers.getValue(service).list(key) }
    suspend fun create(service: AliasService, key: String): EmailAlias = withContext(Dispatchers.IO) { providers.getValue(service).create(key) }
    suspend fun setEnabled(service: AliasService, key: String, alias: EmailAlias, enabled: Boolean): EmailAlias =
        withContext(Dispatchers.IO) { providers.getValue(service).setEnabled(key, alias, enabled) }
}
