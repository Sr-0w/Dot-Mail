package app.dot.mail.ui.pdf

import app.dot.mail.data.PdfText
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import app.dot.mail.R
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.DotIconButton
import app.dot.design.components.DotSpinner
import app.dot.design.components.DotTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * In-app PDF preview with the platform's own renderer: nothing leaves the phone, no extra library.
 * Pages render lazily at screen width; pinch to zoom, double-tap to reset. "Share" hands the file to any app
 * (Paperless-ngx, a printer, cloud storage…), "Open with" to a full PDF app.
 */
@Composable
fun PdfViewerDialog(
    uri: String,
    name: String,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onOpenExternally: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val c = Dot.colors
        var showText by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().background(c.background)) {
            DotTopBar(
                title = name,
                navigation = { DotIconButton(Icons.Outlined.Close, stringResource(R.string.compose_close), onClick = onDismiss) },
                actions = {
                    if (PdfText.supported) {
                        DotIconButton(
                            if (showText) Icons.Outlined.Description else Icons.AutoMirrored.Outlined.Subject,
                            stringResource(if (showText) R.string.pdf_show_pages else R.string.pdf_show_text),
                            onClick = { showText = !showText },
                        )
                    }
                    DotIconButton(Icons.Outlined.Share, stringResource(R.string.thread_share), onClick = onShare)
                    DotIconButton(Icons.Outlined.OpenInNew, stringResource(R.string.pdf_open_with), onClick = onOpenExternally)
                },
            )
            if (showText) PdfTextView(uri, Modifier.weight(1f))
            else PdfPages(uri, onFailed = onOpenExternally, modifier = Modifier.weight(1f))
        }
    }
}

private class PdfDocument(val fd: ParcelFileDescriptor, val renderer: PdfRenderer) {
    val lock = Mutex()
    fun close() { runCatching { renderer.close() }; runCatching { fd.close() } }
}

@Composable
private fun PdfPages(uri: String, onFailed: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = Dot.colors
    val doc by produceState<Result<PdfDocument>?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val fd = requireNotNull(context.contentResolver.openFileDescriptor(Uri.parse(uri), "r"))
                try { PdfDocument(fd, PdfRenderer(fd)) } catch (e: Exception) { fd.close(); throw e }
            }
        }
    }
    DisposableEffect(doc) { val d = doc?.getOrNull(); onDispose { d?.close() } }
    val loaded = doc
    when {
        loaded == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DotSpinner(size = 22.dp, color = c.textTertiary) }
        loaded.isFailure -> Box(modifier.fillMaxSize().padding(DotSpacing.xl), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.pdf_unavailable),
                style = Dot.type.callout, color = c.textSecondary,
            )
        }
        else -> {
            val pdf = loaded.getOrThrow()
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val transform = rememberTransformableState { zoom, pan, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                offset = if (scale == 1f) Offset.Zero else offset + pan
            }
            BoxWithConstraints(
                modifier.fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
                    .transformable(transform, lockRotationOnZoomPan = true, canPan = { scale > 1f }),
            ) {
                val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceIn(320, 2400)
                LazyColumn(
                    state = rememberLazyListState(),
                    userScrollEnabled = scale == 1f,
                    contentPadding = PaddingValues(DotSpacing.m),
                    verticalArrangement = Arrangement.spacedBy(DotSpacing.m),
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                    },
                ) {
                    items(pdf.renderer.pageCount) { index -> PdfPage(pdf, index, widthPx) }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(pdf: PdfDocument, index: Int, widthPx: Int) {
    val c = Dot.colors
    val bitmap by produceState<Bitmap?>(null, pdf, index, widthPx) {
        value = withContext(Dispatchers.IO) {
            pdf.lock.withLock {
                runCatching {
                    pdf.renderer.openPage(index).use { page ->
                        val height = (widthPx.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, 4 * widthPx)
                        Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also { bmp ->
                            bmp.eraseColor(AColor.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }.getOrNull()
            }
        }
    }
    val img = bitmap
    if (img == null) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(c.surface, DotShapes.small), contentAlignment = Alignment.Center) {
            DotSpinner(size = 18.dp, color = c.textTertiary)
        }
    } else {
        Image(
            img.asImageBitmap(), contentDescription = stringResource(R.string.pdf_page, index + 1),
            modifier = Modifier.fillMaxWidth().aspectRatio(img.width.toFloat() / img.height),
        )
    }
}


@Composable
private fun PdfTextView(uri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = Dot.colors
    val text by produceState<String?>(null, uri) { value = withContext(Dispatchers.IO) { PdfText.extract(context, uri) ?: "" } }
    val t = text
    when {
        t == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DotSpinner(size = 22.dp, color = c.textTertiary) }
        t.isEmpty() -> Box(modifier.fillMaxSize().padding(DotSpacing.xl), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.pdf_no_text), style = Dot.type.callout, color = c.textSecondary)
        }
        else -> androidx.compose.foundation.text.selection.SelectionContainer(modifier.fillMaxSize()) {
            Text(
                t, style = Dot.type.body, color = c.textPrimary,
                modifier = Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(DotSpacing.gutter),
            )
        }
    }
}
