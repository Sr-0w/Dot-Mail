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
                )
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }
            }
            busy = false
            notice = if (result.isSuccess) "Settings exported" to listOf("The file has no passwords. Keep it private anyway: it lists your accounts, servers and signatures.")
            else "Couldn't export" to listOf(result.exceptionOrNull()?.message ?: "The file couldn't be written.")
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
            if (text == null) { notice = "Couldn't import" to listOf("The file couldn't be read."); return@launch }
            val (parsed, error) = SettingsBackup.parse(text)
            if (parsed == null) { notice = "Couldn't import" to listOf(error ?: "This file can't be used."); return@launch }
            val accountPreview: BackupPreview = graph.accounts.previewBackup(parsed.dataSection)
            if (accountPreview.error != null) { notice = "Couldn't import" to listOf(accountPreview.error!!); return@launch }
            val lines = buildList {
                addAll(accountPreview.lines)
                if (parsed.prefs != null) add("App preferences (appearance, reading, composing, privacy) are replaced. App lock and contact photos are never imported.")
                parsed.rules?.let { add("${it.size} remote-image rules are merged in.") }
                parsed.templates?.let { add("${it.size} templates: those with a new name are added.") }
                if (isEmpty()) add("Nothing to import.")
            }
            pending = PendingImport(parsed, lines, accountPreview.accountsNeedingSignIn)
        }
    }

    SectionLabel("Backup")
    ListRow(
        "Export settings", subtitle = "Accounts, identities, signatures, preferences, templates and rules — no passwords",
        icon = Icons.Outlined.FileUpload, onClick = { infoExport = true },
    )
    ListRow(
        "Import settings", subtitle = "From a Dot Mail backup file. You see what changes first.",
        icon = Icons.Outlined.FileDownload, onClick = { if (!busy) openLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
    )

    if (infoExport) {
        AlertDialog(
            onDismissRequest = { infoExport = false }, containerColor = c.surface, shape = DotShapes.large,
            title = { Text("Export settings", style = Dot.type.title, color = c.textPrimary) },
            text = {
                Text(
                    "The file holds your accounts' addresses and server settings, sender identities and signatures, preferences, templates and remote-image rules. " +
                        "It never contains passwords or sign-in tokens, so after importing you sign in to each account again. It is not encrypted: store it somewhere private.",
                    style = Dot.type.callout, color = c.textSecondary,
                )
            },
            confirmButton = { DotButton("Choose where to save", compact = true, onClick = { infoExport = false; createLauncher.launch("dotmail-settings-${LocalDate.now()}.json") }) },
            dismissButton = { DotButton("Cancel", { infoExport = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null }, containerColor = c.surface, shape = DotShapes.large,
            title = { Text("Import settings?", style = Dot.type.title, color = c.textPrimary) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    p.lines.forEach { Text("• $it", style = Dot.type.callout, color = c.textSecondary, modifier = Modifier.padding(bottom = DotSpacing.s)) }
                    if (p.needSignIn.isNotEmpty()) {
                        Text("Added accounts start signed out; you'll be asked for the password from the banner in the inbox.", style = Dot.type.caption, color = c.textTertiary)
                    }
                }
            },
            confirmButton = {
                DotButton("Import", compact = true, onClick = {
                    pending = null
                    scope.launch { notice = "Imported" to applyImport(context, graph, settings, p.parsed) }
                })
            },
            dismissButton = { DotButton("Cancel", { pending = null }, style = ButtonStyle.Ghost, compact = true) },
        )
    }

    notice?.let { (title, lines) ->
        AlertDialog(
            onDismissRequest = { notice = null }, containerColor = c.surface, shape = DotShapes.large,
            title = { Text(title, style = Dot.type.title, color = c.textPrimary) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { lines.forEach { Text(it, style = Dot.type.callout, color = c.textSecondary, modifier = Modifier.padding(bottom = DotSpacing.s)) } } },
            confirmButton = { DotButton("Close", { notice = null }, style = ButtonStyle.Ghost, compact = true) },
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
    lines += result.lines
    val accounts = graph.accounts.accounts.first()
    val idOf = { email: String -> accounts.firstOrNull { it.email.equals(email, ignoreCase = true) }?.id }
    parsed.prefs?.let { json -> settings.update { SettingsBackup.decodePrefs(json, it, idOf) }; lines += "Preferences restored." }
    parsed.rules?.let { arr ->
        val (rules, dropped) = SettingsBackup.decodeRules(arr, idOf)
        val policy = RemoteImagePolicy.get(context.applicationContext)
        rules.forEach { policy.addRule(it.isDomain, it.value, it.accountId) }
        lines += "${rules.size} remote-image rules merged" + if (dropped > 0) " ($dropped skipped: their account isn't here)." else "."
    }
    parsed.templates?.let { arr ->
        val store = TemplateStore.get(context.applicationContext)
        val fresh = SettingsBackup.newTemplates(store.templates.value, SettingsBackup.decodeTemplates(arr))
        fresh.forEach { store.save(it.name, it.subject, it.bodyText, it.bodyHtml) }
        lines += "${fresh.size} templates added."
    }
    if (result.accountsNeedingSignIn.isNotEmpty()) lines += "Sign in again: " + result.accountsNeedingSignIn.joinToString(", ")
    return lines
}
