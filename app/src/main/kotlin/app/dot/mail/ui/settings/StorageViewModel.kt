package app.dot.mail.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dot.mail.data.MailGraph
import app.dot.mail.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class StorageAccountState(
    val accountId: Long,
    val policy: StoragePolicy,
    val progress: OfflineProgress,
)

/**
 * Repository IO stays out of composition; actions survive navigation/recreation and are serialized.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class StorageViewModel(private val graph: MailGraph) : ViewModel() {
    private val selected = MutableStateFlow<Long?>(null)
    val accounts =
        graph.accounts.accounts.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList(),
        )
    val account =
        selected
            .flatMapLatest { id ->
                if (id == null) flowOf(null)
                else
                    combine(graph.mail.storagePolicy(id), graph.mail.offlineProgress(id)) {
                        policy,
                        progress ->
                        StorageAccountState(id, policy, progress)
                    }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val globalLimit =
        graph.mail.globalStorageLimit.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            0L,
        )
    private val _usage = MutableStateFlow<StorageUsage?>(null)
    val usage = _usage.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()
    private val _freed = MutableStateFlow<Long?>(null)
    val freed = _freed.asStateFlow()
    private val actions = Mutex()

    fun select(id: Long) {
        selected.value = id
    }

    init {
        refreshUsage()
    }

    fun refreshUsage() = action { _usage.value = graph.mail.storageUsage() }

    fun save(id: Long, policy: StoragePolicy) = action {
        graph.mail.setStoragePolicy(id, policy)
        _usage.value = graph.mail.storageUsage()
    }

    fun globalLimit(bytes: Long) = action {
        graph.mail.setGlobalStorageLimit(bytes)
        _usage.value = graph.mail.storageUsage()
    }

    fun pause(id: Long) = action { graph.mail.pauseOfflineDownload(id) }

    fun resume(id: Long) = action { graph.mail.resumeOfflineDownload(id) }

    fun clear(id: Long) = action {
        _freed.value = graph.mail.clearCachedMail(id).freedBytes
        _usage.value = graph.mail.storageUsage()
    }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            actions.withLock {
                _busy.value = true
                _failed.value = false
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _failed.value = true
                } finally {
                    _busy.value = false
                }
            }
        }
    }
}

internal const val MIB = 1024L * 1024

/**
 * Reject overflow and invalid values before dispatch; retained policy fields always remain valid.
 */
internal fun storagePolicyInput(
    base: StoragePolicy,
    count: String,
    days: String,
    limitMiB: String,
): StoragePolicy? {
    val latest = count.toIntOrNull()?.takeIf { it in 1..1_000_000 } ?: return null
    val age = days.toIntOrNull()?.takeIf { it in 1..36500 } ?: return null
    val limit = limitMiB.toLongOrNull()?.takeIf { it in 0..Long.MAX_VALUE / MIB } ?: return null
    return base.copy(latestMessages = latest, days = age, maxBytes = limit * MIB)
}
