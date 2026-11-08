package app.dot.mail.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dot.mail.data.aliases.AliasRepository
import app.dot.mail.data.aliases.AliasService
import app.dot.mail.data.aliases.EmailAlias
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AliasProviderState(
    val apiKey: String = "",
    val aliases: List<EmailAlias> = emptyList(),
    val busy: Boolean = false,
    val loadingKey: Boolean = true,
    val error: Boolean = false,
)

internal data class AliasesUiState(
    val service: AliasService = AliasService.SIMPLELOGIN,
    val providers: Map<AliasService, AliasProviderState> = AliasService.entries.associateWith { AliasProviderState() },
)

internal class AliasesViewModel(context: Context) : ViewModel() {
    private val repository = AliasRepository(context.applicationContext)
    private val mutableState = MutableStateFlow(AliasesUiState())
    val state: StateFlow<AliasesUiState> = mutableState.asStateFlow()

    init {
        AliasService.entries.forEach { service ->
            viewModelScope.launch {
                try {
                    val key = repository.savedKey(service).orEmpty()
                    update(service) { it.copy(apiKey = key, loadingKey = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    update(service) { it.copy(loadingKey = false, error = true) }
                }
            }
        }
    }

    fun selectService(service: AliasService) { mutableState.update { it.copy(service = service) } }

    fun editKey(value: String) {
        val service = state.value.service
        update(service) { it.copy(apiKey = value, error = false) }
    }

    fun testConnection() {
        val service = state.value.service
        val key = state.value.providers.getValue(service).apiKey
        perform(service) {
            repository.saveKey(service, key)
            val aliases = repository.list(service, key)
            update(service) { it.copy(aliases = aliases) }
        }
    }

    fun createRandom() {
        val service = state.value.service
        val key = state.value.providers.getValue(service).apiKey
        perform(service) {
            repository.saveKey(service, key)
            val alias = repository.create(service, key)
            update(service) { it.copy(aliases = (listOf(alias) + it.aliases).distinctBy { item -> item.id }) }
        }
    }

    fun setEnabled(alias: EmailAlias, enabled: Boolean) {
        val service = state.value.service
        val key = state.value.providers.getValue(service).apiKey
        perform(service) {
            val updated = repository.setEnabled(service, key, alias, enabled)
            update(service) { it.copy(aliases = it.aliases.map { item -> if (item.id == alias.id) updated else item }) }
        }
    }

    fun removeKey() {
        val service = state.value.service
        perform(service) {
            repository.removeKey(service)
            update(service) { it.copy(apiKey = "", aliases = emptyList(), error = false) }
        }
    }

    private fun perform(service: AliasService, action: suspend () -> Unit) {
        if (state.value.providers.getValue(service).busy) return
        viewModelScope.launch {
            update(service) { it.copy(busy = true, error = false) }
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                update(service) { it.copy(error = true) }
            } finally {
                update(service) { it.copy(busy = false) }
            }
        }
    }

    private fun update(service: AliasService, transform: (AliasProviderState) -> AliasProviderState) {
        mutableState.update { current ->
            current.copy(providers = current.providers + (service to transform(current.providers.getValue(service))))
        }
    }
}
