package app.dot.mail.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.ListRow
import app.dot.design.components.SectionLabel
import androidx.compose.ui.res.stringResource
import app.dot.mail.R
import app.dot.mail.BuildConfig
import app.dot.mail.LocalMailGraph
import app.dot.mail.LocalSettings
import app.dot.mail.data.model.BackupPreview
import app.dot.mail.ui.compose.TemplateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate

private class PendingImport(val parsed: SettingsBackup.Parsed, val lines: List<String>, val needSignIn: List<String>)

/**
 * Settings → Backup: export the configuration to a file the user chooses (Storage Access Framework, so no storage
 * permission), and import such a file with a preview. The file never contains passwords or tokens.
 */
@Composable
internal fun BackupSettings() {
    val context = LocalContext.current
    val graph = LocalMailGraph.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val c = Dot.colors
    var infoExport by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingImport?>(null) }
    var notice by remember { mutableStateOf<Pair<String, List<String>>?>(null) } // title to lines
    var busy by remember { mutableStateOf(false) }
    val exportedTitle = stringResource(R.string.backup_exported_title)
    val exportedBody = stringResource(R.string.backup_exported_body)
    val exportFailedTitle = stringResource(R.string.backup_export_failed_title)
    val writeFailed = stringResource(R.string.backup_write_failed)
    val importFailedTitle = stringResource(R.string.backup_import_failed_title)
    val readFailed = stringResource(R.string.backup_read_failed)
    val unusable = stringResource(R.string.backup_unusable)
    val importedTitle = stringResource(R.string.backup_imported_title)

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = runCatching {
                val accounts = graph.accounts.accounts.first()
                val emailOf = { id: Long -> accounts.firstOrNull { it.id == id }?.email }
                val text = SettingsBackup.build(
                    dataSection = graph.accounts.exportBackup(),
                    prefs = SettingsBackup.encodePrefs(settings.ui.value, emailOf),
                    rules = SettingsBackup.encodeRules(RemoteImagePolicy.get(context.applicationContext).rules.value, emailOf),
                    templates = SettingsBackup.encodeTemplates(TemplateStore.get(context.applicationContext).templates.value),
                    appVersion = BuildConfig.VERSION_NAME, exportedAt = Instant.now().toString(),
                    smartFolders = SettingsBackup.encodeSmartFolders(app.dot.mail.data.SmartFolderStore.get(context.applicationContext).folders.value, emailOf),
                )
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }
            }
            busy = false
            notice = if (result.isSuccess) exportedTitle to listOf(exportedBody)
            else exportFailedTitle to listOf(result.exceptionOrNull()?.message ?: writeFailed)
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    // Bounded read (API 26 has no readNBytes): anything above ~2 MB is refused by the parser anyway.
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                        while (out.size() <= 2_000_000) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                        out.toString(Charsets.UTF_8.name())
                    }
                }.getOrNull()
            }
            busy = false
            if (text == null) { notice = importFailedTitle to listOf(readFailed); return@launch }
            val (parsed, error) = SettingsBackup.parse(text)
            if (parsed == null) { notice = importFailedTitle to listOf(error?.let { context.getString(it) } ?: unusable); return@launch }
            val accountPreview: BackupPreview = graph.accounts.previewBackup(parsed.dataSection)
            if (accountPreview.error != null) { notice = importFailedTitle to listOf(accountPreview.error!!); return@launch }
            val lines = buildList {
                accountPreview.items.forEach { i ->
                    add(if (!i.added) context.getString(R.string.backup_preview_account_update, i.email)
                    else context.getString(if (i.oauth) R.string.backup_preview_account_add_oauth else R.string.backup_preview_account_add, i.email, i.host))
                }
                accountPreview.warnings.forEach { add(context.getString(R.string.backup_skipped_prefix, it)) }
                if (parsed.prefs != null) add(context.getString(R.string.backup_preview_prefs))
                parsed.rules?.let { add(context.resources.getQuantityString(R.plurals.backup_preview_rules, it.size, it.size)) }
                parsed.templates?.let { add(context.resources.getQuantityString(R.plurals.backup_preview_templates, it.size, it.size)) }
                parsed.smartFolders?.let { add(context.resources.getQuantityString(R.plurals.backup_preview_smart, it.size, it.size)) }
                if (isEmpty()) add(context.getString(R.string.backup_preview_nothing))
            }
            pending = PendingImport(parsed, lines, accountPreview.accountsNeedingSignIn)
        }
    }

    SectionLabel(stringResource(R.string.backup_section))
    ListRow(
        stringResource(R.string.backup_export), subtitle = stringResource(R.string.backup_export_sub),
        icon = Icons.Outlined.FileUpload, onClick = { infoExport = true },
    )
    ListRow(
        stringResource(R.string.backup_import), subtitle = stringResource(R.string.backup_import_sub),
        icon = Icons.Outlined.FileDownload, onClick = { if (!busy) openLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
    )

    if (infoExport) {
        AlertDialog(
            onDismissRequest = { infoExport = false }, containerColor = c.surface, shape = DotShapes.large,
            title = { Text(stringResource(R.string.backup_export), style = Dot.type.title, color = c.textPrimary) },
            text = {
                Text(
                    stringResource(R.string.backup_export_info),
                    style = Dot.type.callout, color = c.textSecondary,
                )
            },
            confirmButton = { DotButton(stringResource(R.string.backup_choose_where), compact = true, onClick = { infoExport = false; createLauncher.launch("dotmail-settings-${LocalDate.now()}.json") }) },
            dismissButton = { DotButton(stringResource(R.string.backup_cancel), { infoExport = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null }, containerColor = c.surface, shape = DotShapes.large,
            title = { Text(stringResource(R.string.backup_import_title), style = Dot.type.title, color = c.textPrimary) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    p.lines.forEach { Text("• $it", style = Dot.type.callout, color = c.textSecondary, modifier = Modifier.padding(bottom = DotSpacing.s)) }
                    if (p.needSignIn.isNotEmpty()) {
                        Text(stringResource(R.string.backup_preview_signed_out_note), style = Dot.type.caption, color = c.textTertiary)
                    }
                }
            },
            confirmButton = {
                DotButton(stringResource(R.string.backup_import_confirm), compact = true, onClick = {
                    pending = null
                    scope.launch { notice = importedTitle to applyImport(context, graph, settings, p.parsed) }
                })
            },
            dismissButton = { DotButton(stringResource(R.string.backup_cancel), { pending = null }, style = ButtonStyle.Ghost, compact = true) },
        )
    }

    notice?.let { (title, lines) ->
        AlertDialog(
            onDismissRequest = { notice = null }, containerColor = c.surface, shape = DotShapes.large,
            title = { Text(title, style = Dot.type.title, color = c.textPrimary) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { lines.forEach { Text(it, style = Dot.type.callout, color = c.textSecondary, modifier = Modifier.padding(bottom = DotSpacing.s)) } } },
            confirmButton = { DotButton(stringResource(R.string.backup_close), { notice = null }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
}

/** Accounts first (everything else refers to them by address), then preferences, rules and templates. */
private suspend fun applyImport(
    context: android.content.Context,
    graph: app.dot.mail.data.MailGraph,
    settings: app.dot.mail.AppSettings,
    parsed: SettingsBackup.Parsed,
): List<String> {
    val lines = mutableListOf<String>()
    val result = graph.accounts.applyBackup(parsed.dataSection)
    if (result.error != null) return listOf(result.error!!)
    result.items.forEach { lines += context.getString(if (it.added) R.string.backup_done_added else R.string.backup_done_updated, it.email) }
    result.warnings.forEach { lines += context.getString(R.string.backup_skipped_prefix, it) }
    val accounts = graph.accounts.accounts.first()
    val idOf = { email: String -> accounts.firstOrNull { it.email.equals(email, ignoreCase = true) }?.id }
    parsed.prefs?.let { json -> settings.update { SettingsBackup.decodePrefs(json, it, idOf) }; lines += context.getString(R.string.backup_done_prefs) }
    parsed.rules?.let { arr ->
        val (rules, dropped) = SettingsBackup.decodeRules(arr, idOf)
        val policy = RemoteImagePolicy.get(context.applicationContext)
        rules.forEach { policy.addRule(it.isDomain, it.value, it.accountId) }
        lines += if (dropped > 0) context.getString(R.string.backup_done_rules_skipped, rules.size, dropped) else context.getString(R.string.backup_done_rules, rules.size)
    }
    parsed.templates?.let { arr ->
        val store = TemplateStore.get(context.applicationContext)
        val fresh = SettingsBackup.newTemplates(store.templates.value, SettingsBackup.decodeTemplates(arr))
        fresh.forEach { store.save(it.name, it.subject, it.bodyText, it.bodyHtml) }
        lines += context.getString(R.string.backup_done_templates, fresh.size)
    }
    parsed.smartFolders?.let { arr ->
        val store = app.dot.mail.data.SmartFolderStore.get(context.applicationContext)
        val known = store.folders.value.map { it.id }.toSet()
        val fresh = SettingsBackup.decodeSmartFolders(arr, idOf).filter { it.id !in known }
        fresh.forEach { store.save(it) }
        lines += context.getString(R.string.backup_done_smart, fresh.size)
    }
    if (result.accountsNeedingSignIn.isNotEmpty()) lines += context.getString(R.string.backup_done_sign_in, result.accountsNeedingSignIn.joinToString(", "))
    return lines
}
