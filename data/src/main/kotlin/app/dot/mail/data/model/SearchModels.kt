package app.dot.mail.data.model

/** Empty account/folder sets mean all. Server search is optional and never downloads full bodies. */
data class SearchScope(val accountIds: Set<Long> = emptySet(), val folderIds: Set<Long> = emptySet(), val includeServer: Boolean = true, val sort: SearchSort = SearchSort.RELEVANCE)
enum class SearchSort { RELEVANCE, NEWEST, OLDEST }
/** UTF-16 offsets into the accompanying original (not normalized) string. */
data class HighlightRange(val start: Int, val endExclusive: Int)
data class SearchResult(val thread: ThreadSummary, val messageId: Long, val folderId: Long, val subject: String, val subjectHighlights: List<HighlightRange>, val snippet: String, val highlights: List<HighlightRange>, val bodyAvailable: Boolean, val serverOnly: Boolean)
data class SearchIndexProgress(val indexed: Int = 0, val total: Int = 0, val running: Boolean = false, val error: String? = null)
data class SearchProgress(val index: SearchIndexProgress = SearchIndexProgress(), val serverRunning: Boolean = false, val serverAvailable: Boolean = false, val serverError: String? = null, val serverLimited: Boolean = false)
