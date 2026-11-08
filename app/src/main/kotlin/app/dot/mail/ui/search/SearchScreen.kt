package app.dot.mail.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import app.dot.mail.Density
import app.dot.mail.LocalSettings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.dot.design.Dot
import app.dot.design.DotSpacing
import app.dot.design.components.BackButton
import app.dot.design.components.EmptyState
import app.dot.design.components.SearchField
import app.dot.mail.LocalMailGraph
import app.dot.mail.data.MailGraph
import app.dot.mail.data.model.ThreadSummary
import app.dot.mail.ui.inbox.ThreadRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(graph: MailGraph) : ViewModel() {
    val query = MutableStateFlow("")
    val results: Flow<PagingData<ThreadSummary>> = query
        .map { it.trim() }
        .debounce(250)
        .distinctUntilChanged()
        .flatMapLatest { q -> if (q.length < 2) flowOf(PagingData.empty()) else graph.mail.search(q) }
        .cachedIn(viewModelScope)
    val current: StateFlow<String> get() = query
}

@Composable
fun SearchScreen(onBack: () -> Unit, onOpenThread: (Long) -> Unit) {
    val graph = LocalMailGraph.current
    val vm: SearchViewModel = viewModel { SearchViewModel(graph) }
    val query by vm.current.collectAsStateWithLifecycle()
    val results = vm.results.collectAsLazyPagingItems()
    val compact = LocalSettings.current.ui.collectAsStateWithLifecycle().value.density == Density.COMPACT
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val c = Dot.colors
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().background(c.background).imePadding()) {
        Row(
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 4.dp, end = DotSpacing.l, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack)
            SearchField(
                value = query,
                onValueChange = { vm.query.value = it },
                modifier = Modifier.weight(1f),
                placeholder = "Search all accounts",
                focusRequester = focus,
                onSearch = { keyboard?.hide() },
            )
        }
        val q = query.trim()
        when {
            q.length < 2 -> SearchHints()
            results.itemCount == 0 && results.loadState.refresh is LoadState.Loading ->
                Box(Modifier.fillMaxSize().padding(top = 64.dp), contentAlignment = Alignment.TopCenter) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = c.textTertiary, strokeWidth = 1.5.dp)
                }
            results.itemCount == 0 && results.loadState.refresh is LoadState.Error ->
                EmptyState(Icons.Outlined.WifiOff, "Search failed", (results.loadState.refresh as LoadState.Error).error.message ?: "Check your connection and try again.")
            results.itemCount == 0 && results.loadState.refresh is LoadState.NotLoading ->
                EmptyState(Icons.Outlined.SearchOff, "No results", "Nothing matches “$q” in downloaded mail or on your servers.")
            else -> LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                items(results.itemCount, key = results.itemKey { it.threadId }) { i ->
                    val t = results[i] ?: return@items
                    ThreadRow(t, selected = false, selectionMode = false, compact = compact, onClick = { onOpenThread(t.threadId) }, onLongClick = {})
                }
            }
        }
    }
}

@Composable
private fun SearchHints() {
    val c = Dot.colors
    Column(Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xl)) {
        Text("Search by sender, subject or words in the message.", style = Dot.type.callout, color = c.textSecondary)
        Spacer(Modifier.height(DotSpacing.l))
        listOf(
            "from:anna" to "Mail from a sender",
            "to:team@example.com" to "Mail sent to an address",
            "subject:invoice" to "Words in the subject",
            "has:attachment" to "Only with attachments",
            "is:unread  is:starred" to "By state",
        ).forEach { (op, desc) ->
            Row(Modifier.padding(vertical = 6.dp)) {
                Text(op, style = Dot.type.label, color = c.textPrimary, modifier = Modifier.weight(1f))
                Text(desc, style = Dot.type.caption, color = c.textTertiary)
            }
        }
    }
}
