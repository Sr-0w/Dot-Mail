package app.dot.mail.ui.compose

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import app.dot.design.Dot
import app.dot.mail.R
import app.dot.mail.data.SignatureLogos

/** Read-only rendering of a signature (formatting, links, inline logo) as recipients will see it. */
@Composable
fun SignaturePreview(html: String, logoName: String?, modifier: Modifier = Modifier) {
    val c = Dot.colors
    val context = LocalContext.current
    val text = remember(html, c.accent) { htmlToAnnotated(html, c.accent) }
    val logo = remember(logoName) {
        logoName?.let { runCatching { BitmapFactory.decodeFile(SignatureLogos.file(context, it).path) }.getOrNull() }
    }
    Column(modifier) {
        if (text.isNotEmpty()) Text(text, style = Dot.type.callout, color = c.textSecondary)
        logo?.let {
            Image(
                it.asImageBitmap(), stringResource(R.string.identity_signature_logo_desc),
                Modifier.padding(top = if (text.isNotEmpty()) 8.dp else 0.dp).widthIn(max = 160.dp),
            )
        }
    }
}

/** Converts a signature HTML fragment to styled text (bold/italic/underline/strike/links); images are dropped. */
internal fun htmlToAnnotated(html: String, linkColor: Color): AnnotatedString {
    if (html.isBlank()) return AnnotatedString("")
    val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
    val raw = spanned.toString()
    val trimmedEnd = raw.trimEnd('\n', ' ', '\uFFFC').length
    return buildAnnotatedString {
        append(raw.substring(0, trimmedEnd).replace('\uFFFC', ' '))
        for (span in spanned.getSpans(0, trimmedEnd, Any::class.java)) {
            val start = spanned.getSpanStart(span).coerceIn(0, trimmedEnd)
            val end = spanned.getSpanEnd(span).coerceIn(start, trimmedEnd)
            if (start == end) continue
            val style = when (span) {
                is StyleSpan -> when (span.style) {
                    Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    Typeface.BOLD_ITALIC -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    else -> null
                }
                is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
                is StrikethroughSpan -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                is URLSpan -> SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                else -> null
            }
            if (style != null) addStyle(style, start, end)
        }
    }
}
