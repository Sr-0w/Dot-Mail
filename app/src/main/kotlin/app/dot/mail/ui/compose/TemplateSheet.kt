package app.dot.mail.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import app.dot.mail.R
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotIconButton
import app.dot.design.components.DotPrimaryButton
import app.dot.design.components.DotTextField
import app.dot.design.components.ListRow
import app.dot.mail.engine.text.EmailTemplate

/**
 * Pick a reusable template to insert, delete one, or save the message being written as a new template.
 * Placeholders: {{first_name}} {{name}} {{email}} {{my_name}} {{date}}.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateSheet(
    current: ComposeState,
    onInsert: (EmailTemplate) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { TemplateStore.get(context) }
    val templates by store.templates.collectAsStateWithLifecycle()
    val c = Dot.colors
    var saving by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface, shape = DotShapes.sheet) {
        Column(Modifier.navigationBarsPadding().padding(bottom = DotSpacing.m)) {
            Text(stringResource(R.string.templates_title), style = Dot.type.title, color = c.textPrimary, modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s))
            if (templates.isEmpty()) {
                Text(
                    stringResource(R.string.templates_empty),
                    style = Dot.type.callout, color = c.textSecondary,
                    modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
                )
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(templates, key = { it.id }) { t ->
                    ListRow(
                        t.name,
                        subtitle = (t.subject?.let { "$it — " } ?: "") + t.bodyText.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                        icon = Icons.Outlined.Description,
                        onClick = { onInsert(t) },
                        trailing = { DotIconButton(Icons.Outlined.DeleteOutline, stringResource(R.string.templates_delete), onClick = { store.delete(t.id) }) },
                    )
                }
            }
            val canSave = current.editableText.isNotBlank()
            ListRow(
                stringResource(R.string.templates_save_this),
                icon = Icons.Outlined.Add,
                titleColor = if (canSave) c.textPrimary else c.textTertiary,
                onClick = if (canSave) ({ saving = true }) else null,
            )
        }
    }
    if (saving) {
        AlertDialog(
            onDismissRequest = { saving = false },
            containerColor = c.surface,
            shape = DotShapes.large,
            title = { Text(stringResource(R.string.templates_save_title), style = Dot.type.title, color = c.textPrimary) },
            text = {
                Column {
                    DotTextField(name, { name = it }, label = stringResource(R.string.templates_name), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(DotSpacing.s))
                    Text(stringResource(R.string.templates_save_note), style = Dot.type.caption, color = c.textTertiary)
                }
            },
            confirmButton = {
                DotPrimaryButton(stringResource(R.string.compose_save), {
                    store.save(name, current.draft?.subject, current.editableText, current.bodyHtml.takeIf { current.isRichText })
                    saving = false; name = ""
                }, compact = true)
            },
            dismissButton = { DotButton(stringResource(R.string.compose_cancel), { saving = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
}
