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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
) {
    val document = remember(html, dark, textColor, linkColor) { wrap(html, dark, textColor, linkColor) }
    AndroidView(
        modifier = modifier,
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
                webViewClient = SafeClient(allowRemote, inlineUris, ctx.contentResolver, onLink)
                tag = null
            }
        },
        update = { web ->
            val client = web.webViewClient as? SafeClient
            if (client == null || client.allowRemote != allowRemote || client.inline != inlineUris) {
                web.settings.blockNetworkLoads = !allowRemote
                web.settings.blockNetworkImage = !allowRemote
                web.webViewClient = SafeClient(allowRemote, inlineUris, web.context.contentResolver, onLink)
            }
            if (web.tag != document) {
                web.tag = document
                // Null base URL = opaque origin: the content can't reach anything relative.
                web.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

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
private fun wrap(body: String, dark: Boolean, textColor: Int, linkColor: Int): String = """
<!DOCTYPE html><html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="${if (dark) "dark light" else "light"}">
<style>
html,body{margin:0;padding:0;background:transparent;}
body{font-family:sans-serif;font-size:15px;line-height:1.55;color:${css(textColor)};word-wrap:break-word;overflow-wrap:anywhere;-webkit-text-size-adjust:100%;}
a{color:${css(linkColor)};}
img{max-width:100%!important;height:auto!important;}
table{max-width:100%;}
pre{white-space:pre-wrap;}
blockquote{margin:8px 0;padding-left:12px;border-left:2px solid rgba(128,128,128,.35);color:inherit;opacity:.85;}
</style></head><body>$body</body></html>
""".trimIndent()
