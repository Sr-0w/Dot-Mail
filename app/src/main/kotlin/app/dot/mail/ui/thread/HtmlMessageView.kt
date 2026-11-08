package app.dot.mail.ui.thread

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/**
 * Renders already-sanitised email HTML. Defence in depth on top of the sanitiser:
 *  - JavaScript, file access, forms autofill, zoom controls, Safe Browsing (would leak URLs to Google) off.
 *  - Every subresource request is intercepted: remote ones are answered with an empty response unless
 *    [allowRemote]; only cid:/content: inline parts that the data layer resolved are let through.
 *  - Navigation never happens inside the WebView: links are handed to [onLink].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlMessageView(
    html: String,
    dark: Boolean,
    allowRemote: Boolean,
    textColor: Int,
    linkColor: Int,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Exact content:// URIs of this message's inline parts; nothing else local is ever served. */
    inlineUris: Set<String> = emptySet(),
    textZoom: Int = 100,
    /** Widest fixed CSS-pixel width the design needs (0 = fluid), from the sanitiser. */
    layoutWidth: Int = 0,
    /** The email adapts to narrow screens itself, so it is laid out at the device width. */
    responsive: Boolean = false,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
    // WebView CSS pixels == dp. A fixed-width design wider than the space we have is laid out at its own
    // width and the overview mode scales it down to fit — like Gmail / Samsung Email — instead of clipping.
    val available = maxWidth.value.toInt().coerceAtLeast(1)
    val viewportWidth = if (!responsive && layoutWidth > available) layoutWidth + 2 * BODY_MARGIN else 0
    val document = remember(html, dark, textColor, linkColor, viewportWidth) { wrap(html, dark, textColor, linkColor, viewportWidth) }
    val currentOnLink by androidx.compose.runtime.rememberUpdatedState(onLink)
    val linkHandler: (String) -> Unit = remember { { url -> currentOnLink(url) } }
    // The WebView's theme (algorithmic darkening) is fixed at creation: recreate it when the theme flips.
    androidx.compose.runtime.key(dark) {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            val themed = ContextThemeWrapper(ctx, if (dark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light)
            WebView(themed).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setBackgroundColor(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                with(settings) {
                    javaScriptEnabled = false
                    javaScriptCanOpenWindowsAutomatically = false
                    allowFileAccess = false
                    allowContentAccess = false // inline parts are served by SafeClient from an exact allow-list
                    domStorageEnabled = false
                    databaseEnabled = false
                    @Suppress("DEPRECATION")
                    saveFormData = false
                    setGeolocationEnabled(false)
                    safeBrowsingEnabled = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = false
                    setSupportZoom(false)
                    setSupportMultipleWindows(false)
                    mediaPlaybackRequiresUserGesture = true
                    blockNetworkLoads = !allowRemote
                    blockNetworkImage = !allowRemote
                    defaultTextEncodingName = "utf-8"
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, dark)
                }
                webViewClient = SafeClient(allowRemote, inlineUris, ctx.contentResolver, linkHandler)
                tag = null
            }
        },
        update = { web ->
            val client = web.webViewClient as? SafeClient
            if (client == null || client.allowRemote != allowRemote || client.inline != inlineUris) {
                web.settings.blockNetworkLoads = !allowRemote
                web.settings.blockNetworkImage = !allowRemote
                web.webViewClient = SafeClient(allowRemote, inlineUris, web.context.contentResolver, linkHandler)
            }
            if (web.settings.textZoom != textZoom) web.settings.textZoom = textZoom
            if (web.tag != document) {
                web.tag = document
                // Null base URL = opaque origin: the content can't reach anything relative.
                web.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
    }
    }
}

/** Breathing room (CSS px) kept around scaled-down fixed-width designs. */
private const val BODY_MARGIN = 4

private class SafeClient(
    val allowRemote: Boolean,
    val inline: Set<String>,
    private val resolver: android.content.ContentResolver,
    val onLink: (String) -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (request.isForMainFrame || request.hasGesture()) onLink(request.url.toString())
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val scheme = request.url.scheme?.lowercase()
        val url = request.url.toString()
        return when {
            scheme == "content" && url in inline -> runCatching {
                WebResourceResponse(resolver.getType(request.url) ?: "application/octet-stream", null, resolver.openInputStream(request.url))
            }.getOrElse { EMPTY() }
            scheme == "data" -> null
            (scheme == "http" || scheme == "https") && allowRemote -> null
            else -> EMPTY()
        }
    }

    @Suppress("FunctionName")
    private fun EMPTY() = WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

private fun css(color: Int) = String.format("#%06X", 0xFFFFFF and color)

/** Base styles: our type, readable measure, nothing wider than the screen. */
private fun wrap(body: String, dark: Boolean, textColor: Int, linkColor: Int, viewportWidth: Int): String = """
<!DOCTYPE html><html><head>
<meta charset="utf-8">
${if (viewportWidth > 0) "<meta name=\"viewport\" content=\"width=$viewportWidth\">" else "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"}
${if (dark) "" else "<meta name=\"color-scheme\" content=\"light\">"}
<style>
html,body{margin:0;padding:0;background:transparent;}
${if (viewportWidth > 0) "body{padding:0 ${BODY_MARGIN}px;}" else ""}
body{font-family:sans-serif;font-size:15px;line-height:1.55;${if (dark) "" else "color:${css(textColor)};"}word-wrap:break-word;overflow-wrap:anywhere;-webkit-text-size-adjust:100%;}
${if (dark) "" else "a{color:${css(linkColor)};}"}
img{max-width:100%!important;height:auto!important;}
${if (viewportWidth > 0) "" else "table{max-width:100%;}"}
pre{white-space:pre-wrap;}
blockquote{margin:8px 0;padding-left:12px;border-left:2px solid rgba(128,128,128,.35);color:inherit;opacity:.85;}
</style></head><body>$body</body></html>
""".trimIndent()
