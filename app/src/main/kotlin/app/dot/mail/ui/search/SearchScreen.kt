package app.dot.mail.ui.search

import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.ui.res.stringResource
import app.dot.mail.R
import app.dot.mail.LocalMailGraph
import app.dot.mail.data.MailGraph
import app.dot.mail.data.model.ThreadSummary
import app.dot.mail.ui.inbox.ThreadRow
import androidx.compose.foundation.border
import app.dot.design.components.backdropSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.dot.design.DotShapes
import app.dot.design.components.DotFilterChip
import app.dot.design.components.DotIconButton
import app.dot.design.components.DotMenu
import app.dot.design.components.DotMenuItem
import app.dot.mail.data.model.HighlightRange
import app.dot.mail.data.model.SearchProgress
import app.dot.mail.data.model.SearchResult
import app.dot.mail.data.model.SearchScope
import app.dot.mail.data.model.SearchSort
import app.dot.mail.ui.common.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Where a search was opened from; lets the scope chips offer "this folder" / "all folders". */
data class SearchContext(val folderIds: Set<Long>, val accountId: Long?)

enum class SearchMode { THIS_FOLDER, ALL_FOLDERS, ALL_ACCOUNTS }

/** Empty account/folder sets mean "everything", so a missing context falls back to all accounts. */
internal fun scopeFor(mode: SearchMode, sort: SearchSort, server: Boolean, ctx: SearchContext?): SearchScope {
    val base = SearchScope(includeServer = server, sort = sort)
    return when {
        ctx == null || mode == SearchMode.ALL_ACCOUNTS -> base
        mode == SearchMode.THIS_FOLDER -> base.copy(folderIds = ctx.folderIds)
        else -> base.copy(accountIds = setOfNotNull(ctx.accountId))
    }
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val graph: MailGraph, private val folderId: Long, private val role: String?) : ViewModel() {
    val query = MutableStateFlow("")
    val mode = MutableStateFlow(SearchMode.ALL_ACCOUNTS)
    val sort = MutableStateFlow(SearchSort.RELEVANCE)
    val includeServer = MutableStateFlow(true)

    /** Resolved from the mailbox the search was opened from; null when opened without one. */
    val context: StateFlow<SearchContext?> = flow {
        val accounts = graph.accounts.accounts.first()
        val folders = accounts.flatMap { a -> graph.mail.folders(a.id).first() }
        emit(
            when {
                folderId > 0 -> folders.firstOrNull { it.id == folderId }?.let { SearchContext(setOf(it.id), it.accountId) }
                role != null -> folders.filter { it.role.name == role }.map { it.id }.toSet().takeIf { it.isNotEmpty() }?.let { SearchContext(it, null) }
                else -> null
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val scope: Flow<SearchScope> = combine(mode, sort, includeServer, context) { m, s, server, ctx -> scopeFor(m, s, server, ctx) }

    // Switch on each edit so results for a previous query are not kept visible during a debounce.
    val debouncedQuery: StateFlow<String> = query.map { it.trim() }.debounce(250).distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    fun queryPending(rawQuery: String, settledQuery: String = debouncedQuery.value): Boolean = rawQuery.trim() != settledQuery
    private val request: Flow<Pair<String, SearchScope>> = combine(debouncedQuery, scope) { q, sc -> q to sc }

    val results: Flow<PagingData<SearchResult>> = request
        .flatMapLatest { (q, sc) -> if (q.length < 2) flowOf(PagingData.empty()) else graph.mail.search(q, sc) }
        .cachedIn(viewModelScope)

    val progress: StateFlow<SearchProgress> = request
        .flatMapLatest { (q, sc) -> if (q.length < 2) flowOf(SearchProgress()) else graph.mail.searchProgress(q, sc) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchProgress())
    val current: StateFlow<String> get() = query
}

@Composable
fun SearchScreen(onBack: () -> Unit, onOpenThread: (Long) -> Unit, folderId: Long = 0, role: String? = null) {
    val graph = LocalMailGraph.current
    val vm: SearchViewModel = viewModel { SearchViewModel(graph, folderId, role) }
    val query by vm.current.collectAsStateWithLifecycle()
    val results = vm.results.collectAsLazyPagingItems()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val debouncedQuery by vm.debouncedQuery.collectAsStateWithLifecycle()
    val queryPending = vm.queryPending(query, debouncedQuery)
    val mode by vm.mode.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val server by vm.includeServer.collectAsStateWithLifecycle()
    val ctx by vm.context.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val c = Dot.colors
    LaunchedEffect(Unit) { focus.requestFocus() }

    val listState=androidx.compose.foundation.lazy.rememberLazyListState()
    val headerControls=app.dot.design.components.rememberHeaderControls()
    val reduced=app.dot.design.rememberReducedMotion()
    LaunchedEffect(query) { headerControls.show() }
    val edgeState=app.dot.design.components.rememberGlassBackdrop()
    val q=query.trim()
    app.dot.design.components.FrostedHeaderLayout(backdrop=edgeState,reserveExpandedHeight=true,modifier=Modifier.fillMaxSize().background(c.background).imePadding(),header={ Column {

        Row(
            Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 4.dp, end = DotSpacing.l, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack)
            SearchField(
                value = query,
                onValueChange = { vm.query.value = it },
                modifier = Modifier.weight(1f),
                placeholder = stringResource(R.string.search_placeholder),
                focusRequester = focus,
                onSearch = { keyboard?.hide() },
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible=headerControls.expanded || !listState.canScrollBackward,
            enter=if(reduced) androidx.compose.animation.EnterTransition.None else androidx.compose.animation.expandVertically()+androidx.compose.animation.fadeIn(),
            exit=if(reduced) androidx.compose.animation.ExitTransition.None else androidx.compose.animation.shrinkVertically()+androidx.compose.animation.fadeOut(),
        ) {
        ScopeBar(
            mode = mode, sort = sort, server = server, hasFolder = ctx != null, hasAccount = ctx?.accountId != null,
            onMode = { vm.mode.value = it }, onSort = { vm.sort.value = it }, onServer = { vm.includeServer.value = it },
        )
        }
        if (q.length >= 2) ProgressLines(progress)
    } }) { headerPadding ->
        Box(Modifier.fillMaxSize().then(if(results.itemCount>0) Modifier else Modifier.padding(headerPadding))) {
        when (searchDisplayState(q, results.itemCount, results.loadState.refresh, progress, queryPending)) {
            SearchDisplayState.IDLE -> SearchHints()
            SearchDisplayState.SEARCHING ->
                Box(Modifier.fillMaxSize().padding(top = 64.dp), contentAlignment = Alignment.TopCenter) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = c.textTertiary, strokeWidth = 1.5.dp)
                }
            SearchDisplayState.ERROR ->
                EmptyState(Icons.Outlined.WifiOff, stringResource(R.string.search_error_title), (results.loadState.refresh as LoadState.Error).error.message ?: stringResource(R.string.search_error_body))
            SearchDisplayState.NO_RESULTS ->
                EmptyState(Icons.Outlined.SearchOff, stringResource(R.string.search_no_results_title), stringResource(if (progress.index.running) R.string.search_no_results_indexing else R.string.search_no_results_body, q))
            SearchDisplayState.PARTIAL_RESULTS -> if (results.itemCount == 0) {
                val unavailable = progress.serverError == null && progress.index.error == null && !progress.serverAvailable
                EmptyState(
                    Icons.Outlined.WifiOff,
                    stringResource(if (unavailable) R.string.search_partial_title else R.string.search_error_title),
                    stringResource(if (unavailable) R.string.search_partial_body else R.string.search_server_error),
                )
            } else ResultsList(results, onOpenThread, edgeState, headerPadding, listState, headerControls)
            SearchDisplayState.RESULTS -> ResultsList(results, onOpenThread, edgeState, headerPadding, listState, headerControls)
        }
            }
    }
}

/** Distinguishes a settled empty result from a local page while indexing or remote search is active. */
internal enum class SearchDisplayState { IDLE, SEARCHING, RESULTS, NO_RESULTS, PARTIAL_RESULTS, ERROR }

internal fun searchDisplayState(query: String, count: Int, refresh: LoadState, progress: SearchProgress, queryPending: Boolean = false): SearchDisplayState {
    if (query.length < 2) return SearchDisplayState.IDLE
    if (queryPending) return SearchDisplayState.SEARCHING
    val refreshError = refresh as? LoadState.Error
    if (count == 0 && refreshError != null) return SearchDisplayState.ERROR
    val serverBusy = progress.serverRunning
    val sourceFailed = progress.serverError != null || progress.index.error != null || (progress.serverAvailable.not() && progress.serverRunning.not())
    if (refresh is LoadState.Loading) return SearchDisplayState.SEARCHING
    if (count > 0) return if (sourceFailed || serverBusy || progress.index.running) SearchDisplayState.PARTIAL_RESULTS else SearchDisplayState.RESULTS
    if (serverBusy) return SearchDisplayState.SEARCHING
    if (refresh !is LoadState.NotLoading) return SearchDisplayState.SEARCHING
    return if (sourceFailed) SearchDisplayState.PARTIAL_RESULTS else SearchDisplayState.NO_RESULTS
}

@Composable
private fun ResultsList(results: androidx.paging.compose.LazyPagingItems<SearchResult>, onOpenThread: (Long) -> Unit, edgeState: app.dot.design.components.Backdrop, headerPadding: androidx.compose.foundation.layout.PaddingValues, listState: androidx.compose.foundation.lazy.LazyListState, headerControls: app.dot.design.components.HeaderControlsState) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding=headerPadding, modifier = Modifier.fillMaxSize().nestedScroll(headerControls.connection).backdropSource(edgeState).navigationBarsPadding()) {
            items(results.itemCount, key = results.itemKey { it.messageId }) { i ->
                val result = results[i] ?: return@items
                SearchResultRow(result, onClick = { onOpenThread(result.thread.threadId) })
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
/** Scope chips (this folder / all folders / all accounts), server toggle and sort menu in one quiet scrolling row. */
@Composable
internal fun ScopeBar(
    mode: SearchMode, sort: SearchSort, server: Boolean, hasFolder: Boolean, hasAccount: Boolean,
    onMode: (SearchMode) -> Unit, onSort: (SearchSort) -> Unit, onServer: (Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // Wraps instead of scrolling: on a phone the five controls overflow one line and a clipped chip looks broken.
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = DotSpacing.s),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DotSpacing.xs),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Box {
            DotIconButton(Icons.Outlined.SwapVert, stringResource(R.string.search_sort), onClick = { menu = true })
            DotMenu(menu, { menu = false }) {
                listOf(
                    SearchSort.RELEVANCE to R.string.search_sort_relevance, SearchSort.NEWEST to R.string.search_sort_newest, SearchSort.OLDEST to R.string.search_sort_oldest,
                ).forEach { (value, label) -> DotMenuItem(stringResource(label), if (value == sort) Icons.Outlined.Check else null) { menu = false; onSort(value) } }
            }
        }
        if (hasFolder) {
            DotFilterChip(stringResource(R.string.search_scope_folder), mode == SearchMode.THIS_FOLDER, { onMode(SearchMode.THIS_FOLDER) }, radio = true)
            if (hasAccount) {
                DotFilterChip(stringResource(R.string.search_scope_all_folders), mode == SearchMode.ALL_FOLDERS, { onMode(SearchMode.ALL_FOLDERS) }, radio = true)
            }
        }
        DotFilterChip(stringResource(R.string.search_scope_all_accounts), mode == SearchMode.ALL_ACCOUNTS, { onMode(SearchMode.ALL_ACCOUNTS) }, radio = true)
        DotFilterChip(stringResource(R.string.search_server_toggle), server, { onServer(!server) })
    }
}

/** Indexing and server activity, shown as quiet caption lines so partial results never look final. */
@Composable
internal fun ProgressLines(p: SearchProgress) {
    val c = Dot.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = DotSpacing.gutter)) {
        if (p.index.running && p.index.total > 0) {
            Text(stringResource(R.string.search_indexing, p.index.indexed, p.index.total), style = Dot.type.caption, color = c.textTertiary)
        } else if (p.index.error != null) {
            Text(stringResource(R.string.search_index_error), style = Dot.type.caption, color = c.textTertiary)
        }
        if (p.serverRunning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.search_server_running), style = Dot.type.caption, color = c.textTertiary)
                Spacer(Modifier.width(DotSpacing.s))
                app.dot.design.components.DotDotsLoader(color = c.textTertiary)
            }
        } else if (p.serverError != null) {
            Text(stringResource(R.string.search_server_error), style = Dot.type.caption, color = c.textTertiary)
        } else if (p.serverLimited) {
            Text(stringResource(R.string.search_server_limited), style = Dot.type.caption, color = c.textTertiary)
        }
    }
}

/** [text] with the engine's [ranges] (UTF-16, end exclusive) emphasised; out-of-bounds ranges are ignored. */
internal fun highlighted(text: String, ranges: List<HighlightRange>, emphasis: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    ranges.forEach { r ->
        val start = r.start.coerceIn(0, text.length)
        val end = r.endExclusive.coerceIn(start, text.length)
        if (end > start) addStyle(emphasis, start, end)
    }
}

@Composable
internal fun SearchResultRow(r: SearchResult, onClick: () -> Unit) {
    val c = Dot.colors
    val t = r.thread
    val emphasis = remember(c.accent) { SpanStyle(fontWeight = FontWeight.SemiBold, background = c.accent.copy(alpha = if (c.isDark) 0.30f else 0.16f)) }
    val subject = r.subject.ifBlank { stringResource(R.string.inbox_no_subject) }
    val onServer = stringResource(R.string.search_server_only)
    val encryptionLabel = stringResource(R.string.pgp_list_encrypted)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { if (r.serverOnly) stateDescription = onServer }
            .padding(horizontal = DotSpacing.gutter, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        app.dot.mail.ui.avatar.SenderAvatar(t.participants.lastOrNull() ?: app.dot.mail.data.model.EmailAddress("?"))
        Spacer(Modifier.width(DotSpacing.m))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.participants.lastOrNull()?.displayName.orEmpty(), style = Dot.type.headline, color = c.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (t.hasAttachments) {
                    Icon(Icons.Outlined.Attachment, null, Modifier.size(15.dp), tint = c.textTertiary)
                    Spacer(Modifier.width(6.dp))
                }
                if (t.encryptionHint != app.dot.mail.engine.EncryptionHint.NONE) {
                    Icon(Icons.Outlined.Lock, encryptionLabel, Modifier.size(15.dp), tint = c.textTertiary)
                    Spacer(Modifier.width(6.dp))
                }
                Text(Format.listDate(t.lastMessageAt), style = Dot.type.caption, color = c.textTertiary)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                highlighted(subject, r.subjectHighlights, emphasis), style = Dot.type.callout, color = c.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (r.snippet.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    highlighted(r.snippet, r.highlights, emphasis), style = Dot.type.callout, color = c.textTertiary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (r.serverOnly) {
                Spacer(Modifier.height(4.dp))
                Text(
                    onServer, style = Dot.type.label, color = c.textSecondary,
                    modifier = Modifier.clip(DotShapes.pill).border(1.dp, c.border, DotShapes.pill).padding(horizontal = DotSpacing.s, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchHints() {
    val c = Dot.colors
    Column(Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xl)) {
        Text(stringResource(R.string.search_hints_intro), style = Dot.type.callout, color = c.textSecondary)
        Spacer(Modifier.height(DotSpacing.l))
        listOf(
            "from:anna" to stringResource(R.string.search_hint_from),
            "to:team@example.com" to stringResource(R.string.search_hint_to),
            "subject:invoice" to stringResource(R.string.search_hint_subject),
            "has:attachment" to stringResource(R.string.search_hint_attachment),
            "is:unread  is:starred" to stringResource(R.string.search_hint_state),
        ).forEach { (op, desc) ->
            Row(Modifier.padding(vertical = 6.dp)) {
                Text(op, style = Dot.type.label, color = c.textPrimary, modifier = Modifier.weight(1f))
                Text(desc, style = Dot.type.caption, color = c.textTertiary)
            }
        }
    }
}
