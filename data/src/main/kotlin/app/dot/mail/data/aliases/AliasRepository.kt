package app.dot.mail.data.aliases

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AliasService(val id: String) { SIMPLELOGIN("simplelogin"), ADDY("addy") }

interface AliasRepository {
    suspend fun savedKey(service: AliasService): String?
    suspend fun saveKey(service: AliasService, key: String)
    suspend fun removeKey(service: AliasService)
    suspend fun list(service: AliasService, key: String): List<EmailAlias>
    suspend fun create(service: AliasService, key: String): EmailAlias
    suspend fun setEnabled(service: AliasService, key: String, alias: EmailAlias, enabled: Boolean): EmailAlias
}

/** Keeps Keystore and provider work off the UI thread. */
class AndroidAliasRepository(context: Context) : AliasRepository {
    private val settings = AliasSettings.create(context.applicationContext)
    private val providers: Map<AliasService, AliasProvider> = mapOf(
        AliasService.SIMPLELOGIN to SimpleLoginClient(),
        AliasService.ADDY to AddyClient(),
    )

    override suspend fun savedKey(service: AliasService): String? = withContext(Dispatchers.IO) { settings.key(service.id) }
    override suspend fun saveKey(service: AliasService, key: String) = withContext(Dispatchers.IO) { settings.save(service.id, key) }
    override suspend fun removeKey(service: AliasService) = withContext(Dispatchers.IO) { settings.remove(service.id) }
    override suspend fun list(service: AliasService, key: String): List<EmailAlias> = withContext(Dispatchers.IO) { providers.getValue(service).list(key) }
    override suspend fun create(service: AliasService, key: String): EmailAlias = withContext(Dispatchers.IO) { providers.getValue(service).create(key) }
    override suspend fun setEnabled(service: AliasService, key: String, alias: EmailAlias, enabled: Boolean): EmailAlias =
        withContext(Dispatchers.IO) { providers.getValue(service).setEnabled(key, alias, enabled) }
}
