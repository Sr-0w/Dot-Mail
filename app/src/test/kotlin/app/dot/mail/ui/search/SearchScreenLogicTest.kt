package app.dot.mail.ui.search

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.paging.LoadState
import app.dot.mail.data.model.HighlightRange
import app.dot.mail.data.model.SearchProgress
import app.dot.mail.data.model.SearchSort
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchScreenLogicTest {
    private val bold = SpanStyle(fontWeight = FontWeight.Bold)

    @Test fun `empty page stays searching until local and server work finish`() {
        val loading = SearchProgress(serverRunning = true, serverAvailable = true)
        assertThat(searchDisplayState("invoice", 0, LoadState.NotLoading(false), loading)).isEqualTo(SearchDisplayState.SEARCHING)
        assertThat(searchDisplayState("invoice", 1, LoadState.NotLoading(false), loading)).isEqualTo(SearchDisplayState.PARTIAL_RESULTS)
        assertThat(searchDisplayState("invoice", 0, LoadState.Loading, SearchProgress())).isEqualTo(SearchDisplayState.SEARCHING)
        assertThat(searchDisplayState("invoice", 0, LoadState.NotLoading(false), SearchProgress(serverAvailable = true))).isEqualTo(SearchDisplayState.NO_RESULTS)
    }

    @Test fun `display state distinguishes idle results partial and failure`() {
        assertThat(searchDisplayState("i", 0, LoadState.NotLoading(false), SearchProgress())).isEqualTo(SearchDisplayState.IDLE)
        assertThat(searchDisplayState("invoice", 1, LoadState.NotLoading(false), SearchProgress(serverAvailable = true))).isEqualTo(SearchDisplayState.RESULTS)
        assertThat(searchDisplayState("invoice", 0, LoadState.NotLoading(false), SearchProgress(serverError = "offline"))).isEqualTo(SearchDisplayState.PARTIAL_RESULTS)
        assertThat(searchDisplayState("invoice", 0, LoadState.Error(IllegalStateException()), SearchProgress())).isEqualTo(SearchDisplayState.ERROR)
    }

    @Test fun `highlights apply to the exact UTF-16 ranges`(): Unit {
        val text = "Facture d'avril"
        val out = highlighted(text, listOf(HighlightRange(0, 7), HighlightRange(10, 15)), bold)
        assertThat(out.text).isEqualTo(text)
        assertThat(out.spanStyles.map { it.start to it.end }).containsExactly(0 to 7, 10 to 15).inOrder()
    }

    @Test fun `supplementary characters keep their surrogate pair inside a range`(): Unit {
        val text = "a😀b"
        val out = highlighted(text, listOf(HighlightRange(1, 3)), bold)
        assertThat(out.text.substring(out.spanStyles.single().start, out.spanStyles.single().end)).isEqualTo("😀")
    }

    @Test fun `out of range or empty highlights are clamped or ignored`(): Unit {
        val out = highlighted("abc", listOf(HighlightRange(-5, 2), HighlightRange(2, 99), HighlightRange(3, 3), HighlightRange(9, 12)), bold)
        assertThat(out.spanStyles.map { it.start to it.end }).containsExactly(0 to 2, 2 to 3).inOrder()
        assertThat(highlighted("", listOf(HighlightRange(0, 1)), bold).spanStyles).isEmpty()
    }

    @Test fun `scope follows the chosen chip and falls back to everything without a folder context`(): Unit {
        val ctx = SearchContext(setOf(5L, 6L), accountId = 2L)
        assertThat(scopeFor(SearchMode.THIS_FOLDER, SearchSort.NEWEST, true, ctx).folderIds).containsExactly(5L, 6L)
        assertThat(scopeFor(SearchMode.ALL_FOLDERS, SearchSort.NEWEST, true, ctx).accountIds).containsExactly(2L)
        assertThat(scopeFor(SearchMode.ALL_FOLDERS, SearchSort.NEWEST, true, ctx).folderIds).isEmpty()
        val all = scopeFor(SearchMode.ALL_ACCOUNTS, SearchSort.OLDEST, false, ctx)
        assertThat(all.accountIds).isEmpty(); assertThat(all.folderIds).isEmpty()
        assertThat(all.includeServer).isFalse(); assertThat(all.sort).isEqualTo(SearchSort.OLDEST)
        assertThat(scopeFor(SearchMode.THIS_FOLDER, SearchSort.RELEVANCE, true, null).folderIds).isEmpty()
        // Unified role contexts have no single account: "all folders" then means everything.
        assertThat(scopeFor(SearchMode.ALL_FOLDERS, SearchSort.RELEVANCE, true, SearchContext(setOf(1L), null)).accountIds).isEmpty()
    }
}
