package app.dot.mail.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.BackButton
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotPrimaryButton
import app.dot.design.components.DotSwitch
import app.dot.design.components.DotTextField
import app.dot.design.components.DotTopBar
import app.dot.design.components.Hairline
import app.dot.design.components.ListRow
import app.dot.design.components.dotSurfaceEnter
import app.dot.mail.R
import app.dot.mail.data.aliases.AliasService
import app.dot.mail.data.aliases.EmailAlias
import app.dot.mail.ui.account.Segmented

@Composable
internal fun AliasesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: AliasesViewModel = viewModel { AliasesViewModel(context.applicationContext) }
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Dot.colors
    var confirmRemove by remember { mutableStateOf(false) }
    val active = state.providers.getValue(state.service)

    Column(Modifier.fillMaxSize().background(c.background)) {
        DotTopBar(title = stringResource(R.string.aliases_title), navigation = { BackButton(onBack) })
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text(
                stringResource(R.string.aliases_subtitle), style = Dot.type.callout, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
            )
            Segmented(
                options = listOf(AliasService.SIMPLELOGIN to "SimpleLogin", AliasService.ADDY to "addy.io"),
                selected = state.service,
                onSelect = vm::selectService,
                modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
            )
            DotTextField(
                value = active.apiKey,
                onValueChange = vm::editKey,
                label = stringResource(R.string.aliases_api_key),
                password = true,
                enabled = !active.loadingKey,
                modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
            )
            Column(
                Modifier.fillMaxWidth().padding(horizontal = DotSpacing.gutter),
                verticalArrangement = Arrangement.spacedBy(DotSpacing.xs),
            ) {
                DotPrimaryButton(
                    text = stringResource(R.string.aliases_test), onClick = vm::testConnection,
                    enabled = active.apiKey.isNotBlank() && !active.busy, loading = active.busy,
                    modifier = Modifier.fillMaxWidth(), compact = true,
                )
                DotButton(
                    text = stringResource(R.string.aliases_create), onClick = vm::createRandom,
                    enabled = active.apiKey.isNotBlank() && !active.busy,
                    modifier = Modifier.fillMaxWidth(), style = ButtonStyle.Secondary, compact = true,
                )
            }
            DotButton(
                text = stringResource(R.string.aliases_remove_key), onClick = { confirmRemove = true },
                enabled = !active.busy && active.apiKey.isNotBlank(), style = ButtonStyle.Ghost, compact = true,
                modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs),
            )
            if (active.error) {
                Text(
                    stringResource(R.string.aliases_error), style = Dot.type.caption, color = c.danger,
                    modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs),
                )
            }
            Text(
                stringResource(R.string.aliases_limit_note), style = Dot.type.caption, color = c.textTertiary,
                modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
            )
            Hairline(Modifier.padding(vertical = DotSpacing.s))
            active.aliases.forEach { alias -> AliasRow(alias, active.busy, context, vm::setEnabled) }
            Spacer(Modifier.height(DotSpacing.xl))
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            modifier = Modifier.dotSurfaceEnter(), containerColor = c.surface, shape = DotShapes.large,
            title = { Text(stringResource(R.string.aliases_remove_title), style = Dot.type.title, color = c.textPrimary) },
            text = { Text(stringResource(R.string.aliases_remove_body), style = Dot.type.callout, color = c.textSecondary) },
            confirmButton = {
                DotButton(stringResource(R.string.aliases_remove_key), onClick = { confirmRemove = false; vm.removeKey() }, style = ButtonStyle.Danger, compact = true)
            },
            dismissButton = { DotButton(stringResource(R.string.identity_cancel), { confirmRemove = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
}

@Composable
private fun AliasRow(alias: EmailAlias, busy: Boolean, context: Context, onEnabled: (EmailAlias, Boolean) -> Unit) {
    val clip = stringResource(R.string.aliases_copy)
    ListRow(
        title = alias.email,
        icon = Icons.Outlined.Email,
        trailing = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                DotButton(
                    stringResource(R.string.aliases_copy), onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText(clip, alias.email))
                    }, style = ButtonStyle.Ghost, compact = true,
                )
                DotSwitch(alias.active, onCheckedChange = { onEnabled(alias, it) }, enabled = !busy)
            }
        },
    )
}
