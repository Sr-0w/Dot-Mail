package app.dot.mail.ui.settings

import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dot.design.Dot
import app.dot.design.DotShapes
import app.dot.design.DotSpacing
import app.dot.design.components.BackButton
import app.dot.design.components.ButtonStyle
import app.dot.design.components.DotButton
import app.dot.design.components.DotIconButton
import app.dot.design.components.DotTextField
import app.dot.design.components.DotTopBar
import app.dot.design.components.Hairline
import app.dot.design.components.ListRow
import app.dot.design.components.SectionLabel
import app.dot.design.components.dotSurfaceEnter
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.dot.mail.R
import app.dot.mail.LocalSettings
import app.dot.mail.data.model.Account

/** Settings → Privacy & security → Remote images: the global choice, the allowlist, and remembered messages. */
@Composable
internal fun RemoteImagesPage(accounts: List<Account>, onBack: () -> Unit) {
    val c = Dot.colors
    val settings = LocalSettings.current
    val prefs by settings.ui.collectAsStateWithLifecycle()
    val appContext = LocalContext.current.applicationContext
    val policy = remember { RemoteImagePolicy.get(appContext) }
    val rules by policy.rules.collectAsStateWithLifecycle()
    val approvals by policy.approvals.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    val frostedSource=app.dot.design.components.rememberGlassBackdrop()
    app.dot.design.components.FrostedHeaderLayout(
        backdrop=frostedSource,modifier=Modifier.fillMaxSize().background(c.background),
        header={ DotTopBar(title = stringResource(R.string.remote_images_title), elevated = scroll.value > 0, navigation = { BackButton(onBack) }) },
    ) { topPadding ->
        Column(Modifier.fillMaxSize().hazeSource(frostedSource).verticalScroll(scroll).navigationBarsPadding()) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(topPadding.calculateTopPadding()))

            Text(
                stringResource(R.string.remote_images_intro),
                style = Dot.type.callout, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.s),
            )
            ToggleRow(
                stringResource(R.string.remote_images_always), stringResource(R.string.remote_images_always_sub),
                Icons.Outlined.Image, prefs.loadRemoteImages,
            ) { v -> settings.update { it.copy(loadRemoteImages = v) } }
            Hairline(Modifier.padding(vertical = DotSpacing.s))

            SectionLabel(stringResource(R.string.remote_images_section_rules))
            if (rules.isEmpty()) {
                Text(stringResource(R.string.remote_images_no_rules),
                    style = Dot.type.caption, color = c.textSecondary, modifier = Modifier.padding(horizontal = DotSpacing.gutter, vertical = DotSpacing.xs))
            }
            rules.forEach { rule ->
                val scope = if (rule.accountId == 0L) stringResource(R.string.remote_images_all_accounts) else accounts.firstOrNull { it.id == rule.accountId }?.email ?: stringResource(R.string.remote_images_removed_account)
                val title = if (rule.isDomain) "@${rule.value}" else rule.value
                ListRow(
                    title = title, subtitle = stringResource(if (rule.isDomain) R.string.remote_images_rule_domain else R.string.remote_images_rule_sender, scope),
                    trailing = { DotIconButton(Icons.Outlined.Close, stringResource(R.string.remote_images_remove_rule, title), onClick = { policy.removeRule(rule) }, tint = c.textSecondary) },
                )
            }
            ListRow(stringResource(R.string.remote_images_add), icon = Icons.Outlined.Add, onClick = { adding = true })
            if (rules.isNotEmpty()) ListRow(stringResource(R.string.remote_images_reset), icon = Icons.Outlined.DeleteSweep, titleColor = c.danger, onClick = { confirmReset = true })
            Hairline(Modifier.padding(vertical = DotSpacing.s))

            SectionLabel(stringResource(R.string.remote_images_section_remembered))
            ListRow(
                stringResource(R.string.remote_images_forget), icon = Icons.Outlined.DeleteSweep,
                subtitle = if (approvals.isEmpty()) stringResource(R.string.remote_images_forget_none) else pluralStringResource(R.plurals.remote_images_forget_count, approvals.size, approvals.size),
                titleColor = if (approvals.isEmpty()) c.textTertiary else c.textPrimary,
                onClick = if (approvals.isEmpty()) null else ({ policy.forgetAllMessages() }),
            )
            Spacer(Modifier.height(DotSpacing.xl))

        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            modifier = Modifier.dotSurfaceEnter(),
            containerColor = c.surface, shape = DotShapes.large,
            title = { Text(stringResource(R.string.remote_images_reset_title), style = Dot.type.title, color = c.textPrimary) },
            text = { Text(stringResource(R.string.remote_images_reset_body), style = Dot.type.callout, color = c.textSecondary) },
            confirmButton = { DotButton(stringResource(R.string.remote_images_reset_confirm), style = ButtonStyle.Danger, compact = true, onClick = { confirmReset = false; policy.resetRules() }) },
            dismissButton = { DotButton(stringResource(R.string.remote_images_cancel), { confirmReset = false }, style = ButtonStyle.Ghost, compact = true) },
        )
    }
    if (adding) AddRuleDialog(accounts, onDismiss = { adding = false }) { isDomain, value, account -> policy.addRule(isDomain, value, account) }
}

@Composable
private fun AddRuleDialog(accounts: List<Account>, onDismiss: () -> Unit, onAdd: (Boolean, String, Long) -> Boolean) {
    val c = Dot.colors
    var text by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    val domainError = stringResource(R.string.remote_images_error_domain)
    val addressError = stringResource(R.string.remote_images_error_address)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.dotSurfaceEnter(),
        containerColor = c.surface, shape = DotShapes.large,
        title = { Text(stringResource(R.string.remote_images_add_title), style = Dot.type.title, color = c.textPrimary) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(DotSpacing.m)) {
                DotTextField(
                    text, { text = it; error = null }, label = stringResource(R.string.remote_images_add_label), placeholder = stringResource(R.string.remote_images_add_placeholder),
                    keyboardType = KeyboardType.Email, isError = error != null, supporting = error,
                )
                if (accounts.size > 1) {
                    Column(Modifier.selectableGroup()) {
                        listOf(0L to stringResource(R.string.remote_images_all_accounts)).plus(accounts.map { it.id to it.email }).forEach { (id, label) ->
                            ListRow(
                                label, titleColor = if (account == id) c.textPrimary else c.textSecondary,
                                modifier = Modifier.selectable(selected = account == id, role = Role.RadioButton, onClick = { account = id }),
                                trailing = { if (account == id) Text("✓", style = Dot.type.label, color = c.textPrimary) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            DotButton(stringResource(R.string.remote_images_add_confirm), enabled = text.isNotBlank(), compact = true, onClick = {
                val t = text.trim()
                val domain = t.startsWith("@") || '@' !in t
                if (onAdd(domain, t, account)) onDismiss() else error = if (domain) domainError else addressError
            })
        },
        dismissButton = { DotButton(stringResource(R.string.remote_images_cancel), onDismiss, style = ButtonStyle.Ghost, compact = true) },
    )
}
