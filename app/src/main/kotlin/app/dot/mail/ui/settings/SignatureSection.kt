package app.dot.mail.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.dot.design.components.ButtonStyle
import app.dot.design.Dot
import app.dot.design.components.DotButton
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.DotTextField
import app.dot.mail.R
import app.dot.mail.engine.text.EmailHtml
import app.dot.mail.ui.compose.FormattingToolbar
import app.dot.mail.ui.compose.RichLinkDialog
import app.dot.mail.ui.compose.SignaturePreview
import app.dot.mail.ui.compose.editorHtmlToEmailHtml
import app.dot.mail.ui.compose.emailHtmlToEditorHtml
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor

/** The email-safe, sanitised HTML currently in [state] ("" when empty). */
internal fun signatureHtmlOf(state: RichTextState): String =
    if (state.annotatedString.text.isBlank()) "" else EmailHtml.sanitizeSignature(editorHtmlToEmailHtml(state.toHtml()))

/** Loads [html] (already email HTML) into [state]. */
internal fun RichTextState.loadSignature(html: String) {
    if (html.isBlank()) clear() else setHtml(emailHtmlToEditorHtml(html))
}

/**
 * Rich signature editor: bold/italic/underline/links/lists, HTML import (sanitised), optional inline logo and a
 * live preview of what recipients will see.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SignatureSection(
    state: RichTextState,
    logo: String?,
    onPickLogo: () -> Unit,
    onRemoveLogo: () -> Unit,
    onPickHtmlFile: ((String) -> Unit) -> Unit,
) {
    val c = Dot.colors
    var focused by remember { mutableStateOf(false) }
    var linkDialog by remember { mutableStateOf(false) }
    var importDialog by remember { mutableStateOf(false) }
    var previewHtml by remember { mutableStateOf("") }
    LaunchedEffect(state) { snapshotFlow { signatureHtmlOf(state) }.collect { previewHtml = it } }

    Column(verticalArrangement = Arrangement.spacedBy(DotSpacing.s)) {
        Text(stringResource(R.string.identity_signature), style = Dot.type.callout, color = c.textSecondary)
        Column(Modifier.fillMaxWidth().border(BorderStroke(1.dp, if (focused) c.accent else c.border), DotShapes.medium)) {
            if (focused) FormattingToolbar(state, wrap = true) { linkDialog = true }
            Box(Modifier.padding(DotSpacing.m)) {
                if (state.annotatedString.text.isEmpty()) {
                    Text(stringResource(R.string.identity_signature_placeholder_rich), style = Dot.type.body, color = c.textTertiary)
                }
                BasicRichTextEditor(
                    state = state,
                    textStyle = Dot.type.body.copy(color = c.textPrimary),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).onFocusChanged { focused = it.isFocused },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(DotSpacing.s), verticalArrangement = Arrangement.spacedBy(DotSpacing.xs)) {
            DotButton(stringResource(R.string.identity_signature_import), { importDialog = true }, style = ButtonStyle.Ghost, compact = true)
            DotButton(
                stringResource(if (logo == null) R.string.identity_signature_logo_add else R.string.identity_signature_logo_replace),
                onPickLogo, style = ButtonStyle.Ghost, compact = true,
            )
            if (logo != null) DotButton(stringResource(R.string.identity_signature_logo_remove), onRemoveLogo, style = ButtonStyle.Ghost, compact = true)
        }
        if (logo != null) Text(stringResource(R.string.identity_signature_logo_note), style = Dot.type.caption, color = c.textTertiary)
        if (previewHtml.isNotBlank() || logo != null) {
            Text(stringResource(R.string.identity_signature_preview), style = Dot.type.caption, color = c.textTertiary, modifier = Modifier.padding(top = DotSpacing.s))
            Box(Modifier.fillMaxWidth().border(BorderStroke(1.dp, c.border), DotShapes.medium).padding(DotSpacing.m)) {
                SignaturePreview(previewHtml, logo)
            }
        }
    }

    if (linkDialog) RichLinkDialog(state) { linkDialog = false }
    if (importDialog) ImportHtmlDialog(
        onPickFile = onPickHtmlFile,
        onDismiss = { importDialog = false },
        onImport = { cleaned -> state.loadSignature(cleaned); importDialog = false },
    )
}

@Composable
private fun ImportHtmlDialog(onPickFile: ((String) -> Unit) -> Unit, onDismiss: () -> Unit, onImport: (String) -> Unit) {
    val c = Dot.colors
    var raw by remember { mutableStateOf("") }
    var empty by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface, shape = DotShapes.large,
        title = { Text(stringResource(R.string.identity_signature_import_title), style = Dot.type.title, color = c.textPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DotSpacing.s)) {
                DotTextField(
                    raw, { raw = it; empty = false }, label = "HTML", singleLine = false,
                    isError = empty,
                    supporting = stringResource(if (empty) R.string.identity_signature_import_empty else R.string.identity_signature_import_hint),
                )
                DotButton(stringResource(R.string.identity_signature_import_file), { onPickFile { raw = it; empty = false } }, style = ButtonStyle.Ghost, compact = true)
            }
        },
        confirmButton = {
            DotButton(stringResource(R.string.identity_signature_import_confirm), enabled = raw.isNotBlank(), compact = true, onClick = {
                val cleaned = EmailHtml.sanitizeSignature(raw)
                if (cleaned.isBlank()) empty = true else onImport(cleaned)
            })
        },
        dismissButton = { DotButton(stringResource(R.string.identity_cancel), onDismiss, style = ButtonStyle.Ghost, compact = true) },
    )
}
