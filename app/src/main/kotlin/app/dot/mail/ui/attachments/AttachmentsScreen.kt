package app.dot.mail.ui.attachments

import androidx.compose.runtime.setValue

import androidx.compose.runtime.mutableStateOf

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.BackButton
import app.dot.design.components.DotSnackbar
import app.dot.design.components.DotTopBar
import app.dot.design.components.EmptyState
import app.dot.design.components.SearchField
import app.dot.mail.LocalMailGraph
import app.dot.mail.data.MailGraph
import app.dot.mail.data.model.AttachmentItem
import app.dot.mail.data.model.AttachmentKind
import app.dot.mail.ui.common.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One-off result of tapping a file: the screen launches the matching chooser. */
internal sealed interface AttachmentEvent {
    data class Open(val uri: String, val mime: String, val name: String, val share: Boolean) : AttachmentEvent
    data class Message(val text: String) : AttachmentEvent
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class AttachmentsViewModel(private val graph: MailGraph) : ViewModel() {
    val query = MutableStateFlow("")
    val kind = MutableStateFlow<AttachmentKind?>(null)
    val items: Flow<PagingData<AttachmentItem>> =
        combine(query.debounce(250), kind) { q, k -> q to k }
            .flatMapLatest { (q, k) -> graph.mail.attachments(k, q) }
            .cachedIn(viewModelScope)

    private val _events = Channel<AttachmentEvent>(Channel.BUFFERED)
    internal val events = _events.receiveAsFlow()

    /** Opens (or shares) the file, downloading it first if it isn't on the device yet — only because the user asked. */
    fun launch(item: AttachmentItem, share: Boolean) {
        viewModelScope.launch {
            if (!item.isDownloaded) _events.send(AttachmentEvent.Message("Downloading ${item.attachment.fileName}…"))
            runCatching { graph.mail.openAttachment(item.attachment) }
                .onSuccess { _events.send(AttachmentEvent.Open(it, item.attachment.mimeType, item.attachment.fileName, share)) }
                .onFailure { _events.send(AttachmentEvent.Message("Couldn't open ${item.attachment.fileName}")) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AttachmentsScreen(onBack: () -> Unit) {
    val graph = LocalMailGraph.current
    val vm: AttachmentsViewModel = viewModel { AttachmentsViewModel(graph) }
    val query by vm.query.collectAsStateWithLifecycle()
    val kind by vm.kind.collectAsStateWithLifecycle()
    val items = vm.items.collectAsLazyPagingItems()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val c = Dot.colors
    var pdf by remember { mutableStateOf<AttachmentEvent.Open?>(null) }
    fun external(e: AttachmentEvent.Open, share: Boolean) {
        val uri = Uri.parse(e.uri)
        val intent = if (share) Intent(Intent.ACTION_SEND).setType(e.mime).putExtra(Intent.EXTRA_STREAM, uri)
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mime)
        try { context.startActivity(Intent.createChooser(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), e.name)) }
        catch (_: ActivityNotFoundException) { }
    }
    pdf?.let { e ->
        app.dot.mail.ui.pdf.PdfViewerDialog(e.uri, e.name, onDismiss = { pdf = null }, onShare = { external(e, true) }, onOpenExternally = { external(e, false) })
    }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is AttachmentEvent.Message -> snackbar.showSnackbar(e.text)
                is AttachmentEvent.Open -> if (!e.share && (e.mime.equals("application/pdf", true) || e.name.endsWith(".pdf", true))) pdf = e else {
                    val uri = Uri.parse(e.uri)
                    val intent = if (e.share) Intent(Intent.ACTION_SEND).setType(e.mime).putExtra(Intent.EXTRA_STREAM, uri)
                    else Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mime)
                    try { context.startActivity(Intent.createChooser(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), e.name)) }
                    catch (_: ActivityNotFoundException) { snackbar.showSnackbar("No app can open ${e.name}") }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize()) {
            DotTopBar(title = "Attachments", subtitle = "All accounts", elevated = listState.canScrollBackward, navigation = { BackButton(onBack) })
            SearchField(
                value = query, onValueChange = { vm.query.value = it },
                placeholder = "Search file name or sender",
                modifier = Modifier.fillMaxWidth().padding(horizontal = DotSpacing.gutter),
            )
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DotSpacing.s),
            ) {
                item { FilterChip("All", kind == null) { vm.kind.value = null } }
                items(AttachmentKind.entries.toList()) { k -> FilterChip(label(k), kind == k) { vm.kind.value = if (kind == k) null else k } }
            }
            if (items.itemCount == 0 && items.loadState.refresh is androidx.paging.LoadState.NotLoading) {
                EmptyState(
                    Icons.Outlined.AttachFile,
                    if (query.isBlank() && kind == null) "No attachments yet" else "No matching attachments",
                    if (query.isBlank() && kind == null) "Files sent to you appear here, across all accounts." else "Try another type or search.",
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                    items(items.itemCount, key = items.itemKey { it.attachment.id }) { index ->
                        val item = items[index] ?: return@items
                        val month = remember(item.sentAt) { YearMonth.from(Instant.ofEpochMilli(item.sentAt).atZone(ZoneId.systemDefault())) }
                        val previous = if (index > 0) items.peek(index - 1) else null
                        val previousMonth = previous?.let { YearMonth.from(Instant.ofEpochMilli(it.sentAt).atZone(ZoneId.systemDefault())) }
                        if (previousMonth != month) MonthHeader(month)
                        AttachmentRow(item, onOpen = { vm.launch(item, share = false) }, onShare = { vm.launch(item, share = true) })
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp)) { DotSnackbar(it) }
    }
}

private fun label(k: AttachmentKind) = when (k) {
    AttachmentKind.IMAGES -> "Images"
    AttachmentKind.PDFS -> "PDFs"
    AttachmentKind.DOCUMENTS -> "Documents"
    AttachmentKind.OTHER -> "Other"
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Dot.colors
    Box(
        Modifier.defaultMinSize(minHeight = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = Dot.type.label, color = if (selected) c.onInverse else c.textSecondary,
            modifier = Modifier.clip(DotShapes.small).background(if (selected) c.inverse else androidx.compose.ui.graphics.Color.Transparent)
                .border(1.dp, if (selected) c.inverse else c.border, DotShapes.small).padding(horizontal = DotSpacing.m, vertical = 7.dp),
        )
    }
}

@Composable
private fun MonthHeader(month: YearMonth) {
    val text = remember(month) { DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()).format(month).replaceFirstChar { it.titlecase(Locale.getDefault()) } }
    Text(
        text.uppercase(Locale.getDefault()), style = Dot.type.overline, color = Dot.colors.textTertiary,
        modifier = Modifier.fillMaxWidth().padding(start = DotSpacing.gutter, end = DotSpacing.gutter, top = DotSpacing.l, bottom = DotSpacing.xs)
            .androidxHeading(),
    )
}

private fun Modifier.androidxHeading() = this.then(Modifier.semantics { heading() })

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentRow(item: AttachmentItem, onOpen: () -> Unit, onShare: () -> Unit) {
    val c = Dot.colors
    val a = item.attachment
    val date = remember(item.sentAt) { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(Instant.ofEpochMilli(item.sentAt).atZone(ZoneId.systemDefault())) }
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClickLabel = if (item.isDownloaded) "Open" else "Download and open", onClick = onOpen, onLongClickLabel = "Share", onLongClick = onShare)
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = DotSpacing.gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(item)
        Spacer(Modifier.width(DotSpacing.m))
        Column(Modifier.weight(1f)) {
            Text(a.fileName, style = Dot.type.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${item.from.displayName} · ${Format.size(a.sizeBytes)} · $date",
                style = Dot.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (item.subject.isNotBlank()) Text(item.subject, style = Dot.type.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Process-wide small cache of decoded previews. Only files already on the device are ever decoded. */
private object Thumbs {
    val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
}

@Composable
private fun Thumbnail(item: AttachmentItem) {
    val c = Dot.colors
    val a = item.attachment
    val kind = AttachmentKind.of(a.mimeType)
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, a.localUri) {
        val uri = a.localUri
        value = if (kind != AttachmentKind.IMAGES || uri == null) null else Thumbs.cache.get(uri) ?: withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 128 && bounds.outHeight / (sample * 2) >= 128) sample *= 2
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            }.getOrNull()?.also { Thumbs.cache.put(uri, it) }
        }
    }
    Box(Modifier.size(48.dp).clip(DotShapes.small).background(c.surface), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val icon = when (kind) {
                AttachmentKind.IMAGES -> Icons.Outlined.Image
                AttachmentKind.PDFS -> Icons.Outlined.PictureAsPdf
                AttachmentKind.DOCUMENTS -> Icons.Outlined.Description
                AttachmentKind.OTHER -> Icons.Outlined.InsertDriveFile
            }
            Icon(icon, null, Modifier.size(22.dp), tint = c.textSecondary)
        }
    }
}
